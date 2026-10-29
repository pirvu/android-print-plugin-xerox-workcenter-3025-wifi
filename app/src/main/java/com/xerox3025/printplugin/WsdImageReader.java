package com.xerox3025.printplugin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Bounded transport has already removed HTTP framing; only complete MIME parts are returned. */
final class WsdImageReader {
    static byte[] extract(byte[] response) {
        if (response.length < 4) return null;
        if (response[0] != '-' || response[1] != '-') return validImage(response) ? response : null;
        int endLine = find(response, bytes("\r\n"), 0);
        if (endLine < 3 || endLine > 200) return null;
        byte[] boundary = Arrays.copyOfRange(response, 0, endLine);
        byte[] delimiter = bytes("\r\n" + new String(boundary, StandardCharsets.ISO_8859_1));
        int start = endLine + 2;
        byte[] image = null;
        while (start < response.length) {
            int endHeaders = find(response, bytes("\r\n\r\n"), start);
            if (endHeaders < start || endHeaders - start > 8192) return null;
            String headers = "\r\n" + new String(response, start, endHeaders - start, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            int dataStart = endHeaders + 4;
            int next = find(response, delimiter, dataStart);
            if (next < 0) return null;
            if (headers.matches("(?s).*\\r\\ncontent-type:[ \\t]*image/(jpeg|tiff)([; \\t\\r\\n].*)?")) {
                byte[] candidate = Arrays.copyOfRange(response, dataStart, next);
                if (!validImage(candidate) || image != null) return null;
                image = candidate;
            }
            int suffix = next + delimiter.length;
            if (suffix + 2 > response.length) return null;
            if (response[suffix] == '-' && response[suffix + 1] == '-') return image;
            if (response[suffix] != '\r' || response[suffix + 1] != '\n') return null;
            start = suffix + 2;
        }
        return null;
    }

    private static boolean validImage(byte[] data) {
        int n = data.length;
        return n >= 4 && ((data[0] == (byte)0xff && data[1] == (byte)0xd8
                && data[n - 2] == (byte)0xff && data[n - 1] == (byte)0xd9)
                || data[0] == 'I' && data[1] == 'I' && data[2] == 42 && data[3] == 0
                || data[0] == 'M' && data[1] == 'M' && data[2] == 0 && data[3] == 42);
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
    private static int find(byte[] data, byte[] pattern, int from) {
        outer: for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i+j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }
}
