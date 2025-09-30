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

import java.util.List;

public class Types {

    public static final int MAGIC = 0x514649FB;

    public static class Version {
        public static final int V3 = 3;
    }

    public static class EncryptionMethod {
        public static final int NO_ENCRYPTION = 0;
        public static final int AES_ENCRYPTION = 1;
    }

    public static class RefcountOrder {
        public static final int REFCOUNT_ORDER_16 = 4;
        public static final int REFCOUNT_ORDER_32 = 5;
        public static final int REFCOUNT_ORDER_64 = 6;
    }

    public static class IncompatibleFeatures {
        public static final long INCOMPATIBLE_DIRTY = 1L << 0;
        public static final long INCOMPATIBLE_CORRUPT = 1L << 1;
        public static final long INCOMPATIBLE_EXTERNAL_DATA = 1L << 2;
        public static final long INCOMPATIBLE_EXTENDED_L2 = 1L << 3;
    }

    public static class CompatibleFeatures {
        public static final long COMPATIBLE_LAZY_REFCOUNTS = 1L << 0;
    }

    public static class AutoclearFeatures {
        public static final long AUTOCLEAR_BITMAPS = 1L << 0;
        public static final long AUTOCLEAR_RAW = 1L << 1;
    }

    public static class CompressionType {
        public static final byte COMPRESSION_TYPE_DEFLATE = 0;
        public static final byte COMPRESSION_TYPE_ZSTD = 1;
    }

    public static class Header {
        public int magic;
        public int version;
        public long backingFileOffset;
        public int backingFileSize;
        public int clusterBits;
        public long size;
        public int cryptMethod;
        public int l1Size;
        public long l1TableOffset;
        public long refcountTableOffset;
        public int refcountTableClusters;
        public int nbSnapshots;
        public long snapshotsOffset;
        public long incompatibleFeatures;
        public long compatibleFeatures;
        public long autoclearFeatures;
        public int refcountOrder;
        public int headerLength;
    }

    public static class HeaderAdditionalFields {
        public byte compressionType;
        public byte[] padding = new byte[7];
    }

    public static class HeaderExtensionType {
        public static final int END_OF_HEADER_EXTENSION_AREA = 0x00000000;
        public static final int BACKING_FILE_FORMAT_NAME = 0xe2792aca;
        public static final int FEATURE_NAME_TABLE = 0x6803f857;
        public static final int BITMAPS_EXTENSION = 0x23852875;
        public static final int FULL_DISK_ENCRYPTION_HEADER = 0x0537be77;
        public static final int EXTERNAL_DATA_FILE_NAME = 0x44415441;
    }

    public static class HeaderExtensionMetadata {
        public int type;
        public int length;
    }

    public static class HeaderExtension {
        public HeaderExtensionMetadata metadata;
        public byte[] data;
    }

    public static class HeaderAndAdditionalFields {
        public Header header;
        public HeaderAdditionalFields additionalFields;
        public List<HeaderExtension> extensions;
    }

    public static class L1TableEntry {
        private final long value;

        public L1TableEntry(long value) {
            this.value = value;
        }

        public static L1TableEntry newL1TableEntry(long offset) {
            return new L1TableEntry((1L << 63) | (offset & (((1L << 48) - 1) << 9)));
        }

        public boolean isUsed() {
            return (value & (1L << 63)) != 0;
        }

        public long getOffset() {
            return value & (((1L << 48) - 1) << 9);
        }

        public long getValue() {
            return value;
        }
    }

    public static class L2TableEntry {
        private final long value;

        public L2TableEntry(long value) {
            this.value = value;
        }

        public static L2TableEntry newL2TableEntry(HeaderAndAdditionalFields hdr, long offset, boolean compressed, long compressedSize) {
            long e = 1L << 63;
            if (compressed) {
                long hostClusterBits = 62 - (hdr.header.clusterBits - 8);
                long additionalSectors = compressedSize / 512;
                e |= (1L << 62) | (additionalSectors << hostClusterBits) | (offset & ((1L << hostClusterBits) - 1));
            } else {
                e |= offset & (((1L << 48) - 1) << 9);
            }
            return new L2TableEntry(e);
        }

        public boolean isUnallocated() {
            return value == 0 || (!isCompressed() && (value & 0x1) == 1);
        }

        public boolean isUsed() {
            return (value & (1L << 63)) != 0;
        }

        public boolean isCompressed() {
            return (value & (1L << 62)) != 0;
        }

        public long getOffset(HeaderAndAdditionalFields hdr) {
            if (isCompressed()) {
                long hostClusterBits = 62 - (hdr.header.clusterBits - 8);
                return value & ((1L << hostClusterBits) - 1);
            } else {
                return value & (((1L << 48) - 1) << 9);
            }
        }

        public long getCompressedSize(HeaderAndAdditionalFields hdr) {
            long hostClusterBits = 62 - (hdr.header.clusterBits - 8);
            long additionalSectors = (value >> hostClusterBits) & ((1L << (61 - hostClusterBits + 1)) - 1);
            return (additionalSectors + 1) * 512;
        }

        public long getValue() {
            return value;
        }
    }
}