package com.xerox3025.printplugin;

import android.graphics.*;
import android.graphics.pdf.*;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import org.junit.*;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PdfSpoolerTest {
    private File dir;
    @Before public void setup() throws Exception { dir=Files.createTempDirectory("pdf-spool-test").toFile(); }
    @After public void cleanup() { for(File file:dir.listFiles())file.delete();dir.delete(); }
    private File pdf(int pages) throws Exception {
        File file=new File(dir,"input.pdf");
        PdfDocument doc=new PdfDocument();
        try {
            Paint paint=new Paint();paint.setColor(Color.BLACK);
            for(int i=0;i<pages;i++) {
                PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(72,108,i).create());
                Canvas c=page.getCanvas();c.drawRect(3,4,30,35,paint);c.drawLine(0,0,72,108,paint);
                paint.setColor(Color.rgb(80+i%100,90,160));c.drawCircle(50,70,12,paint);
                doc.finishPage(page);
            }
            try(OutputStream out=new FileOutputStream(file)){doc.writeTo(out);}
        }
        finally { doc.close(); }
        return file;
    }
    @Test public void bandsPreserveRasterWithinNativeRoundingAndRecycleBitmap() throws Exception {
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(pdf(1),ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page page=renderer.openPage(0)) {
            int width=600,height=900;
            Bitmap full=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);full.eraseColor(Color.WHITE);
            float scale=Math.min((float)width/page.getWidth(),(float)height/page.getHeight());
            Matrix m=new Matrix();m.setScale(scale,scale);
            m.postTranslate((width-page.getWidth()*scale)/2,(height-page.getHeight()*scale)/2);
            page.render(full,null,m,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            PdfSpooler.BandSource source=new PdfSpooler.BandSource(page,width,height,new JobControl());
            assertTrue(source.band.getAllocationByteCount()<=width*128*4);
            byte[] row=new byte[width],expected=new byte[width];int[] pixels=new int[width];
            int maxDifference=0,changedPixels=0;
            try {
                for(int y=0;y<height;y++) {
                    source.read(y,row);full.getPixels(pixels,0,width,0,y,width,1);UrfEncoder.toGray(pixels,expected);
                    for(int x=0;x<width;x++) {
                        int difference=Math.abs((expected[x]&255)-(row[x]&255));
                        if(difference!=0)changedPixels++;
                        maxDifference=Math.max(maxDifference,difference);
                    }
                }
                String comparison="max gray difference="+maxDifference+", changed pixels="
                        +changedPixels+" / "+(width*height);
                android.util.Log.i("PdfBandComparison",comparison);
                // Native tile transforms may change antialiased edge rounding by 2/255.
                // Every pixel must stay within that bound, with at least 99% byte-identical.
                // Missing/shifted content and band seams therefore still fail this check.
                assertTrue(comparison,maxDifference<=2);
                assertTrue(comparison,changedPixels<=width*height/100);
            } finally { source.close();full.recycle(); }
            assertTrue(source.band.isRecycled());
        }
    }
    @Test public void multipageCopiesAndRepeatedSpoolsAreDeterministic() throws Exception {
        File pdf=pdf(5),out=new File(dir,"output.urf");byte[] first=null;
        PdfSpooler.encode(pdf,out,null,1,new JobControl()); // warm native renderer
        int descriptorsBefore=new File("/proc/self/fd").list().length;
        for(int i=0;i<3;i++) {
            assertEquals(10,PdfSpooler.encode(pdf,out,null,2,new JobControl()));
            byte[] encoded=Files.readAllBytes(out.toPath());assertEquals(10,encoded[11]);
            if(first!=null)assertArrayEquals(first,encoded);first=encoded;
        }
        int descriptorsAfter=new File("/proc/self/fd").list().length;
        assertTrue("File descriptors grew: "+descriptorsBefore+" -> "+descriptorsAfter,
                descriptorsAfter<=descriptorsBefore+2);
        // Output can be reopened after every run.
        assertTrue(pdf.renameTo(new File(dir,"renamed.pdf")));
        try(FileOutputStream overwrite=new FileOutputStream(out)){overwrite.write(1);}
        assertEquals(1,out.length());
    }
    @Test public void mediaAndLandscapeKeep600Dpi() throws Exception {
        for(PrintAttributes.MediaSize media:new PrintAttributes.MediaSize[]{PrintAttributes.MediaSize.ISO_A4,
                PrintAttributes.MediaSize.ISO_A5,PrintAttributes.MediaSize.NA_LETTER}) {
            for(PrintAttributes.MediaSize oriented:new PrintAttributes.MediaSize[]{media.asPortrait(),media.asLandscape()}) {
                int[] size=PdfSpooler.dimensions(new PrintAttributes.Builder().setMediaSize(oriented).build(),72,108);
                assertEquals((int)(oriented.getWidthMils()*0.6f),size[0]);
                assertEquals((int)(oriented.getHeightMils()*0.6f),size[1]);
            }
        }
    }
    @Test public void cancellationAndInvalidDocumentsReleaseResources() throws Exception {
        File pdf=pdf(3),out=new File(dir,"output.urf");JobControl control=new JobControl();control.cancel();
        assertThrows(InterruptedIOException.class,()->PdfSpooler.encode(pdf,out,null,1,control));
        assertEquals(3,PdfSpooler.encode(pdf,out,null,1,new JobControl()));
        File bad=new File(dir,"bad.pdf");Files.write(bad.toPath(),new byte[]{1,2,3});
        assertThrows(IOException.class,()->PdfSpooler.encode(bad,out,null,1,new JobControl()));
    }
    @Test public void unsupportedDuplexAndExcessCopiesFailBeforeSending() throws Exception {
        File pdf=pdf(1),out=new File(dir,"output.urf");
        PrintAttributes duplex=new PrintAttributes.Builder().setDuplexMode(PrintAttributes.DUPLEX_MODE_LONG_EDGE).build();
        assertThrows(IOException.class,()->PdfSpooler.encode(pdf,out,duplex,1,new JobControl()));
        assertThrows(IOException.class,()->PdfSpooler.encode(pdf,out,null,101,new JobControl()));
    }
}
