package com.xerox3025.printplugin;

import android.content.Context;
import android.print.*;
import android.printservice.PrintJob;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import java.util.Map;
import static org.junit.Assert.*;

/** Uses real Android PrintJob state transitions with a fake system-spooler binder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class PrintServiceLifecycleTest {
    static final class Spooler {
        final PrintJobInfo info;
        final PrintJob job;
        int documentReads;
        Spooler(String tag) throws Exception {
            Constructor<PrintJobInfo> infoCtor=PrintJobInfo.class.getDeclaredConstructor();infoCtor.setAccessible(true);
            info=infoCtor.newInstance();
            Constructor<PrintJobId> idCtor=PrintJobId.class.getDeclaredConstructor(String.class);idCtor.setAccessible(true);
            set("setId",PrintJobId.class,idCtor.newInstance(java.util.UUID.randomUUID().toString()));
            set("setLabel",String.class,"job");set("setState",int.class,PrintJobInfo.STATE_QUEUED);
            set("setTag",String.class,tag);
            set("setDocumentInfo",PrintDocumentInfo.class,new PrintDocumentInfo.Builder("test.pdf").setPageCount(1).build());
            Class<?> clientClass=Class.forName("android.printservice.IPrintServiceClient");
            Object client=Proxy.newProxyInstance(clientClass.getClassLoader(),new Class[]{clientClass},(proxy,method,args)->{
                switch(method.getName()) {
                    case "getPrintJobInfo": return info;
                    case "setPrintJobState": set("setState",int.class,args[1]);return true;
                    case "setPrintJobTag": set("setTag",String.class,args[1]);return true;
                    case "writePrintJobData": documentReads++;throw new AssertionError("Must not reread/resend a submitted job");
                    case "asBinder": return null;
                    case "toString": return "FakeSpooler";
                    default: return null;
                }
            });
            Constructor<PrintJob> jobCtor=PrintJob.class.getDeclaredConstructor(Context.class,PrintJobInfo.class,clientClass);
            jobCtor.setAccessible(true);job=jobCtor.newInstance(RuntimeEnvironment.getApplication(),info,client);
        }
        void set(String method,Class<?> type,Object value) throws Exception {
            PrintJobInfo.class.getMethod(method,type).invoke(info,value);
        }
    }
    @Test public void requeuedOrRecoveredSubmissionIsHeldWithoutReadingDocument() throws Exception {
        var controller=Robolectric.buildService(Xerox3025PrintService.class).create();
        try {
            Spooler spooler=new Spooler("xerox:submission-started");
            controller.get().onPrintJobQueued(spooler.job);
            assertEquals(PrintJobInfo.STATE_BLOCKED,spooler.info.getState());assertEquals(0,spooler.documentReads);
            controller.get().onPrintJobQueued(spooler.job);
            assertEquals(PrintJobInfo.STATE_BLOCKED,spooler.info.getState());assertEquals(0,spooler.documentReads);
        } finally { controller.destroy(); }
    }
    @Test public void cancelOneJobDoesNotCancelAnotherAndNoLaterComplete() throws Exception {
        var controller=Robolectric.buildService(Xerox3025PrintService.class).create();
        try {
            Xerox3025PrintService service=controller.get();Spooler a=new Spooler(null),b=new Spooler(null);
            JobControl first=new JobControl(),second=new JobControl();
            Field f=Xerox3025PrintService.class.getDeclaredField("jobs");f.setAccessible(true);
            @SuppressWarnings("unchecked") Map<PrintJobId,JobControl> jobs=(Map<PrintJobId,JobControl>)f.get(service);
            jobs.put(a.job.getId(),first);jobs.put(b.job.getId(),second);
            service.onRequestCancelPrintJob(a.job);
            assertTrue(first.isCancelled());assertFalse(second.isCancelled());
            assertTrue(a.job.isCancelled());assertFalse(a.job.complete());
            assertTrue(b.job.isQueued());
        } finally { controller.destroy(); }
    }
}
