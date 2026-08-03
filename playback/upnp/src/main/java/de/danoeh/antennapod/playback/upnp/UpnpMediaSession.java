package de.danoeh.antennapod.playback.upnp;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.Nullable;

import de.danoeh.antennapod.model.playback.Playable;

import org.jupnp.UpnpService;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.Service;
import org.jupnp.model.types.UDAServiceType;
import org.jupnp.support.avtransport.callback.GetPositionInfo;
import org.jupnp.support.avtransport.callback.Pause;
import org.jupnp.support.avtransport.callback.Play;
import org.jupnp.support.avtransport.callback.Seek;
import org.jupnp.support.avtransport.callback.SetAVTransportURI;
import org.jupnp.support.avtransport.callback.Stop;
import org.jupnp.support.model.PositionInfo;
import org.jupnp.support.model.SeekMode;
import org.jupnp.support.renderingcontrol.callback.SetVolume;

/**
 * Sends SOAP commands to a UPnP AVTransport renderer.
 * Stateless — callers own session lifecycle.
 */
@SuppressWarnings("rawtypes")
public class UpnpMediaSession {

    public interface PositionCallback {
        void onPosition(int positionMs);
    }

    private static final String TAG = "UpnpMediaSession";

    private final UpnpService upnpService;
    private final RemoteDevice device;

    public UpnpMediaSession(UpnpService upnpService, RemoteDevice device) {
        this.upnpService = upnpService;
        this.device = device;
    }

    @Nullable
    public static UpnpMediaSession fromCurrentDevice() {
        UpnpDeviceManager mgr = UpnpDeviceManager.getInstance();
        RemoteDevice device = mgr.getSelectedDevice();
        UpnpService svc = mgr.getUpnpService();
        if (device == null || svc == null) {
            return null;
        }
        return new UpnpMediaSession(svc, device);
    }

    public void startPlayback(String streamUrl, @Nullable Playable media, int positionMs) {
        Service avTransport = getAvTransport();
        if (avTransport == null) {
            Log.e(TAG, "No AVTransport on " + device.getDetails().getFriendlyName());
            return;
        }
        String metadata = media != null
                ? UpnpMetadataCreator.createMetadata(media, streamUrl, null, media.getDuration())
                : buildMinimalMetadata(streamUrl);
        Log.d(TAG, "startPlayback: url=" + streamUrl
                + " device=" + device.getDetails().getFriendlyName()
                + " positionMs=" + positionMs);
        upnpService.getControlPoint().execute(new SetAVTransportURI(avTransport, streamUrl, metadata) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "SetAVTransportURI succeeded");
                if (positionMs > 0) {
                    seekThenPlay(positionMs);
                } else {
                    doPlay();
                }
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                Log.e(TAG, "SetAVTransportURI failed: " + msg);
            }
        });
    }

    public void stop() {
        Service avTransport = getAvTransport();
        if (avTransport == null) {
            return;
        }
        upnpService.getControlPoint().execute(new Stop(avTransport) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Stop succeeded");
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                Log.w(TAG, "Stop failed: " + msg);
            }
        });
    }

    public void seekTo(int positionMs) {
        Service avTransport = getAvTransport();
        if (avTransport == null) {
            return;
        }
        upnpService.getControlPoint().execute(
                new Seek(avTransport, SeekMode.REL_TIME, UpnpMetadataCreator.formatDuration(positionMs)) {
                    @Override
                    public void success(ActionInvocation invocation) {
                        Log.d(TAG, "Seek to " + positionMs + "ms succeeded");
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                        Log.w(TAG, "Seek failed: " + msg);
                    }
                });
    }

    public void pause() {
        Service avTransport = getAvTransport();
        if (avTransport == null) {
            return;
        }
        upnpService.getControlPoint().execute(new Pause(avTransport) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Pause succeeded");
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                Log.w(TAG, "Pause failed: " + msg);
            }
        });
    }

    public void resume() {
        doPlay();
    }

    public void getPositionMs(Handler mainHandler, PositionCallback callback) {
        Service avTransport = getAvTransport();
        if (avTransport == null) {
            mainHandler.post(() -> callback.onPosition(0));
            return;
        }
        upnpService.getControlPoint().execute(new GetPositionInfo(avTransport) {
            @Override
            public void received(ActionInvocation invocation, PositionInfo positionInfo) {
                int posMs = UpnpMetadataCreator.parseTimeToMs(positionInfo.getRelTime());
                mainHandler.post(() -> callback.onPosition(posMs));
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                Log.w(TAG, "GetPositionInfo failed: " + msg);
                mainHandler.post(() -> callback.onPosition(0));
            }
        });
    }

    private void seekThenPlay(int ms) {
        Service avTransport = getAvTransport();
        if (avTransport == null) {
            doPlay();
            return;
        }
        upnpService.getControlPoint().execute(
                new Seek(avTransport, SeekMode.REL_TIME, UpnpMetadataCreator.formatDuration(ms)) {
                    @Override
                    public void success(ActionInvocation invocation) {
                        doPlay();
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                        Log.e(TAG, "Seek failed: " + msg);
                        doPlay();
                    }
                });
    }

    private void doPlay() {
        Service avTransport = getAvTransport();
        if (avTransport == null) {
            return;
        }
        upnpService.getControlPoint().execute(new Play(avTransport) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Play succeeded");
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                Log.e(TAG, "Play failed: " + msg);
            }
        });
    }

    public void setVolume(int volume0to100) {
        Service renderingControl = getRenderingControl();
        if (renderingControl == null) {
            Log.w(TAG, "No RenderingControl on " + device.getDetails().getFriendlyName());
            return;
        }
        upnpService.getControlPoint().execute(
                new SetVolume(renderingControl, (long) volume0to100) {
                    @Override
                    public void success(ActionInvocation invocation) {
                        Log.d(TAG, "SetVolume " + volume0to100 + " succeeded");
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                        Log.w(TAG, "SetVolume failed: " + msg);
                    }
                });
    }

    @Nullable
    private Service getAvTransport() {
        return device.findService(new UDAServiceType("AVTransport", 1));
    }

    @Nullable
    private Service getRenderingControl() {
        return device.findService(new UDAServiceType("RenderingControl", 1));
    }

    private static String buildMinimalMetadata(String streamUrl) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"1\" parentID=\"0\" restricted=\"1\">"
                + "<upnp:class>object.item.audioItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:audio/mpeg:*\">"
                + streamUrl.replace("&", "&amp;")
                + "</res></item></DIDL-Lite>";
    }
}
