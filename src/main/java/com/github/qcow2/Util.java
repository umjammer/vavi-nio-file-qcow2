/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2023 Damian Peckett <damian@peckett>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.qcow2;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;


public class Util {

    public static class ZeroReader extends InputStream {
        @Override
        public int read(byte[] b) {
            Arrays.fill(b, (byte) 0);
            return b.length;
        }

        @Override
        public int read() {
            return 0;
        }
    }

    public static class OffsetReader extends InputStream {
        private final RandomAccessFile f;
        private long offset;

        public OffsetReader(File f, long offset) throws IOException {
            this.f = new RandomAccessFile(f, "r");
            this.f.seek(offset);
            this.offset = offset;
        }

        @Override
        public int read(byte[] b) throws IOException {
            int n = f.read(b);
            if (n > 0) {
                offset += n;
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            int b = f.read();
            if (b != -1) {
                offset++;
            }
            return b;
        }

        @Override
        public void close() throws IOException {
            f.close();
        }
    }

    public static class OffsetWriter extends OutputStream {
        private final RandomAccessFile f;
        private long offset;

        public OffsetWriter(File f, long offset) throws IOException {
            this.f = new RandomAccessFile(f, "rw");
            this.f.seek(offset);
            this.offset = offset;
        }

        @Override
        public void write(byte[] b) throws IOException {
            f.write(b);
            offset += b.length;
        }

        @Override
        public void write(int b) throws IOException {
            f.write(b);
            offset++;
        }

        @Override
        public void close() throws IOException {
            f.close();
        }
    }

    public static class LimitWriter extends OutputStream {
        private final OutputStream w;
        private final long limit;
        private long written;

        public LimitWriter(OutputStream w, long limit) {
            this.w = w;
            this.limit = limit;
        }

        @Override
        public void write(byte[] b) throws IOException {
            if (written >= limit) {
                throw new IOException("EOF");
            }

            long bytesToWrite = b.length;
            if (written + bytesToWrite > limit) {
                bytesToWrite = limit - written;
            }

            w.write(b, 0, (int) bytesToWrite);
            written += bytesToWrite;
        }

        @Override
        public void write(int b) throws IOException {
            if (written >= limit) {
                throw new IOException("EOF");
            }

            w.write(b);
            written++;
        }

        @Override
        public void close() throws IOException {
            w.close();
        }
    }
}