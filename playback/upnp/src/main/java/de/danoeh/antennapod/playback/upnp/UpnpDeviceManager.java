package de.danoeh.antennapod.playback.upnp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;
import org.jupnp.android.AndroidUpnpService;
import org.jupnp.android.AndroidUpnpServiceImpl;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class UpnpDeviceManager {

    private static final String TAG = "UpnpDeviceManager";
    private static UpnpDeviceManager instance;

    private static final int MAX_SEARCH_RETRIES = 30;

    private AndroidUpnpService upnpService;
    private final List<RemoteDevice> discoveredDevices = new CopyOnWriteArrayList<>();
    private RemoteDevice selectedDevice;
    private WifiManager.MulticastLock multicastLock;
    private boolean bound = false;
    private int searchRetryCount = 0;

    private final DefaultRegistryListener registryListener = new DefaultRegistryListener() {
        @Override
        public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
            if (isMediaRenderer(device)) {
                discoveredDevices.add(device);
                Log.d(TAG, "UPnP renderer found: " + device.getDetails().getFriendlyName());
                EventBus.getDefault().post(new UpnpDeviceDiscoveryEvent(new ArrayList<>(discoveredDevices)));
            }
        }

        @Override
        public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
            if (discoveredDevices.remove(device)) {
                Log.d(TAG, "UPnP renderer removed: " + device.getDetails().getFriendlyName());
                if (device.equals(selectedDevice)) {
                    setSelectedDevice(null);
                }
                EventBus.getDefault().post(new UpnpDeviceDiscoveryEvent(new ArrayList<>(discoveredDevices)));
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "jUPnP service connected");
            upnpService = (AndroidUpnpService) service;
            searchRetryCount = 0;
            startSearch();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "jUPnP service disconnected");
            upnpService = null;
        }
    };

    private UpnpDeviceManager() {
    }

    private void startSearch() {
        if (upnpService == null) {
            return;
        }
        org.jupnp.UpnpService svc = upnpService.get();
        if (svc == null || svc.getRegistry() == null || svc.getControlPoint() == null) {
            if (searchRetryCount >= MAX_SEARCH_RETRIES) {
                Log.e(TAG, "jUPnP never finished initializing after " + MAX_SEARCH_RETRIES + " s");
                return;
            }
            Log.w(TAG, "jUPnP not ready yet — retrying in 1 s (attempt "
                    + (searchRetryCount + 1) + "/" + MAX_SEARCH_RETRIES + ")");
            searchRetryCount++;
            new Handler(Looper.getMainLooper()).postDelayed(this::startSearch, 1000);
            return;
        }
        Log.d(TAG, "jUPnP ready — starting MediaRenderer search");
        svc.getRegistry().addListener(registryListener);
        svc.getControlPoint().search(
                new org.jupnp.model.message.header.UDADeviceTypeHeader(
                        new UDADeviceType("MediaRenderer", 1)));
    }

    public static synchronized UpnpDeviceManager getInstance() {
        if (instance == null) {
            instance = new UpnpDeviceManager();
        }
        return instance;
    }

    public void startDiscovery(@NonNull Context context) {
        if (bound) {
            return;
        }
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock(TAG);
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }
        bound = context.getApplicationContext().bindService(
                new Intent(context, AndroidUpnpServiceImpl.class),
                serviceConnection,
                Context.BIND_AUTO_CREATE);
        Log.d(TAG, "Discovery started, bound=" + bound);
    }

    public void stopDiscovery(@NonNull Context context) {
        if (!bound) {
            return;
        }
        if (upnpService != null && upnpService.get() != null
                && upnpService.get().getRegistry() != null) {
            upnpService.get().getRegistry().removeListener(registryListener);
        }
        context.getApplicationContext().unbindService(serviceConnection);
        bound = false;
        upnpService = null;
        searchRetryCount = 0;
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            multicastLock = null;
        }
        Log.d(TAG, "Discovery stopped");
    }

    public void refreshDiscovery() {
        if (upnpService != null && upnpService.get() != null
                && upnpService.get().getControlPoint() != null) {
            upnpService.get().getControlPoint().search(
                    new org.jupnp.model.message.header.UDADeviceTypeHeader(
                            new UDADeviceType("MediaRenderer", 1)));
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
    public AndroidUpnpService getUpnpService() {
        return upnpService;
    }

    private static boolean isMediaRenderer(RemoteDevice device) {
        return device.getType() != null
                && "MediaRenderer".equals(device.getType().getType());
    }
}
