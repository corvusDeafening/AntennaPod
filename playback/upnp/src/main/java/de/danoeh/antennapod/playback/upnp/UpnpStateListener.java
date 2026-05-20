package de.danoeh.antennapod.playback.upnp;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class UpnpStateListener {

    public UpnpStateListener() {
        EventBus.getDefault().register(this);
    }

    public void destroy() {
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceSelected(UpnpDeviceSelectedEvent event) {
        onSessionStartedOrEnded();
    }

    public void onSessionStartedOrEnded() {
    }
}
