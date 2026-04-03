package com.xerox3025.printplugin;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SettingsActivity extends AppCompatActivity {

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
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private static final int CONNECT_TIMEOUT_MS = 5000;
        private static final int RAW_PORT = 9100;
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

            EditTextPreference portPref = findPreference("printer_port");
            if (portPref != null) {
                portPref.setOnBindEditTextListener(editText ->
                        editText.setInputType(InputType.TYPE_CLASS_NUMBER));
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
        }

        private String getPrinterIp() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            return prefs.getString("printer_ip", "192.168.0.109");
        }

        private int getPrinterPort() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            try {
                return Integer.parseInt(prefs.getString("printer_port", String.valueOf(RAW_PORT)).trim());
            } catch (NumberFormatException e) {
                return RAW_PORT;
            }
        }

        private void runNetworkTest() {
            String ip = getPrinterIp();
            int port = getPrinterPort();

            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Network Test")
                    .setMessage("Testing connection to " + ip + ":" + port + "...")
                    .setCancelable(false)
                    .create();
            dialog.show();

            new Thread(() -> {
                StringBuilder result = new StringBuilder();
                boolean success = true;

                result.append("Target: ").append(ip).append(":").append(port).append("\n\n");
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
                                    .append(" ms) — may still work\n");
                        }
                    } catch (Exception e) {
                        result.append("[WARN] Ping failed: ").append(e.getMessage()).append("\n");
                    }
                }

                if (success) {
                    Socket socket = new Socket();
                    try {
                        long start = System.currentTimeMillis();
                        socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
                        long elapsed = System.currentTimeMillis() - start;
                        result.append("[OK] TCP port ").append(port)
                                .append(" open (").append(elapsed).append(" ms)\n");
                    } catch (IOException e) {
                        result.append("[FAIL] TCP port ").append(port)
                                .append(" unreachable: ").append(e.getMessage()).append("\n");
                        success = false;
                    } finally {
                        try { socket.close(); } catch (IOException ignored) {}
                    }
                }

                // Also test IPP port
                if (success) {
                    Socket socket = new Socket();
                    try {
                        long start = System.currentTimeMillis();
                        socket.connect(new InetSocketAddress(ip, IPP_PORT), CONNECT_TIMEOUT_MS);
                        long elapsed = System.currentTimeMillis() - start;
                        result.append("[OK] IPP port ").append(IPP_PORT)
                                .append(" open (").append(elapsed).append(" ms)\n");
                    } catch (IOException e) {
                        result.append("[WARN] IPP port ").append(IPP_PORT)
                                .append(" closed: ").append(e.getMessage()).append("\n");
                    } finally {
                        try { socket.close(); } catch (IOException ignored) {}
                    }
                }

                result.append("\n");
                if (success) {
                    result.append("Printer is reachable and ready.");
                } else {
                    result.append("Could not reach printer. Check IP, port, and that the printer is powered on and connected to WiFi.");
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
                    .setMessage("Sending test page via IPP to " + ip + "...")
                    .setCancelable(false)
                    .create();
            dialog.show();

            new Thread(() -> {
                String resultMessage;
                try {
                    // Load pre-rendered URF test page from assets
                    byte[] urfData = loadAsset("test_page.urf");

                    // Build IPP Print-Job request
                    byte[] ippRequest = buildIppPrintJob(ip, urfData);

                    // Send via HTTP POST to IPP port
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(ip, IPP_PORT), CONNECT_TIMEOUT_MS);
                    socket.setSoTimeout(30000);

                    String httpHeader = "POST /ipp/print HTTP/1.1\r\n"
                            + "Host: " + ip + "\r\n"
                            + "Content-Type: application/ipp\r\n"
                            + "Content-Length: " + ippRequest.length + "\r\n"
                            + "\r\n";

                    OutputStream out = socket.getOutputStream();
                    out.write(httpHeader.getBytes());
                    out.write(ippRequest);
                    out.flush();

                    // Read response
                    InputStream in = socket.getInputStream();
                    byte[] resp = new byte[4096];
                    int read = in.read(resp);
                    socket.close();

                    // Check for HTTP 200
                    String response = new String(resp, 0, Math.min(read, 100));
                    if (response.contains("200")) {
                        resultMessage = "Test page sent successfully!\n\n"
                                + urfData.length + " bytes sent via IPP to " + ip + "\n\n"
                                + "The printer should produce a page shortly.";
                    } else {
                        resultMessage = "IPP request failed:\n" + response;
                    }
                } catch (IOException e) {
                    resultMessage = "Failed to send test page:\n\n"
                            + e.getMessage() + "\n\n"
                            + "Check that the printer is on and reachable.";
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

        private byte[] buildIppPrintJob(String printerIp, byte[] documentData) {
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            try {
                // IPP version 1.1
                req.write(new byte[]{0x01, 0x01});
                // Operation: Print-Job (0x0002)
                req.write(new byte[]{0x00, 0x02});
                // Request ID
                req.write(intToBytes(1));

                // Operation attributes tag
                req.write(0x01);
                // attributes-charset = utf-8
                writeIppString(req, (byte) 0x47, "attributes-charset", "utf-8");
                // attributes-natural-language = en
                writeIppString(req, (byte) 0x48, "attributes-natural-language", "en");
                // printer-uri
                writeIppString(req, (byte) 0x45, "printer-uri",
                        "ipp://" + printerIp + "/ipp/print");
                // requesting-user-name
                writeIppString(req, (byte) 0x42, "requesting-user-name", "android-plugin");
                // job-name
                writeIppString(req, (byte) 0x42, "job-name", "Test Page");
                // document-format = image/urf
                writeIppString(req, (byte) 0x49, "document-format", "image/urf");

                // End of attributes
                req.write(0x03);

                // Document data
                req.write(documentData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return req.toByteArray();
        }

        private void writeIppString(ByteArrayOutputStream out, byte tag,
                                     String name, String value) throws IOException {
            out.write(tag);
            byte[] nameBytes = name.getBytes();
            out.write(shortToBytes(nameBytes.length));
            out.write(nameBytes);
            byte[] valueBytes = value.getBytes();
            out.write(shortToBytes(valueBytes.length));
            out.write(valueBytes);
        }

        private byte[] intToBytes(int value) {
            return new byte[]{
                    (byte) (value >> 24), (byte) (value >> 16),
                    (byte) (value >> 8), (byte) value
            };
        }

        private byte[] shortToBytes(int value) {
            return new byte[]{(byte) (value >> 8), (byte) value};
        }
    }
}
