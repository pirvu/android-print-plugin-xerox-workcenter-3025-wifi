package com.xerox3025.printplugin;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WsdScanClient {

    private static final String TAG = "WsdScan";
    private static final int WSD_PORT = 8018;
    private static final String WSD_PATH = "/wsd/scan";
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 120000;

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
        return getScannerStatus(ip, new JobControl());
    }

    static ScannerStatus getScannerStatus(String ip, JobControl control) {
        PrintLog.i(TAG, "Getting scanner status from " + ip);
        try {
            String body = "<wscn:GetScannerElementsRequest xmlns:wscn=\"" + NS_SCAN + "\">"
                    + "<wscn:RequestedElements>"
                    + "<wscn:Name>wscn:ScannerStatus</wscn:Name>"
                    + "</wscn:RequestedElements>"
                    + "</wscn:GetScannerElementsRequest>";

            String response = sendSoapRequest(ip, "GetScannerElements", body, control);
            String state = extractXmlValue(response, "ScannerState");
            PrintLog.i(TAG, "Scanner state: " + state);
            return new ScannerStatus(state != null ? state : "Unknown", null);
        } catch (Exception e) {
            PrintLog.e(TAG, "Failed to get scanner status", e);
            return new ScannerStatus("Error", e.getMessage());
        }
    }

    public static ScannerCapabilities getScannerCapabilities(String ip) {
        return getScannerCapabilities(ip, new JobControl());
    }

    static ScannerCapabilities getScannerCapabilities(String ip, JobControl control) {
        PrintLog.i(TAG, "Getting scanner capabilities from " + ip);
        ScannerCapabilities caps = new ScannerCapabilities();
        try {
            String body = "<wscn:GetScannerElementsRequest xmlns:wscn=\"" + NS_SCAN + "\">"
                    + "<wscn:RequestedElements>"
                    + "<wscn:Name>wscn:ScannerConfiguration</wscn:Name>"
                    + "</wscn:RequestedElements>"
                    + "</wscn:GetScannerElementsRequest>";

            String response = sendSoapRequest(ip, "GetScannerElements", body, control);
            parseCapabilities(response, caps);
            PrintLog.i(TAG, "Capabilities: res=" + caps.resolutions
                    + " colors=" + caps.colorModes + " formats=" + caps.formats);
        } catch (Exception e) {
            PrintLog.e(TAG, "Failed to get capabilities", e);
        }
        return caps;
    }

    public static ScanJobResult createScanJob(String ip, ScanSettings settings) {
        return createScanJob(ip, settings, new JobControl());
    }

    static ScanJobResult createScanJob(String ip, ScanSettings settings, JobControl control) {
        PrintLog.i(TAG, "Creating scan job: " + settings.resolution + " DPI, "
                + settings.colorMode + ", " + settings.format);
        try {
            if (!java.util.Arrays.asList(75, 100, 150, 200, 300).contains(settings.resolution)
                    || !java.util.Arrays.asList("Grayscale8", "RGB24", "BlackAndWhite1").contains(settings.colorMode)
                    || !java.util.Arrays.asList("jfif", "tiff-single-uncompressed").contains(settings.format)
                    || !java.util.Arrays.asList("Auto", "Text", "Photo", "Mixed").contains(settings.contentType)) {
                throw new IOException("Unsupported scan settings");
            }
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

            String response = sendSoapRequest(ip, "CreateScanJob", body, control);
            String jobIdStr = extractXmlValue(response, "JobId");
            String jobToken = extractXmlValue(response, "JobToken");

            if (jobIdStr != null && jobToken != null) {
                int jobId = Integer.parseInt(jobIdStr);
                PrintLog.i(TAG, "Scan job created: id=" + jobId);
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
        return retrieveImage(ip, jobId, imageUri, new JobControl());
    }

    static byte[] retrieveImage(String ip, int jobId, String imageUri, JobControl control) {
        PrintLog.i(TAG, "Retrieving image for job " + jobId);
        try {
            String body = "<wscn:RetrieveImageRequest xmlns:wscn=\"" + NS_SCAN + "\">"
                    + "<wscn:DocumentDescription>"
                    + "<wscn:DocumentName>scan.jpg</wscn:DocumentName>"
                    + "</wscn:DocumentDescription>"
                    + "<wscn:JobId>" + jobId + "</wscn:JobId>"
                    + "<wscn:JobToken>" + escapeXml(imageUri) + "</wscn:JobToken>"
                    + "</wscn:RetrieveImageRequest>";

            byte[] rawResponse = sendSoapRequestRaw(ip, "RetrieveImage", body, control);
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

    private static String sendSoapRequest(String ip, String operation, String bodyContent, JobControl control)
            throws IOException {
        byte[] raw = sendSoapRequestRaw(ip, operation, bodyContent, control);
        return new String(raw, "UTF-8");
    }

    private static byte[] sendSoapRequestRaw(String ip, String operation, String bodyContent, JobControl control)
            throws IOException {
        control.check();
        LocalPrinterAddress.parse(ip);
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

        byte[] request = soap.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            control.attach(socket);
            ScheduledFuture<?> deadline = IppClient.closeAfter(socket, 180000);
            try {
                socket.connect(new InetSocketAddress(LocalPrinterAddress.parse(ip), WSD_PORT), CONNECT_TIMEOUT);
                socket.setSoTimeout(READ_TIMEOUT);
                String header = "POST " + WSD_PATH + " HTTP/1.1\r\nHost: " + ip + ":" + WSD_PORT
                        + "\r\nContent-Type: application/soap+xml; charset=utf-8; action=\"" + action
                        + "\"\r\nContent-Length: " + request.length + "\r\nConnection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(header.getBytes(StandardCharsets.US_ASCII));
                out.write(request);
                out.flush();
                HttpResponseReader response = HttpResponseReader.read(new BufferedInputStream(socket.getInputStream()),
                        operation.equals("RetrieveImage") ? 32 * 1024 * 1024 : 1024 * 1024);
                if (response.status != 200) throw new IOException("WSD HTTP " + response.status);
                return response.body;
            } finally { deadline.cancel(false); control.detach(socket); }
        }
    }

    static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // --- XML parsing helpers ---

    private static String extractXmlValue(String xml, String localName) {
        try {
            if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("DTD/entity not allowed");
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
            if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("DTD/entity not allowed");
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

    static byte[] extractImageFromResponse(byte[] response) {
        return WsdImageReader.extract(response);
    }
}
