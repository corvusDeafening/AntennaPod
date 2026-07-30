package de.danoeh.antennapod.playback.upnp;

import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class UpnpStateListener {

    private static final String TAG = "UpnpStateListener";

    public UpnpStateListener() {
        EventBus.getDefault().register(this);
        Log.d(TAG, "Registered with EventBus");
    }

    public void destroy() {
        EventBus.getDefault().unregister(this);
        Log.d(TAG, "Unregistered from EventBus");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceSelected(UpnpDeviceSelectedEvent event) {
        Log.d(TAG, "onDeviceSelected: device=" + (event.device != null
                ? event.device.getDetails().getFriendlyName() : "null"));
        onSessionStartedOrEnded();
    }

    public void onSessionStartedOrEnded() {
    }
}
