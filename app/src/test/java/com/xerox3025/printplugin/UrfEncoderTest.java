package com.xerox3025.printplugin;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class UrfEncoderTest {
    static byte[] decodeLine(InputStream in,int width) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while(out.size()<width) {
            int n=in.read(); if(n<0) throw new EOFException();
            if(n<=127) { int b=in.read(); if(b<0) throw new EOFException(); for(int j=0;j<=n;j++)out.write(b); }
            else if(n>=129) { for(int j=0;j<257-n;j++){int b=in.read();if(b<0)throw new EOFException();out.write(b);} }
        }
        assertEquals(width,out.size()); return out.toByteArray();
    }
    @Test public void independentDecoderRoundTripBoundaryAndRandomRows() throws Exception {
        Random random=new Random(3025);
        for(int length:new int[]{1,2,3,127,128,129,255,256,4960,7015}) {
            for(int kind=0;kind<3;kind++) {
                byte[] row=new byte[length];
                if(kind==1)random.nextBytes(row);
                if(kind==2)for(int i=0;i<length;i++)row[i]=(byte)(i/2);
                ByteArrayOutputStream out=new ByteArrayOutputStream();UrfEncoder.pwgEncodeLine(out,row);
                ByteArrayInputStream in=new ByteArrayInputStream(out.toByteArray());
                assertArrayEquals(row,decodeLine(in,length));assertEquals(-1,in.read());
            }
        }
    }
    @Test public void repeatsAcross256RowsAndMultiplePages() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        UrfEncoder.writeHeader(out,2);
        for(int p=0;p<2;p++) UrfEncoder.writePage(out,16,520,(y,row)->Arrays.fill(row,(byte)(y<512?255:0)),new JobControl());
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        byte[] magic=new byte[8];in.readFully(magic);assertArrayEquals(HttpResponseReaderTest.bytes("UNIRAST\0"),magic);
        assertEquals(2,in.readInt());
        for(int p=0;p<2;p++) {
            byte[] hdr=new byte[32];in.readFully(hdr);assertEquals(8,hdr[0]);assertEquals(1,hdr[2]);
            int y=0;
            while(y<520) {
                int repeat=in.readUnsignedByte();byte[] row=decodeLine(in,16);
                for(int n=0;n<=repeat;n++,y++) for(byte b:row)assertEquals(y<512?255:0,b&255);
            }
            assertEquals(520,y);
        }
        assertEquals(-1,in.read());
    }
    @Test public void cancelStopsReadingRows() {
        JobControl control=new JobControl();int[] reads={0};
        assertThrows(InterruptedIOException.class,()->UrfEncoder.writePage(new ByteArrayOutputStream(),64,1000,(y,row)-> {
            reads[0]++;if(y==7)control.cancel();
        },control));
        assertEquals(8,reads[0]);
    }
    @Test public void outputFailurePropagates() {
        OutputStream out=new OutputStream(){@Override public void write(int b)throws IOException{throw new IOException("disk full");}};
        assertThrows(IOException.class,()->UrfEncoder.writePage(out,2,2,(y,row)->{},new JobControl()));
    }
    @Test public void jobCancellationIsIsolatedAndClosesOwnedIo() throws Exception {
        JobControl a=new JobControl(),b=new JobControl();boolean[] closed={false};
        Closeable resource=()->closed[0]=true;a.attach(resource);a.cancel();
        assertTrue(closed[0]);assertThrows(InterruptedIOException.class,a::check);b.check();
        closed[0]=false;assertThrows(InterruptedIOException.class,()->a.attach(resource));assertTrue(closed[0]);
    }
    @Test public void detachedIoNotClosedByCancellation() throws Exception {
        JobControl control=new JobControl();boolean[] closed={false};Closeable resource=()->closed[0]=true;
        control.attach(resource);control.detach(resource);control.cancel();assertFalse(closed[0]);
    }
    @Test public void invalidRasterSizesRejectedBeforeAllocating() {
        assertThrows(IOException.class,()->UrfEncoder.writePage(new ByteArrayOutputStream(),Integer.MAX_VALUE,2,(y,r)->{},new JobControl()));
    }
}
