package com.litevision.tv;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client for Hikvision NVR ISAPI with proper Digest authentication.
 * Makes exactly 2 requests for Digest auth: one to get challenge, one with auth.
 * Caches nonce for subsequent requests to avoid repeated 401s.
 */
public class NvrClient {
    private static final String TAG = "NvrClient";
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 10000;

    private final String host;
    private final int httpPort;
    private final String username;
    private final String password;
    /** ISAPI 基础地址，形如 "http://host:port"，构造时算好，避免每次请求重复拼接。 */
    private final String baseUrl;

    // Cached digest auth state
    private String cachedRealm;
    private String cachedNonce;
    private String cachedQop;
    private String cachedAlgorithm;
    private String cachedOpaque;
    private int nonceCount = 0;

    /**
     * @param host     NVR 主机（IP 或域名；域名建议先由调用方解析为 IPv4）
     * @param httpPort ISAPI 的 HTTP 端口。此前版本固定写死 80，导致用户在设置页
     *                 填写的端口对 ISAPI 完全无效（外网/域名映射端口通常不是 80）。
     * @param username ISAPI 用户名
     * @param password ISAPI 密码
     */
    public NvrClient(String host, int httpPort, String username, String password) {
        this.host = host;
        this.httpPort = httpPort;
        this.username = username;
        this.password = password;
        this.baseUrl = "http://" + formatAuthority(host, httpPort);
        Log.d(TAG, "ISAPI 客户端已创建: " + baseUrl);
    }

