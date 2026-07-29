package de.danoeh.antennapod.playback.upnp;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;
import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.android.AndroidRouter;
import org.jupnp.android.AndroidUpnpServiceConfiguration;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.protocol.ProtocolFactory;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;
import org.jupnp.transport.Router;
import org.jupnp.transport.spi.NetworkAddressFactory;
import org.jupnp.transport.spi.StreamServer;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class UpnpDeviceManager {

    private static final String TAG = "UpnpDeviceManager";
    private static UpnpDeviceManager instance;

    private volatile UpnpService upnpService;
    private final List<RemoteDevice> discoveredDevices = new CopyOnWriteArrayList<>();
    private RemoteDevice selectedDevice;
    private WifiManager.MulticastLock multicastLock;

    private final DefaultRegistryListener registryListener = new DefaultRegistryListener() {
        @Override
        public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
            Log.d(TAG, "UPnP device found: type=" + device.getType()
                    + " name=" + (device.getDetails() != null ? device.getDetails().getFriendlyName() : "?"));
            if (isMediaRenderer(device)) {
                discoveredDevices.add(device);
                Log.d(TAG, "UPnP renderer added: " + device.getDetails().getFriendlyName());
                EventBus.getDefault().post(new UpnpDeviceDiscoveryEvent(new ArrayList<>(discoveredDevices)));
            }
        }

        @Override
        public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
            if (discoveredDevices.remove(device)) {
                Log.d(TAG, "UPnP renderer removed: " + device.getDetails().getFriendlyName());
                if (device.equals(selectedDevice)) {
                    new Handler(Looper.getMainLooper()).post(() -> setSelectedDevice(null));
                }
                EventBus.getDefault().post(new UpnpDeviceDiscoveryEvent(new ArrayList<>(discoveredDevices)));
            }
        }
    };

    private UpnpDeviceManager() {
    }

    public static synchronized UpnpDeviceManager getInstance() {
        if (instance == null) {
            instance = new UpnpDeviceManager();
        }
        return instance;
    }

    public void startDiscovery(@NonNull Context context) {
        if (upnpService != null) {
            return;
        }
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock(TAG);
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                Log.d(TAG, "Creating UpnpServiceImpl with AndroidRouter");
                UpnpServiceImpl svc = new UpnpServiceImpl(new AndroidUpnpServiceConfiguration() {
                    @Override
                    @SuppressWarnings("rawtypes")
                    public StreamServer createStreamServer(NetworkAddressFactory naf) {
                        // Jetty (required by the default impl) is not bundled in the APK.
                        // Returning null disables UPnP event subscriptions but discovery
                        // and AVTransport commands still work fine.
                        return null;
                    }
                }) {
                    @Override
                    protected Router createRouter(ProtocolFactory protocolFactory, Registry registry) {
                        return new AndroidRouter(getConfiguration(), protocolFactory, appContext);
                    }
                };
                // In jUPnP 3.x, activate() initializes registry, router, and controlPoint.
                // AndroidUpnpServiceImpl fails to call this, leaving them all null.
                svc.activate(new UpnpServiceImpl.Config() {
                    @Override
                    public boolean initialSearchEnabled() {
                        return true;
                    }

                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return UpnpServiceImpl.Config.class;
                    }
                });
                Log.d(TAG, "jUPnP activated — registry=" + svc.getRegistry()
                        + " cp=" + svc.getControlPoint()
                        + " router=" + svc.getRouter());
                if (svc.getRegistry() == null || svc.getControlPoint() == null) {
                    Log.e(TAG, "jUPnP registry/controlPoint still null after activate — UPnP disabled");
                    return;
                }
                // Log which network interfaces jUPnP will use for SSDP
                try {
                    java.util.Enumeration<java.net.NetworkInterface> ifaces =
                            java.net.NetworkInterface.getNetworkInterfaces();
                    while (ifaces != null && ifaces.hasMoreElements()) {
                        java.net.NetworkInterface iface = ifaces.nextElement();
                        if (iface.isUp() && !iface.isLoopback() && iface.supportsMulticast()) {
                            Log.d(TAG, "multicast-capable iface: " + iface.getName()
                                    + " addrs=" + java.util.Collections.list(iface.getInetAddresses()));
                        }
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "Could not enumerate interfaces", ex);
                }
                upnpService = svc;
                svc.getRegistry().addListener(registryListener);
                svc.getControlPoint().search(new org.jupnp.model.message.header.STAllHeader());
                Log.d(TAG, "ssdp:all search issued");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start jUPnP", e);
            }
        }, "upnp-init").start();
        Log.d(TAG, "Discovery started");
    }

    public void stopDiscovery(@NonNull Context context) {
        UpnpService svc = upnpService;
        if (svc == null) {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                multicastLock = null;
            }
            return;
        }
        upnpService = null;
        try {
            if (svc.getRegistry() != null) {
                svc.getRegistry().removeListener(registryListener);
            }
            svc.shutdown();
        } catch (Exception e) {
            Log.w(TAG, "Error stopping jUPnP", e);
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            multicastLock = null;
        }
        Log.d(TAG, "Discovery stopped");
    }

    public void refreshDiscovery() {
        UpnpService svc = upnpService;
        if (svc != null && svc.getControlPoint() != null) {
            svc.getControlPoint().search(new org.jupnp.model.message.header.STAllHeader());
        }
    }

    @NonNull
    public List<RemoteDevice> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices);
    }

    @Nullable
    public RemoteDevice getSelectedDevice() {
        return selectedDevice;
    }

    public void setSelectedDevice(@Nullable RemoteDevice device) {
        selectedDevice = device;
        EventBus.getDefault().post(new UpnpDeviceSelectedEvent(device));
        Log.d(TAG, "Selected device: " + (device != null ? device.getDetails().getFriendlyName() : "none"));
    }

    @Nullable
    public UpnpService getUpnpService() {
        return upnpService;
    }

    private static boolean isMediaRenderer(RemoteDevice device) {
        return device.getType() != null
                && "MediaRenderer".equals(device.getType().getType());
    }
}
