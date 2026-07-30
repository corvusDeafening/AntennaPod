package de.danoeh.antennapod.playback.upnp;

import android.util.Log;

import org.jupnp.model.message.StreamRequestMessage;
import org.jupnp.model.message.StreamResponseMessage;
import org.jupnp.model.message.UpnpHeaders;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.transport.spi.StreamClient;
import org.jupnp.transport.spi.StreamClientConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class HttpUrlConnectionStreamClient implements StreamClient<StreamClientConfiguration> {

    private static final String TAG = "UpnpHttpClient";
    private static final int TIMEOUT_MS = 10000;

    private final StreamClientConfiguration config = new StreamClientConfiguration() {
        private final ExecutorService executor = Executors.newCachedThreadPool();

        @Override
        public ExecutorService getRequestExecutorService() {
            return executor;
        }

        @Override
        public int getTimeoutSeconds() {
            return 10;
        }

        @Override
        public int getRetryIterations() {
            return 0;
        }

        @Override
        public int getLogWarningSeconds() {
            return 5;
        }

        @Override
        public int getRetryAfterSeconds() {
            return 0;
        }

        @Override
        public String getUserAgentValue(int major, int minor) {
            return "AntennaPod/UPnP " + major + "." + minor;
        }
    };

    @Override
    public StreamResponseMessage sendRequest(StreamRequestMessage req) throws InterruptedException {
        Log.d(TAG, "→ " + req.getOperation().getHttpMethodName() + " " + req.getUri());
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) req.getUri().toURL().openConnection();
            conn.setRequestMethod(req.getOperation().getHttpMethodName());
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            for (Map.Entry<String, List<String>> entry : req.getHeaders().entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    conn.addRequestProperty(entry.getKey(), value);
                }
            }

            if (req.hasBody()) {
                conn.setDoOutput(true);
                byte[] body = req.isBodyNonEmptyString()
                        ? req.getBodyString().getBytes(StandardCharsets.UTF_8)
                        : req.getBodyBytes();
                if (body != null) {
                    conn.getOutputStream().write(body);
                }
            }

            int statusCode = conn.getResponseCode();
            String statusMessage = conn.getResponseMessage();
            Log.d(TAG, "← " + statusCode + " " + statusMessage + " (" + req.getUri() + ")");

            Map<String, List<String>> rawHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                if (entry.getKey() != null) {
                    rawHeaders.put(entry.getKey(), entry.getValue());
                }
            }

            InputStream is = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] responseBody = null;
            if (is != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                is.close();
                responseBody = baos.toByteArray();
            }

            StreamResponseMessage response =
                    new StreamResponseMessage(new UpnpResponse(statusCode, statusMessage));
            response.setHeaders(new UpnpHeaders(rawHeaders));
            if (responseBody != null && responseBody.length > 0) {
                response.setBodyCharacters(responseBody);
            }
            return response;
        } catch (IOException e) {
            Log.w(TAG, "HTTP request failed for " + req.getUri() + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public void stop() {
        // Per-request connections need no global teardown.
    }

    @Override
    public StreamClientConfiguration getConfiguration() {
        return config;
    }

}
