package de.danoeh.antennapod.playback.upnp;

import android.app.Dialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jupnp.model.meta.RemoteDevice;

import java.util.ArrayList;
import java.util.List;

public class UpnpDevicePickerDialog extends DialogFragment {

    public static final String TAG = "UpnpDevicePickerDialog";

    private ArrayAdapter<String> adapter;
    private final List<RemoteDevice> deviceList = new ArrayList<>();

    public static UpnpDevicePickerDialog newInstance() {
        return new UpnpDevicePickerDialog();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        List<String> names = buildNameList();
        adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_single_choice, names);

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.upnp_device_picker_title);
        builder.setSingleChoiceItems(adapter, getSelectedIndex(), (dialog, which) -> {
            if (which == deviceList.size()) {
                // Disconnect option
                UpnpDeviceManager.getInstance().setSelectedDevice(null);
            } else {
                UpnpDeviceManager.getInstance().setSelectedDevice(deviceList.get(which));
            }
            dismiss();
        });
        builder.setNegativeButton(android.R.string.cancel, null);

        return builder.create();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        UpnpDeviceManager.getInstance().refreshDiscovery();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceDiscovery(UpnpDeviceDiscoveryEvent event) {
        deviceList.clear();
        deviceList.addAll(event.devices);
        if (adapter != null) {
            adapter.clear();
            adapter.addAll(buildNameList());
            adapter.notifyDataSetChanged();
        }
    }

    private List<String> buildNameList() {
        deviceList.clear();
        deviceList.addAll(UpnpDeviceManager.getInstance().getDiscoveredDevices());
        List<String> names = new ArrayList<>();
        for (RemoteDevice d : deviceList) {
            names.add(d.getDetails().getFriendlyName());
        }
        names.add(getString(R.string.upnp_disconnect));
        return names;
    }

    private int getSelectedIndex() {
        RemoteDevice selected = UpnpDeviceManager.getInstance().getSelectedDevice();
        if (selected == null) {
            return -1;
        }
        for (int i = 0; i < deviceList.size(); i++) {
            if (deviceList.get(i).equals(selected)) {
                return i;
            }
        }
        return -1;
    }
}