    /**
     * 拼接 URL 的 authority 段（host:port），正确处理以下情况：
     *   - "192.168.1.23"        + 80    → "192.168.1.23:80"
     *   - "nvr.example.com"     + 8080  → "nvr.example.com:8080"
     *   - "nvr.example.com:8080"+ 80    → "nvr.example.com:8080"（主机已带端口，不重复追加）
     *   - "2001:db8::1"         + 80    → "[2001:db8::1]:80"（IPv6 自动补方括号）
     *   - "[2001:db8::1]"       + 80    → "[2001:db8::1]:80"
     *   - "[2001:db8::1]:8080"  + 80    → "[2001:db8::1]:8080"
     */
    public static String formatAuthority(String host, int port) {
        String value = host == null ? "" : host.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.charAt(0) == '[') {
            int close = value.indexOf(']');
            if (close >= 0) {
                String address = value.substring(0, close + 1);
                String rest = value.substring(close + 1);
                if (rest.startsWith(":")) {
                    return value; // [ipv6]:port 已完整
                }
                return address + ":" + port; // [ipv6] 补端口
            }
        }
        int first = value.indexOf(':');
        if (first >= 0) {
            if (first == value.lastIndexOf(':')) {
                return value; // host:port，端口由用户指定，不再追加
            }
            return "[" + value + "]:" + port; // 裸 IPv6
        }
        return value + ":" + port;
    }

    /**
     * Extract the URI path from a URL string for Digest auth computation.
     * For "http://host/path" returns "/path".
     * For "/path" returns "/path".
     */
    private static String extractPath(String urlOrPath) {
        if (urlOrPath == null) return "/";
        if (urlOrPath.startsWith("/")) return urlOrPath;
        try {
            URI uri = new URI(urlOrPath);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return "/";
            if (uri.getQuery() != null) path += "?" + uri.getQuery();
            return path;
        } catch (Exception e) {
            // Fallback: try to extract path manually
            int idx = urlOrPath.indexOf("://");
            if (idx >= 0) {
                int slash = urlOrPath.indexOf("/", idx + 3);
                if (slash >= 0) return urlOrPath.substring(slash);
            }
            return urlOrPath;
        }
    }

    /**
     * Make an authenticated GET request to an ISAPI endpoint.
     * Handles Digest auth with minimal requests (2 max, 1 if cached).
     */
    public Response get(String path) {
        String urlStr = path.startsWith("http") ? path : baseUrl + path;
        String uriPath = extractPath(path);

        try {
            // If we have cached digest params, try with auth first
            if (cachedNonce != null) {
                Response resp = doGetWithDigestCached(urlStr, uriPath);
                if (resp.code != 401) return resp;
                // Auth failed with cached nonce (stale?) - clear cache and retry
                Log.w(TAG, "Cached digest failed (stale nonce?), re-authenticating");
                cachedNonce = null;
            }

            // First request to get the 401 challenge
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code != 401) {
                return readResponse(conn, code, urlStr);
            }

            // Parse the Digest challenge from the 401 response
            String authHeader = conn.getHeaderField("WWW-Authenticate");
            conn.disconnect();

            if (authHeader == null || authHeader.isEmpty()) {
                return new Response(401, null, "No WWW-Authenticate header", urlStr);
            }

            if (authHeader.toLowerCase().contains("digest")) {
                // Parse and cache digest params
                Map<String, String> params = parseDigestChallenge(authHeader);
                cachedRealm = params.get("realm");
                cachedNonce = params.get("nonce");
                cachedQop = params.get("qop");
                cachedAlgorithm = params.getOrDefault("algorithm", "MD5");
                cachedOpaque = params.get("opaque");
                nonceCount = 0;

                // Make authenticated request
                return doGetWithDigestCached(urlStr, uriPath);
            } else {
                // Try Basic auth
                return doGetWithBasic(urlStr);
            }

        } catch (Exception e) {
            Log.e(TAG, "GET error: " + urlStr + " - " + e.getMessage(), e);
            return new Response(-1, null, e.getMessage(), urlStr);
        }
    }

    /**
     * Make a Digest-authenticated request using cached nonce.
     */
    private Response doGetWithDigestCached(String urlStr, String uriPath) {
        try {
            nonceCount++;
            String nc = String.format("%08x", nonceCount);
            String cnonce = generateCnonce();

            String a1 = username + ":" + cachedRealm + ":" + password;
            String h1 = md5Hex(a1);
            String a2 = "GET:" + uriPath;
            String h2 = md5Hex(a2);

            String response;
            if (cachedQop != null && cachedQop.contains("auth")) {
                response = md5Hex(h1 + ":" + cachedNonce + ":" + nc + ":" + cnonce + ":auth:" + h2);
            } else {
                response = md5Hex(h1 + ":" + cachedNonce + ":" + h2);
            }

            // Build Authorization header
            StringBuilder authVal = new StringBuilder("Digest ");
            authVal.append("username=\"").append(username).append("\", ");
            authVal.append("realm=\"").append(cachedRealm).append("\", ");
            authVal.append("nonce=\"").append(cachedNonce).append("\", ");
            authVal.append("uri=\"").append(uriPath).append("\", ");
            authVal.append("response=\"").append(response).append("\", ");
            authVal.append("algorithm=").append(cachedAlgorithm);
            if (cachedQop != null && cachedQop.contains("auth")) {
                authVal.append(", qop=auth, nc=").append(nc);
                authVal.append(", cnonce=\"").append(cnonce).append("\"");
            }
            if (cachedOpaque != null && !cachedOpaque.isEmpty()) {
                authVal.append(", opaque=\"").append(cachedOpaque).append("\"");
            }

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Authorization", authVal.toString());

            int code = conn.getResponseCode();
            return readResponse(conn, code, urlStr);

        } catch (Exception e) {
            Log.e(TAG, "Digest auth error", e);
            return new Response(-1, null, e.getMessage(), urlStr);
        }
    }

    private Response doGetWithBasic(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);

            String auth = username + ":" + password;
            String encoded = Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            conn.setRequestProperty("Authorization", "Basic " + encoded);

            int code = conn.getResponseCode();
            return readResponse(conn, code, urlStr);
        } catch (Exception e) {
            Log.e(TAG, "Basic auth GET error", e);
            return new Response(-1, null, e.getMessage(), urlStr);
        }
    }

    private Response readResponse(HttpURLConnection conn, int code, String urlStr) {
        try {
            if (code == 200) {
                InputStream is = new BufferedInputStream(conn.getInputStream());
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                is.close();
                return new Response(code, baos.toByteArray(), null, urlStr);
            } else {
                // Read error body for diagnostics
                InputStream es = conn.getErrorStream();
                String errBody = "";
                if (es != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = es.read(buf)) > 0) {
                        baos.write(buf, 0, n);
                    }
                    es.close();
                    errBody = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                }
                // Check for account lock
                if (errBody.contains("lockStatus>lock")) {
                    return new Response(code, null, "账号被锁定，请等待几分钟后重试", urlStr);
                }
                return new Response(code, null,
                        "HTTP " + code + (errBody.isEmpty() ? "" : ": " + errBody.substring(0, Math.min(200, errBody.length()))),
                        urlStr);
            }
        } catch (Exception e) {
            return new Response(code, null, e.getMessage(), urlStr);
        } finally {
            conn.disconnect();
        }
    }

    private Map<String, String> parseDigestChallenge(String header) {
        Map<String, String> params = new HashMap<>();
        String s = header.replaceFirst("(?i)^Digest\\s+", "").replaceFirst("(?i)^Basic\\s+", "");
        Pattern p = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))");
        Matcher m = p.matcher(s);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            params.put(key, val);
        }
        return params;
    }

    private String generateCnonce() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class Response {
        public final int code;
        public final byte[] data;
        public final String error;
        /** 本次请求实际使用的 URL（脱敏前），用于诊断"端口/域名是否生效"。 */
        public final String url;

        public Response(int code, byte[] data, String error) {
            this(code, data, error, null);
        }

        public Response(int code, byte[] data, String error, String url) {
            this.code = code;
            this.data = data;
            this.error = error;
            this.url = url;
        }

        public boolean isSuccess() {
            return code == 200 && data != null;
        }

        public String asString() {
            if (data == null) return "";
            return new String(data, StandardCharsets.UTF_8);
        }
    }
}
