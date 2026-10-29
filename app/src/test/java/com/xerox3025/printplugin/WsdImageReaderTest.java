package com.xerox3025.printplugin;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class WsdImageReaderTest {
    static byte[] image={(byte)255,(byte)216,1,2,3,(byte)255,(byte)217};
    static byte[] multipart(boolean complete) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(HttpResponseReaderTest.bytes("--arbitrary-boundary\r\nContent-Type: application/xop+xml\r\n\r\n<soap/>\r\n--arbitrary-boundary\r\nContent-Type: image/jpeg\r\nContent-ID: <image>\r\n\r\n"));
        out.write(image);
        if(complete)out.write(HttpResponseReaderTest.bytes("\r\n--arbitrary-boundary--\r\n"));return out.toByteArray();
    }
    @Test public void arbitraryMtomBoundaryAndBinaryPart() throws Exception { assertArrayEquals(image,WsdImageReader.extract(multipart(true))); }
    @Test public void truncatedMultipartNeverSavedAsSuccess() throws Exception { assertNull(WsdImageReader.extract(multipart(false))); }
    @Test public void directJpegAndTiff() {
        assertArrayEquals(image,WsdImageReader.extract(image));
        byte[] tiff={'I','I',42,0,1,2};assertArrayEquals(tiff,WsdImageReader.extract(tiff));
    }
    @Test public void faultAndIncompleteJpegRejected() {
        assertNull(WsdImageReader.extract(HttpResponseReaderTest.bytes("<soap:Fault>no image</soap:Fault>")));
        assertNull(WsdImageReader.extract(new byte[]{(byte)255,(byte)216,1,2}));
    }
}
