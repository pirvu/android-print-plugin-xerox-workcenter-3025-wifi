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
        private Context appContext;
        private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private void postUi(Runnable update) {
            uiHandler.post(() -> {
                if (isAdded() && getActivity() != null && !getActivity().isFinishing()) update.run();
            });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            appContext = requireContext().getApplicationContext();
            setPreferencesFromResource(R.xml.preferences, rootKey);

            EditTextPreference ipPref = findPreference("printer_ip");
            if (ipPref != null) {
                ipPref.setOnPreferenceChangeListener((preference, value) -> {
                    try { LocalPrinterAddress.parse(String.valueOf(value)); return true; }
                    catch (IOException e) {
                        Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                        return false;
                    }
                });
                ipPref.setOnBindEditTextListener(editText ->
                        editText.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_URI));
            }

            Preference scanDoc = findPreference("scan_document");
            if (scanDoc != null) {
                scanDoc.setOnPreferenceClickListener(pref -> {
                    startActivity(new android.content.Intent(requireContext(), ScanActivity.class));
                    return true;
                });
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
                    InetAddress addr = LocalPrinterAddress.parse(ip);
                    long elapsed = System.currentTimeMillis() - start;
                    result.append("[OK] Local address: ").append(addr.getHostAddress())
                            .append(" (").append(elapsed).append(" ms)\n");
                } catch (Exception e) {
                    result.append("[FAIL] Invalid local address: ").append(e.getMessage()).append("\n");
                    success = false;
                }

                if (success) {
                    try {
                        long start = System.currentTimeMillis();
                        boolean reachable = LocalPrinterAddress.parse(ip).isReachable(CONNECT_TIMEOUT_MS);
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
                    result.append("IPP TCP port is reachable. This does not confirm printer readiness.");
                } else {
                    result.append("Could not reach printer. Check IP and that the printer is on.");
                }

                String finalMessage = result.toString();
                postUi(() -> {
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
                        resultMessage = result.message + "\n\n"
                                + urfData.length + " bytes sent via IPP to " + ip;
                    } else {
                        resultMessage = result.message;
                    }
                } catch (IOException e) {
                    resultMessage = "Failed to send test page:\n\n" + e.getMessage();
                }

                String finalMessage = resultMessage;
                postUi(() -> {
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
                private PrintAttributes attributes;
                @Override
                public void onLayout(android.print.PrintAttributes oldAttributes,
                                     android.print.PrintAttributes newAttributes,
                                     android.os.CancellationSignal cancellationSignal,
                                     LayoutResultCallback callback,
                                     android.os.Bundle extras) {
                    if (cancellationSignal.isCanceled()) { callback.onLayoutCancelled(); return; }
                    attributes = newAttributes;
                    PrintDocumentInfo info = new PrintDocumentInfo.Builder("test-document.pdf")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(2)
                            .build();
                    callback.onLayoutFinished(info, true);
                }

                @Override
                public void onWrite(android.print.PageRange[] pages,
                                    android.os.ParcelFileDescriptor destination,
                                    android.os.CancellationSignal cancellationSignal,
                                    WriteResultCallback callback) {
                    try {
                        if (cancellationSignal.isCanceled()) { callback.onWriteCancelled(); return; }
                        byte[] pdf = generateMinimalPdf(attributes);
                        try (FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                            out.write(pdf);
                        }
                        if (cancellationSignal.isCanceled()) { callback.onWriteCancelled(); return; }
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

        private byte[] generateMinimalPdf(PrintAttributes attributes) throws IOException {
            PrintAttributes.MediaSize media = attributes.getMediaSize();
            int width = Math.round(media.getWidthMils() * 72f / 1000);
            int height = Math.round(media.getHeightMils() * 72f / 1000);
            android.graphics.pdf.PdfDocument document = new android.graphics.pdf.PdfDocument();
            try {
                android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                for (int i = 0; i < 2; i++) {
                    android.graphics.pdf.PdfDocument.Page page = document.startPage(
                            new android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, i).create());
                    paint.setColor(android.graphics.Color.BLACK);
                    paint.setTextSize(22);
                    page.getCanvas().drawText("Print quality test — page " + (i + 1) + " / 2", 36, 60, paint);
                    paint.setTextSize(12);
                    page.getCanvas().drawText("600 DPI: The quick brown fox jumps over the lazy dog.", 36, 92, paint);
                    for (int band = 0; band < 6; band++) {
                        int gray = band * 45;
                        paint.setColor(android.graphics.Color.rgb(gray, gray, gray));
                        page.getCanvas().drawRect(36, 120 + band * 35, width - 36, 145 + band * 35, paint);
                    }
                    document.finishPage(page);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                document.writeTo(out);
                return out.toByteArray();
            } finally { document.close(); }
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
                int accepted = 0, rejected = 0, unknown = 0, skipped = 0;

                for (int t = 0; t < testFiles.length; t++) {
                    String name = testFiles[t];
                    String status;
                    try {
                        String path = appContext.getCacheDir() + "/" + name;
                        java.io.File pdfFile = new java.io.File(path);
                        if (!pdfFile.exists()) {
                            status = "SKIP (file not found)";
                            skipped++;
                            results.append(String.format(java.util.Locale.getDefault(), "%d. %s: %s\n", t + 1, name, status));
                            continue;
                        }

                        int msgNum = t + 1;
                        postUi(() ->
                                dialog.setMessage("Test " + msgNum + "/" + testFiles.length + ": " + name));

                        java.io.File urfFile = java.io.File.createTempFile("test_", ".urf", appContext.getCacheDir());
                        IppClient.IppResult result;
                        int pageCount;
                        long urfSize;
                        try {
                            JobControl control = new JobControl();
                            pageCount = PdfSpooler.encode(pdfFile, urfFile, null, 1, control);
                            urfSize = urfFile.length();
                            result = IppClient.sendPrintJob(ip, urfFile, name, control);
                        } finally { urfFile.delete(); }

                        if (result.success) {
                            status = String.format(java.util.Locale.getDefault(), "ACCEPTED (%d pages, %d KB URF)",
                                    pageCount, urfSize / 1024);
                            accepted++;
                        } else {
                            status = result.outcome + ": " + result.message;
                            if (result.outcome == IppClient.Outcome.UNKNOWN) unknown++;
                            else rejected++;
                        }

                        if (result.outcome == IppClient.Outcome.UNKNOWN) {
                            results.append(name).append(": ").append(status).append("\nStopped; check printer output.\n");
                            break;
                        }
                        // Brief pause between jobs so printer can process
                        Thread.sleep(3000);

                    } catch (Exception e) {
                        status = "FAIL: " + e.getMessage();
                        rejected++;
                    }
                    results.append(String.format(java.util.Locale.getDefault(), "%d. %s: %s\n", t + 1, name, status));
                }

                String summary = String.format(java.util.Locale.getDefault(),
                        "Results: %d accepted, %d not accepted, %d unknown, %d skipped\n\n%s",
                        accepted, rejected, unknown, skipped, results);
                postUi(() -> {
                    dialog.setMessage(summary);
                    dialog.setCancelable(true);
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK",
                            (d, w) -> d.dismiss());
                });
            }).start();
        }

        private byte[] loadAsset(String filename) throws IOException {
            try (InputStream is = appContext.getAssets().open(filename)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int len;
                while ((len = is.read(chunk)) != -1) {
                    buffer.write(chunk, 0, len);
                }
                return buffer.toByteArray();
            }
        }
    }
}
