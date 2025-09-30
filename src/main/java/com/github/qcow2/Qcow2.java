/* SPDX-License-Identifier: Apache-2.0
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

import com.github.qcow2.Types.*;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.commons.compress.utils.BoundedInputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Qcow2 {

    public static Image create(String path, long size) throws IOException {
        File f = new File(path);
        HeaderUtil.writeHeader(f, size);
        return open(path, false);
    }

    public static Image open(String path, boolean readOnly) throws IOException {
        return new Image(path, readOnly);
    }
}

class Image implements Closeable {
    private final ReadWriteLock mu = new ReentrantReadWriteLock();
    private final String path;
    private final RandomAccessFile f;
    private final HeaderAndAdditionalFields hdr;
    private final LoadingCache<TableKey, long[]> tableCache;
    private final long clusterSize;
    private final Lock cursorMu = new ReentrantLock();
    private long cursor;

    Image(String path, boolean readOnly) throws IOException {
        this.path = path;
        this.f = new RandomAccessFile(path, readOnly ? "r" : "rw");
        this.hdr = HeaderUtil.readHeader(new File(path));
        this.clusterSize = 1L << this.hdr.header.clusterBits;
        this.tableCache = CacheBuilder.newBuilder()
                .maximumSize(1000)
                .build(new CacheLoader<TableKey, long[]>() {
                    @Override
                    public long[] load(TableKey key) throws IOException {
                        return tableLoader(key);
                    }
                });
    }

    @Override
    public void close() throws IOException {
        f.close();
    }

    public long getSize() {
        return hdr.header.size;
    }

    public void sync() throws IOException {
        mu.writeLock().lock();
        try {
            f.getFD().sync();
        } finally {
            mu.writeLock().unlock();
        }
    }

    public int read(byte[] p) throws IOException {
        cursorMu.lock();
        try {
            int n = readAt(p, cursor);
            if (n > 0) {
                cursor += n;
            }
            return n;
        } finally {
            cursorMu.unlock();
        }
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if (off == 0 && len == b.length) {
            write(b);
            return;
        }
        byte[] temp = new byte[len];
        System.arraycopy(b, off, temp, 0, len);
        write(temp);
    }

    public int readAt(byte[] p, long diskOffset) throws IOException {
        mu.readLock().lock();
        try {
            int n = p.length;
            if (n == 0) {
                return 0;
            }

            if (diskOffset + n > hdr.header.size) {
                n = (int) (hdr.header.size - diskOffset);
                if (n < 0) {
                    return -1; // EOF
                }
            }

            int remaining = n;
            int bytesRead = 0;
            while (remaining > 0) {
                try (InputStream r = clusterReader(diskOffset)) {
                    int bytesToRead = (int) Math.min(clusterSize, remaining);
                    int readInCluster = r.read(p, bytesRead, bytesToRead);
                    if (readInCluster == -1) {
                        break;
                    }

                    diskOffset += readInCluster;
                    bytesRead += readInCluster;
                    remaining -= readInCluster;
                }
            }

            return bytesRead == 0 && n > 0 ? -1 : bytesRead;
        } finally {
            mu.readLock().unlock();
        }
    }

    public int write(byte[] p) throws IOException {
        cursorMu.lock();
        try {
            int n = writeAt(p, cursor);
            cursor += n;
            return n;
        } finally {
            cursorMu.unlock();
        }
    }

    public int writeAt(byte[] p, long diskOffset) throws IOException {
        mu.writeLock().lock();
        try {
            int n = p.length;
            if (n == 0) {
                return 0;
            }

            if (diskOffset + n > hdr.header.size) {
                throw new IOException("Write extends beyond end of disk");
            }

            int remaining = n;
            int bytesWritten = 0;
            while (remaining > 0) {
                long bytesInCluster = clusterSize - (diskOffset % clusterSize);
                int bytesToWrite = (int) Math.min(bytesInCluster, remaining);

                try (OutputStream w = clusterWriter(diskOffset)) {
                    w.write(p, bytesWritten, bytesToWrite);
                }

                diskOffset += bytesToWrite;
                bytesWritten += bytesToWrite;
                remaining -= bytesToWrite;
            }
            return bytesWritten;
        } finally {
            mu.writeLock().unlock();
        }
    }

    public void snapshot() throws IOException {
        mu.writeLock().lock();
        try {
            incrementRefcounts(hdr.header.l1TableOffset, hdr.header.l1Size);
        } finally {
            mu.writeLock().unlock();
        }
    }

    // Cluster methods
    private InputStream clusterReader(long diskOffset) throws IOException {
        long bytesRemainingInCluster = clusterSize - (diskOffset % clusterSize);

        long l2Entries = clusterSize / 8;
        long l2Index = (diskOffset / clusterSize) % l2Entries;
        long l1Index = (diskOffset / clusterSize) / l2Entries;

        long[] l1Table = readTable(hdr.header.l1TableOffset, hdr.header.l1Size);
        L1TableEntry l1Entry = new L1TableEntry(l1Table[(int) l1Index]);
        long[] l2Table = readTable(l1Entry.getOffset(), (int) l2Entries);
        L2TableEntry l2Entry = new L2TableEntry(l2Table[(int) l2Index]);

        if (l2Entry.isUnallocated()) {
            return new Util.ZeroReader();
        }

        if (l2Entry.isCompressed()) {
            long imageOffset = l2Entry.getOffset(hdr);
            InputStream limitedReader = new BoundedInputStream(new Util.OffsetReader(new File(this.path), imageOffset), l2Entry.getCompressedSize(hdr));
            InflaterInputStream inflater = new InflaterInputStream(limitedReader, new Inflater(true));
            IOUtils.skip(inflater, diskOffset % clusterSize);
            return new BoundedInputStream(inflater, bytesRemainingInCluster);
        }

        long imageOffset = l2Entry.getOffset(hdr) + (diskOffset % clusterSize);
        return new BoundedInputStream(new Util.OffsetReader(new File(this.path), imageOffset), bytesRemainingInCluster);
    }

    private OutputStream clusterWriter(long diskOffset) throws IOException {
        L2TableEntry l2Entry = getL2Entry(diskOffset);
        long imageOffset = l2Entry.getOffset(hdr) + (diskOffset % clusterSize);

        long refcount = 0;
        if (!l2Entry.isUnallocated()) {
            refcount = getRefcount(diskOffset);
        }

        if (refcount == 0) {
            long imageOffsetClusterBase = allocateCluster();
            updateL2Table(imageOffsetClusterBase, alignToClusterBoundary(diskOffset));
            setRefcount(diskOffset, 1);
            imageOffset = imageOffsetClusterBase + (diskOffset % clusterSize);
        } else if (refcount > 1) {
            long imageOffsetClusterBase = copyCluster(alignToClusterBoundary(diskOffset));
            updateL2Table(imageOffsetClusterBase, alignToClusterBoundary(diskOffset));
            setRefcount(diskOffset, 1);
            imageOffset = imageOffsetClusterBase + (diskOffset % clusterSize);
        }

        return new Util.LimitWriter(new Util.OffsetWriter(new File(this.path), imageOffset), clusterSize - (diskOffset % clusterSize));
    }

    private long allocateCluster() throws IOException {
        long imageOffset = f.length();
        f.setLength(imageOffset + clusterSize);
        return imageOffset;
    }

    private long copyCluster(long diskOffset) throws IOException {
        long newImageOffset = allocateCluster();
        L2TableEntry l2Entry = getL2Entry(diskOffset);
        long oldImageOffset = l2Entry.getOffset(hdr);

        byte[] buffer = new byte[(int) clusterSize];
        f.seek(oldImageOffset);
        f.readFully(buffer);
        f.seek(newImageOffset);
        f.write(buffer);

        return newImageOffset;
    }

    private void updateL2Table(long imageOffset, long diskOffset) throws IOException {
        long l2Entries = clusterSize / 8;
        long l2Index = (diskOffset / clusterSize) % l2Entries;
        long l1Index = (diskOffset / clusterSize) / l2Entries;

        long[] l1Table = readTable(hdr.header.l1TableOffset, hdr.header.l1Size);
        L1TableEntry l1Entry = new L1TableEntry(l1Table[(int) l1Index]);
        long[] l2Table = readTable(l1Entry.getOffset(), (int) l2Entries);
        l2Table[(int) l2Index] = L2TableEntry.newL2TableEntry(hdr, imageOffset, false, 0).getValue();
        writeTable(l1Entry.getOffset(), l2Table);
    }

    private L2TableEntry getL2Entry(long diskOffset) throws IOException {
        long l2Entries = clusterSize / 8;
        long l2Index = (diskOffset / clusterSize) % l2Entries;
        long l1Index = (diskOffset / clusterSize) / l2Entries;

        long[] l1Table = readTable(hdr.header.l1TableOffset, hdr.header.l1Size);
        L1TableEntry l1Entry = new L1TableEntry(l1Table[(int) l1Index]);
        long[] l2Table = readTable(l1Entry.getOffset(), (int) l2Entries);
        return new L2TableEntry(l2Table[(int) l2Index]);
    }

    private long alignToClusterBoundary(long offset) {
        return clusterSize * (offset / clusterSize);
    }


    // Refcount methods
    private long getRefcount(long diskOffset) throws IOException {
        long refcountOffset = diskToRefcountOffset(diskOffset);
        long refcountBits = 1L << hdr.header.refcountOrder;
        return readBits(refcountOffset, refcountBits);
    }

    private void setRefcount(long diskOffset, long refcount) throws IOException {
        long refcountOffset = diskToRefcountOffset(diskOffset);
        long refcountBits = 1L << hdr.header.refcountOrder;
        writeBits(refcountOffset, refcountBits, refcount);
    }

    private void incrementRefcounts(long l1TableOffset, int l1Size) throws IOException {
        long l2EntriesPerTable = clusterSize / 8;
        long[] l1Table = readTable(l1TableOffset, l1Size);

        for (int l1Index = 0; l1Index < l1Table.length; l1Index++) {
            L1TableEntry l1Entry = new L1TableEntry(l1Table[l1Index]);
            long[] l2Table = readTable(l1Entry.getOffset(), (int) l2EntriesPerTable);

            for (int l2Index = 0; l2Index < l2Table.length; l2Index++) {
                L2TableEntry l2Entry = new L2TableEntry(l2Table[l2Index]);
                if (l2Entry.isUnallocated()) {
                    continue;
                }
                long diskOffset = (long) l1Index * l2EntriesPerTable * clusterSize + (long) l2Index * clusterSize;
                long refcount = getRefcount(diskOffset);
                setRefcount(diskOffset, refcount + 1);
            }
        }
    }

    private long diskToRefcountOffset(long diskOffset) throws IOException {
        long refcountBits = 1L << hdr.header.refcountOrder;
        long refcountBlockEntries = clusterSize * 8 / refcountBits;
        long guestClusterIndex = diskOffset / clusterSize;
        long refcountTableIndex = guestClusterIndex / refcountBlockEntries;
        long refcountBlockIndex = guestClusterIndex % refcountBlockEntries;

        long refCountTableEntries = (hdr.header.refcountTableClusters * clusterSize) / 8;
        long[] refCountTable = readTable(hdr.header.refcountTableOffset, (int) refCountTableEntries);
        long refcountBlockOffset = refCountTable[(int) refcountTableIndex] & ~((1L << 9) - 1);
        long refcountEntryOffsetInBits = refcountBlockIndex * refcountBits;
        return refcountBlockOffset + refcountEntryOffsetInBits / 8;
    }

    private long readBits(long imageOffset, long nBits) throws IOException {
        f.seek(imageOffset);
        if (nBits == 16) return f.readUnsignedShort();
        if (nBits == 32) return f.readInt() & 0xFFFFFFFFL;
        if (nBits == 64) return f.readLong();
        throw new IOException("Unsupported refcount bit size: " + nBits);
    }

    private void writeBits(long imageOffset, long nBits, long value) throws IOException {
        f.seek(imageOffset);
        if (nBits == 16) f.writeShort((short) value);
        else if (nBits == 32) f.writeInt((int) value);
        else if (nBits == 64) f.writeLong(value);
        else throw new IOException("Unsupported refcount bit size: " + nBits);
    }

    // Table methods
    private long[] readTable(long imageOffset, int n) throws IOException {
        try {
            return tableCache.get(new TableKey(imageOffset, n));
        } catch (ExecutionException e) {
            throw new IOException("Failed to read table", e.getCause());
        }
    }

    private void writeTable(long imageOffset, long[] t) throws IOException {
        byte[] buf = new byte[8 * t.length];
        ByteBuffer buffer = ByteBuffer.wrap(buf);
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (long v : t) {
            buffer.putLong(v);
        }
        f.seek(imageOffset);
        f.write(buf);
        tableCache.invalidate(new TableKey(imageOffset, t.length));
    }

    private long[] tableLoader(TableKey key) throws IOException {
        byte[] buf = new byte[8 * key.n];
        f.seek(key.imageOffset);
        f.readFully(buf);

        ByteBuffer buffer = ByteBuffer.wrap(buf);
        buffer.order(ByteOrder.BIG_ENDIAN);

        long[] t = new long[key.n];
        for (int i = 0; i < t.length; i++) {
            t[i] = buffer.getLong();
        }
        return t;
    }

    private static class TableKey {
        private final long imageOffset;
        private final int n;

        TableKey(long imageOffset, int n) {
            this.imageOffset = imageOffset;
            this.n = n;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TableKey tableKey = (TableKey) o;
            return imageOffset == tableKey.imageOffset && n == tableKey.n;
        }

        @Override
        public int hashCode() {
            return Objects.hash(imageOffset, n);
        }
    }
}