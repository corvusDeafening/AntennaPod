package de.danoeh.antennapod.playback.upnp;

import androidx.annotation.Nullable;
import org.jupnp.model.meta.RemoteDevice;

public class UpnpDeviceSelectedEvent {
    @Nullable
    public final RemoteDevice device;

    public UpnpDeviceSelectedEvent(@Nullable RemoteDevice device) {
        this.device = device;
    }
}
