package com.xerox3025.printplugin;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
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
import java.util.concurrent.CopyOnWriteArrayList;

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
                CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
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
            String err = getString(R.string.print_error_no_doc);
            PrintLog.e(TAG, err);
            printJob.fail(err);
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED",
                    err, 0));
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
                .setContentTitle(getString(R.string.notif_printing_title))
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
                            getString(R.string.print_error_cancelled), pageCount));
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
                        getString(R.string.print_error_cancelled), pageCount));
                return;
            }

            // Send via IPP
            IppClient.IppResult result = IppClient.sendPrintJob(printerIp, urfData, jobName);

            if (result.success) {
                PrintLog.i(TAG, "Print job completed: " + jobName);
                mainHandler.post(printJob::complete);

                // Update notification
                try {
                    String pageStr = getString(R.string.pages_count, pageCount);
                    notifBuilder.setContentTitle(getString(R.string.notif_print_complete_title))
                            .setContentText(jobName + " — " + pageStr)
                            .setOngoing(false)
                            .setAutoCancel(true)
                            .setProgress(0, 0, false);
                    getSystemService(NotificationManager.class).notify(notifId, notifBuilder.build());
                } catch (Exception ignored) {}

                PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                        jobName, System.currentTimeMillis(), "COMPLETED",
                        urfData.length + " bytes, " + getString(R.string.pages_count, pageCount), pageCount));
            } else {
                PrintLog.e(TAG, "IPP failed: " + result.message);
                mainHandler.post(() -> printJob.fail(result.message));
                try {
                    NotificationCompat.Builder notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_printer)
                            .setContentTitle(getString(R.string.notif_print_failed_title))
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
            String reason = getString(R.string.print_error_could_not_print, printerIp, e.getMessage());
            mainHandler.post(() -> printJob.fail(reason));
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED", reason, 0));
        } catch (OutOfMemoryError e) {
            PrintLog.e(TAG, "Out of memory rendering PDF");
            String reason = getString(R.string.print_error_oom);
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
    // Discovery session — discovers printers via mDNS + manual fallback
    // -------------------------------------------------------------------------
    static class Xerox3025DiscoverySession extends PrinterDiscoverySession {

        private final PrintService service;
        private NsdManager nsdManager;
        private NsdManager.DiscoveryListener discoveryListener;
        private final CopyOnWriteArrayList<PrinterInfo> discoveredPrinters = new CopyOnWriteArrayList<>();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private static final int DISCOVERY_TIMEOUT_MS = 10000;

        Xerox3025DiscoverySession(PrintService service) {
            this.service = service;
        }

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            PrintLog.i(TAG, "onStartPrinterDiscovery (priority list size: " + priorityList.size() + ")");
            addManualPrinter();
            startMdnsDiscovery();
        }

        @Override
        public void onStopPrinterDiscovery() {
            PrintLog.i(TAG, "onStopPrinterDiscovery");
            stopMdnsDiscovery();
        }

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
            PrintLog.i(TAG, "onValidatePrinters: " + printerIds.size() + " printer(s)");
        }

        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
            PrintLog.i(TAG, "onStartPrinterStateTracking: " + printerId);
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {
            PrintLog.i(TAG, "onStopPrinterStateTracking");
        }

        @Override
        public void onDestroy() {
            PrintLog.i(TAG, "DiscoverySession onDestroy");
            stopMdnsDiscovery();
        }

        private void addManualPrinter() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    service.getApplicationContext());
            String ip = prefs.getString("printer_ip", "192.168.0.109");
            String displayName = prefs.getString("printer_name", "Xerox WorkCentre 3025");
            PrintLog.i(TAG, "addManualPrinter: name=" + displayName + ", ip=" + ip);

            PrinterId printerId = service.generatePrinterId("xerox3025_" + ip);
            PrinterInfo printer = buildPrinterInfo(printerId, displayName);

            List<PrinterInfo> list = new ArrayList<>();
            list.add(printer);
            addPrinters(list);
        }

        private void startMdnsDiscovery() {
            nsdManager = (NsdManager) service.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) {
                PrintLog.w(TAG, "NSD service not available");
                return;
            }

            PrintLog.i(TAG, "Starting mDNS discovery for _ipp._tcp");
            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                    PrintLog.i(TAG, "mDNS discovery started: " + serviceType);
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    PrintLog.i(TAG, "mDNS service found: " + serviceInfo.getServiceName());
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            PrintLog.w(TAG, "mDNS resolve failed: " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            String host = info.getHost() != null
                                    ? info.getHost().getHostAddress() : null;
                            if (host == null) return;

                            String name = info.getServiceName();
                            PrintLog.i(TAG, "mDNS resolved: " + name + " at " + host);

                            mainHandler.post(() -> {
                                PrinterId printerId = service.generatePrinterId("mdns_" + host);
                                PrinterInfo printer = buildPrinterInfo(printerId, name);
                                List<PrinterInfo> list = new ArrayList<>();
                                list.add(printer);
                                addPrinters(list);
                                discoveredPrinters.add(printer);
                            });
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                    PrintLog.i(TAG, "mDNS service lost: " + serviceInfo.getServiceName());
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    PrintLog.i(TAG, "mDNS discovery stopped: " + serviceType);
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    PrintLog.w(TAG, "mDNS discovery start failed: " + errorCode);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    PrintLog.w(TAG, "mDNS stop discovery failed: " + errorCode);
                }
            };

            try {
                nsdManager.discoverServices("_ipp._tcp.",
                        NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            } catch (Exception e) {
                PrintLog.e(TAG, "Failed to start mDNS discovery: " + e.getMessage());
            }

            mainHandler.postDelayed(this::stopMdnsDiscovery, DISCOVERY_TIMEOUT_MS);
        }

        private void stopMdnsDiscovery() {
            if (nsdManager != null && discoveryListener != null) {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener);
                } catch (Exception e) {
                    PrintLog.w(TAG, "Error stopping mDNS: " + e.getMessage());
                }
                discoveryListener = null;
            }
        }

        private PrinterInfo buildPrinterInfo(PrinterId printerId, String displayName) {
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

            return new PrinterInfo.Builder(
                    printerId, displayName, PrinterInfo.STATUS_IDLE)
                    .setCapabilities(capabilities)
                    .build();
        }
    }
}
