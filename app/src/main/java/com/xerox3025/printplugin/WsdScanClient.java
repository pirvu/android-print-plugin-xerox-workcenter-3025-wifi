package com.xerox3025.printplugin;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WsdScanClient {

    private static final String TAG = "WsdScan";
    private static final int WSD_PORT = 8018;
    private static final String WSD_PATH = "/wsd/scan";
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 60000;

    private static final String NS_SOAP = "http://www.w3.org/2003/05/soap-envelope";
    private static final String NS_WSA = "http://schemas.xmlsoap.org/ws/2004/08/addressing";
    private static final String NS_SCAN = "http://schemas.microsoft.com/windows/2006/08/wdp/scan";
    private static final String ACTION_PREFIX = NS_SCAN + "/";

    // --- Data classes ---

    public static class ScannerStatus {
        public final String state; // "Idle", "Processing", "Stopped"
        public final String error;

        ScannerStatus(String state, String error) {
            this.state = state;
            this.error = error;
        }

        public boolean isIdle() { return "Idle".equals(state); }
    }

    public static class ScannerCapabilities {
        public final List<Integer> resolutions = new ArrayList<>();
        public final List<String> colorModes = new ArrayList<>();
        public final List<String> formats = new ArrayList<>();
        public int maxWidth;
        public int maxHeight;
    }

    public static class ScanSettings {
        public int resolution = 300;
        public String colorMode = "Grayscale8"; // BlackAndWhite1, Grayscale8, RGB24
        public String format = "jfif"; // jfif, tiff-single-uncompressed
        public String contentType = "Auto"; // Auto, Text, Photo, Mixed
    }

    public static class ScanJobResult {
        public final boolean success;
        public final int jobId;
        public final String imageUri;
        public final String error;

        ScanJobResult(boolean success, int jobId, String imageUri, String error) {
            this.success = success;
            this.jobId = jobId;
            this.imageUri = imageUri;
            this.error = error;
        }
    }

    // --- Public API ---

    public static ScannerStatus getScannerStatus(String ip) {
        PrintLog.i(TAG, "Getting scanner status from " + ip);
        try {
            String body = "<wscn:GetScannerElementsRequest xmlns:wscn=\"" + NS_SCAN + "\">"
                    + "<wscn:RequestedElements>"
                    + "<wscn:Name>wscn:ScannerStatus</wscn:Name>"
                    + "</wscn:RequestedElements>"
                    + "</wscn:GetScannerElementsRequest>";

            String response = sendSoapRequest(ip, "GetScannerElements", body);
            String state = extractXmlValue(response, "ScannerState");
            PrintLog.i(TAG, "Scanner state: " + state);
            return new ScannerStatus(state != null ? state : "Unknown", null);
        } catch (Exception e) {
            PrintLog.e(TAG, "Failed to get scanner status", e);
            return new ScannerStatus("Error", e.getMessage());
        }
    }

    public static ScannerCapabilities getScannerCapabilities(String ip) {
        PrintLog.i(TAG, "Getting scanner capabilities from " + ip);
        ScannerCapabilities caps = new ScannerCapabilities();
        try {
            String body = "<wscn:GetScannerElementsRequest xmlns:wscn=\"" + NS_SCAN + "\">"
                    + "<wscn:RequestedElements>"
                    + "<wscn:Name>wscn:ScannerConfiguration</wscn:Name>"
                    + "</wscn:RequestedElements>"
                    + "</wscn:GetScannerElementsRequest>";

            String response = sendSoapRequest(ip, "GetScannerElements", body);
            parseCapabilities(response, caps);
            PrintLog.i(TAG, "Capabilities: res=" + caps.resolutions
                    + " colors=" + caps.colorModes + " formats=" + caps.formats);
        } catch (Exception e) {
            PrintLog.e(TAG, "Failed to get capabilities", e);
        }
        return caps;
    }

    public static ScanJobResult createScanJob(String ip, ScanSettings settings) {
        PrintLog.i(TAG, "Creating scan job: " + settings.resolution + " DPI, "
                + settings.colorMode + ", " + settings.format);
        try {
            String body = "<wscn:CreateScanJobRequest xmlns:wscn=\"" + NS_SCAN + "\">"
                    + "<wscn:ScanTicket>"
                    + "<wscn:JobDescription>"
                    + "<wscn:JobName>AndroidScan</wscn:JobName>"
                    + "<wscn:JobOriginatingUserName>android-plugin</wscn:JobOriginatingUserName>"
                    + "</wscn:JobDescription>"
                    + "<wscn:DocumentParameters>"
                    + "<wscn:Format>" + settings.format + "</wscn:Format>"
                    + "<wscn:CompressionQualityFactor>85</wscn:CompressionQualityFactor>"
                    + "<wscn:ImagesToTransfer>1</wscn:ImagesToTransfer>"
                    + "<wscn:InputSize>"
                    + "<wscn:DocumentSizeAutoDetect>false</wscn:DocumentSizeAutoDetect>"
                    + "</wscn:InputSize>"
                    + "<wscn:InputSource>Platen</wscn:InputSource>"
                    + "<wscn:ContentType>" + settings.contentType + "</wscn:ContentType>"
                    + "<wscn:Scaling>"
                    + "<wscn:ScalingWidth>100</wscn:ScalingWidth>"
                    + "<wscn:ScalingHeight>100</wscn:ScalingHeight>"
                    + "</wscn:Scaling>"
                    + "<wscn:Rotation>0</wscn:Rotation>"
                    + "<wscn:MediaSides>"
                    + "<wscn:MediaFront>"
                    + "<wscn:ScanRegion>"
                    + "<wscn:ScanRegionXOffset>0</wscn:ScanRegionXOffset>"
                    + "<wscn:ScanRegionYOffset>0</wscn:ScanRegionYOffset>"
                    + "<wscn:ScanRegionWidth>8503</wscn:ScanRegionWidth>"
                    + "<wscn:ScanRegionHeight>11732</wscn:ScanRegionHeight>"
                    + "</wscn:ScanRegion>"
                    + "<wscn:ColorProcessing>" + settings.colorMode + "</wscn:ColorProcessing>"
                    + "<wscn:Resolution>"
                    + "<wscn:Width>" + settings.resolution + "</wscn:Width>"
                    + "<wscn:Height>" + settings.resolution + "</wscn:Height>"
                    + "</wscn:Resolution>"
                    + "</wscn:MediaFront>"
                    + "</wscn:MediaSides>"
                    + "</wscn:DocumentParameters>"
                    + "</wscn:ScanTicket>"
                    + "</wscn:CreateScanJobRequest>";

            String response = sendSoapRequest(ip, "CreateScanJob", body);
            String jobIdStr = extractXmlValue(response, "JobId");
            String jobToken = extractXmlValue(response, "JobToken");

            if (jobIdStr != null && jobToken != null) {
                int jobId = Integer.parseInt(jobIdStr);
                PrintLog.i(TAG, "Scan job created: id=" + jobId + " token=" + jobToken);
                return new ScanJobResult(true, jobId, jobToken, null);
            } else {
                String fault = extractXmlValue(response, "Text");
                if (fault == null) fault = extractXmlValue(response, "Reason");
                String error = fault != null ? fault : "Unknown error creating scan job";
                PrintLog.e(TAG, "CreateScanJob failed: " + error);
                return new ScanJobResult(false, -1, null, error);
            }
        } catch (Exception e) {
            PrintLog.e(TAG, "CreateScanJob exception", e);
            return new ScanJobResult(false, -1, null, e.getMessage());
        }
    }

    public static byte[] retrieveImage(String ip, int jobId, String imageUri) {
        PrintLog.i(TAG, "Retrieving image for job " + jobId);
        try {
            String body = "<wscn:RetrieveImageRequest xmlns:wscn=\"" + NS_SCAN + "\">"
                    + "<wscn:DocumentDescription>"
                    + "<wscn:DocumentName>scan.jpg</wscn:DocumentName>"
                    + "</wscn:DocumentDescription>"
                    + "<wscn:JobId>" + jobId + "</wscn:JobId>"
                    + "<wscn:JobToken>" + imageUri + "</wscn:JobToken>"
                    + "</wscn:RetrieveImageRequest>";

            byte[] rawResponse = sendSoapRequestRaw(ip, "RetrieveImage", body);
            byte[] imageData = extractImageFromResponse(rawResponse);

            if (imageData != null && imageData.length > 0) {
                PrintLog.i(TAG, "Image retrieved: " + imageData.length + " bytes");
                return imageData;
            } else {
                PrintLog.e(TAG, "No image data in response (" + rawResponse.length + " bytes raw)");
                return null;
            }
        } catch (Exception e) {
            PrintLog.e(TAG, "RetrieveImage failed", e);
            return null;
        }
    }

    // --- SOAP transport ---

    private static String sendSoapRequest(String ip, String operation, String bodyContent)
            throws IOException {
        byte[] raw = sendSoapRequestRaw(ip, operation, bodyContent);
        return new String(raw, "UTF-8");
    }

    private static byte[] sendSoapRequestRaw(String ip, String operation, String bodyContent)
            throws IOException {
        String action = ACTION_PREFIX + operation;
        String messageId = "urn:uuid:" + UUID.randomUUID();

        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + NS_SOAP + "\""
                + " xmlns:wsa=\"" + NS_WSA + "\">"
                + "<soap:Header>"
                + "<wsa:To soap:mustUnderstand=\"1\">http://" + ip + ":" + WSD_PORT + WSD_PATH + "</wsa:To>"
                + "<wsa:Action soap:mustUnderstand=\"1\">" + action + "</wsa:Action>"
                + "<wsa:MessageID>" + messageId + "</wsa:MessageID>"
                + "<wsa:ReplyTo>"
                + "<wsa:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:Address>"
                + "</wsa:ReplyTo>"
                + "</soap:Header>"
                + "<soap:Body>" + bodyContent + "</soap:Body>"
                + "</soap:Envelope>";

        URL url = new URL("http://" + ip + ":" + WSD_PORT + WSD_PATH);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type",
                "application/soap+xml; charset=utf-8; action=\"" + action + "\"");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setDoOutput(true);

        OutputStream out = conn.getOutputStream();
        out.write(soap.getBytes("UTF-8"));
        out.flush();
        out.close();

        int status = conn.getResponseCode();
        InputStream in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int len;
        while ((len = in.read(chunk)) != -1) {
            buf.write(chunk, 0, len);
        }
        in.close();
        conn.disconnect();

        if (status >= 400) {
            throw new IOException("WSD HTTP " + status + ": " + buf.toString("UTF-8").substring(0, Math.min(200, buf.size())));
        }

        return buf.toByteArray();
    }

    // --- XML parsing helpers ---

    private static String extractXmlValue(String xml, String localName) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && localName.equals(parser.getName())) {
                    return parser.nextText();
                }
            }
        } catch (Exception e) {
            PrintLog.w(TAG, "XML parse error for " + localName + ": " + e.getMessage());
        }
        return null;
    }

    private static void parseCapabilities(String xml, ScannerCapabilities caps) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            boolean inWidths = false, inColors = false, inFormats = false;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    switch (name) {
                        case "Widths": inWidths = true; break;
                        case "Width":
                            if (inWidths) {
                                try { caps.resolutions.add(Integer.parseInt(parser.nextText())); }
                                catch (NumberFormatException ignored) {}
                            }
                            break;
                        case "PlatenColor": inColors = true; break;
                        case "ColorEntry":
                            if (inColors) caps.colorModes.add(parser.nextText());
                            break;
                        case "FormatsSupported": inFormats = true; break;
                        case "FormatValue":
                            if (inFormats) caps.formats.add(parser.nextText());
                            break;
                        case "PlatenMaximumSize":
                            break;
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String name = parser.getName();
                    switch (name) {
                        case "Widths": inWidths = false; break;
                        case "PlatenColor": inColors = false; break;
                        case "FormatsSupported": inFormats = false; break;
                        case "PlatenMaximumSize": break;
                    }
                }
            }
        } catch (Exception e) {
            PrintLog.w(TAG, "Capabilities parse error: " + e.getMessage());
        }
    }

    /**
     * Extract image data from WSD RetrieveImage response.
     * The image may be embedded as XOP/MTOM (MIME multipart) or inline base64.
     * We detect MIME boundary and extract the binary part.
     */
    private static byte[] extractImageFromResponse(byte[] response) {
        // Check for MIME multipart (MTOM/XOP)
        // Look for a MIME boundary pattern
        String header = new String(response, 0, Math.min(response.length, 500));

        if (header.contains("--MIMEBoundary") || header.contains("--_DPWS")
                || header.contains("Content-Type: multipart")) {
            return extractMtomImage(response);
        }

        // Check for XOP include reference — image might be in a subsequent MIME part
        if (header.contains("xop:Include")) {
            return extractMtomImage(response);
        }

        // If no MTOM, the image might be directly in the SOAP body
        // Look for JFIF header (JPEG: FF D8 FF)
        int jpegStart = indexOf(response, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        if (jpegStart >= 0) {
            byte[] image = new byte[response.length - jpegStart];
            System.arraycopy(response, jpegStart, image, 0, image.length);
            return image;
        }

        // Look for TIFF header (49 49 2A 00 or 4D 4D 00 2A)
        int tiffStart = indexOf(response, new byte[]{0x49, 0x49, 0x2A, 0x00});
        if (tiffStart < 0) tiffStart = indexOf(response, new byte[]{0x4D, 0x4D, 0x00, 0x2A});
        if (tiffStart >= 0) {
            byte[] image = new byte[response.length - tiffStart];
            System.arraycopy(response, tiffStart, image, 0, image.length);
            return image;
        }

        PrintLog.w(TAG, "Could not find image data in response");
        return null;
    }

    private static byte[] extractMtomImage(byte[] response) {
        // Find the MIME boundary (starts with --)
        String text = new String(response, 0, Math.min(response.length, 1000));
        int boundaryIdx = text.indexOf("--_DPWS");
        if (boundaryIdx < 0) boundaryIdx = text.indexOf("--MIMEBoundary");
        if (boundaryIdx < 0) {
            boundaryIdx = text.indexOf("--");
            if (boundaryIdx < 0) return null;
        }

        // Find the end of the boundary line
        int lineEnd = text.indexOf("\r\n", boundaryIdx);
        if (lineEnd < 0) return null;
        String boundary = text.substring(boundaryIdx, lineEnd);

        // Find the second MIME part (first is SOAP XML, second is binary image)
        byte[] boundaryBytes = boundary.getBytes();
        int secondPart = indexOf(response, boundaryBytes, lineEnd + 2);
        if (secondPart < 0) return null;

        // Skip past the boundary and headers to find the binary data
        int headersEnd = indexOf(response, "\r\n\r\n".getBytes(), secondPart);
        if (headersEnd < 0) return null;
        int dataStart = headersEnd + 4;

        // Find the closing boundary
        int dataEnd = indexOf(response, boundaryBytes, dataStart);
        if (dataEnd < 0) dataEnd = response.length;
        // Remove trailing \r\n before boundary
        if (dataEnd >= 2 && response[dataEnd - 1] == '\n' && response[dataEnd - 2] == '\r') {
            dataEnd -= 2;
        }

        byte[] image = new byte[dataEnd - dataStart];
        System.arraycopy(response, dataStart, image, 0, image.length);
        return image;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        return indexOf(data, pattern, 0);
    }

    private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }
}
