package com.xerox3025.printplugin;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SettingsActivity extends AppCompatActivity {

    private static final int NOTIF_PERMISSION_REQUEST = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
            getSupportActionBar().setSubtitle(R.string.settings_subtitle);
        }

        // Request notification permission on API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIF_PERMISSION_REQUEST);
            }
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private static final int CONNECT_TIMEOUT_MS = 5000;
        private static final int IPP_PORT = 631;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            EditTextPreference ipPref = findPreference("printer_ip");
            if (ipPref != null) {
                ipPref.setOnBindEditTextListener(editText ->
                        editText.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_URI));
            }

            Preference testNetwork = findPreference("test_network");
            if (testNetwork != null) {
                testNetwork.setOnPreferenceClickListener(pref -> {
                    runNetworkTest();
                    return true;
                });
            }

            Preference printTestPage = findPreference("print_test_page");
            if (printTestPage != null) {
                printTestPage.setOnPreferenceClickListener(pref -> {
                    runPrintTestPage();
                    return true;
                });
            }

            Preference printViaAndroid = findPreference("print_via_android");
            if (printViaAndroid != null) {
                printViaAndroid.setOnPreferenceClickListener(pref -> {
                    runPrintViaAndroid();
                    return true;
                });
            }

            Preference jobHistory = findPreference("job_history");
            if (jobHistory != null) {
                jobHistory.setOnPreferenceClickListener(pref -> {
                    startActivity(new Intent(requireContext(), JobHistoryActivity.class));
                    return true;
                });
            }

            Preference viewLogs = findPreference("view_logs");
            if (viewLogs != null) {
                viewLogs.setOnPreferenceClickListener(pref -> {
                    showLogViewer();
                    return true;
                });
            }

            Preference runTests = findPreference("run_test_suite");
            if (runTests != null) {
                runTests.setOnPreferenceClickListener(pref -> {
                    runPrintTestSuite();
                    return true;
                });
            }
        }

        private String getPrinterIp() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            return prefs.getString("printer_ip", "192.168.0.109");
        }

        private void runNetworkTest() {
            String ip = getPrinterIp();

            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Network Test")
                    .setMessage("Testing connection to " + ip + "...")
                    .setCancelable(false)
                    .create();
            dialog.show();

            new Thread(() -> {
                StringBuilder result = new StringBuilder();
                boolean success = true;

                result.append("Target: ").append(ip).append("\n\n");
                try {
                    long start = System.currentTimeMillis();
                    InetAddress addr = InetAddress.getByName(ip);
                    long elapsed = System.currentTimeMillis() - start;
                    result.append("[OK] DNS resolved: ").append(addr.getHostAddress())
                            .append(" (").append(elapsed).append(" ms)\n");
                } catch (Exception e) {
                    result.append("[FAIL] DNS resolution failed: ").append(e.getMessage()).append("\n");
                    success = false;
                }

                if (success) {
                    try {
                        long start = System.currentTimeMillis();
                        boolean reachable = InetAddress.getByName(ip).isReachable(CONNECT_TIMEOUT_MS);
                        long elapsed = System.currentTimeMillis() - start;
                        if (reachable) {
                            result.append("[OK] Ping: reachable (").append(elapsed).append(" ms)\n");
                        } else {
                            result.append("[WARN] Ping: no ICMP reply (").append(elapsed)
                                    .append(" ms)\n");
                        }
                    } catch (Exception e) {
                        result.append("[WARN] Ping failed: ").append(e.getMessage()).append("\n");
                    }
                }

                if (success) {
                    Socket socket = new Socket();
                    try {
                        long start = System.currentTimeMillis();
                        socket.connect(new InetSocketAddress(ip, 9100), CONNECT_TIMEOUT_MS);
                        long elapsed = System.currentTimeMillis() - start;
                        result.append("[OK] Port 9100 open (").append(elapsed).append(" ms)\n");
                    } catch (IOException e) {
                        result.append("[WARN] Port 9100 closed\n");
                    } finally {
                        try { socket.close(); } catch (IOException ignored) {}
                    }
                }

                if (success) {
                    Socket socket = new Socket();
                    try {
                        long start = System.currentTimeMillis();
                        socket.connect(new InetSocketAddress(ip, IPP_PORT), CONNECT_TIMEOUT_MS);
                        long elapsed = System.currentTimeMillis() - start;
                        result.append("[OK] IPP port ").append(IPP_PORT)
                                .append(" open (").append(elapsed).append(" ms)\n");
                    } catch (IOException e) {
                        result.append("[FAIL] IPP port ").append(IPP_PORT)
                                .append(" closed: ").append(e.getMessage()).append("\n");
                        success = false;
                    } finally {
                        try { socket.close(); } catch (IOException ignored) {}
                    }
                }

                result.append("\n");
                if (success) {
                    result.append("Printer is reachable and ready.");
                } else {
                    result.append("Could not reach printer. Check IP and that the printer is on.");
                }

                String finalMessage = result.toString();
                requireActivity().runOnUiThread(() -> {
                    dialog.setMessage(finalMessage);
                    dialog.setCancelable(true);
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK",
                            (d, w) -> d.dismiss());
                });
            }).start();
        }

        private void runPrintTestPage() {
            String ip = getPrinterIp();

            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Print Test Page")
                    .setMessage("Sending test page to " + ip + "...")
                    .setCancelable(false)
                    .create();
            dialog.show();

            new Thread(() -> {
                String resultMessage;
                try {
                    byte[] urfData = loadAsset("test_page.urf");
                    IppClient.IppResult result = IppClient.sendPrintJob(ip, urfData, "Test Page");

                    if (result.success) {
                        resultMessage = "Test page sent successfully!\n\n"
                                + urfData.length + " bytes sent via IPP to " + ip + "\n\n"
                                + "The printer should produce a page shortly.";
                    } else {
                        resultMessage = "IPP request failed:\n" + result.message;
                    }
                } catch (IOException e) {
                    resultMessage = "Failed to send test page:\n\n" + e.getMessage();
                }

                String finalMessage = resultMessage;
                requireActivity().runOnUiThread(() -> {
                    dialog.setMessage(finalMessage);
                    dialog.setCancelable(true);
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK",
                            (d, w) -> d.dismiss());
                });
            }).start();
        }

        private void runPrintViaAndroid() {
            PrintManager printManager = (PrintManager) requireContext()
                    .getSystemService(Context.PRINT_SERVICE);

            PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
                @Override
                public void onLayout(android.print.PrintAttributes oldAttributes,
                                     android.print.PrintAttributes newAttributes,
                                     android.os.CancellationSignal cancellationSignal,
                                     LayoutResultCallback callback,
                                     android.os.Bundle extras) {
                    PrintDocumentInfo info = new PrintDocumentInfo.Builder("test-document.pdf")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(1)
                            .build();
                    callback.onLayoutFinished(info, true);
                }

                @Override
                public void onWrite(android.print.PageRange[] pages,
                                    android.os.ParcelFileDescriptor destination,
                                    android.os.CancellationSignal cancellationSignal,
                                    WriteResultCallback callback) {
                    try {
                        // Load test PDF and write it
                        InputStream in = requireContext().getAssets().open("test_page.urf");
                        // Actually we need a real PDF. Generate a minimal one.
                        byte[] pdf = generateMinimalPdf();
                        FileOutputStream out = new FileOutputStream(destination.getFileDescriptor());
                        out.write(pdf);
                        out.close();
                        callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
                    } catch (IOException e) {
                        callback.onWriteFailed(e.getMessage());
                    }
                }
            };

            printManager.print("Test Document", adapter,
                    new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setResolution(new PrintAttributes.Resolution("600dpi", "600 dpi", 600, 600))
                            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                            .build());
        }

        private byte[] generateMinimalPdf() {
            // 2-page test PDF with varied content for print quality testing
            String p1 = "BT\n" +
                    "/F1 36 Tf 50 780 Td (Print Quality Test) Tj\n" +
                    "/F1 12 Tf 0 -50 Td (Normal text at 12pt) Tj\n" +
                    "0 -20 Td (The quick brown fox jumps over the lazy dog.) Tj\n" +
                    "0 -20 Td (ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789) Tj\n" +
                    "/F1 8 Tf 0 -30 Td (Small 8pt: Should be readable at 600 DPI) Tj\n" +
                    "/F1 24 Tf 0 -40 Td (Large 24pt heading) Tj\n" +
                    "/F1 12 Tf 0 -30 Td (Repeated patterns:) Tj\n" +
                    "0 -20 Td (||||||||||||||||||||||||||||||||||||||||) Tj\n" +
                    "0 -20 Td (========================================) Tj\n" +
                    "0 -20 Td (########################################) Tj\n" +
                    "/F1 14 Tf 0 -40 Td (Page 1 of 2) Tj\n" +
                    "ET\n" +
                    "0.5 w 50 430 m 545 430 l S\n";
            String p2 = "BT\n" +
                    "/F1 28 Tf 50 780 Td (Page 2: Layout Test) Tj\n" +
                    "/F1 12 Tf 50 720 Td (Left aligned) Tj 350 720 Td (Right area) Tj\n" +
                    "/F1 16 Tf 50 680 Td (Medium 16pt heading) Tj\n" +
                    "/F1 10 Tf 50 650 Td (Body text below the heading for layout testing.) Tj\n" +
                    "50 635 Td (Row 1: 100 200 300 400 500) Tj\n" +
                    "50 620 Td (Row 2: 150 250 350 450 550) Tj\n" +
                    "/F1 14 Tf 50 480 Td (End of test - Page 2 of 2) Tj\n" +
                    "ET\n";
            String pdf = "%PDF-1.4\n" +
                    "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
                    "2 0 obj<</Type/Pages/Kids[3 0 R 6 0 R]/Count 2>>endobj\n" +
                    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]" +
                    "/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n" +
                    "4 0 obj<</Length " + p1.length() + ">>stream\n" + p1 + "endstream\nendobj\n" +
                    "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n" +
                    "6 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]" +
                    "/Contents 7 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n" +
                    "7 0 obj<</Length " + p2.length() + ">>stream\n" + p2 + "endstream\nendobj\n" +
                    "xref\n0 8\n" +
                    "0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n" +
                    "0000000115 00000 n \n0000000266 00000 n \n0000000900 00000 n \n" +
                    "0000000977 00000 n \n0000001100 00000 n \n" +
                    "trailer<</Size 8/Root 1 0 R>>\nstartxref\n1400\n%%EOF";
            return pdf.getBytes();
        }

        private void showLogViewer() {
            String logs = PrintLog.exportAsText();
            if (logs.isEmpty()) logs = "(no log entries)";

            TextView textView = new TextView(requireContext());
            textView.setText(logs);
            textView.setTextSize(11);
            textView.setPadding(32, 16, 32, 16);
            textView.setTypeface(android.graphics.Typeface.MONOSPACE);
            textView.setMovementMethod(new ScrollingMovementMethod());
            textView.setVerticalScrollBarEnabled(true);

            new AlertDialog.Builder(requireContext())
                    .setTitle("Debug Logs")
                    .setView(textView)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Copy", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) requireContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("Print Logs",
                                PrintLog.exportAsText()));
                        Toast.makeText(requireContext(), "Logs copied", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Clear", (d, w) -> {
                        PrintLog.clear();
                        Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        }

        private void runPrintTestSuite() {
            String ip = getPrinterIp();
            String[] testFiles = {
                    "invoice_3page.pdf",
                    "grayscale_test.pdf",
                    "dense_text.pdf",
                    "shapes_test.pdf",
                    "letter_size.pdf"
            };

            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Print Test Suite")
                    .setMessage("Starting test suite (5 documents)...")
                    .setCancelable(false)
                    .create();
            dialog.show();

            new Thread(() -> {
                StringBuilder results = new StringBuilder();
                int passed = 0, failed = 0;

                for (int t = 0; t < testFiles.length; t++) {
                    String name = testFiles[t];
                    String status;
                    try {
                        String path = requireContext().getCacheDir() + "/" + name;
                        java.io.File pdfFile = new java.io.File(path);
                        if (!pdfFile.exists()) {
                            status = "SKIP (file not found)";
                            failed++;
                            results.append(String.format("%d. %s: %s\n", t + 1, name, status));
                            continue;
                        }

                        int msgNum = t + 1;
                        requireActivity().runOnUiThread(() ->
                                dialog.setMessage("Test " + msgNum + "/" + testFiles.length + ": " + name));

                        android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.open(
                                pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                        android.graphics.pdf.PdfRenderer renderer =
                                new android.graphics.pdf.PdfRenderer(pfd);
                        int pageCount = renderer.getPageCount();

                        android.graphics.Bitmap[] pages = new android.graphics.Bitmap[pageCount];
                        for (int i = 0; i < pageCount; i++) {
                            android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(i);
                            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                                    UrfEncoder.A4_WIDTH_600, UrfEncoder.A4_HEIGHT_600,
                                    android.graphics.Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(0xFFFFFFFF);
                            page.render(bmp, null, null,
                                    android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            page.close();
                            pages[i] = bmp;
                        }
                        renderer.close();
                        pfd.close();

                        byte[] urfData = UrfEncoder.encode(pages);
                        for (android.graphics.Bitmap bmp : pages) {
                            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                        }

                        IppClient.IppResult result = IppClient.sendPrintJob(ip, urfData, name);

                        if (result.success) {
                            status = String.format("OK (%d pages, %d KB URF)",
                                    pageCount, urfData.length / 1024);
                            passed++;
                        } else {
                            status = "IPP ERROR: " + result.message;
                            failed++;
                        }

                        // Brief pause between jobs so printer can process
                        Thread.sleep(3000);

                    } catch (Exception e) {
                        status = "FAIL: " + e.getMessage();
                        failed++;
                    }
                    results.append(String.format("%d. %s: %s\n", t + 1, name, status));
                }

                String summary = String.format(
                        "Results: %d passed, %d failed\n\n%s", passed, failed, results);
                requireActivity().runOnUiThread(() -> {
                    dialog.setMessage(summary);
                    dialog.setCancelable(true);
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK",
                            (d, w) -> d.dismiss());
                });
            }).start();
        }

        private byte[] loadAsset(String filename) throws IOException {
            InputStream is = requireContext().getAssets().open(filename);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int len;
            while ((len = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, len);
            }
            is.close();
            return buffer.toByteArray();
        }
    }
}
