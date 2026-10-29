package com.xerox3025.printplugin;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class HttpResponseReaderTest {
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
    static InputStream fragmented(byte[] data) {
        return new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(1, len)); }
        };
    }
    static HttpResponseReader parse(String s) throws IOException { return HttpResponseReader.read(fragmented(bytes(s))); }

    @Test public void fragmentedHeadersAndBody() throws Exception {
        HttpResponseReader r = parse("HTTP/1.1 200 OK\r\ncOnTeNt-LeNgTh: 9\r\nContent-Type: application/ipp\r\n\r\n123456789");
        assertEquals(200, r.status); assertArrayEquals(bytes("123456789"), r.body);
    }
    @Test public void chunkedExtensionsTrailersAndInterim() throws Exception {
        HttpResponseReader r = parse("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nTransfer-Encoding: Chunked\r\n\r\n2;foo=bar\r\nab\r\n3\r\ncde\r\n0\r\nX-End: yes\r\n\r\n");
        assertArrayEquals(bytes("abcde"), r.body);
    }
    @Test public void closeDelimitedHttp10() throws Exception {
        assertArrayEquals(bytes("abc"), parse("HTTP/1.0 200 OK\r\n\r\nabc").body);
    }
    @Test public void exactLengthDoesNotWaitForEof() throws Exception {
        byte[] data = bytes("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc");
        InputStream in = new ByteArrayInputStream(data) {
            @Override public synchronized int read() { if (available() == 0) throw new AssertionError("read beyond frame"); return super.read(); }
        };
        assertArrayEquals(bytes("abc"), HttpResponseReader.read(in).body);
    }
    @Test public void repeatedIdenticalLength() throws Exception {
        assertEquals(1, parse("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx").body.length);
    }
    @Test public void incompleteFramesRejected() {
        for (String s : new String[]{"", "HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\n1234",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type:"}) {
            assertThrows(IOException.class, () -> parse(s));
        }
    }
    @Test public void invalidStatusFramingEncodingAndLimitsRejected() {
        for (String header : new String[]{"garbage 200 OK\r\n", "HTTP/1.1 500 200\r\nContent-Length: -1\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 1,2\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 1048577\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n",
                "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n", "HTTP/1.1 200 OK\r\n bad: header\r\n"}) {
            assertThrows(IOException.class, () -> parse(header + "\r\n"));
        }
        assertThrows(IOException.class, () -> parse("HTTP/1.1 200 OK\r\nX: " + "a".repeat(33000) + "\r\n\r\n"));
    }
    @Test public void malformedChunksRejected() {
        for (String chunk : new String[]{"Z\r\n", "200000\r\n", "1\r\nxZZ", "0\r\nContent-Length: 4\r\n\r\n"}) {
            assertThrows(IOException.class, () -> parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + chunk));
        }
    }
}
