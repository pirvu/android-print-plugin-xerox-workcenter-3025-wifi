package com.xerox3025.printplugin;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class UrfEncoder {

    private static final String TAG = "UrfEncoder";

    // A4 at 600 DPI — must match exactly what the printer expects
    public static final int A4_WIDTH_600 = 4960;
    public static final int A4_HEIGHT_600 = 7015;
    public static final int DPI_600 = 600;

    /**
     * Encode one or more grayscale bitmaps as a URF document.
     * Each bitmap should be A4_WIDTH_600 x A4_HEIGHT_600 pixels.
     */
    public static byte[] encode(Bitmap[] pages) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // File header: "UNIRAST\0" + page count
        out.write(new byte[]{'U', 'N', 'I', 'R', 'A', 'S', 'T', 0x00});
        writeInt(out, pages.length);

        for (int p = 0; p < pages.length; p++) {
            Bitmap bmp = pages[p];
            int width = bmp.getWidth();
            int height = bmp.getHeight();

            PrintLog.i(TAG, "Encoding page " + (p + 1) + "/" + pages.length
                    + " (" + width + "x" + height + ")");

            // Page header (32 bytes)
            byte[] pageHdr = new byte[32];
            pageHdr[0] = 8;    // bitsPerPixel
            pageHdr[1] = 0;    // colorSpace: W8 (grayscale)
            pageHdr[2] = 1;    // duplex: 1 (one-sided, required by printer)
            pageHdr[3] = 0;    // quality: default
            // bytes 4-11: reserved (zeros)
            putInt(pageHdr, 12, width);
            putInt(pageHdr, 16, height);
            putInt(pageHdr, 20, DPI_600);
            // bytes 24-31: reserved (zeros)
            out.write(pageHdr);

            // Convert bitmap to grayscale byte rows and encode with PWG compression
            byte[] prevRow = null;
            int y = 0;

            while (y < height) {
                byte[] row = bitmapRowToGray(bmp, y, width);

                // Count identical following lines for repeat
                int repeat = 0;
                while (y + repeat + 1 < height && repeat < 255) {
                    byte[] nextRow = bitmapRowToGray(bmp, y + repeat + 1, width);
                    if (java.util.Arrays.equals(row, nextRow)) {
                        repeat++;
                    } else {
                        break;
                    }
                }

                // Line repeat count
                out.write(repeat);

                // PWG-compressed line data
                pwgEncodeLine(out, row);

                y += repeat + 1;
            }

            long pageSize = out.size();
            PrintLog.i(TAG, "Page " + (p + 1) + " encoded, total so far: " + pageSize + " bytes");
        }

        return out.toByteArray();
    }

    /**
     * Extract one row of grayscale pixels from a bitmap.
     * Input bitmap is ARGB_8888; output is 8-bit grayscale (0=black, 255=white).
     */
    private static byte[] bitmapRowToGray(Bitmap bmp, int y, int width) {
        int[] pixels = new int[width];
        bmp.getPixels(pixels, 0, width, 0, y, width, 1);

        byte[] gray = new byte[width];
        for (int x = 0; x < width; x++) {
            int pixel = pixels[x];
            int r = Color.red(pixel);
            int g = Color.green(pixel);
            int b = Color.blue(pixel);
            // Standard luminance formula
            gray[x] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
        }
        return gray;
    }

    /**
     * PWG raster compression for one scanline.
     * 0-127: repeat next byte (N+1) times
     * 128-255: copy next (N-127) literal bytes
     */
    private static void pwgEncodeLine(ByteArrayOutputStream out, byte[] row) {
        int i = 0;
        while (i < row.length) {
            byte val = row[i];
            int run = 1;
            while (i + run < row.length && row[i + run] == val && run < 128) {
                run++;
            }

            if (run >= 2) {
                // Repeat encoding
                out.write(run - 1);
                out.write(val & 0xFF);
                i += run;
            } else {
                // Literal encoding
                int litStart = i;
                ByteArrayOutputStream lit = new ByteArrayOutputStream();
                while (i < row.length && lit.size() < 128) {
                    byte v = row[i];
                    int r = 1;
                    while (i + r < row.length && row[i + r] == v && r < 128) {
                        r++;
                    }
                    if (r >= 2 && lit.size() > 0) {
                        break;
                    }
                    lit.write(v & 0xFF);
                    i++;
                }
                out.write(lit.size() - 1 + 128);
                try {
                    lit.writeTo(out);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void putInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 3] = (byte) (value & 0xFF);
    }
}
