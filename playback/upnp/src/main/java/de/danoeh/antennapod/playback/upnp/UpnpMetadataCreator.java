package de.danoeh.antennapod.playback.upnp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.danoeh.antennapod.model.playback.Playable;

import java.util.Locale;

public class UpnpMetadataCreator {

    private UpnpMetadataCreator() {
    }

    @NonNull
    public static String createMetadata(@NonNull Playable playable, @NonNull String streamUrl,
                                        @Nullable String mimeType, int durationMs) {
        String title = escapeXml(playable.getEpisodeTitle());
        String artist = escapeXml(playable.getFeedTitle());
        String artworkUrl = playable.getImageLocation();
        final String duration = durationMs > 0 ? formatDuration(durationMs) : "0:00:00";
        final String mime = mimeType != null ? mimeType : "audio/mpeg";

        StringBuilder sb = new StringBuilder();
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
          .append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
          .append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
          .append("<item id=\"1\" parentID=\"0\" restricted=\"1\">")
          .append("<dc:title>").append(title).append("</dc:title>")
          .append("<upnp:artist>").append(artist).append("</upnp:artist>");

        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            sb.append("<upnp:albumArtURI>").append(escapeXml(artworkUrl)).append("</upnp:albumArtURI>");
        }

        sb.append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>");
        sb.append("<res protocolInfo=\"http-get:*:" + mime + ":*\" duration=\"" + duration + "\">");
        sb.append(escapeXml(streamUrl));
        sb.append("</res></item></DIDL-Lite>");

        return sb.toString();
    }

    @NonNull
    static String formatDuration(int ms) {
        int totalSeconds = ms / 1000;
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
    }

    static int parseTimeToMs(@Nullable String time) {
        if (time == null || time.isEmpty() || "NOT_IMPLEMENTED".equals(time)) {
            return 0;
        }
        try {
            String[] parts = time.split(":");
            if (parts.length != 3) {
                return 0;
            }
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            double s = Double.parseDouble(parts[2].trim());
            return (int) ((h * 3600 + m * 60 + s) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @NonNull
    private static String escapeXml(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
