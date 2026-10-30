package com.xerox3025.printplugin;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class IppClientTest {
    static byte[] ipp(int status, int id) {
        return new byte[]{1,1,(byte)(status >>> 8),(byte)status,(byte)(id >>> 24),
                (byte)(id >>> 16),(byte)(id >>> 8),(byte)id,3};
    }
    static byte[] response(int status, int id) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HttpResponseReaderTest.bytes("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: 9\r\n\r\n"));
        out.write(ipp(status, id)); return out.toByteArray();
    }
    @Test public void successClassIncludesWarnings() throws Exception {
        for (int status : new int[]{0,1,2,7,0xff}) {
            IppClient.IppResult r = IppClient.interpret(HttpResponseReader.read(HttpResponseReaderTest.fragmented(response(status, 7))), 7);
            assertEquals(IppClient.Outcome.ACCEPTED, r.outcome); assertEquals(status, r.statusCode);
        }
    }
    @Test public void clientAndServerErrorsAreRejected() throws Exception {
        for (int status : new int[]{0x400,0x406,0x500,0x507}) {
            assertEquals(IppClient.Outcome.REJECTED, IppClient.interpret(HttpResponseReader.read(
                    new ByteArrayInputStream(response(status, 9))),9).outcome);
        }
    }
    @Test public void missingMismatchMalformedAndRedirectAreUnknown() throws Exception {
        for (int status : new int[]{0x100,0x300,0xffff}) {
            assertEquals(IppClient.Outcome.UNKNOWN, IppClient.interpret(HttpResponseReader.read(
                    new ByteArrayInputStream(response(status,1))),1).outcome);
        }
        assertEquals(IppClient.Outcome.UNKNOWN, IppClient.interpret(HttpResponseReader.read(
                new ByteArrayInputStream(response(0,2))),1).outcome);
        for (String text : new String[]{"HTTP/1.1 500 200\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 0\r\n\r\n"}) {
            assertEquals(IppClient.Outcome.UNKNOWN, IppClient.interpret(HttpResponseReaderTest.parse(text),1).outcome);
        }
        byte[] bad = response(0,1); bad[bad.length-1] = 0x42;
        assertEquals(IppClient.Outcome.UNKNOWN, IppClient.interpret(HttpResponseReader.read(new ByteArrayInputStream(bad)),1).outcome);
    }
    @Test public void utf8NameLengthAndRequestId() throws Exception {
        byte[] request = IppClient.buildIppAttributes("192.168.1.2", "Žluťoučký 🖨".repeat(50), 123);
        assertEquals(123, request[7]);
        assertTrue(request.length < 600);
        assertTrue(new String(request, StandardCharsets.UTF_8).contains("Žluťoučký 🖨"));
    }
    interface Reply { void reply(Socket socket, int id) throws Exception; }
    static int consumeRequest(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        // Request uses the same fixed length framing; replace the request line for this helper.
        while (in.read() != '\n') { }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(HttpResponseReaderTest.bytes("HTTP/1.1 200 OK\r\n"));
        InputStream combined = new SequenceInputStream(new ByteArrayInputStream(bytes.toByteArray()), in);
        byte[] body = HttpResponseReader.read(combined).body;
        return (body[4]&255)<<24 | (body[5]&255)<<16 | (body[6]&255)<<8 | body[7]&255;
    }
    static IppClient.IppResult exchange(Reply reply, JobControl control, int readMs, int totalMs) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> task = executor.submit(() -> {
                try (Socket s = server.accept()) { s.setSoTimeout(3000); int id = consumeRequest(s); reply.reply(s,id); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            try {
                IppClient.IppResult result = IppClient.send(new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),
                        "192.168.1.2", new ByteArrayInputStream(new byte[]{9,8,7}), 3, "test",control,1000,readMs,totalMs);
                task.get(4,TimeUnit.SECONDS);
                server.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, server::accept); // no automatic resubmission
                return result;
            } finally { executor.shutdownNow(); }
        }
    }
    @Test public void socketFragmentsAndRepeatedJobs() throws Exception {
        for(int i=0;i<3;i++) assertEquals(IppClient.Outcome.ACCEPTED, exchange((s,id)-> {
            for(byte b:response(0,id)) { s.getOutputStream().write(b); s.getOutputStream().flush(); }
        },new JobControl(),1000,3000).outcome);
    }
    @Test public void disconnectAfterSubmissionIsUnknown() throws Exception {
        assertEquals(IppClient.Outcome.UNKNOWN,exchange((s,id)->{},new JobControl(),500,2000).outcome);
    }
    @Test public void truncatedBodyIsUnknown() throws Exception {
        assertEquals(IppClient.Outcome.UNKNOWN,exchange((s,id)-> {
            byte[] all=response(0,id); s.getOutputStream().write(all,0,all.length-2);
        },new JobControl(),500,2000).outcome);
    }
    @Test public void readTimeoutIsUnknown() throws Exception {
        assertEquals(IppClient.Outcome.UNKNOWN,exchange((s,id)->Thread.sleep(300),new JobControl(),80,2000).outcome);
    }
    @Test public void totalDeadlineStopsWaiting() throws Exception {
        assertEquals(IppClient.Outcome.UNKNOWN,exchange((s,id)->Thread.sleep(300),new JobControl(),2000,80).outcome);
    }
    @Test public void cancellationClosesBlockedResponse() throws Exception {
        JobControl control=new JobControl();
        assertEquals(IppClient.Outcome.CANCELLED,exchange((s,id)-> { control.cancel(); Thread.sleep(50); },control,2000,3000).outcome);
    }
    @Test public void refusedConnectionIsNotSent() throws Exception {
        int port;
        try(ServerSocket s=new ServerSocket(0)) { port=s.getLocalPort(); }
        assertEquals(IppClient.Outcome.REJECTED,IppClient.send(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),
                "192.168.1.2",new ByteArrayInputStream(new byte[0]),0,"test",new JobControl(),1000,1000,2000).outcome);
    }
    @Test public void cancelledBeforeConnectionSendsNothing() throws Exception {
        JobControl control=new JobControl(); control.cancel();
        assertEquals(IppClient.Outcome.CANCELLED,IppClient.send(new InetSocketAddress(InetAddress.getLoopbackAddress(),1),
                "192.168.1.2",new ByteArrayInputStream(new byte[0]),0,"test",control,1000,1000,2000).outcome);
    }
    @Test public void deadlineAlsoUnblocksStalledUpload() throws Exception {
        try(ServerSocket server=new ServerSocket(0,4,InetAddress.getLoopbackAddress())) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> accepted=executor.submit(()->{
                try(Socket socket=server.accept()){Thread.sleep(400);}
                catch(Exception e){throw new RuntimeException(e);}
            });
            long before=System.nanoTime();
            try {
                IppClient.IppResult result=IppClient.send(new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),
                        "192.168.1.2",new ByteArrayInputStream(new byte[16*1024*1024]),16*1024*1024,
                        "blocked upload",new JobControl(),1000,3000,150);
                assertEquals(IppClient.Outcome.UNKNOWN,result.outcome);
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<2000);
                accepted.get(3,TimeUnit.SECONDS);
            } finally {executor.shutdownNow();}
        }
    }
}
