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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.github.qcow2.Types.CompressionType;
import com.github.qcow2.Types.EncryptionMethod;
import com.github.qcow2.Types.Header;
import com.github.qcow2.Types.HeaderAdditionalFields;
import com.github.qcow2.Types.HeaderAndAdditionalFields;
import com.github.qcow2.Types.HeaderExtension;
import com.github.qcow2.Types.HeaderExtensionMetadata;
import com.github.qcow2.Types.HeaderExtensionType;
import com.github.qcow2.Types.L1TableEntry;
import com.github.qcow2.Types.Version;


public class HeaderUtil {

    private static final int HEADER_BASE_SIZE = 104;

    public static HeaderAndAdditionalFields readHeader(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(0);
            byte[] headerBytes = new byte[HEADER_BASE_SIZE];
            int bytesRead = raf.read(headerBytes);
            if (bytesRead < HEADER_BASE_SIZE) {
                throw new IOException("Could not read full header");
            }

            ByteBuffer buffer = ByteBuffer.wrap(headerBytes);
            buffer.order(ByteOrder.BIG_ENDIAN);

            Header hdr = new Header();
            hdr.magic = buffer.getInt();
            hdr.version = buffer.getInt();
            hdr.backingFileOffset = buffer.getLong();
            hdr.backingFileSize = buffer.getInt();
            hdr.clusterBits = buffer.getInt();
            hdr.size = buffer.getLong();
            hdr.cryptMethod = buffer.getInt();
            hdr.l1Size = buffer.getInt();
            hdr.l1TableOffset = buffer.getLong();
            hdr.refcountTableOffset = buffer.getLong();
            hdr.refcountTableClusters = buffer.getInt();
            hdr.nbSnapshots = buffer.getInt();
            hdr.snapshotsOffset = buffer.getLong();
            hdr.incompatibleFeatures = buffer.getLong();
            hdr.compatibleFeatures = buffer.getLong();
            hdr.autoclearFeatures = buffer.getLong();
            hdr.refcountOrder = buffer.getInt();
            hdr.headerLength = buffer.getInt();

            if (hdr.magic != Types.MAGIC) {
                throw new IOException("Invalid magic bytes");
            }

            if (hdr.version != Version.V3) {
                throw new IOException("Only version 3 is supported");
            }

            if (hdr.backingFileOffset != 0) {
                throw new IOException("Backing files are not supported");
            }

            if (hdr.cryptMethod != EncryptionMethod.NO_ENCRYPTION) {
                throw new IOException("Encryption is not supported");
            }

            if (hdr.incompatibleFeatures != 0) {
                throw new IOException("Incompatible features are not supported");
            }

            HeaderAdditionalFields additionalFields = null;
            if (hdr.headerLength > HEADER_BASE_SIZE) {
                additionalFields = new HeaderAdditionalFields();
                byte[] additionalFieldsBytes = new byte[8];
                raf.readFully(additionalFieldsBytes);
                ByteBuffer additionalFieldsBuffer = ByteBuffer.wrap(additionalFieldsBytes);
                additionalFieldsBuffer.order(ByteOrder.BIG_ENDIAN);
                additionalFields.compressionType = additionalFieldsBuffer.get();
                additionalFieldsBuffer.get(additionalFields.padding);
            }

            if (additionalFields != null && additionalFields.compressionType != CompressionType.COMPRESSION_TYPE_DEFLATE) {
                throw new IOException("Unsupported compression type");
            }

            List<HeaderExtension> extensions = new ArrayList<>();
            while (raf.getFilePointer() < hdr.headerLength) {
                HeaderExtension headerExtension = new HeaderExtension();
                headerExtension.metadata = new HeaderExtensionMetadata();

                byte[] metadataBytes = new byte[8];
                raf.readFully(metadataBytes);
                ByteBuffer metadataBuffer = ByteBuffer.wrap(metadataBytes);
                metadataBuffer.order(ByteOrder.BIG_ENDIAN);
                headerExtension.metadata.type = metadataBuffer.getInt();
                headerExtension.metadata.length = metadataBuffer.getInt();

                if (headerExtension.metadata.type == HeaderExtensionType.END_OF_HEADER_EXTENSION_AREA) {
                    break;
                }

                if (headerExtension.metadata.type == HeaderExtensionType.BACKING_FILE_FORMAT_NAME ||
                        headerExtension.metadata.type == HeaderExtensionType.EXTERNAL_DATA_FILE_NAME ||
                        headerExtension.metadata.type == HeaderExtensionType.FULL_DISK_ENCRYPTION_HEADER) {
                    throw new IOException("Unsupported header extension");
                }

                headerExtension.data = new byte[headerExtension.metadata.length];
                raf.readFully(headerExtension.data);

                extensions.add(headerExtension);
            }

            HeaderAndAdditionalFields result = new HeaderAndAdditionalFields();
            result.header = hdr;
            result.additionalFields = additionalFields;
            result.extensions = extensions;
            return result;
        }
    }

    public static void writeHeader(File f, long size) throws IOException {
        int clusterBits = 16;
        long clusterSize = 1L << clusterBits;

        // Round size up to the nearest cluster.
        size = clusterSize * ((size + clusterSize - 1) / clusterSize);

        Header hdr = new Header();
        hdr.magic = Types.MAGIC;
        hdr.version = Version.V3;
        hdr.clusterBits = clusterBits;
        hdr.size = size;
        hdr.cryptMethod = EncryptionMethod.NO_ENCRYPTION;
        hdr.refcountOrder = 4;
        hdr.headerLength = 104; // Standard v3 header size

        long l2Entries = clusterSize / 8;

        long totalClusters = 1 + size / clusterSize;
        long l2TableClusters = 1 + totalClusters / l2Entries;

        hdr.l1Size = (int) l2TableClusters;

        long refcountBits = 1L << hdr.refcountOrder;
        long refcountBlockEntries = clusterSize / refcountBits;
        long totalRefcountBlocks = 1 + totalClusters / refcountBlockEntries;

        hdr.refcountTableClusters = (int) (1 + totalRefcountBlocks / (clusterSize / 8));

        long imageOffset = clusterSize;

        // write the L1 table
        long[] l1Table = new long[(int) (clusterSize / 8)];
        for (long j = 0; j < l2TableClusters; j++) {
            l1Table[(int) j] = L1TableEntry.newL1TableEntry(imageOffset + (j + 1) * clusterSize).getValue();
        }
        writeTable(f, imageOffset, l1Table);
        hdr.l1TableOffset = imageOffset;
        imageOffset += clusterSize;

        // write the L2 table/s
        for (int j = 0; j < l2TableClusters; j++) {
            long[] l2Table = new long[(int) (clusterSize / 8)];
            writeTable(f, imageOffset, l2Table);
            imageOffset += clusterSize;
        }

        // write the refcount table
        long[] refcountTable = new long[(int) ((hdr.refcountTableClusters * clusterSize) / 8)];
        for (int j = 0; j < totalRefcountBlocks; j++) {
            refcountTable[j] = (imageOffset + (j + hdr.refcountTableClusters) * clusterSize) & ~((1 << 9) - 1);
        }
        writeTable(f, imageOffset, refcountTable);
        hdr.refcountTableOffset = imageOffset;
        imageOffset += (long) hdr.refcountTableClusters * clusterSize;

        // write the refcount block/s
        try (Util.OffsetWriter writer = new Util.OffsetWriter(f, imageOffset)) {
            byte[] zeros = new byte[(int) clusterSize];
            for (int j = 0; j < totalRefcountBlocks; j++) {
                writer.write(zeros);
                imageOffset += clusterSize;
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(hdr.magic);
        dos.writeInt(hdr.version);
        dos.writeLong(hdr.backingFileOffset);
        dos.writeInt(hdr.backingFileSize);
        dos.writeInt(hdr.clusterBits);
        dos.writeLong(hdr.size);
        dos.writeInt(hdr.cryptMethod);
        dos.writeInt(hdr.l1Size);
        dos.writeLong(hdr.l1TableOffset);
        dos.writeLong(hdr.refcountTableOffset);
        dos.writeInt(hdr.refcountTableClusters);
        dos.writeInt(hdr.nbSnapshots);
        dos.writeLong(hdr.snapshotsOffset);
        dos.writeLong(hdr.incompatibleFeatures);
        dos.writeLong(hdr.compatibleFeatures);
        dos.writeLong(hdr.autoclearFeatures);
        dos.writeInt(hdr.refcountOrder);
        dos.writeInt(hdr.headerLength);

        // Write additional fields (compression type)
        dos.writeByte(CompressionType.COMPRESSION_TYPE_DEFLATE);
        dos.write(new byte[7]); // padding

        dos.writeInt(HeaderExtensionType.END_OF_HEADER_EXTENSION_AREA);
        dos.writeInt(0);

        byte[] headerBytes = baos.toByteArray();

        try (Util.OffsetWriter writer = new Util.OffsetWriter(f, 0)) {
            writer.write(headerBytes);
            if (headerBytes.length < clusterSize) {
                writer.write(new byte[(int) (clusterSize - headerBytes.length)]);
            }
        }
    }

    private static void writeTable(File f, long offset, long[] table) throws IOException {
        try (Util.OffsetWriter writer = new Util.OffsetWriter(f, offset)) {
            ByteBuffer buffer = ByteBuffer.allocate(table.length * 8);
            buffer.order(ByteOrder.BIG_ENDIAN);
            for (long value : table) {
                buffer.putLong(value);
            }
            writer.write(buffer.array());
        }
    }
}