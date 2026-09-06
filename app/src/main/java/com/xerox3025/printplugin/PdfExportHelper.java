package com.xerox3025.printplugin;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PdfExportHelper {

    public static class ExportResult {
        public final boolean success;
        public final String filePath;
        public final String error;

        public ExportResult(boolean success, String filePath, String error) {
            this.success = success;
            this.filePath = filePath;
            this.error = error;
        }
    }

    private static final float A4_WIDTH_PT = 595;
    private static final float A4_HEIGHT_PT = 842;

    public static ExportResult exportToPdf(Context context, byte[] imageData, String mimeType) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
        if (bitmap == null) {
            return new ExportResult(false, null, "Could not decode image data");
        }

        try {
            PdfDocument document = createPdfFromBitmap(bitmap);
            bitmap.recycle();

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            String filename = "Scan_" + timestamp + ".pdf";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveViaMediaStore(context, document, filename);
            } else {
                return saveToDownloadsLegacy(context, document, filename);
            }
        } catch (Exception e) {
            return new ExportResult(false, null, e.getMessage());
        }
    }

    private static PdfDocument createPdfFromBitmap(Bitmap bitmap) {
        PdfDocument document = new PdfDocument();

        int imgWidth = bitmap.getWidth();
        int imgHeight = bitmap.getHeight();

        float scale = Math.min(A4_WIDTH_PT / imgWidth, A4_HEIGHT_PT / imgHeight);
        int scaledWidth = (int) (imgWidth * scale);
        int scaledHeight = (int) (imgHeight * scale);
        float offsetX = (A4_WIDTH_PT - scaledWidth) / 2f;
        float offsetY = (A4_HEIGHT_PT - scaledHeight) / 2f;

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                (int) A4_WIDTH_PT, (int) A4_HEIGHT_PT, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);

        Canvas canvas = page.getCanvas();
        canvas.drawColor(android.graphics.Color.WHITE);
        canvas.drawBitmap(bitmap, null,
                new android.graphics.RectF(offsetX, offsetY,
                        offsetX + scaledWidth, offsetY + scaledHeight),
                null);

        document.finishPage(page);
        return document;
    }

    private static ExportResult saveViaMediaStore(Context context, PdfDocument document,
                                                  String filename) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            document.close();
            return new ExportResult(false, null, "Failed to create MediaStore entry");
        }

        OutputStream out = context.getContentResolver().openOutputStream(uri);
        if (out == null) {
            document.close();
            return new ExportResult(false, null, "Failed to open output stream");
        }

        document.writeTo(out);
        out.close();
        document.close();

        return new ExportResult(true, "Downloads/" + filename, null);
    }

    private static ExportResult saveToDownloadsLegacy(Context context, PdfDocument document,
                                                      String filename) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, filename);
        FileOutputStream fos = new FileOutputStream(file);
        document.writeTo(fos);
        fos.close();
        document.close();

        return new ExportResult(true, file.getAbsolutePath(), null);
    }
}
