package de.danoeh.antennapod.playback.upnp;

import org.junit.Test;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteDeviceIdentity;
import org.jupnp.model.types.UDN;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UpnpDeviceManagerTest {

    private static RemoteDevice deviceWithUdn(String uuid) {
        RemoteDevice device = mock(RemoteDevice.class);
        RemoteDeviceIdentity identity = mock(RemoteDeviceIdentity.class);
        when(device.getIdentity()).thenReturn(identity);
        when(identity.getUdn()).thenReturn(new UDN(uuid));
        return device;
    }

    @Test
    public void isAlreadyDiscovered_emptyList_returnsFalse() {
        List<RemoteDevice> list = new ArrayList<>();
        assertFalse(UpnpDeviceManager.isAlreadyDiscovered(list, deviceWithUdn("uuid-1")));
    }

    @Test
    public void isAlreadyDiscovered_sameUdn_returnsTrue() {
        List<RemoteDevice> list = new ArrayList<>();
        list.add(deviceWithUdn("uuid-1"));
        assertTrue(UpnpDeviceManager.isAlreadyDiscovered(list, deviceWithUdn("uuid-1")));
    }

    @Test
    public void isAlreadyDiscovered_differentUdn_returnsFalse() {
        List<RemoteDevice> list = new ArrayList<>();
        list.add(deviceWithUdn("uuid-1"));
        assertFalse(UpnpDeviceManager.isAlreadyDiscovered(list, deviceWithUdn("uuid-2")));
    }

    @Test
    public void isAlreadyDiscovered_multipleDevices_matchesCorrectUdn() {
        List<RemoteDevice> list = new ArrayList<>();
        list.add(deviceWithUdn("uuid-1"));
        list.add(deviceWithUdn("uuid-2"));
        assertTrue(UpnpDeviceManager.isAlreadyDiscovered(list, deviceWithUdn("uuid-2")));
        assertFalse(UpnpDeviceManager.isAlreadyDiscovered(list, deviceWithUdn("uuid-3")));
    }
}
