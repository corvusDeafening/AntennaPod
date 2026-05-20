package de.danoeh.antennapod.playback.upnp;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteOrder;
import java.util.Locale;

public class LocalMediaServer extends NanoHTTPD {

    private static final String TAG = "LocalMediaServer";
    private final String filePath;
    private final String mimeType;

    public LocalMediaServer(@NonNull String filePath) throws IOException {
        super(0);
        this.filePath = filePath;
        this.mimeType = guessMimeType(filePath);
        start();
        Log.d(TAG, "Local media server started on port " + getListeningPort());
    }

    @Override
    public Response serve(IHTTPSession session) {
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            return new Response(Response.Status.NOT_FOUND, "text/plain", "File not found");
        }
        try {
            return new Response(Response.Status.OK, mimeType, new FileInputStream(file));
        } catch (IOException e) {
            Log.e(TAG, "Error serving file", e);
            return new Response(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    @Nullable
    public String getStreamUrl(@NonNull Context context) {
        String ip = getWifiIpAddress(context);
        if (ip == null) {
            return null;
        }
        return "http://" + ip + ":" + getListeningPort() + "/stream";
    }

    @Nullable
    private static String getWifiIpAddress(@NonNull Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            int ip = wifiManager.getConnectionInfo().getIpAddress();
            if (ip == 0) {
                return null;
            }
            if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                ip = Integer.reverseBytes(ip);
            }
            return InetAddress.getByAddress(new byte[]{
                    (byte) (ip >> 24 & 0xff),
                    (byte) (ip >> 16 & 0xff),
                    (byte) (ip >> 8 & 0xff),
                    (byte) (ip & 0xff)
            }).getHostAddress();
        } catch (Exception e) {
            Log.e(TAG, "Could not get WiFi IP", e);
            return null;
        }
    }

    @NonNull
    private static String guessMimeType(@NonNull String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        } else if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            return "audio/mp4";
        } else if (lower.endsWith(".ogg") || lower.endsWith(".oga")) {
            return "audio/ogg";
        } else if (lower.endsWith(".flac")) {
            return "audio/flac";
        } else if (lower.endsWith(".opus")) {
            return "audio/ogg";
        } else if (lower.endsWith(".wav")) {
            return "audio/wav";
        } else if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        return "audio/mpeg";
    }

    public String getMimeType() {
        return mimeType;
    }
}
