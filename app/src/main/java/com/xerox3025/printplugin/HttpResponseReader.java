package com.xerox3025.printplugin;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** HTTP/1.x response framing for IPP; intentionally no redirects or retries. */
final class HttpResponseReader {
    static final int MAX_BODY = 1024 * 1024;
    private static final int MAX_HEADERS = 32768;
    private static final int MAX_LINE = 8192;
    final int status;
    final Map<String, String> headers;
    final byte[] body;

    private HttpResponseReader(int status, Map<String, String> headers, byte[] body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    static HttpResponseReader read(InputStream in) throws IOException {
        return read(in, MAX_BODY);
    }

    static HttpResponseReader read(InputStream in, int maxBody) throws IOException {
        int[] budget = {MAX_HEADERS};
        for (int interim = 0; interim < 9; interim++) {
            String statusLine = line(in, budget);
            if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?")) {
                throw new IOException("Malformed HTTP status line");
            }
            int status = Integer.parseInt(statusLine.substring(9, 12));
            Map<String, String> headers = headers(in, budget);
            if (status < 200) {
                if (status == 101) throw new IOException("Unexpected HTTP protocol upgrade");
                continue;
            }
            String transfer = headers.get("transfer-encoding");
            String length = headers.get("content-length");
            String encoding = headers.get("content-encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                throw new IOException("Unsupported HTTP content encoding");
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (status == 204 || status == 304) return new HttpResponseReader(status, headers, new byte[0]);
            if (transfer != null) {
                if (!transfer.equalsIgnoreCase("chunked") || length != null) {
                    throw new IOException("Unsupported or conflicting HTTP framing");
                }
                while (true) {
                    String chunkLine = line(in, new int[]{MAX_LINE});
                    String size = chunkLine.split(";", 2)[0].trim();
                    if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Invalid HTTP chunk size");
                    long count = Long.parseLong(size, 16);
                    if (count > maxBody - body.size()) throw new IOException("IPP response exceeds limit");
                    if (count == 0) {
                        Map<String, String> trailers = headers(in, budget);
                        if (trailers.containsKey("content-length") || trailers.containsKey("transfer-encoding")) {
                            throw new IOException("Invalid HTTP trailer");
                        }
                        break;
                    }
                    copyExactly(in, body, (int) count);
                    if (in.read() != '\r' || in.read() != '\n') throw new IOException("Missing chunk terminator");
                }
            } else if (length != null) {
                // Repeated identical Content-Length values are legal; conflicting ones are not.
                String[] values = length.split(",", -1);
                long count = -1;
                for (String value : values) {
                    value = value.trim();
                    if (!value.matches("[0-9]{1,10}")) throw new IOException("Invalid Content-Length");
                    long next = Long.parseLong(value);
                    if (next > maxBody || count >= 0 && next != count) throw new IOException("Invalid Content-Length");
                    count = next;
                }
                copyExactly(in, body, (int) count);
            } else {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (body.size() + n > maxBody) throw new IOException("IPP response exceeds limit");
                    body.write(buffer, 0, n);
                }
            }
            return new HttpResponseReader(status, headers, body.toByteArray());
        }
        throw new IOException("Too many informational HTTP responses");
    }

    private static Map<String, String> headers(InputStream in, int[] budget) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = line(in, budget)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new IOException("Malformed HTTP header");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (headers.containsKey(name)) value = headers.get(name) + "," + value;
            headers.put(name, value);
        }
        return headers;
    }

    private static String line(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c == -1) throw new EOFException("Incomplete HTTP headers/chunk framing");
            if (--budget[0] < 0 || out.size() >= MAX_LINE) throw new IOException("HTTP headers exceed limit");
            if (c == '\r') {
                if (in.read() != '\n') throw new IOException("Invalid HTTP line ending");
                if (--budget[0] < 0) throw new IOException("HTTP headers exceed limit");
                return out.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (c == '\n' || c < 32 && c != '\t' || c == 127) throw new IOException("Invalid HTTP line");
            out.write(c);
        }
    }

    private static void copyExactly(InputStream in, ByteArrayOutputStream out, int count) throws IOException {
        byte[] buffer = new byte[8192];
        while (count > 0) {
            int n = in.read(buffer, 0, Math.min(count, buffer.length));
            if (n == -1) throw new EOFException("Incomplete HTTP response body");
            out.write(buffer, 0, n);
            count -= n;
        }
    }
}
