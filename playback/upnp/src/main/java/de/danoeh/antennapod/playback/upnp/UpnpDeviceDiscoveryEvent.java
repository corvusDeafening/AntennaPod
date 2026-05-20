package de.danoeh.antennapod.playback.upnp;

import org.jupnp.model.meta.RemoteDevice;

import java.util.List;

public class UpnpDeviceDiscoveryEvent {
    public final List<RemoteDevice> devices;

    public UpnpDeviceDiscoveryEvent(List<RemoteDevice> devices) {
        this.devices = devices;
    }
}
