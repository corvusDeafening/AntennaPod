package de.danoeh.antennapod.playback.upnp;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import de.danoeh.antennapod.playback.cast.CastEnabledActivity;

public abstract class UpnpEnabledActivity extends CastEnabledActivity {

    @Override
    protected void onDestroy() {
        super.onDestroy();
        UpnpDeviceManager.getInstance().stopDiscovery(this);
    }

    public void requestUpnpButton(Menu menu) {
        if (menu.findItem(R.id.upnp_device_menu_item) != null) {
            return;
        }
        getMenuInflater().inflate(R.menu.upnp_button, menu);
    }

    public boolean onUpnpMenuItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.upnp_device_menu_item) {
            UpnpDevicePickerDialog.newInstance()
                    .show(getSupportFragmentManager(), UpnpDevicePickerDialog.TAG);
            return true;
        }
        return false;
    }
}
