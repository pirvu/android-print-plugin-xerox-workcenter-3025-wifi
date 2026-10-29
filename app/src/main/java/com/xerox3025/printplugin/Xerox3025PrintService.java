package com.xerox3025.printplugin;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintJobId;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Xerox3025PrintService extends PrintService {
    private static final String TAG = "Xerox3025Print";
    private static final String CHANNEL_ID = "print_jobs";
    private static final String SUBMITTED = "xerox:submission-started";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<PrintJobId, JobControl> jobs = new HashMap<>(); // main thread only
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8));

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Print Jobs", NotificationManager.IMPORTANCE_LOW));
        // Only this service owns this directory. Remove private leftovers after process death.
        File[] stale = spoolDirectory().listFiles();
        if (stale != null) for (File f : stale) if (!f.delete()) PrintLog.w(TAG, "Could not remove stale spool file");
    }

    private File spoolDirectory() {
        File dir = new File(getCacheDir(), "print-spool");
        if (!dir.isDirectory() && !dir.mkdirs()) PrintLog.w(TAG, "Could not create spool directory");
        return dir;
    }

    @Override protected void onConnected() {
        // PrintJob tags live in the system spooler and survive this process dying.
        for (PrintJob job : getActivePrintJobs()) {
            if (SUBMITTED.equals(job.getTag()) && !jobs.containsKey(job.getId())) holdUnknown(job);
        }
    }

    private void holdUnknown(PrintJob job) {
        if (job.isQueued()) job.start();
        job.block("Result unknown. Check printer output; cancel this job before printing again. No automatic resend.");
    }

    @Override protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new Xerox3025DiscoverySession(this);
    }

    @Override protected void onRequestCancelPrintJob(PrintJob job) {
        JobControl token = jobs.get(job.getId());
        if (token != null) token.cancel();
        job.cancel();
        getSystemService(NotificationManager.class).cancel(job.getId().hashCode());
    }

    @Override public void onDestroy() {
        for (JobControl token : jobs.values()) token.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onPrintJobQueued(PrintJob job) {
        if (jobs.containsKey(job.getId())) return;
        if (SUBMITTED.equals(job.getTag())) { holdUnknown(job); return; }
        String name = job.getInfo().getLabel();
        PrintJobId id = job.getId();
        PrintAttributes attributes = job.getInfo().getAttributes();
        int copies = Math.max(1, job.getInfo().getCopies());
        // The Android spooler supplies the requested pages in the PDF it delivers to the service.
        if (!job.start()) return;
        ParcelFileDescriptor data = job.getDocument().getData();
        if (data == null) { job.fail("No document data available"); return; }
        JobControl control = new JobControl();
        try { control.attach(data); } catch (IOException e) { job.fail(e.getMessage()); return; }
        jobs.put(id, control);
        String ip = PreferenceManager.getDefaultSharedPreferences(this).getString("printer_ip", "192.168.0.109");
        try {
            worker.execute(() -> process(job, id, name, data, ip, attributes, copies, control));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            control.cancel(); jobs.remove(id); job.fail("Print queue full (maximum 9 jobs). Try later.");
        }
    }

    private void process(PrintJob job, PrintJobId id, String name, ParcelFileDescriptor data,
                         String ip, PrintAttributes attributes, int copies, JobControl control) {
        File pdf = null, urf = null;
        int pages = 0;
        boolean armed = false;
        IppClient.IppResult result;
        notifyStatus(id, "Preparing print", "600 DPI", true);
        try {
            control.check();
            LocalPrinterAddress.parse(ip);
            pdf = File.createTempFile("print_", ".pdf", spoolDirectory());
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(data);
                 FileOutputStream out = new FileOutputStream(pdf)) {
                byte[] buffer = new byte[32768];
                long size = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    control.check();
                    size += n;
                    if (size > 256L * 1024 * 1024) throw new IOException("PDF exceeds 256 MiB limit");
                    out.write(buffer, 0, n);
                }
            } finally { control.detach(data); }
            urf = File.createTempFile("print_", ".urf", spoolDirectory());
            pages = PdfSpooler.encode(pdf, urf, attributes, copies, control);
            control.check();
            FutureTask<Boolean> mark = new FutureTask<>(() -> !control.isCancelled()
                    && job.isStarted() && job.setTag(SUBMITTED));
            mainHandler.post(mark);
            if (!mark.get(10, TimeUnit.SECONDS)) throw new IOException("Could not record submission state");
            armed = true;
            control.check();
            notifyStatus(id, "Sending print", pages + " page(s)", true);
            result = IppClient.sendPrintJob(ip, urf, name, control);
        } catch (Exception | OutOfMemoryError e) {
            result = new IppClient.IppResult(control.isCancelled() ? IppClient.Outcome.CANCELLED
                    : armed ? IppClient.Outcome.UNKNOWN : IppClient.Outcome.REJECTED, -1,
                    (armed ? "Result unknown. Check printer before printing again. " : "Not sent: ")
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
            PrintLog.e(TAG, "Job processing stopped: " + result.message);
        } finally {
            control.detach(data);
            try { data.close(); } catch (IOException ignored) { }
            if (pdf != null && !pdf.delete()) PrintLog.w(TAG, "Could not remove PDF spool");
            if (urf != null && !urf.delete()) PrintLog.w(TAG, "Could not remove URF spool");
        }
        IppClient.IppResult finalResult = result;
        int finalPages = pages;
        mainHandler.post(() -> {
            jobs.remove(id);
            String state;
            if (control.isCancelled() || job.isCancelled()) {
                if (!job.isCancelled()) job.cancel();
                state = "CANCELLED";
                getSystemService(NotificationManager.class).cancel(id.hashCode());
            } else if (finalResult.outcome == IppClient.Outcome.ACCEPTED) {
                job.complete(); state = "ACCEPTED";
                notifyStatus(id, "Printer accepted job", "Physical completion is not monitored", false);
            } else if (finalResult.outcome == IppClient.Outcome.UNKNOWN) {
                holdUnknown(job); state = "UNKNOWN";
                notifyStatus(id, "Print result unknown", "Check printer before printing again", false);
            } else {
                job.fail(finalResult.message); state = "FAILED";
                notifyStatus(id, "Print not accepted", finalResult.message, false);
            }
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(name, System.currentTimeMillis(),
                    state, control.isCancelled() ? "Sending stopped; already received pages may still print."
                    : finalResult.message, finalPages));
            PrintLog.i(TAG, "Job result: " + state + "; " + finalResult.message);
        });
    }

    private void notifyStatus(PrintJobId id, String title, String detail, boolean ongoing) {
        try {
            getSystemService(NotificationManager.class).notify(id.hashCode(),
                    new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_printer)
                            .setContentTitle(title).setContentText(detail).setOngoing(ongoing)
                            .setAutoCancel(!ongoing).build());
        } catch (SecurityException e) { PrintLog.w(TAG, "Notifications disabled"); }
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
                    .setMinMargins(new PrintAttributes.Margins(197, 197, 197, 197))
                    .setColorModes(
                            PrintAttributes.COLOR_MODE_MONOCHROME,
                            PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setDuplexModes(
                            PrintAttributes.DUPLEX_MODE_NONE,
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
