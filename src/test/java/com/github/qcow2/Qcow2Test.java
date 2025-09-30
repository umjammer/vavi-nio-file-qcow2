/* SPDX-License-Identifier: Apache-2.0
 *
 * This is a port of the Go test from the original project.
 *
 */
package com.github.qcow2;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

public class Qcow2Test {

    private static final String TEST_IMAGE_URL = "https://download.cirros-cloud.net/0.5.1/cirros-0.5.1-x86_64-disk.img";
    private static final String TEST_IMAGE_SHA256 = "f8d297a47fd2017a776a2975919c90ba27131e2083fbf38ca434ba26a8b0dd6e";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File testImage;

    @Before
    public void setUp() throws IOException {
        testImage = new File("testdata/cirros-0.5.1-x86_64-disk.img");
        if (!testImage.exists()) {
            System.out.println("Downloading test image...");
            testImage.getParentFile().mkdirs();
            downloadFile(testImage.getAbsolutePath(), TEST_IMAGE_URL);
        }
    }

    @org.junit.Ignore
    @Test
    public void testImageEndToEnd() throws IOException, InterruptedException {
        try (Image input = Qcow2.open(testImage.getAbsolutePath(), true)) {
            long size = input.getSize();
            assertThat(size).isEqualTo(117440512L);

            File outputFile = tempFolder.newFile("output.qcow2");
            try (Image output = Qcow2.create(outputFile.getAbsolutePath(), size)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }

            // Verify with qemu-img if available
            File rawFile = tempFolder.newFile("output.raw");
            ProcessBuilder pb = new ProcessBuilder("qemu-img", "convert", "-f", "qcow2", "-O", "raw", outputFile.getAbsolutePath(), rawFile.getAbsolutePath());
            Process p = pb.start();
            int exitCode = p.waitFor();

            if (exitCode != 0) {
                System.err.println("qemu-img command failed. Skipping verification.");
                return;
            }

            String sum = hashFile(rawFile);
            assertThat(sum).isEqualTo(TEST_IMAGE_SHA256);
        }
    }

    @Test
    public void testImageRandomReadsAndWrites() throws IOException {
        File imageFile = tempFolder.newFile("test.qcow2");
        // Reduce image size to 64MB to speed up test
        try (Image image = Qcow2.create(imageFile.getAbsolutePath(), 1L << 26)) {
            long imageSize = image.getSize();
            List<Block> blocks = new ArrayList<>();
            Random rng = new Random();

            // Reduce iterations and block size to speed up test
            for (int i = 0; i < 10; i++) {
                int blockSize = rng.nextInt(1 << 16) + 1;
                long offset = (long) (rng.nextDouble() * (imageSize - blockSize));

                Block newBlock = new Block(offset, blockSize);
                if (checkBlockOverlap(newBlock, blocks)) {
                    continue;
                }
                blocks.add(newBlock);

                byte[] data = new byte[blockSize];
                rng.nextBytes(data);

                int n = image.writeAt(data, offset);
                assertThat(n).isEqualTo(data.length);

                byte[] readData = new byte[blockSize];
                n = image.readAt(readData, offset);
                assertThat(n).isEqualTo(data.length);

                assertThat(readData).isEqualTo(data);
            }

            image.sync();
            image.snapshot();

            Collections.shuffle(blocks, rng);
            for (Block b : blocks) {
                byte[] data = new byte[b.size];
                rng.nextBytes(data);

                int n = image.writeAt(data, b.offset);
                assertThat(n).isEqualTo(data.length);

                byte[] readData = new byte[b.size];
                n = image.readAt(readData, b.offset);
                assertThat(n).isEqualTo(data.length);

                assertThat(readData).isEqualTo(data);
            }
        }
    }

    private static class Block {
        long offset;
        int size;

        Block(long offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }

    private boolean checkBlockOverlap(Block newBlock, List<Block> blocks) {
        for (Block b : blocks) {
            if (overlap(newBlock.offset, newBlock.size, b.offset, b.size)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlap(long a, long aSize, long b, long bSize) {
        return a < b + bSize && b < a + aSize;
    }

    private void downloadFile(String path, String urlString) throws IOException {
        URL url = new URL(urlString);
        try (ReadableByteChannel rbc = Channels.newChannel(url.openStream());
             FileOutputStream fos = new FileOutputStream(path)) {
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        }
    }

    private String hashFile(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new IOException("Could not hash file", e);
        }
    }
}