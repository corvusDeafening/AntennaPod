package de.danoeh.antennapod.playback.upnp;

import android.content.Context;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.danoeh.antennapod.event.PlayerErrorEvent;
import de.danoeh.antennapod.event.playback.SpeedChangedEvent;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.base.PlaybackServiceMediaPlayer;
import de.danoeh.antennapod.playback.base.PlayerStatus;
import de.danoeh.antennapod.playback.base.RewindAfterPauseUtils;

import org.greenrobot.eventbus.EventBus;
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
import org.jupnp.support.model.SeekMode;
import org.jupnp.support.avtransport.callback.SetAVTransportURI;
import org.jupnp.support.avtransport.callback.Stop;
import org.jupnp.support.model.PositionInfo;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("rawtypes")
public class UpnpPsmp extends PlaybackServiceMediaPlayer {

    public static final String TAG = "UpnpPsmp";

    private volatile Playable media;
    private volatile MediaType mediaType;
    private final RemoteDevice device;
    private final UpnpService upnpService;
    private final AtomicBoolean startWhenPrepared = new AtomicBoolean(false);

    private volatile int positionMs = 0;
    private volatile int durationMs = 0;

    private LocalMediaServer localMediaServer;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> positionPoller;

    @Nullable
    public static PlaybackServiceMediaPlayer getInstanceIfConnected(@NonNull Context context,
                                                                    @NonNull PSMPCallback callback) {
        UpnpDeviceManager mgr = UpnpDeviceManager.getInstance();
        RemoteDevice device = mgr.getSelectedDevice();
        UpnpService svc = mgr.getUpnpService();
        Log.d(TAG, "getInstanceIfConnected: device=" + (device != null ? device.getDetails().getFriendlyName() : "null")
                + " svc=" + (svc != null ? "ok" : "null"));
        if (device == null || svc == null) {
            return null;
        }
        Log.d(TAG, "Creating UpnpPsmp for device: " + device.getDetails().getFriendlyName());
        return new UpnpPsmp(context, callback, device, svc);
    }

    public UpnpPsmp(@NonNull Context context, @NonNull PSMPCallback callback,
                    @NonNull RemoteDevice device, @NonNull UpnpService upnpService) {
        super(context, callback);
        this.device = device;
        this.upnpService = upnpService;
    }

    @Override
    public void playMediaObject(@NonNull Playable playable, boolean stream,
                                boolean startWhenPrepared, boolean prepareImmediately) {
        if (media != null && media.getIdentifier().equals(playable.getIdentifier())
                && playerStatus == PlayerStatus.PLAYING) {
            return;
        }

        if (media != null && !media.getIdentifier().equals(playable.getIdentifier())) {
            callback.onPostPlayback(media, false, false, true);
        }

        stopPositionPoller();
        stopLocalServer();

        this.media = playable;
        this.mediaType = playable.getMediaType();
        this.startWhenPrepared.set(startWhenPrepared);
        this.durationMs = playable.getDuration();

        setPlayerStatus(PlayerStatus.INITIALIZING, media);
        callback.ensureMediaInfoLoaded(media);
        callback.onMediaChanged(true);
        setPlayerStatus(PlayerStatus.INITIALIZED, media);

        if (prepareImmediately) {
            prepare();
        }
    }

