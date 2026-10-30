/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

/**
 * A QR code encoder, just enough for a short link: byte mode, error correction level M,
 * versions 1 to 6 (up to 106 bytes), the best of the eight masks. It has no Android
 * dependency, so it can be tested on a computer (tools/QrCheck.java).
 */
final class QrCode {

    private static final int MAX_VERSION = 6;
    /** Level M: error correction codewords per block, and number of blocks, by version. */
    private static final int[] ECC_PER_BLOCK = { -1, 10, 16, 26, 18, 24, 16 };
    private static final int[] BLOCKS = { -1, 1, 1, 1, 2, 2, 4 };
    /** The format bits of level M. */
    private static final int ECL_BITS = 0;

    private QrCode() { }

    /** The modules of the code, true for dark; square, without the quiet zone around it. */
    static boolean[][] encode(String text) {
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Exception e) { throw new IllegalStateException(e); }

        int version = 0;
        for (int v = 1; v <= MAX_VERSION; v++) {
            if (4 + 8 + data.length * 8 <= dataCodewords(v) * 8) { version = v; break; }
        }
        if (version == 0) throw new IllegalArgumentException("text too long for the code");

        // The bits: mode (byte), length, the bytes, a terminator, then padding to the capacity.
        int capacity = dataCodewords(version);
        BitBuffer bb = new BitBuffer();
        bb.append(0x4, 4);
        bb.append(data.length, 8);
        for (int i = 0; i < data.length; i++) bb.append(data[i] & 0xFF, 8);
        bb.append(0, Math.min(4, capacity * 8 - bb.length()));
        bb.append(0, (8 - bb.length() % 8) % 8);
        for (int pad = 0xEC; bb.length() < capacity * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[capacity];
        for (int i = 0; i < capacity * 8; i++) {
            codewords[i >>> 3] |= bb.bit(i) << (7 - (i & 7));
        }

        return new Grid(version, interleave(version, codewords)).build();
    }

    // ---------------------------------------------------------------- sizes

