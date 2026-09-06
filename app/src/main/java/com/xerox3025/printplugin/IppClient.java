package com.xerox3025.printplugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

public class IppClient {

    private static final String TAG = "IppClient";
    private static final int IPP_PORT = 631;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 60000;

    public static class IppResult {
        public final boolean success;
        public final int statusCode;
        public final String message;

        IppResult(boolean success, int statusCode, String message) {
            this.success = success;
            this.statusCode = statusCode;
            this.message = message;
        }
    }

    public static IppResult sendPrintJob(String printerIp, byte[] urfData, String jobName) {
        PrintLog.i(TAG, "Sending IPP Print-Job to " + printerIp + ":" + IPP_PORT
                + " (" + urfData.length + " bytes, job: " + jobName + ")");

        Socket socket = null;
        try {
            byte[] ippRequest = buildIppPrintJob(printerIp, urfData, jobName);

            socket = new Socket();
            socket.connect(new InetSocketAddress(printerIp, IPP_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String httpHeader = "POST /ipp/print HTTP/1.1\r\n"
                    + "Host: " + printerIp + "\r\n"
                    + "Content-Type: application/ipp\r\n"
                    + "Content-Length: " + ippRequest.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";

            OutputStream out = socket.getOutputStream();
            out.write(httpHeader.getBytes("UTF-8"));
            out.write(ippRequest);
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] rawResponse = readHttpResponse(in);

            if (rawResponse == null || rawResponse.length == 0) {
                PrintLog.e(TAG, "Empty response from printer");
                return new IppResult(false, -1, "Empty response from printer");
            }

            return parseIppResponse(rawResponse);

        } catch (IOException e) {
            PrintLog.e(TAG, "IPP send failed: " + e.getMessage(), e);
            return new IppResult(false, -1, e.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Reads the entire HTTP response from the socket input stream.
     */
    private static byte[] readHttpResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int headerEnd = -1;
        int contentLength = -1;
        boolean isChunked = false;

        while (true) {
            int read = in.read(chunk);
            if (read == -1) {
                break;
            }
            buffer.write(chunk, 0, read);
            byte[] currentData = buffer.toByteArray();

            // Find end of headers if not found yet
            if (headerEnd == -1) {
                headerEnd = indexOf(currentData, currentData.length, "\r\n\r\n");
                if (headerEnd != -1) {
                    String headers = new String(currentData, 0, headerEnd, "UTF-8");
                    String lowerHeaders = headers.toLowerCase(Locale.US);

                    // Check for HTTP 100 Continue
                    if (headers.startsWith("HTTP/1.1 100") || headers.startsWith("HTTP/1.0 100")) {
                        // Reset to find the real response headers
                        int nextHeaderStart = headerEnd + 4;
                        byte[] remaining = new byte[currentData.length - nextHeaderStart];
                        System.arraycopy(currentData, nextHeaderStart, remaining, 0, remaining.length);
                        buffer.reset();
                        buffer.write(remaining);
                        headerEnd = -1;
                        continue;
                    }

                    if (lowerHeaders.contains("transfer-encoding: chunked")
                            || lowerHeaders.contains("transfer-encoding:chunked")) {
                        isChunked = true;
                    }

                    int clIdx = lowerHeaders.indexOf("content-length:");
                    if (clIdx != -1) {
                        int valStart = clIdx + 15;
                        int valEnd = lowerHeaders.indexOf("\r\n", valStart);
                        if (valEnd != -1) {
                            try {
                                contentLength = Integer.parseInt(headers.substring(valStart, valEnd).trim());
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            // Check completion conditions
            if (headerEnd != -1) {
                int bodyBytesRead = currentData.length - (headerEnd + 4);
                if (contentLength >= 0 && bodyBytesRead >= contentLength) {
                    break;
                }
                if (isChunked && indexOf(currentData, currentData.length, "0\r\n\r\n", headerEnd + 4) != -1) {
                    break;
                }
            }
        }

        return buffer.toByteArray();
    }

    /**
     * Parses the HTTP response and extracts the IPP status.
     */
    static IppResult parseIppResponse(byte[] rawResponse) {
        int headerEnd = indexOf(rawResponse, rawResponse.length, "\r\n\r\n");
        if (headerEnd == -1) {
            String str = new String(rawResponse, 0, Math.min(rawResponse.length, 200));
            boolean httpOk = str.contains("200");
            return new IppResult(httpOk, httpOk ? 0 : -1,
                    httpOk ? "Job accepted" : "Malformed HTTP response");
        }

        String headerStr = new String(rawResponse, 0, headerEnd);
        int httpStatusCode = parseHttpStatusCode(headerStr);
        boolean httpOk = (httpStatusCode >= 200 && httpStatusCode < 300);

        int bodyStart = headerEnd + 4;
        byte[] body = null;

        String lowerHeaders = headerStr.toLowerCase(Locale.US);
        if (lowerHeaders.contains("transfer-encoding: chunked")
                || lowerHeaders.contains("transfer-encoding:chunked")) {
            body = decodeChunkedBody(rawResponse, bodyStart);
        } else {
            int bodyLen = rawResponse.length - bodyStart;
            if (bodyLen > 0) {
                body = new byte[bodyLen];
                System.arraycopy(rawResponse, bodyStart, body, 0, bodyLen);
            }
        }

        int ippStatus = -1;
        if (body != null && body.length >= 4) {
            // IPP binary format:
            // byte 0: major version (e.g. 0x01)
            // byte 1: minor version (e.g. 0x01)
            // byte 2-3: status-code (big endian)
            // byte 4-7: request-id (big endian)
            ippStatus = ((body[2] & 0xFF) << 8) | (body[3] & 0xFF);
        }

        if (httpOk) {
            if (ippStatus >= 0x0000 && ippStatus <= 0x00FF) {
                // Successful IPP status (0x0000 = successful-ok, 0x0001 = successful-ok-ignored-or-substituted-attributes, etc.)
                PrintLog.i(TAG, "IPP job accepted (status 0x" + String.format("%04x", ippStatus) + ")");
                return new IppResult(true, ippStatus, "Job accepted");
            } else if (ippStatus == -1) {
                // HTTP 200 OK received with no explicit IPP error payload
                PrintLog.i(TAG, "IPP job accepted (HTTP 200 OK)");
                return new IppResult(true, 0, "Job accepted");
            } else {
                String errorName = getIppStatusDescription(ippStatus);
                String msg = "IPP error: 0x" + String.format("%04x", ippStatus) + " (" + errorName + ")";
                PrintLog.e(TAG, msg);
                return new IppResult(false, ippStatus, msg);
            }
        } else {
            String msg = "HTTP error " + httpStatusCode + (ippStatus != -1 ? " (IPP 0x" + String.format("%04x", ippStatus) + ")" : "");
            PrintLog.e(TAG, msg);
            return new IppResult(false, ippStatus != -1 ? ippStatus : httpStatusCode, msg);
        }
    }

    private static int parseHttpStatusCode(String headers) {
        String[] lines = headers.split("\r\n");
        if (lines.length > 0) {
            String statusLine = lines[0];
            String[] parts = statusLine.split("\\s+");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
        }
        return headers.contains("200") ? 200 : 500;
    }

    /**
     * Decodes a chunked HTTP body into raw payload bytes.
     */
    private static byte[] decodeChunkedBody(byte[] data, int offset) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int pos = offset;

        while (pos < data.length) {
            int lineEnd = indexOf(data, data.length, "\r\n", pos);
            if (lineEnd == -1) break;

            String chunkSizeStr = new String(data, pos, lineEnd - pos).trim();
            int semi = chunkSizeStr.indexOf(';');
            if (semi != -1) {
                chunkSizeStr = chunkSizeStr.substring(0, semi).trim();
            }

            int chunkSize;
            try {
                chunkSize = Integer.parseInt(chunkSizeStr, 16);
            } catch (NumberFormatException e) {
                break;
            }

            if (chunkSize == 0) {
                break; // Last chunk
            }

            int chunkDataStart = lineEnd + 2;
            int chunkDataEnd = chunkDataStart + chunkSize;
            if (chunkDataEnd <= data.length) {
                decoded.write(data, chunkDataStart, chunkSize);
            } else {
                decoded.write(data, chunkDataStart, data.length - chunkDataStart);
                break;
            }

            pos = chunkDataEnd + 2; // skip trailing \r\n
        }

        return decoded.toByteArray();
    }

    private static String getIppStatusDescription(int statusCode) {
        switch (statusCode) {
            case 0x0000: return "successful-ok";
            case 0x0001: return "successful-ok-ignored-or-substituted-attributes";
            case 0x0002: return "successful-ok-conflicting-attributes";
            case 0x0400: return "client-error-bad-request";
            case 0x0401: return "client-error-forbidden";
            case 0x0402: return "client-error-not-authenticated";
            case 0x0403: return "client-error-not-authorized";
            case 0x0404: return "client-error-not-possible";
            case 0x0405: return "client-error-timeout";
            case 0x0406: return "client-error-not-found";
            case 0x0407: return "client-error-gone";
            case 0x0408: return "client-error-request-entity-too-large";
            case 0x0409: return "client-error-request-value-too-long";
            case 0x040A: return "client-error-document-format-not-supported";
            case 0x040B: return "client-error-attributes-or-values-not-supported";
            case 0x0500: return "server-error-internal-error";
            case 0x0501: return "server-error-operation-not-supported";
            case 0x0502: return "server-error-service-unavailable";
            case 0x0503: return "server-error-version-not-supported";
            case 0x0504: return "server-error-device-error";
            case 0x0505: return "server-error-temporary-error";
            case 0x0506: return "server-error-not-accepting-jobs";
            case 0x0507: return "server-error-busy";
            case 0x0508: return "server-error-job-canceled";
            default: return "status-code-0x" + String.format("%04x", statusCode);
        }
    }

    private static int indexOf(byte[] data, int length, String pattern) {
        return indexOf(data, length, pattern, 0);
    }

    private static int indexOf(byte[] data, int length, String pattern, int fromIndex) {
        byte[] p = pattern.getBytes();
        if (p.length == 0 || length < p.length || fromIndex < 0) return -1;
        for (int i = fromIndex; i <= length - p.length; i++) {
            boolean match = true;
            for (int j = 0; j < p.length; j++) {
                if (data[i + j] != p[j]) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }

    static byte[] buildIppPrintJob(String printerIp, byte[] documentData, String jobName) {
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
            writeIppString(req, (byte) 0x47, "attributes-charset", "utf-8");
            writeIppString(req, (byte) 0x48, "attributes-natural-language", "en");
            writeIppString(req, (byte) 0x45, "printer-uri",
                    "ipp://" + printerIp + "/ipp/print");
            writeIppString(req, (byte) 0x42, "requesting-user-name", "android-plugin");
            writeIppString(req, (byte) 0x42, "job-name", jobName != null ? jobName : "Document");
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

    private static void writeIppString(ByteArrayOutputStream out, byte tag,
                                        String name, String value) throws IOException {
        out.write(tag);
        byte[] nameBytes = name.getBytes("UTF-8");
        out.write(shortToBytes(nameBytes.length));
        out.write(nameBytes);
        byte[] valueBytes = value.getBytes("UTF-8");
        out.write(shortToBytes(valueBytes.length));
        out.write(valueBytes);
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24), (byte) (value >> 16),
                (byte) (value >> 8), (byte) value
        };
    }

    private static byte[] shortToBytes(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }
}
