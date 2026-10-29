package com.charlichaap.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The on-phone HTTP order desk.
 *
 * Serves the Charlie Chaap web app and the same REST API as server.js, so a
 * phone running this app can host the whole service for the restaurant's
 * Wi-Fi / hotspot. Plain Java only: no Android classes, so it can be run and
 * tested on a normal JVM.
 */
public class CharlieServer {

    public interface Logger {
        void log(String message);
    }

    private static final long SESSION_TTL_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_BODY_BYTES = 35 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Store store;
    private final File indexFile;
    private final int port;
    private final Logger logger;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, long[]> rateLimits = new ConcurrentHashMap<>();

    public CharlieServer(Store store, File indexFile, int port, Logger logger) {
        this.store = store;
        this.indexFile = indexFile;
        this.port = port;
        this.logger = logger;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running.get();
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port), 64);
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "charlichaap-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("Order desk listening on port " + port);
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                Thread worker = new Thread(() -> handleClient(client), "charlichaap-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException error) {
                if (running.get()) log("Accept failed: " + error.getMessage());
            }
        }
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }

    // ------------------------------------------------------------------ HTTP

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(20000);
            InputStream in = client.getInputStream();
            OutputStream rawOut = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.ENGLISH);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ENGLISH),
                        line.substring(colon + 1).trim());
            }

            byte[] body = new byte[0];
            String lengthHeader = headers.get("content-length");
            if (lengthHeader != null) {
                int length;
                try {
                    length = Integer.parseInt(lengthHeader.trim());
                } catch (NumberFormatException e) {
                    length = 0;
                }
                if (length > MAX_BODY_BYTES) {
                    writeJson(rawOut, 413, error("Request body is too large."), null);
                    client.close();
                    return;
                }
                if (length > 0) {
                    body = new byte[length];
                    int read = 0;
                    while (read < length) {
                        int chunk = in.read(body, read, length - read);
                        if (chunk < 0) break;
                        read += chunk;
                    }
                }
            }

            Response response;
            try {
                response = route(method, target, headers, body, client.getInetAddress().getHostAddress());
            } catch (ApiException error) {
                int status = error.getStatus() >= 100 ? error.getStatus() : 400;
                if (status >= 500) log("Request failed: " + error.getMessage());
                response = Response.json(status, error(error.getMessage() == null ? "Request failed." : error.getMessage()));
            } catch (Exception error) {
                log("Unhandled error: " + error);
                response = Response.json(500, error("Request failed."));
            }

            if (response.isFile()) {
                writeFile(rawOut, response);
            } else if (response.isText()) {
                writeText(rawOut, response);
            } else {
                writeJson(rawOut, response.status, response.json, response.headers);
            }
            rawOut.flush();
            client.close();
        } catch (Exception error) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buffer.write(c);
            if (buffer.size() > 16384) break;
        }
        if (c == -1 && buffer.size() == 0) return null;
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ routing

    private Response route(String method, String target, Map<String, String> headers,
                           byte[] body, String clientIp) throws Exception {
        String rawPath = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            rawPath = target.substring(0, q);
            query = target.substring(q + 1);
        }
        String pathname;
        try {
            pathname = URLDecoder.decode(rawPath, "UTF-8");
        } catch (Exception e) {
            pathname = rawPath;
        }

        if ("GET".equals(method) && pathname.equals("/api/health")) {
            JSONObject payload = new JSONObject();
            payload.put("service", "Charlie Chaap Service API");
            payload.put("version", 1);
            payload.put("status", "ok");
            return Response.json(200, payload);
        }

        if ("GET".equals(method) && pathname.equals("/api/public-config")) {
            JSONObject payload = new JSONObject();
            payload.put("config", store.config());
            payload.put("updatedAt", store.updatedAt());
            return Response.json(200, payload);
        }

        if ("GET".equals(method) && pathname.equals("/api/auth/session")) {
            JSONObject payload = new JSONObject();
            payload.put("authenticated", currentSession(headers) != null);
            return Response.json(200, payload);
        }

        if ("POST".equals(method) && pathname.equals("/api/auth/login")) {
            if (!rateLimit(clientIp, "login", 8, 15 * 60 * 1000L)) {
                throw new ApiException(429, "Too many sign-in attempts. Try again in 15 minutes.");
            }
            JSONObject payload = parseBody(body, 16 * 1024);
            String supplied = payload.optString("password", "");
            if (!equalSecret(supplied, store.getAdminPassword())) {
                throw new ApiException(401, "Incorrect staff password.");
            }
            String token = newToken();
            sessions.put(token, System.currentTimeMillis() + SESSION_TTL_MS);
            Map<String, String> extra = new HashMap<>();
            extra.put("Set-Cookie", "cc_staff_session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                    + (SESSION_TTL_MS / 1000));
            JSONObject result = new JSONObject();
            result.put("authenticated", true);
            result.put("expiresIn", SESSION_TTL_MS);
            return Response.json(200, result, extra);
        }

        if ("POST".equals(method) && pathname.equals("/api/auth/logout")) {
            String token = sessionToken(headers);
            if (token != null) sessions.remove(token);
            Map<String, String> extra = new HashMap<>();
            extra.put("Set-Cookie", "cc_staff_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            JSONObject result = new JSONObject();
            result.put("authenticated", false);
            return Response.json(200, result, extra);
        }

        if ("GET".equals(method) && pathname.equals("/api/menu")) {
            if ("1".equals(param(query, "all"))) {
                requireStaff(headers);
            }
            JSONObject payload = new JSONObject();
            payload.put("items", store.menu());
            payload.put("updatedAt", store.updatedAt());
            return Response.json(200, payload);
        }

        if ("PUT".equals(method) && pathname.equals("/api/menu")) {
            requireStaff(headers);
            JSONObject payload = parseBody(body, MAX_BODY_BYTES);
            store.replaceMenu(payload.optJSONArray("items"));
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("count", store.menu().length());
            result.put("updatedAt", store.updatedAt());
            return Response.json(200, result);
        }

        if ("PUT".equals(method) && pathname.equals("/api/service-config")) {
            requireStaff(headers);
            JSONObject payload = parseBody(body, MAX_BODY_BYTES);
            JSONObject config = store.replaceConfig(payload);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("config", config);
            result.put("updatedAt", store.updatedAt());
            return Response.json(200, result);
        }

        if ("POST".equals(method) && pathname.equals("/api/orders")) {
            if (!rateLimit(clientIp, "customer-order", 25, 60 * 1000L)) {
                throw new ApiException(429, "Too many orders from this connection. Wait a moment and retry.");
            }
            JSONObject payload = parseBody(body, 256 * 1024);
            JSONObject order = store.makeOrder(payload, "customer");
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("order", order);
            return Response.json(201, result);
        }

        if ("POST".equals(method) && pathname.equals("/api/orders/manual")) {
            requireStaff(headers);
            JSONObject payload = parseBody(body, 256 * 1024);
            JSONObject order = store.makeOrder(payload, "staff");
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("order", order);
            return Response.json(201, result);
        }

        if ("GET".equals(method) && pathname.matches("^/api/orders/[^/]+/status$")) {
            if (!rateLimit(clientIp, "order-status", 90, 15 * 60 * 1000L)) {
                throw new ApiException(429, "Too many tracking checks. Try again in a few minutes.");
            }
            String id = pathname.split("/")[3];
            JSONObject order = store.findOrder(id);
            if (order == null) throw new ApiException(404, "Order not found.");
            JSONObject summary = new JSONObject();
            summary.put("id", order.opt("id"));
            summary.put("status", order.optString("status", "pending"));
            summary.put("total", order.optDouble("total", 0));
            JSONArray items = order.optJSONArray("items");
            int count = 0;
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject line = items.optJSONObject(i);
                    if (line != null) count += line.optInt("qty", 0);
                }
            }
            summary.put("itemCount", count);
            summary.put("time", order.optString("time", ""));
            JSONObject result = new JSONObject();
            result.put("order", summary);
            return Response.json(200, result);
        }

        if ("GET".equals(method) && pathname.equals("/api/orders")) {
            requireStaff(headers);
            JSONObject result = new JSONObject();
            result.put("orders", store.orders());
            result.put("updatedAt", store.updatedAt());
            return Response.json(200, result);
        }

        if ("PATCH".equals(method) && pathname.matches("^/api/orders/[^/]+$")) {
            requireStaff(headers);
            String id = pathname.split("/")[3];
            JSONObject payload = parseBody(body, 256 * 1024);
            JSONObject order = store.updateOrder(id, payload);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("order", order);
            return Response.json(200, result);
        }

        if ("DELETE".equals(method) && pathname.matches("^/api/orders/[^/]+$")) {
            requireStaff(headers);
            String id = pathname.split("/")[3];
            if (!store.deleteOrder(id)) throw new ApiException(404, "Order not found.");
            JSONObject result = new JSONObject();
            result.put("ok", true);
            return Response.json(200, result);
        }

        if ("GET".equals(method) && pathname.equals("/api/backup")) {
            requireStaff(headers);
            return Response.json(200, store.backup());
        }

        if ("POST".equals(method) && pathname.equals("/api/backup/restore")) {
            requireStaff(headers);
            JSONObject payload = parseBody(body, MAX_BODY_BYTES);
            JSONObject data = payload.optJSONObject("data");
            if (data == null) throw new ApiException(400, "Backup has an invalid structure.");
            return Response.json(200, store.restore(data));
        }

        if ("GET".equals(method) && pathname.equals("/")) {
            Map<String, String> extra = new HashMap<>();
            extra.put("Location", "/customer");
            extra.put("Cache-Control", "no-store");
            return Response.redirect(302, extra);
        }

        if ("GET".equals(method) && (pathname.equals("/customer") || pathname.equals("/customer/")
                || pathname.equals("/staff") || pathname.equals("/staff/")
                || pathname.equals("/admin") || pathname.equals("/admin/")
                || pathname.equals("/index.html") || pathname.equals("/Charlie_Chaap_Service_Ready.html"))) {
            return Response.file(indexFile);
        }

        throw new ApiException(404, "Route not found.");
    }

    private static String param(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ auth

    private String sessionToken(Map<String, String> headers) {
        String cookie = headers.get("cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            if (part.substring(0, eq).trim().equals("cc_staff_session")) {
                try {
                    return URLDecoder.decode(part.substring(eq + 1).trim(), "UTF-8");
                } catch (Exception e) {
                    return part.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    private String currentSession(Map<String, String> headers) {
        String token = sessionToken(headers);
        if (token == null) return null;
        Long expiresAt = sessions.get(token);
        if (expiresAt == null) return null;
        if (expiresAt < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        sessions.put(token, System.currentTimeMillis() + SESSION_TTL_MS);
        return token;
    }

    private void requireStaff(Map<String, String> headers) throws ApiException {
        if (currentSession(headers) == null) {
            throw new ApiException(401, "Staff sign-in is required for this action.");
        }
    }

    private static boolean equalSecret(String a, String b) {
        byte[] left = (a == null ? "" : a).getBytes(StandardCharsets.UTF_8);
        byte[] right = (b == null ? "" : b).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right) && left.length == right.length;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) builder.append(String.format(Locale.ENGLISH, "%02x", value));
        return builder.toString();
    }

    private boolean rateLimit(String clientIp, String bucket, int max, long windowMs) {
        String key = bucket + ":" + (clientIp == null ? "unknown" : clientIp);
        long now = System.currentTimeMillis();
        long[] entry = rateLimits.get(key);
        if (entry == null || now - entry[0] >= windowMs) {
            rateLimits.put(key, new long[]{now, 1});
            return true;
        }
        entry[1] += 1;
        return entry[1] <= max;
    }

    // ------------------------------------------------------------------ body

    private JSONObject parseBody(byte[] body, int limit) throws ApiException {
        if (body == null) body = new byte[0];
        if (body.length > limit) throw new ApiException(413, "Request body is too large.");
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new ApiException(400, "Request body must be valid JSON.");
        }
    }

    private static JSONObject error(String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("error", message);
        } catch (JSONException ignored) {
        }
        return payload;
    }

    // ------------------------------------------------------------------ output

    private static final class Response {
        int status;
        JSONObject json;
        String text;
        File file;
        Map<String, String> headers;

        static Response json(int status, JSONObject json) {
            return json(status, json, null);
        }

        static Response json(int status, JSONObject json, Map<String, String> headers) {
            Response response = new Response();
            response.status = status;
            response.json = json;
            response.headers = headers == null ? new HashMap<>() : headers;
            return response;
        }

        static Response text(int status, String text) {
            Response response = new Response();
            response.status = status;
            response.text = text;
            response.headers = new HashMap<>();
            return response;
        }

        static Response file(File file) {
            Response response = new Response();
            response.status = 200;
            response.file = file;
            response.headers = new HashMap<>();
            return response;
        }

        static Response redirect(int status, Map<String, String> headers) {
            Response response = new Response();
            response.status = status;
            response.headers = headers;
            return response;
        }

        boolean isFile() {
            return file != null;
        }

        boolean isText() {
            return text != null;
        }
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private static void writeHeaders(OutputStream out, int status, Map<String, String> headers,
                                     String contentType, long contentLength) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
        builder.append("X-Frame-Options: SAMEORIGIN\r\n");
        builder.append("Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n");
        builder.append("Referrer-Policy: same-origin\r\n");
        builder.append("X-Content-Type-Options: nosniff\r\n");
        builder.append("Connection: close\r\n");
        if (contentType != null) builder.append("Content-Type: ").append(contentType).append("\r\n");
        if (contentLength >= 0) builder.append("Content-Length: ").append(contentLength).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        builder.append("\r\n");
        out.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeJson(OutputStream out, int status, JSONObject payload,
                                  Map<String, String> headers) throws IOException {
        byte[] body = payload == null ? new byte[]{'n', 'u', 'l', 'l'}
                : payload.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, String> merged = new HashMap<>();
        if (headers != null) merged.putAll(headers);
        merged.put("Cache-Control", "no-store");
        BufferedOutputStream buffered = new BufferedOutputStream(out);
        writeHeaders(buffered, status, merged, "application/json; charset=utf-8", body.length);
        buffered.write(body);
        buffered.flush();
    }

    private static void writeText(OutputStream out, Response response) throws IOException {
        byte[] body = response.text == null ? new byte[0] : response.text.getBytes(StandardCharsets.UTF_8);
        BufferedOutputStream buffered = new BufferedOutputStream(out);
        Map<String, String> merged = new HashMap<>(response.headers);
        merged.put("Cache-Control", "no-store");
        writeHeaders(buffered, response.status, merged, "text/plain; charset=utf-8", body.length);
        buffered.write(body);
        buffered.flush();
    }

    private static void writeFile(OutputStream out, Response response) throws IOException {
        if (response.file == null || !response.file.exists()) {
            writeJson(out, 404, error("Route not found."), null);
            return;
        }
        long length = response.file.length();
        BufferedOutputStream buffered = new BufferedOutputStream(out);
        Map<String, String> merged = new HashMap<>(response.headers);
        merged.put("Cache-Control", "no-cache");
        writeHeaders(buffered, 200, merged, "text/html; charset=utf-8", length);
        FileInputStream in = new FileInputStream(response.file);
        try {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) > 0) buffered.write(buffer, 0, read);
        } finally {
            in.close();
        }
        buffered.flush();
    }

    public static String nowStamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }
}
