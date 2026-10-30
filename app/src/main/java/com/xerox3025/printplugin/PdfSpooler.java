package com.xerox3025.printplugin;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** One PDF page open at a time, at most one 128-row ARGB band resident. */
final class PdfSpooler {
    static final int BAND_ROWS = 128;
    static final long MAX_URF_BYTES = 512L * 1024 * 1024;
    static final int MAX_PAGES = 1000;

    static int encode(File pdf, File urf, PrintAttributes attributes, int copies, JobControl control)
            throws IOException {
        if (copies < 1 || copies > 100) throw new IOException("Copies must be between 1 and 100");
        if (attributes != null && attributes.getDuplexMode() != 0
                && attributes.getDuplexMode() != PrintAttributes.DUPLEX_MODE_NONE) {
            throw new IOException("Duplex is not supported");
        }
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd);
             OutputStream out = new BufferedOutputStream(new LimitedOutput(new FileOutputStream(urf)))) {
            int count = renderer.getPageCount();
            if (count < 1 || (long)count * copies > MAX_PAGES) throw new IOException("Page limit exceeded (1000 including copies)");
            UrfEncoder.writeHeader(out, count * copies);
            for (int copy = 0; copy < copies; copy++) {
                for (int i = 0; i < count; i++) {
                    control.check();
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        int[] size = dimensions(attributes, page.getWidth(), page.getHeight());
                        try (BandSource rows = new BandSource(page, size[0], size[1], control)) {
                            UrfEncoder.writePage(out, size[0], size[1], rows, control);
                        }
                    }
                }
            }
            control.check();
            return count * copies;
        }
    }

    static int[] dimensions(PrintAttributes attributes, int pdfWidth, int pdfHeight) throws IOException {
        PrintAttributes.MediaSize media = attributes == null ? null : attributes.getMediaSize();
        int width, height;
        if (media == null) {
            // Diagnostic input has no print attributes. Preserve original PDF physical size.
            width = Math.round(pdfWidth * (600f / 72));
            height = Math.round(pdfHeight * (600f / 72));
        } else {
            String id = media.getId();
            if (!id.equals(PrintAttributes.MediaSize.ISO_A4.getId())
                    && !id.equals(PrintAttributes.MediaSize.ISO_A5.getId())
                    && !id.equals(PrintAttributes.MediaSize.NA_LETTER.getId())) throw new IOException("Unsupported paper size");
            width = (int)(media.getWidthMils() * 0.6f);
            height = (int)(media.getHeightMils() * 0.6f);
        }
        if (width < 1 || height < 1 || width > 7200 || height > 7200) throw new IOException("Unsupported page size at 600 DPI");
        return new int[]{width, height};
    }

    static final class BandSource implements UrfEncoder.RowSource, AutoCloseable {
        final Bitmap band;
        private final PdfRenderer.Page page;
        private final int width, height;
        private final JobControl control;
        private final int[] pixels;
        private int top = -1;

        BandSource(PdfRenderer.Page page, int width, int height, JobControl control) {
            this.page = page; this.width = width; this.height = height; this.control = control;
            band = Bitmap.createBitmap(width, Math.min(BAND_ROWS, height), Bitmap.Config.ARGB_8888);
            pixels = new int[width];
        }

        @Override public void read(int y, byte[] gray) throws IOException {
            control.check();
            if (top < 0 || y < top || y >= top + band.getHeight()) {
                top = y;
                band.eraseColor(0xffffffff);
                // Fit uniformly to the selected sheet, retaining aspect ratio and white margins.
                float scale = Math.min((float)width / page.getWidth(), (float)height / page.getHeight());
                Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                matrix.postTranslate((width - page.getWidth() * scale) / 2,
                        (height - page.getHeight() * scale) / 2 - top);
                page.render(band, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                control.check();
            }
            band.getPixels(pixels, 0, width, 0, y - top, width, 1);
            UrfEncoder.toGray(pixels, gray);
        }

        @Override public void close() { band.recycle(); }
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private long written;
        LimitedOutput(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { reserve(1); out.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { reserve(len); out.write(b, off, len); }
        private void reserve(int len) throws IOException {
            if (len > MAX_URF_BYTES - written) throw new IOException("URF spool limit exceeded (512 MiB)");
            written += len;
        }
    }
}
