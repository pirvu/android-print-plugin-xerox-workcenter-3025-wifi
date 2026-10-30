package com.xerox3025.printplugin;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;
public class LocalPrinterAddressTest {
    @Test public void privateAndLinkLocalAllowed() throws Exception {
        for(String ip:new String[]{"10.0.0.1","172.16.0.1","172.31.255.254","192.168.1.2","169.254.4.1"})
            assertEquals(ip,LocalPrinterAddress.parse(ip).getHostAddress());
    }
    @Test public void publicHostnamesInjectionAndAlternativeNotationsRejected() {
        for(String ip:new String[]{"8.8.8.8","127.0.0.1","0.0.0.0","224.0.0.1","172.32.0.1","printer.local",
                "192.168.01.2","192.168.1.256","192.168.1.2\r\nInjected:yes","0xc0a80102","3232235778","[::1]","192.168.1.2:631",""})
            assertThrows(ip,IOException.class,()->LocalPrinterAddress.parse(ip));
    }
}
