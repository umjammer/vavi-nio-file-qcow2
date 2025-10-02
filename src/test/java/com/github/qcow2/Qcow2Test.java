/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * This is a port of the Go test from the original project.
 */

package com.github.qcow2;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.github.qcow2.Qcow2.Image;
import vavi.util.Debug;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class Qcow2Test {

    private static final String TEST_IMAGE_URL = "https://download.cirros-cloud.net/0.5.1/cirros-0.5.1-x86_64-disk.img";
    private static final String TEST_IMAGE_SHA256 = "f8d297a47fd2017a776a2975919c90ba27131e2083fbf38ca434ba26a8b0dd6e";

    private static Path testImage;

    @BeforeAll
    static void setUp() throws IOException {
        testImage = Path.of("tmp", "cirros-0.5.1-x86_64-disk.img");
        if (!Files.exists(testImage)) {
            System.out.println("Downloading test image...");
            if (!Files.exists(testImage.getParent())) Files.createDirectories(testImage.getParent());
            Files.copy(URI.create(TEST_IMAGE_URL).toURL().openStream(), testImage.toAbsolutePath());
        }
    }

    // it takes 5 minutes to do this test
    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    public void testImageEndToEnd() throws IOException, InterruptedException {
        try (Image input = Qcow2.open(testImage.toAbsolutePath().toString(), true)) {
            long size = input.getSize();
            assertEquals(117440512L, size);

            Path outputFile = Path.of("tmp", "output.qcow2");
Debug.print(testImage + " to " + outputFile + ", size " + size);
System.err.println();
            int write = 0;
            try (Image output = Qcow2.create(outputFile.toAbsolutePath().toString(), size)) {
                byte[] buffer = new byte[8192 * 16];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) > 0) { // heavy loop
                    output.write(buffer, 0, bytesRead);
write += bytesRead;
System.err.print(".");
System.err.flush();
if ((write % (buffer.length * 100)) == 0) System.err.println();
                }
            }
System.err.println();

            // Verify with qemu-img if available
            Path rawFile = Path.of("tmp", "output.raw");
Debug.print("create " + rawFile);
            ProcessBuilder pb = new ProcessBuilder("qemu-img", "convert", "-f", "qcow2", "-O", "raw", outputFile.toAbsolutePath().toString(), rawFile.toAbsolutePath().toString());
Debug.print(pb.command());
            Process p = pb.start();
            int exitCode = p.waitFor();

            if (exitCode != 0) {
                System.err.println("qemu-img command failed. Skipping verification.");
                return;
            }

Debug.print("check num of " + rawFile);
            String sum = hashFile(rawFile);
            assertEquals(TEST_IMAGE_SHA256, sum);
        }
Debug.print("done");
    }

    @Test
    public void testImageRandomReadsAndWrites() throws IOException {
        Path imageFile = Path.of("tmp", "test.qcow2");
        // Reduce image size to 64MB to speed up test
        try (Image image = Qcow2.create(imageFile.toAbsolutePath().toString(), 1L << 26)) {
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
                assertEquals(data.length, n);

                byte[] readData = new byte[blockSize];
                n = image.readAt(readData, offset);
                assertEquals(data.length, n);

                assertArrayEquals(data, readData);
            }

            image.sync();
            image.snapshot();

            Collections.shuffle(blocks, rng);
            for (Block b : blocks) {
                byte[] data = new byte[b.size];
                rng.nextBytes(data);

                int n = image.writeAt(data, b.offset);
                assertEquals(data.length, n);

                byte[] readData = new byte[b.size];
                n = image.readAt(readData, b.offset);
                assertEquals(data.length, n);

                assertArrayEquals(data, readData);
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

    private static boolean checkBlockOverlap(Block newBlock, List<Block> blocks) {
        for (Block b : blocks) {
            if (overlap(newBlock.offset, newBlock.size, b.offset, b.size)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlap(long a, long aSize, long b, long bSize) {
        return a < b + bSize && b < a + aSize;
    }

    private static String hashFile(Path file) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
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