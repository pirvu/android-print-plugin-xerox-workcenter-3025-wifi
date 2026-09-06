package com.xerox3025.printplugin;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class PrinterDiscoveryManager {

    public static class DiscoveredPrinter {
        public final String name;
        public final String host;
        public final int port;
        public final String serviceType;
        public final String attributes;

        public DiscoveredPrinter(String name, String host, int port,
                                 String serviceType, String attributes) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.serviceType = serviceType;
            this.attributes = attributes;
        }

        @Override
        public String toString() {
            return name + " (" + host + ":" + port + ")";
        }
    }

    public interface DiscoveryListener {
        void onPrinterFound(DiscoveredPrinter printer);
        void onDiscoveryComplete(List<DiscoveredPrinter> printers);
        void onDiscoveryFailed(String error);
    }

    private static final int DISCOVERY_TIMEOUT_MS = 8000;
    private static final String SERVICE_TYPE_IPP = "_ipp._tcp.";
    private static final String SERVICE_TYPE_PDL = "_pdl-datastream._tcp.";

    private final Context context;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener activeDiscoveryListener;
    private String activeServiceType;
    private DiscoveryListener callback;
    private final CopyOnWriteArrayList<DiscoveredPrinter> foundPrinters = new CopyOnWriteArrayList<>();
    private boolean discoveryActive = false;

    public PrinterDiscoveryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void startDiscovery(DiscoveryListener listener) {
        if (discoveryActive) {
            stopDiscovery();
        }

        this.callback = listener;
        this.foundPrinters.clear();
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);

        if (nsdManager == null) {
            if (callback != null) callback.onDiscoveryFailed("NSD service not available");
            return;
        }

        discoveryActive = true;
        PrintLog.i("PrinterDiscovery", "Starting mDNS discovery for IPP printers");
        discover(SERVICE_TYPE_IPP);
    }

    private void discover(String serviceType) {
        activeServiceType = serviceType;
        activeDiscoveryListener = createDiscoveryListener(serviceType);
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                activeDiscoveryListener);

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (discoveryActive && SERVICE_TYPE_IPP.equals(activeServiceType)) {
                PrintLog.i("PrinterDiscovery", "IPP discovery timeout, trying PDL");
                nsdManager.stopServiceDiscovery(activeDiscoveryListener);
                discover(SERVICE_TYPE_PDL);
            } else if (discoveryActive) {
                stopDiscovery();
                notifyComplete();
            }
        }, DISCOVERY_TIMEOUT_MS);
    }

    private NsdManager.DiscoveryListener createDiscoveryListener(String serviceType) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                PrintLog.i("PrinterDiscovery", "Discovery started: " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                PrintLog.i("PrinterDiscovery", "Service found: " + serviceInfo.getServiceName()
                        + " type=" + serviceInfo.getServiceType());
                nsdManager.resolveService(serviceInfo, createResolveListener());
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                PrintLog.i("PrinterDiscovery", "Service lost: " + serviceInfo.getServiceName());
                DiscoveredPrinter removed = null;
                for (DiscoveredPrinter p : foundPrinters) {
                    if (p.name.equals(serviceInfo.getServiceName())) {
                        removed = p;
                        break;
                    }
                }
                if (removed != null) {
                    foundPrinters.remove(removed);
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                PrintLog.i("PrinterDiscovery", "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                PrintLog.e("PrinterDiscovery", "Discovery start failed: " + errorCode);
                discoveryActive = false;
                if (callback != null) callback.onDiscoveryFailed(
                        "Discovery failed (error " + errorCode + ")");
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                PrintLog.w("PrinterDiscovery", "Stop discovery failed: " + errorCode);
            }
        };
    }

    private NsdManager.ResolveListener createResolveListener() {
        return new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                PrintLog.w("PrinterDiscovery", "Resolve failed for "
                        + serviceInfo.getServiceName() + ": " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                String host = serviceInfo.getHost() != null
                        ? serviceInfo.getHost().getHostAddress() : "unknown";
                int port = serviceInfo.getPort();
                String type = serviceInfo.getServiceType();
                String attrs = serviceInfo.getAttributes() != null
                        ? parseAttributes(serviceInfo.getAttributes()) : "";

                PrintLog.i("PrinterDiscovery", "Resolved: " + name
                        + " at " + host + ":" + port);

                DiscoveredPrinter printer = new DiscoveredPrinter(
                        name, host, port, type, attrs);

                for (DiscoveredPrinter existing : foundPrinters) {
                    if (existing.host.equals(host) && existing.port == port) {
                        return;
                    }
                }
                foundPrinters.add(printer);

                if (callback != null) callback.onPrinterFound(printer);
            }
        };
    }

    private String parseAttributes(Map<String, byte[]> attributes) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            String value = new String(entry.getValue());
            sb.append(entry.getKey()).append("=").append(value);
        }
        return sb.toString();
    }

    public void stopDiscovery() {
        discoveryActive = false;
        if (nsdManager != null && activeDiscoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(activeDiscoveryListener);
            } catch (Exception e) {
                PrintLog.w("PrinterDiscovery", "Error stopping discovery: " + e.getMessage());
            }
        }
        activeDiscoveryListener = null;
    }

    private void notifyComplete() {
        if (callback != null) {
            List<DiscoveredPrinter> result = new ArrayList<>(foundPrinters);
            callback.onDiscoveryComplete(result);
        }
    }

    public List<DiscoveredPrinter> getFoundPrinters() {
        return Collections.unmodifiableList(new ArrayList<>(foundPrinters));
    }

    public void destroy() {
        stopDiscovery();
        foundPrinters.clear();
    }
}