    private static int rawModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
        }
        return result;
    }

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    // ---------------------------------------------------------------- error correction

    /** Splits the data into blocks, adds each block's Reed-Solomon codewords and interleaves them. */
    private static byte[] interleave(int ver, byte[] data) {
        int numBlocks = BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver];
        int rawCodewords = rawModules(ver) / 8;
        int numShort = numBlocks - rawCodewords % numBlocks;
        int shortLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] gen = generator(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int dataLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[dataLen];
            System.arraycopy(data, k, dat, 0, dataLen);
            k += dataLen;
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, dataLen);
            byte[] ecc = remainder(dat, gen);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks[i] = block;
        }

        byte[] result = new byte[rawCodewords];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                // The short blocks have no byte where the long ones keep their last data byte.
                if (i != shortLen - eccLen || j >= numShort) {
                    result[k++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    /** The coefficients of the generator polynomial of the given degree, highest term left out. */
    private static byte[] generator(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, byte[] gen) {
        byte[] result = new byte[gen.length];
        for (int i = 0; i < data.length; i++) {
            int factor = (data[i] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int j = 0; j < result.length; j++) {
                result[j] ^= multiply(gen[j] & 0xFF, factor);
            }
        }
        return result;
    }

    /** Multiplication in GF(2^8) with the polynomial 0x11D. */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---------------------------------------------------------------- bits

    private static final class BitBuffer {
        private final java.util.ArrayList<Integer> bits = new java.util.ArrayList<Integer>();
        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) bits.add((value >>> i) & 1);
        }
        int length() { return bits.size(); }
        int bit(int i) { return bits.get(i); }
    }

    // ---------------------------------------------------------------- the picture

    private static final class Grid {
        final int version, size;
        final byte[] codewords;
        final boolean[][] modules;
        final boolean[][] fixed;

        Grid(int version, byte[] codewords) {
            this.version = version;
            this.codewords = codewords;
            size = version * 4 + 17;
            modules = new boolean[size][size];
            fixed = new boolean[size][size];
        }

        boolean[][] build() {
            // Timing patterns, then the three finders and the alignment patterns.
            for (int i = 0; i < size; i++) {
                set(6, i, i % 2 == 0);
                set(i, 6, i % 2 == 0);
            }
            finder(3, 3);
            finder(size - 4, 3);
            finder(3, size - 4);
            int[] pos = alignmentPositions();
            for (int i = 0; i < pos.length; i++) {
                for (int j = 0; j < pos.length; j++) {
                    boolean onFinder = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1)
                            || (i == pos.length - 1 && j == 0);
                    if (!onFinder) alignment(pos[i], pos[j]);
                }
            }
            formatBits(0);     // reserves the format areas before the data goes in
            place();

            int best = 0;
            long lowest = Long.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                mask(m);
                formatBits(m);
                long p = penalty();
                if (p < lowest) { lowest = p; best = m; }
                mask(m);       // the same mask again takes it off
            }
            mask(best);
            formatBits(best);
            return modules;
        }

        void set(int x, int y, boolean dark) {
            modules[y][x] = dark;
            fixed[y][x] = true;
        }

        void finder(int cx, int cy) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    int x = cx + dx, y = cy + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, dist != 2 && dist != 4);
                }
            }
        }

        void alignment(int cx, int cy) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }

        int[] alignmentPositions() {
            if (version == 1) return new int[0];
            int n = version / 7 + 2;
            int step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
            int[] result = new int[n];
            result[0] = 6;
            for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step) result[i] = p;
            return result;
        }

        void formatBits(int mask) {
            int data = ECL_BITS << 3 | mask;
            int rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
            int bits = (data << 10 | rem) ^ 0x5412;
            for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
            set(8, 7, bit(bits, 6));
            set(8, 8, bit(bits, 7));
            set(7, 8, bit(bits, 8));
            for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
            for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
            for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
            set(8, size - 8, true);     // the one module that is always dark
        }

        boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

        /** The codewords, two columns at a time, from the bottom right going up and down. */
        void place() {
            int i = 0;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                for (int vert = 0; vert < size; vert++) {
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        boolean upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!fixed[y][x] && i < codewords.length * 8) {
                            modules[y][x] = bit(codewords[i >>> 3], 7 - (i & 7));
                            i++;
                        }
                    }
                }
            }
        }

        void mask(int m) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    boolean flip;
                    switch (m) {
                        case 0: flip = (x + y) % 2 == 0; break;
                        case 1: flip = y % 2 == 0; break;
                        case 2: flip = x % 3 == 0; break;
                        case 3: flip = (x + y) % 3 == 0; break;
                        case 4: flip = (x / 3 + y / 2) % 2 == 0; break;
                        case 5: flip = x * y % 2 + x * y % 3 == 0; break;
                        case 6: flip = (x * y % 2 + x * y % 3) % 2 == 0; break;
                        default: flip = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    }
                    if (!fixed[y][x] && flip) modules[y][x] = !modules[y][x];
                }
            }
        }

        /** How hard the picture is to read: runs, blocks, finder look-alikes and balance. */
        long penalty() {
            long result = 0;
            int dark = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (modules[y][x]) dark++;
                }
            }
            for (int pass = 0; pass < 2; pass++) {
                for (int a = 0; a < size; a++) {
                    int run = 1;
                    for (int b = 1; b < size; b++) {
                        boolean same = get(pass, a, b) == get(pass, a, b - 1);
                        if (same) {
                            run++;
                            if (run == 5) result += 3; else if (run > 5) result++;
                        } else {
                            run = 1;
                        }
                    }
                    // 1011101 with four light modules on either side looks like a finder.
                    for (int b = 0; b + 10 < size; b++) {
                        if (looks(pass, a, b, new int[]{1,0,1,1,1,0,1,0,0,0,0})
                                || looks(pass, a, b, new int[]{0,0,0,0,1,0,1,1,1,0,1})) {
                            result += 40;
                        }
                    }
                }
            }
            for (int y = 0; y + 1 < size; y++) {
                for (int x = 0; x + 1 < size; x++) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                        result += 3;
                    }
                }
            }
            int total = size * size;
            int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
            return result + Math.max(0, k) * 10;
        }

        boolean get(int pass, int a, int b) { return pass == 0 ? modules[a][b] : modules[b][a]; }

        boolean looks(int pass, int a, int start, int[] pattern) {
            for (int i = 0; i < pattern.length; i++) {
                if ((get(pass, a, start + i) ? 1 : 0) != pattern[i]) return false;
            }
            return true;
        }
    }
}
