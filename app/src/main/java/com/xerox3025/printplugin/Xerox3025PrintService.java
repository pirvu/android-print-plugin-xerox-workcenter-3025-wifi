package com.xerox3025.printplugin;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintDocument;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Xerox3025PrintService extends PrintService {

    private static final String TAG = "Xerox3025Print";
    private static final String CHANNEL_ID = "print_jobs";
    private volatile boolean cancelled = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        PrintLog.i(TAG, "=== PrintService onCreate ===");
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Print Jobs", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Print job status notifications");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        PrintLog.i(TAG, "=== PrintService onStartCommand ===");
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onConnected() {
        PrintLog.i(TAG, "=== PrintService onConnected (enabled by user) ===");
    }

    @Override
    protected void onDisconnected() {
        PrintLog.i(TAG, "=== PrintService onDisconnected ===");
    }

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        PrintLog.i(TAG, "=== onCreatePrinterDiscoverySession ===");
        return new Xerox3025DiscoverySession(this);
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob printJob) {
        cancelled = true;
        printJob.cancel();
    }

    @Override
    protected void onPrintJobQueued(PrintJob printJob) {
        // All PrintJob methods must be called on the main thread
        String jobName = printJob.getInfo().getLabel();
        int notifId = printJob.getId().hashCode();
        PrintLog.i(TAG, "onPrintJobQueued called: " + jobName);
        cancelled = false;
        printJob.start();

        // Get document data on main thread
        PrintDocument document = printJob.getDocument();
        ParcelFileDescriptor pfd = document.getData();
        if (pfd == null) {
            PrintLog.e(TAG, "No document data");
            printJob.fail("No document data available");
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED",
                    "No document data available", 0));
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String printerIp = prefs.getString("printer_ip", "192.168.0.109");

        new Thread(() -> {
            try {
                processPrintJob(printJob, jobName, notifId, pfd, printerIp);
            } catch (Exception e) {
                PrintLog.e(TAG, "Uncaught exception in processPrintJob", e);
                mainHandler.post(() -> {
                    try { printJob.fail("Internal error: " + e.getMessage()); }
                    catch (Exception ignored) {}
                });
                PrintJobHistory.addJob(getApplicationContext(),
                        new PrintJobHistory.JobRecord(jobName,
                                System.currentTimeMillis(), "FAILED",
                                "Crash: " + e.getMessage(), 0));
            }
        }).start();
    }

    private void processPrintJob(PrintJob printJob, String jobName, int notifId,
                                  ParcelFileDescriptor pfd, String printerIp) {
        PrintLog.i(TAG, "Starting print job: " + jobName);

        // Show ongoing notification
        NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_printer)
                .setContentTitle("Printing...")
                .setContentText(jobName)
                .setOngoing(true)
                .setProgress(0, 0, true);
        try {
            getSystemService(NotificationManager.class).notify(notifId, notifBuilder.build());
        } catch (Exception e) {
            PrintLog.w(TAG, "Could not show notification: " + e.getMessage());
        }

        File tempFile = null;
        try {
            // Copy PFD to temp file (PdfRenderer needs seekable FD)
            tempFile = File.createTempFile("print_", ".pdf", getCacheDir());
            copyToFile(pfd, tempFile);
            long pdfSize = tempFile.length();
            PrintLog.i(TAG, "PDF copied to temp file: " + pdfSize + " bytes");

            // Open PDF and render pages to URF
            ParcelFileDescriptor tempPfd = ParcelFileDescriptor.open(tempFile,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer = new PdfRenderer(tempPfd);
            int pageCount = renderer.getPageCount();
            PrintLog.i(TAG, "PDF has " + pageCount + " page(s)");

            Bitmap[] pages = new Bitmap[pageCount];
            for (int i = 0; i < pageCount; i++) {
                if (cancelled) {
                    PrintLog.i(TAG, "Job cancelled by user");
                    renderer.close();
                    tempPfd.close();
                    mainHandler.post(printJob::cancel);
                    getSystemService(NotificationManager.class).cancel(notifId);
                    PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                            jobName, System.currentTimeMillis(), "CANCELLED",
                            "Cancelled by user", pageCount));
                    return;
                }

                long renderStart = System.currentTimeMillis();
                PdfRenderer.Page page = renderer.openPage(i);

                // Render at 600 DPI, matching printer's expected dimensions
                Bitmap bmp = Bitmap.createBitmap(
                        UrfEncoder.A4_WIDTH_600, UrfEncoder.A4_HEIGHT_600,
                        Bitmap.Config.ARGB_8888);
                // Fill white first (PDF may not fill background)
                bmp.eraseColor(0xFFFFFFFF);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                page.close();

                pages[i] = bmp;
                long renderTime = System.currentTimeMillis() - renderStart;
                PrintLog.i(TAG, "Page " + (i + 1) + " rendered in " + renderTime + " ms");
            }
            renderer.close();
            tempPfd.close();

            // Encode as URF
            long encodeStart = System.currentTimeMillis();
            byte[] urfData = UrfEncoder.encode(pages);
            long encodeTime = System.currentTimeMillis() - encodeStart;
            PrintLog.i(TAG, "URF encoded: " + urfData.length + " bytes in " + encodeTime + " ms");

            // Recycle bitmaps
            for (Bitmap bmp : pages) {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            }

            if (cancelled) {
                PrintLog.i(TAG, "Job cancelled by user");
                mainHandler.post(printJob::cancel);
                getSystemService(NotificationManager.class).cancel(notifId);
                PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                        jobName, System.currentTimeMillis(), "CANCELLED",
                        "Cancelled by user", pageCount));
                return;
            }

            // Send via IPP
            IppClient.IppResult result = IppClient.sendPrintJob(printerIp, urfData, jobName);

            if (result.success) {
                PrintLog.i(TAG, "Print job completed: " + jobName);
                mainHandler.post(printJob::complete);

                // Update notification
                try {
                    notifBuilder.setContentTitle("Print complete")
                            .setContentText(jobName + " — " + pageCount + " page(s)")
                            .setOngoing(false)
                            .setAutoCancel(true)
                            .setProgress(0, 0, false);
                    getSystemService(NotificationManager.class).notify(notifId, notifBuilder.build());
                } catch (Exception ignored) {}

                PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                        jobName, System.currentTimeMillis(), "COMPLETED",
                        urfData.length + " bytes, " + pageCount + " page(s)", pageCount));
            } else {
                PrintLog.e(TAG, "IPP failed: " + result.message);
                mainHandler.post(() -> printJob.fail(result.message));
                try {
                    NotificationCompat.Builder notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_printer)
                            .setContentTitle("Print failed")
                            .setContentText(result.message)
                            .setOngoing(false).setAutoCancel(true);
                    getSystemService(NotificationManager.class).notify(notifId, notif.build());
                } catch (Exception ignored) {}
                PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                        jobName, System.currentTimeMillis(), "FAILED",
                        result.message, pageCount));
            }

        } catch (IOException e) {
            PrintLog.e(TAG, "Print failed: " + e.getMessage(), e);
            String reason = "Could not print to " + printerIp + " — " + e.getMessage();
            mainHandler.post(() -> printJob.fail(reason));
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED", reason, 0));
        } catch (OutOfMemoryError e) {
            PrintLog.e(TAG, "Out of memory rendering PDF");
            String reason = "Document too large to render at 600 DPI";
            mainHandler.post(() -> printJob.fail(reason));
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED", reason, 0));
        } finally {
            if (tempFile != null) tempFile.delete();
        }
    }

    private void copyToFile(ParcelFileDescriptor pfd, File dest) throws IOException {
        InputStream in = new FileInputStream(pfd.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        out.close();
        in.close();
    }

    // -------------------------------------------------------------------------
    // Discovery session — advertises the printer to Android's print system
    // -------------------------------------------------------------------------
    static class Xerox3025DiscoverySession extends PrinterDiscoverySession {

        private final PrintService service;

        Xerox3025DiscoverySession(PrintService service) {
            this.service = service;
        }

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            PrintLog.i(TAG, "onStartPrinterDiscovery (priority list size: " + priorityList.size() + ")");
            addPrinters(buildPrinterList());
        }

        @Override
        public void onStopPrinterDiscovery() {
            PrintLog.i(TAG, "onStopPrinterDiscovery");
        }

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
            PrintLog.i(TAG, "onValidatePrinters: " + printerIds.size() + " printer(s)");
        }

        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
            PrintLog.i(TAG, "onStartPrinterStateTracking: " + printerId);
            addPrinters(buildPrinterList());
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {
            PrintLog.i(TAG, "onStopPrinterStateTracking");
        }

        @Override
        public void onDestroy() {
            PrintLog.i(TAG, "DiscoverySession onDestroy");
        }

        private List<PrinterInfo> buildPrinterList() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    service.getApplicationContext());
            String ip = prefs.getString("printer_ip", "192.168.0.109");
            String displayName = prefs.getString("printer_name", "Xerox WorkCentre 3025");
            PrintLog.i(TAG, "buildPrinterList: name=" + displayName + ", ip=" + ip);

            PrinterId printerId = service.generatePrinterId("xerox3025_" + ip);

            PrinterCapabilitiesInfo capabilities = new PrinterCapabilitiesInfo.Builder(printerId)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                    .addResolution(new PrintAttributes.Resolution("600dpi", "600 dpi", 600, 600), true)
                    .addResolution(new PrintAttributes.Resolution("300dpi", "300 dpi", 300, 300), false)
                    .setColorModes(
                            PrintAttributes.COLOR_MODE_MONOCHROME,
                            PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setDuplexModes(
                            PrintAttributes.DUPLEX_MODE_NONE | PrintAttributes.DUPLEX_MODE_LONG_EDGE,
                            PrintAttributes.DUPLEX_MODE_NONE)
                    .build();

            PrinterInfo printer = new PrinterInfo.Builder(
                    printerId, displayName, PrinterInfo.STATUS_IDLE)
                    .setCapabilities(capabilities)
                    .build();

            List<PrinterInfo> list = new ArrayList<>();
            list.add(printer);
            return list;
        }
    }
}
