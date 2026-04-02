package com.xerox3025.printplugin;

import android.content.SharedPreferences;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintDocument;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class Xerox3025PrintService extends PrintService {

    private static final String TAG = "Xerox3025Print";
    private static final int RAW_PORT = 9100;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 30000;

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new Xerox3025DiscoverySession(this);
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob printJob) {
        printJob.cancel();
    }

    @Override
    protected void onPrintJobQueued(PrintJob printJob) {
        new Thread(() -> processPrintJob(printJob)).start();
    }

    private void processPrintJob(PrintJob printJob) {
        printJob.start();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String printerIp = prefs.getString("printer_ip", "192.168.0.109");
        int printerPort = RAW_PORT;

        try {
            String portStr = prefs.getString("printer_port", String.valueOf(RAW_PORT));
            printerPort = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid port, using default 9100");
        }

        Log.i(TAG, "Sending job to " + printerIp + ":" + printerPort);

        PrintDocument document = printJob.getDocument();
        ParcelFileDescriptor pfd = document.getData();

        if (pfd == null) {
            Log.e(TAG, "No document data");
            printJob.fail("No document data available");
            return;
        }

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(printerIp, printerPort), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                if (printJob.isCancelled()) {
                    Log.i(TAG, "Job cancelled by user");
                    printJob.cancel();
                    return;
                }
                out.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            out.flush();
            Log.i(TAG, "Sent " + totalBytes + " bytes to printer");

            in.close();
            printJob.complete();

        } catch (IOException e) {
            Log.e(TAG, "Print failed: " + e.getMessage(), e);
            printJob.fail("Could not connect to printer at " + printerIp + ":" + printerPort
                    + " — " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
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
            addPrinters(buildPrinterList());
        }

        @Override
        public void onStopPrinterDiscovery() {}

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {}

        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
            // Re-advertise with full capabilities when tracked
            List<PrinterInfo> printers = buildPrinterList();
            addPrinters(printers);
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {}

        @Override
        public void onDestroy() {}

        private List<PrinterInfo> buildPrinterList() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    service.getApplicationContext());
            String ip = prefs.getString("printer_ip", "192.168.0.109");
            String displayName = prefs.getString("printer_name", "Xerox WorkCentre 3025");

            PrinterId printerId = service.generatePrinterId("xerox3025_" + ip);

            PrinterCapabilitiesInfo capabilities = new PrinterCapabilitiesInfo.Builder(printerId)
                    // Paper sizes
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                    // Resolutions
                    .addResolution(new PrintAttributes.Resolution("600dpi", "600 dpi", 600, 600), true)
                    .addResolution(new PrintAttributes.Resolution("300dpi", "300 dpi", 300, 300), false)
                    // Color modes — 3025 is mono only
                    .setColorModes(
                            PrintAttributes.COLOR_MODE_MONOCHROME,
                            PrintAttributes.COLOR_MODE_MONOCHROME)
                    // Duplex
                    .setDuplexModes(
                            PrintAttributes.DUPLEX_MODE_NONE | PrintAttributes.DUPLEX_MODE_LONG_EDGE,
                            PrintAttributes.DUPLEX_MODE_NONE)
                    .build();

            PrinterInfo printer = new PrinterInfo.Builder(
                    printerId,
                    displayName,
                    PrinterInfo.STATUS_IDLE)
                    .setCapabilities(capabilities)
                    .build();

            List<PrinterInfo> list = new ArrayList<>();
            list.add(printer);
            return list;
        }
    }
}
