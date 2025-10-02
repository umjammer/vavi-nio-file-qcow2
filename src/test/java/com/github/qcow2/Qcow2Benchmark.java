/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * This is a port of the Go benchmark tool from the original project.
 */

package com.github.qcow2;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

import com.github.qcow2.Qcow2.Image;


public class Qcow2Benchmark {

    private static final int BLOCK_SIZE = 4096;
    private static final int TOTAL_BLOCKS = 10000;
    private static final int QUEUE_DEPTH = 20;

    private static class Block {
        long offset;
        long crc; // Use long for unsigned int

        Block(long offset) {
            this.offset = offset;
        }
    }

    private static class Operation {
        boolean isWrite;
        Block block;

        Operation(boolean isWrite, Block block) {
            this.isWrite = isWrite;
            this.block = block;
        }
    }

    // TODO causes too many open files
    public static void main(String[] args) throws IOException, InterruptedException {
        Random rng = new Random();
        Path tempDir = Files.createTempDirectory("qcow2-benchmark");
        Path imageFile = Path.of("tmp", "test.qcow2");

        try (Image image = Qcow2.create(imageFile.toAbsolutePath().toString(), 1L << 30)) {
            long imageSize = image.getSize();

            List<Block> blocks = new ArrayList<>();
            for (int i = 0; i < TOTAL_BLOCKS; i++) {
                while (true) {
                    long offset = (long) (rng.nextDouble() * (imageSize - BLOCK_SIZE));
                    Block newBlock = new Block(offset);
                    if (!checkBlockOverlap(newBlock, blocks)) {
                        blocks.add(newBlock);
                        break;
                    }
                }
            }

            List<Operation> writeOperations = new ArrayList<>();
            for (Block block : blocks) {
                writeOperations.add(new Operation(true, block));
            }

            List<Operation> readOperations = new ArrayList<>();
            for (Block block : blocks) {
                readOperations.add(new Operation(false, block));
            }

            ExecutorService executor = Executors.newFixedThreadPool(QUEUE_DEPTH);

            long start = System.nanoTime();

            // Perform writes
            CountDownLatch writeLatch = new CountDownLatch(writeOperations.size());
            for (Operation op : writeOperations) {
                executor.submit(() -> worker(writeLatch, op, rng, image));
            }
            writeLatch.await();

            // Perform reads
            CountDownLatch readLatch = new CountDownLatch(readOperations.size());
            for (Operation op : readOperations) {
                executor.submit(() -> worker(readLatch, op, rng, image));
            }
            readLatch.await();

            long elapsedNanos = System.nanoTime() - start;
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

            executor.shutdown();

            double iops = (double) (writeOperations.size() + readOperations.size()) / elapsedSeconds;
            double throughput = iops * BLOCK_SIZE / (1024 * 1024); // MB/s

            System.out.printf("IOPS: %.2f, Throughput: %.2f MB/s\n", iops, throughput);

        } finally {
            // Clean up the temp directory
            Files.walk(tempDir)
                 .map(Path::toFile)
                 .forEach(File::delete);
            Files.delete(tempDir);
        }
    }

    private static void worker(CountDownLatch latch, Operation op, Random rng, Image image) {
        try {
            byte[] data = new byte[BLOCK_SIZE];
            if (op.isWrite) {
                rng.nextBytes(data);
                if (image.writeAt(data, op.block.offset) != BLOCK_SIZE) {
                    throw new IOException("Short write");
                }
                CRC32 crc = new CRC32();
                crc.update(data);
                op.block.crc = crc.getValue();
            } else {
                if (image.readAt(data, op.block.offset) != BLOCK_SIZE) {
                    throw new IOException("Short read");
                }
                CRC32 crc = new CRC32();
                crc.update(data);
                if (crc.getValue() != op.block.crc) {
                    System.err.printf("CRC mismatch: %x != %x\n", crc.getValue(), op.block.crc);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            latch.countDown();
        }
    }

    private static boolean checkBlockOverlap(Block newBlock, List<Block> blocks) {
        for (Block b : blocks) {
            if (overlap(newBlock.offset, BLOCK_SIZE, b.offset, BLOCK_SIZE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlap(long a, long aSize, long b, long bSize) {
        return a < b + bSize && b < a + aSize;
    }
}