    @Override
    public void prepare() {
        if (playerStatus != PlayerStatus.INITIALIZED) {
            return;
        }
        setPlayerStatus(PlayerStatus.PREPARING, media);

        String streamUrl = resolveStreamUrl();
        if (streamUrl == null) {
            Log.e(TAG, "Could not resolve stream URL");
            EventBus.getDefault().postSticky(new PlayerErrorEvent("Could not resolve stream URL"));
            setPlayerStatus(PlayerStatus.ERROR, media);
            return;
        }

        Log.d(TAG, "prepare: url=" + streamUrl + " device=" + device.getDetails().getFriendlyName());
        String mimeType = localMediaServer != null ? localMediaServer.getMimeType() : null;
        String metadata = UpnpMetadataCreator.createMetadata(media, streamUrl, mimeType, durationMs);

        Service avTransport = getAvTransportService();
        if (avTransport == null) {
            Log.e(TAG, "Device has no AVTransport service");
            EventBus.getDefault().postSticky(new PlayerErrorEvent("UPnP device has no AVTransport"));
            setPlayerStatus(PlayerStatus.ERROR, media);
            return;
        }

        final String finalUrl = streamUrl;
        upnpService.getControlPoint().execute(new SetAVTransportURI(avTransport, finalUrl, metadata) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "SetAVTransportURI succeeded");
                int position = media.getPosition();
                if (position > 0) {
                    position = RewindAfterPauseUtils.calculatePositionWithRewind(
                            position, media.getLastPlayedTimeStatistics());
                }
                if (position > 0) {
                    seekToInternal(position, () -> {
                        if (startWhenPrepared.get()) {
                            doPlay();
                        } else {
                            setPlayerStatus(PlayerStatus.PREPARED, media);
                        }
                    });
                } else {
                    if (startWhenPrepared.get()) {
                        doPlay();
                    } else {
                        setPlayerStatus(PlayerStatus.PREPARED, media);
                    }
                }
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String defaultMsg) {
                Log.e(TAG, "SetAVTransportURI failed: " + defaultMsg);
                EventBus.getDefault().postSticky(new PlayerErrorEvent("UPnP: " + defaultMsg));
                setPlayerStatus(PlayerStatus.ERROR, media);
            }
        });
    }

    private void doPlay() {
        Service avTransport = getAvTransportService();
        if (avTransport == null) {
            return;
        }
        upnpService.getControlPoint().execute(new Play(avTransport) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Play succeeded");
                setPlayerStatus(PlayerStatus.PLAYING, media);
                startPositionPoller();
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String defaultMsg) {
                Log.e(TAG, "Play failed: " + defaultMsg);
                EventBus.getDefault().postSticky(new PlayerErrorEvent("UPnP play: " + defaultMsg));
                setPlayerStatus(PlayerStatus.ERROR, media);
            }
        });
    }

    @Override
    public void resume() {
        int newPosition = RewindAfterPauseUtils.calculatePositionWithRewind(
                media.getPosition(), media.getLastPlayedTimeStatistics());
        seekTo(newPosition);
        doPlay();
    }

    @Override
    public void pause(boolean abandonFocus, boolean reinit) {
        stopPositionPoller();
        Service avTransport = getAvTransportService();
        if (avTransport == null) {
            return;
        }
        upnpService.getControlPoint().execute(new Pause(avTransport) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Pause succeeded");
                if (media != null) {
                    media.setPosition(positionMs);
                }
                setPlayerStatus(PlayerStatus.PAUSED, media);
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String defaultMsg) {
                Log.e(TAG, "Pause failed: " + defaultMsg);
            }
        });
    }

    @Override
    public void reinit() {
        if (media != null) {
            playMediaObject(media, true, startWhenPrepared.get(), false);
        }
    }

    @Override
    public void seekTo(int t) {
        seekToInternal(t, null);
    }

    private void seekToInternal(int ms, @Nullable Runnable onSuccess) {
        Service avTransport = getAvTransportService();
        if (avTransport == null) {
            return;
        }
        String time = UpnpMetadataCreator.formatDuration(ms);
        upnpService.getControlPoint().execute(
                new Seek(avTransport, SeekMode.REL_TIME, time) {
                    @Override
                    public void success(ActionInvocation invocation) {
                        positionMs = ms;
                        if (media != null) {
                            media.setPosition(ms);
                        }
                        if (onSuccess != null) {
                            onSuccess.run();
                        }
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse response, String defaultMsg) {
                        Log.e(TAG, "Seek failed: " + defaultMsg);
                    }
                });
    }

    @Override
    public void seekDelta(int d) {
        seekTo(Math.max(0, positionMs + d));
    }

    @Override
    public int getDuration() {
        return durationMs > 0 ? durationMs : (media != null ? media.getDuration() : Playable.INVALID_TIME);
    }

    @Override
    public int getPosition() {
        return positionMs;
    }

    @Override
    public boolean isStartWhenPrepared() {
        return startWhenPrepared.get();
    }

    @Override
    public void setStartWhenPrepared(boolean startWhenPrepared) {
        this.startWhenPrepared.set(startWhenPrepared);
    }

    @Override
    public void setPlaybackParams(float speed, boolean skipSilence) {
        EventBus.getDefault().post(new SpeedChangedEvent(1.0f));
    }

    @Override
    public float getPlaybackSpeed() {
        return 1.0f;
    }

    @Override
    public boolean getSkipSilence() {
        return false;
    }

    @Override
    public void setVolume(float volumeLeft, float volumeRight) {
        // Volume control via RenderingControl is a future enhancement
    }

    @Override
    public MediaType getCurrentMediaType() {
        return mediaType;
    }

    @Override
    public boolean isStreaming() {
        return true;
    }

    @Override
    public void shutdown() {
        stopPositionPoller();
        executor.shutdownNow();
        stopLocalServer();
        Service avTransport = getAvTransportService();
        if (avTransport != null) {
            upnpService.getControlPoint().execute(new Stop(avTransport) {
                @Override
                public void success(ActionInvocation invocation) {
                    Log.d(TAG, "Stop on shutdown succeeded");
                }

                @Override
                public void failure(ActionInvocation invocation, UpnpResponse response, String defaultMsg) {
                    Log.w(TAG, "Stop on shutdown failed: " + defaultMsg);
                }
            });
        }
    }

    @Override
    public void setVideoSurface(SurfaceHolder surface) {
        throw new UnsupportedOperationException("Video not supported for UPnP audio");
    }

    @Override
    public void resetVideoSurface() {
        // no-op
    }

    @Override
    public Pair<Integer, Integer> getVideoSize() {
        return null;
    }

    @Override
    public Playable getPlayable() {
        return media;
    }

    @Override
    protected void setPlayable(Playable playable) {
        media = playable;
    }

    @Override
    public List<String> getAudioTracks() {
        return Collections.emptyList();
    }

    @Override
    public void setAudioTrack(int track) {
    }

    @Override
    public int getSelectedAudioTrack() {
        return -1;
    }

    @Override
    protected void endPlayback(boolean hasEnded, boolean wasSkipped,
                               boolean shouldContinue, boolean toStoppedState) {
        stopPositionPoller();
        boolean isPlaying = playerStatus == PlayerStatus.PLAYING;
        if (playerStatus != PlayerStatus.INDETERMINATE) {
            setPlayerStatus(PlayerStatus.INDETERMINATE, media);
        }
        if (media != null && wasSkipped) {
            media.setPosition(positionMs);
        }
        final Playable currentMedia = media;
        Playable nextMedia = null;
        if (shouldContinue) {
            nextMedia = callback.getNextInQueue(currentMedia);
            boolean playNext = isPlaying && nextMedia != null;
            if (nextMedia != null) {
                callback.onPlaybackEnded(nextMedia.getMediaType(), !playNext);
                media = null;
                playMediaObject(nextMedia, true, playNext, playNext);
            }
        }
        if (shouldContinue || toStoppedState) {
            if (nextMedia == null) {
                Service avTransport = getAvTransportService();
                if (avTransport != null) {
                    upnpService.getControlPoint().execute(new Stop(avTransport) {
                        @Override
                        public void success(ActionInvocation invocation) {
                        }

                        @Override
                        public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                        }
                    });
                }
                callback.onPostPlayback(currentMedia, hasEnded, wasSkipped, false);
            } else {
                callback.onPostPlayback(currentMedia, hasEnded, wasSkipped, true);
            }
        } else if (isPlaying) {
            callback.onPlaybackPause(currentMedia, positionMs);
        }
    }

    @Override
    protected boolean shouldLockWifi() {
        return false;
    }

    @Override
    public boolean isCasting() {
        return true;
    }

    @Nullable
    private Service getAvTransportService() {
        return device.findService(new UDAServiceType("AVTransport", 1));
    }

    @Nullable
    private String resolveStreamUrl() {
        if (media == null) {
            return null;
        }
        String streamUrl = media.getStreamUrl();
        if (streamUrl != null && !streamUrl.isEmpty()
                && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))) {
            return streamUrl;
        }
        // Local file — serve via NanoHTTPD
        String localPath = media.getLocalFileUrl();
        if (localPath == null || localPath.isEmpty()) {
            return null;
        }
        try {
            stopLocalServer();
            localMediaServer = new LocalMediaServer(localPath);
            return localMediaServer.getStreamUrl(context);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start local media server", e);
            return null;
        }
    }

    private void stopLocalServer() {
        if (localMediaServer != null) {
            localMediaServer.stop();
            localMediaServer = null;
        }
    }

    private void startPositionPoller() {
        stopPositionPoller();
        positionPoller = executor.scheduleWithFixedDelay(this::pollPosition, 1, 1, TimeUnit.SECONDS);
    }

    private void stopPositionPoller() {
        if (positionPoller != null && !positionPoller.isCancelled()) {
            positionPoller.cancel(false);
            positionPoller = null;
        }
    }

    private void pollPosition() {
        Service avTransport = getAvTransportService();
        if (avTransport == null) {
            return;
        }
        upnpService.getControlPoint().execute(new GetPositionInfo(avTransport) {
            @Override
            public void received(ActionInvocation invocation, PositionInfo positionInfo) {
                int newPos = UpnpMetadataCreator.parseTimeToMs(positionInfo.getRelTime());
                int dur = UpnpMetadataCreator.parseTimeToMs(positionInfo.getTrackDuration());
                if (newPos > 0) {
                    positionMs = newPos;
                    if (media != null) {
                        media.setPosition(newPos);
                    }
                }
                if (dur > 0 && dur != durationMs) {
                    durationMs = dur;
                    if (media != null) {
                        media.setDuration(dur);
                    }
                }
                if (dur > 0 && newPos > 0 && newPos >= dur - 2000
                        && playerStatus == PlayerStatus.PLAYING) {
                    endPlayback(true, false, true, true);
                }
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String defaultMsg) {
                Log.w(TAG, "GetPositionInfo failed: " + defaultMsg);
            }
        });
    }
}
