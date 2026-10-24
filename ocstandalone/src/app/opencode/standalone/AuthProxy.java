package app.opencode.standalone;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Loopback HTTP proxy in front of the bundled opencode v2 server.
 *
 * Why this exists: opencode v2 serves its web UI without auth, but every /api/*
 * request requires HTTP Basic auth (user "opencode", password = the server
 * password). Chromium in a WebView does not answer that challenge for the UI's
 * fetch/XHR/EventSource requests - neither onReceivedHttpAuthRequest nor
 * setHttpAuthUsernamePassword applies to them - so the v2 UI cannot load data.
 *
 * The proxy listens on a second loopback port (4097), forwards each request to
 * the real server (4096) and injects the Authorization header. It sends
 * "Connection: close" upstream, so every browser connection carries exactly one
 * authenticated request; responses (including the SSE stream on /api/event) are
 * streamed back until the server closes. WebSocket upgrades tunnel unchanged
 * after their handshake headers.
 */
final class AuthProxy {

    private static final String TAG = "ocstandalone";
    static final int PORT = 4097;
    private static final String UPSTREAM_HOST = "127.0.0.1";
    private static final int UPSTREAM_PORT = ServerService.PORT;
    private static final int HEAD_LIMIT = 32 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final String authorization;
    private ServerSocket server;
    private volatile boolean running;

    AuthProxy(String password) {
        String cred = ServerService.AUTH_USER + ":" + (password == null ? "" : password);
        this.authorization = "Basic " + android.util.Base64.encodeToString(
                cred.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "oc-authproxy");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        server = null;
    }

    private void acceptLoop() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            server = s;
            Log.i(TAG, "auth proxy listening on 127.0.0.1:" + PORT);
            while (running) {
                final Socket client;
                try {
                    client = s.accept();
                } catch (Throwable t) {
                    break;
                }
                Thread t2 = new Thread(new Runnable() {
                    public void run() {
                        handle(client);
                    }
                }, "oc-proxy-conn");
                t2.setDaemon(true);
                t2.start();
            }
        } catch (Throwable t) {
            Log.e(TAG, "auth proxy could not listen on " + PORT, t);
        }
    }

    private void handle(Socket client) {
        Socket up = null;
        try {
            client.setSoTimeout(CONNECT_TIMEOUT_MS);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            byte[] head = readHead(cin);
            if (head == null) {
                client.close();
                return;
            }

            up = new Socket();
            up.connect(new InetSocketAddress(InetAddress.getByName(UPSTREAM_HOST), UPSTREAM_PORT),
                    CONNECT_TIMEOUT_MS);
            up.setSoTimeout(0); // responses may idle (SSE)
            OutputStream uout = up.getOutputStream();
            InputStream uin = up.getInputStream();

            uout.write(rewriteHead(new String(head, StandardCharsets.ISO_8859_1))
                    .getBytes(StandardCharsets.ISO_8859_1));
            uout.flush();
            client.setSoTimeout(0);

            final Socket upRef = up;
            Thread toUp = pump(cin, uout, new Runnable() {
                public void run() {
                    try {
                        upRef.close();
                    } catch (Throwable ignored) {
                    }
                }
            });
            Thread toClient = pump(uin, cout, null);
            toUp.start();
            toClient.start();
            toClient.join(); // ends when the upstream closes (server sets Connection: close)
        } catch (Throwable t) {
            Log.d(TAG, "proxy: " + t);
            try {
                client.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            } catch (Throwable ignored) {
            }
        } finally {
            try {
                if (up != null) up.close();
            } catch (Throwable ignored) {
            }
            try {
                client.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Rewrites the request head: drops the client's Connection/Keep-Alive and any
     * existing Authorization, adds our Basic credentials and Connection: close.
     * The request line, path and all other headers pass through unchanged, so the
     * origin (and Host/Origin) stay exactly what the browser expects.
     */
    private String rewriteHead(String head) {
        String[] lines = head.split("\r\n");
        StringBuilder sb = new StringBuilder(head.length() + 96);
        sb.append(lines.length > 0 ? lines[0] : "GET / HTTP/1.1").append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) continue;
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("authorization:")
                    || lower.startsWith("connection:")
                    || lower.startsWith("proxy-connection:")
                    || lower.startsWith("keep-alive:")) {
                continue;
            }
            sb.append(line).append("\r\n");
        }
        sb.append("Authorization: ").append(authorization).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }

    /** Reads bytes up to and including the blank line that ends the request head. */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        int a = -1, b = -1, c = -1, d = -1;
        while (buf.size() < HEAD_LIMIT) {
            int r = in.read();
            if (r < 0) return null;
            buf.write(r);
            a = b;
            b = c;
            c = d;
            d = r;
            if (a == '\r' && b == '\n' && c == '\r' && d == '\n') {
                return buf.toByteArray();
            }
        }
        return null;
    }

    private Thread pump(final InputStream in, final OutputStream out, final Runnable onEnd) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        out.flush();
                    } catch (Throwable ignored) {
                    }
                    if (onEnd != null) onEnd.run();
                }
            }
        }, "oc-proxy-pump");
        t.setDaemon(true);
        return t;
    }
}
