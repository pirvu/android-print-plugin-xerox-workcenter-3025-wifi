package com.xerox3025.printplugin;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

public class UrfEncoder {
    public static final int A4_WIDTH_600 = 4960;
    public static final int A4_HEIGHT_600 = 7015;
    public static final int DPI_600 = 600;

    interface RowSource { void read(int y, byte[] gray) throws IOException; }

    static void writeHeader(OutputStream out, int pages) throws IOException {
        if (pages < 1) throw new IOException("No pages to print");
        out.write(new byte[]{'U', 'N', 'I', 'R', 'A', 'S', 'T', 0});
        out.write(new byte[]{(byte)(pages >>> 24), (byte)(pages >>> 16), (byte)(pages >>> 8), (byte)pages});
    }

    static void writePage(OutputStream out, int width, int height, RowSource source,
                          JobControl control) throws IOException {
        if (width < 1 || height < 1 || width > 7200 || height > 7200) throw new IOException("Unsupported raster size");
        byte[] header = new byte[32];
        header[0] = 8; header[2] = 1; // W8, simplex; preserve upstream printer-tested format.
        putInt(header, 12, width); putInt(header, 16, height); putInt(header, 20, DPI_600);
        out.write(header);
        byte[] row = new byte[width], next = new byte[width];
        int repeats = 0;
        control.check();
        source.read(0, row);
        for (int y = 1; y < height; y++) {
            control.check();
            source.read(y, next);
            if (repeats < 255 && Arrays.equals(row, next)) {
                repeats++;
            } else {
                out.write(repeats);
                pwgEncodeLine(out, row);
                byte[] swap = row; row = next; next = swap;
                repeats = 0;
            }
        }
        control.check();
        out.write(repeats);
        pwgEncodeLine(out, row);
    }

    /** Compatibility helper for small callers; the print service uses the streaming API. */
    public static byte[] encode(Bitmap[] pages) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeHeader(out, pages.length);
        for (Bitmap bitmap : pages) {
            int[] pixels = new int[bitmap.getWidth()];
            writePage(out, bitmap.getWidth(), bitmap.getHeight(), (y, gray) -> {
                bitmap.getPixels(pixels, 0, pixels.length, 0, y, pixels.length, 1);
                toGray(pixels, gray);
            }, new JobControl());
        }
        return out.toByteArray();
    }

    static void toGray(int[] pixels, byte[] gray) {
        for (int x = 0; x < gray.length; x++) {
            int p = pixels[x];
            gray[x] = (byte)((((p >>> 16) & 255) * 77 + ((p >>> 8) & 255) * 150 + (p & 255) * 29) >>> 8);
        }
    }

    // CUPS PackBits: 0..127 repeat N+1; 129..255 literal 257-N. Singleton is run 0.
    static void pwgEncodeLine(OutputStream out, byte[] row) throws IOException {
        int i = 0;
        while (i < row.length) {
            int run = 1;
            while (i + run < row.length && row[i + run] == row[i] && run < 128) run++;
            if (run >= 3 || run == 2 && i + run == row.length) {
                out.write(run - 1); out.write(row[i] & 255); i += run;
            } else {
                int start = i;
                while (i < row.length && i - start < 128) {
                    int r = 1;
                    while (i + r < row.length && row[i + r] == row[i] && r < 3) r++;
                    if (r >= 3 && i > start) break;
                    i++; // a single byte step makes the 128-byte boundary exact
                }
                int count = i - start;
                out.write(count == 1 ? 0 : 257 - count);
                out.write(row, start, count);
            }
        }
    }

    private static void putInt(byte[] b, int offset, int n) {
        b[offset] = (byte)(n >>> 24); b[offset + 1] = (byte)(n >>> 16);
        b[offset + 2] = (byte)(n >>> 8); b[offset + 3] = (byte)n;
    }
}
