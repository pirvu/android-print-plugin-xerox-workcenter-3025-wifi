package com.xerox3025.printplugin;

import android.Manifest;
import android.content.*;
import android.content.pm.*;
import android.print.PrintAttributes;
import android.print.PrinterInfo;
import android.printservice.PrintService;
import android.net.Uri;
import androidx.core.content.FileProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class AndroidIntegrationTest {
    @Test public void serviceRegistrationAndLeastPrivilege() throws Exception {
        Context context=RuntimeEnvironment.getApplication();PackageManager pm=context.getPackageManager();
        ServiceInfo service=pm.getServiceInfo(new ComponentName(context,Xerox3025PrintService.class),PackageManager.GET_META_DATA);
        assertTrue(service.exported);assertEquals(Manifest.permission.BIND_PRINT_SERVICE,service.permission);
        assertNotNull(service.metaData);assertTrue(service.metaData.containsKey("android.printservice"));
        PackageInfo info=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_PERMISSIONS);
        Set<String> permissions=new HashSet<>(Arrays.asList(info.requestedPermissions));
        assertFalse(permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE));
        assertFalse(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION));
        assertFalse(permissions.contains(Manifest.permission.ACCESS_WIFI_STATE));
        assertFalse(permissions.contains(Manifest.permission.ACCESS_NETWORK_STATE));
        assertEquals(0,context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP);
    }
    @Test public void discoveryOnlyAdvertisesImplementedModes() {
        var controller=Robolectric.buildService(Xerox3025PrintService.class).create();
        Xerox3025PrintService service=controller.get();
        var discovery=new Xerox3025PrintService.Xerox3025DiscoverySession(service);
        discovery.onStartPrinterDiscovery(Collections.emptyList());
        List<PrinterInfo> printers=discovery.getPrinters();assertEquals(1,printers.size());
        var caps=printers.get(0).getCapabilities();assertEquals(3,caps.getMediaSizes().size());
        assertEquals(1,caps.getResolutions().size());assertEquals(600,caps.getResolutions().get(0).getHorizontalDpi());
        assertEquals(PrintAttributes.DUPLEX_MODE_NONE,caps.getDuplexModes());
        assertEquals(PrintAttributes.COLOR_MODE_MONOCHROME,caps.getColorModes());
        controller.destroy();
    }
    @Test public void settingsAndScanLaunchAndDestroyOnAndroid15() {
        try(var settings=Robolectric.buildActivity(SettingsActivity.class).setup()) { assertNotNull(settings.get()); }
        // Deliberately invalid address makes the initial status query fail without LAN traffic.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit().putString("printer_ip","invalid").commit();
        try(var scan=Robolectric.buildActivity(ScanActivity.class).setup()) { assertNotNull(scan.get()); }
    }
    @Test public void fileProviderCannotExposePdfSpoolOrPreferences() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        File scan=new File(new File(c.getCacheDir(),"scans"),"page.jpg");scan.getParentFile().mkdirs();scan.createNewFile();
        Uri uri=FileProvider.getUriForFile(c,c.getPackageName()+".fileprovider",scan);assertEquals("content",uri.getScheme());
        File pdf=new File(c.getCacheDir(),"private.pdf");pdf.createNewFile();
        assertThrows(IllegalArgumentException.class,()->FileProvider.getUriForFile(c,c.getPackageName()+".fileprovider",pdf));
        scan.delete();pdf.delete();
    }
    @Test public void synchronizedHistoryRetainsConcurrentResults() throws Exception {
        Context c=RuntimeEnvironment.getApplication();PrintJobHistory.clearHistory(c);
        List<Thread> threads=new ArrayList<>();
        for(int i=0;i<20;i++){int n=i;Thread t=new Thread(()->PrintJobHistory.addJob(c,
                new PrintJobHistory.JobRecord("job"+n,n,"UNKNOWN","Check output",1)));threads.add(t);t.start();}
        for(Thread t:threads)t.join();assertEquals(20,PrintJobHistory.getHistory(c).size());
    }
}
