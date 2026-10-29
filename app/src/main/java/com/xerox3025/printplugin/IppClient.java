package com.xerox3025.printplugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class IppClient {
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(1);
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "ipp-timeout");
        t.setDaemon(true);
        return t;
    });
    static { WATCHDOG.setRemoveOnCancelPolicy(true); }

    public enum Outcome { ACCEPTED, REJECTED, UNKNOWN, CANCELLED }

    public static class IppResult {
        public final Outcome outcome;
        public final boolean success;
        public final int statusCode;
        public final String message;
        IppResult(Outcome outcome, int statusCode, String message) {
            this.outcome = outcome;
            this.success = outcome == Outcome.ACCEPTED;
            this.statusCode = statusCode;
            this.message = message;
        }
    }

    public static IppResult sendPrintJob(String ip, byte[] urf, String jobName) {
        try {
            InetSocketAddress address = new InetSocketAddress(LocalPrinterAddress.parse(ip), 631);
            return send(address, ip, new ByteArrayInputStream(urf), urf.length, jobName,
                    new JobControl(), 5000, 120000, 600000);
        } catch (IOException e) {
            return new IppResult(Outcome.REJECTED, -1, "Not sent: " + e.getMessage());
        }
    }

    static IppResult sendPrintJob(String ip, File urf, String jobName, JobControl control) {
        try (InputStream in = new FileInputStream(urf)) {
            InetSocketAddress address = new InetSocketAddress(LocalPrinterAddress.parse(ip), 631);
            return send(address, ip, in, urf.length(), jobName, control, 5000, 120000, 600000);
        } catch (IOException e) {
            return new IppResult(control.isCancelled() ? Outcome.CANCELLED : Outcome.REJECTED,
                    -1, "Not sent: " + e.getMessage());
        }
    }

    /** Test seam uses a loopback server; production entry points validate the LAN address. */
    static IppResult send(InetSocketAddress address, String ip, InputStream document, long length,
                          String name, JobControl control, int connectMs, int readMs, int totalMs) {
        boolean submissionStarted = false;
        int requestId = REQUEST_IDS.getAndUpdate(i -> i == Integer.MAX_VALUE ? 1 : i + 1);
        ScheduledFuture<?> deadline = null;
        try (Socket socket = new Socket()) {
            control.check();
            byte[] prefix = buildIppAttributes(ip, name, requestId);
            if (length < 0 || length > 512L * 1024 * 1024) throw new IOException("Document exceeds spool limit");
            control.attach(socket);
            try {
                // Includes connect, blocked writes, and slow/trickled responses (SO_TIMEOUT is read-only).
                deadline = closeAfter(socket, totalMs);
                socket.connect(address, connectMs);
                socket.setSoTimeout(readMs);
                String header = "POST /ipp/print HTTP/1.1\r\nHost: " + ip + ":" + address.getPort()
                        + "\r\nContent-Type: application/ipp\r\nContent-Length: " + (prefix.length + length)
                        + "\r\nConnection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                control.check();
                // From this point a write error can mean the printer accepted some/all bytes.
                submissionStarted = true;
                out.write(header.getBytes(StandardCharsets.US_ASCII));
                out.write(prefix);
                byte[] buffer = new byte[32768];
                long remaining = length;
                while (remaining > 0) {
                    control.check();
                    int n = document.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n == -1) throw new IOException("Spool file truncated");
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
                out.flush();
                control.check();
                return interpret(HttpResponseReader.read(new BufferedInputStream(socket.getInputStream())), requestId);
            } finally {
                control.detach(socket);
            }
        } catch (IOException | IllegalArgumentException e) {
            if (control.isCancelled()) return new IppResult(Outcome.CANCELLED, -1,
                    "Sending stopped. Pages already received by the printer may still print.");
            return new IppResult(submissionStarted ? Outcome.UNKNOWN : Outcome.REJECTED, -1,
                    (submissionStarted ? "Result unknown; check the printer before printing again. " : "Not sent: ")
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (deadline != null) deadline.cancel(false);
        }
    }

    static ScheduledFuture<?> closeAfter(Socket socket, int timeoutMs) {
        return WATCHDOG.schedule(() -> {
            try { socket.close(); } catch (IOException ignored) { }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    static IppResult interpret(HttpResponseReader response, int requestId) {
        if (response.status != 200) {
            // An HTTP gateway/device error alone cannot establish whether paper was printed.
            return unknown("HTTP " + response.status + "; no confirmed IPP result");
        }
        String type = response.headers.get("content-type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/ipp")) {
            return unknown("Missing/invalid application/ipp response type");
        }
        byte[] body = response.body;
        if (body.length < 9 || !((body[0] == 1 && (body[1] == 0 || body[1] == 1))
                || (body[0] == 2 && body[1] >= 0 && body[1] <= 2))) return unknown("Invalid IPP header/version");
        int returnedId = (body[4] & 255) << 24 | (body[5] & 255) << 16 | (body[6] & 255) << 8 | body[7] & 255;
        if (returnedId != requestId) return unknown("IPP request-id mismatch");
        if (!validAttributes(body)) return unknown("Incomplete/malformed IPP attributes");
        int status = (body[2] & 255) << 8 | body[3] & 255;
        String code = String.format(java.util.Locale.ROOT, "0x%04x", status);
        if (status <= 0x00ff) return new IppResult(Outcome.ACCEPTED, status,
                "Printer accepted the job (IPP " + code + "). Physical completion is not monitored.");
        if (status >= 0x0400 && status <= 0x05ff) return new IppResult(Outcome.REJECTED, status,
                "Printer rejected the job (IPP " + code + ")");
        return unknown("Unrecognized/redirect IPP status " + code);
    }

    private static IppResult unknown(String reason) {
        return new IppResult(Outcome.UNKNOWN, -1,
                "Result unknown; check the printer before printing again. " + reason);
    }

    private static boolean validAttributes(byte[] body) {
        int p = 8;
        boolean group = false;
        while (p < body.length) {
            int tag = body[p++] & 255;
            if (tag == 3) return p == body.length;
            if (tag >= 1 && tag <= 15) { group = true; continue; }
            if (!group || tag < 0x10 || tag == 0x7f || p + 2 > body.length) return false;
            int nameLength = (body[p] & 255) << 8 | body[p + 1] & 255;
            p += 2;
            if (nameLength > body.length - p - 2) return false;
            p += nameLength;
            int valueLength = (body[p] & 255) << 8 | body[p + 1] & 255;
            p += 2;
            if (valueLength > body.length - p) return false;
            p += valueLength;
        }
        return false;
    }

    static byte[] buildIppAttributes(String ip, String name, int requestId) throws IOException {
        if (ip == null || !ip.matches("[0-9.]+")) throw new IOException("Invalid printer address");
        if (name == null || name.isEmpty()) name = "Android print job";
        // IPP nameWithoutLanguage is limited to 255 UTF-8 octets; don't split a code point.
        int end = 0, octets = 0;
        while (end < name.length()) {
            int cp = name.codePointAt(end);
            int size = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (octets + size > 255) break;
            octets += size;
            end += Character.charCount(cp);
        }
        name = name.substring(0, end);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{1, 1, 0, 2});
        out.write(new byte[]{(byte)(requestId >>> 24), (byte)(requestId >>> 16),
                (byte)(requestId >>> 8), (byte)requestId});
        out.write(1);
        writeString(out, 0x47, "attributes-charset", "utf-8");
        writeString(out, 0x48, "attributes-natural-language", "en");
        writeString(out, 0x45, "printer-uri", "ipp://" + ip + "/ipp/print");
        writeString(out, 0x42, "requesting-user-name", "android-plugin");
        writeString(out, 0x42, "job-name", name);
        writeString(out, 0x49, "document-format", "image/urf");
        out.write(3);
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, int tag, String name, String value) throws IOException {
        out.write(tag);
        byte[] key = name.getBytes(StandardCharsets.UTF_8), data = value.getBytes(StandardCharsets.UTF_8);
        out.write(key.length >>> 8); out.write(key.length); out.write(key);
        out.write(data.length >>> 8); out.write(data.length); out.write(data);
    }
}
