package com.litevision.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Manages NVR/camera configuration via SharedPreferences.
 * Fetches channel info from Hikvision NVR via ISAPI.
 */
public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREFS_NAME = "litevision_config";
    private static final String KEY_HOST = "nvr_host";
    private static final String KEY_RTSP_PORT = "rtsp_port";
    /** ISAPI（HTTP）端口：通道列表、缩略图抓图走的都是这个端口，与 RTSP 端口相互独立。 */
    private static final String KEY_HTTP_PORT = "http_port";
    /** 当 ISAPI 与 RTSP 共用同一个外网映射端口时，勾选此项即可，无需重复填写。 */
    private static final String KEY_HTTP_PORT_FOLLOW_RTSP = "http_port_follow_rtsp";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_USE_SUB_STREAM = "use_sub_stream";
    /** 解码模式："hard"=硬解 / "soft"=软解 / "auto"=自动 */
    private static final String KEY_DECODE_MODE = "decode_mode";
    private static final String KEY_CHANNEL_NAMES = "channel_names";
    private static final String KEY_LAST_DIAGNOSTIC = "last_diagnostic";

    // ===== 状态记忆（多画面 + 退出恢复）=====
    /** 上次退出时的视图模式："single"=单画面 / "multi"=多画面 / null=不恢复 */
    private static final String KEY_LAST_VIEW_MODE = "last_view_mode";
    /** 上次单画面/进入全屏时的通道索引 */
    private static final String KEY_LAST_CHANNEL_INDEX = "last_channel_index";
    /** 上次码流偏好："sub"=子码流 / "main"=主码流 / "auto"=回退 use_sub_stream */
    private static final String KEY_LAST_STREAM_TYPE = "last_stream_type";
    /** 上次多画面宫格大小：1 / 4 / 6 / 9 */
    private static final String KEY_LAST_MULTI_LAYOUT = "last_multi_layout";

    // ===== 默认 NVR 占位值（用户从未配过时的占位，不是真实地址）=====
    public static final String DEFAULT_HOST = "192.168.1.23";
    private static final int DEFAULT_RTSP_PORT = 554;
    /** 历史行为：ISAPI 一直硬编码走 80 端口，保持该默认值以兼容已有配置。 */
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final boolean DEFAULT_HTTP_PORT_FOLLOW_RTSP = false;
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "";
    private static final boolean DEFAULT_USE_SUB_STREAM = false;

    // ===== 解码模式 =====
    /** 硬解：Android 上强制使用 MediaCodec。 */
    public static final String DECODE_MODE_HARD = "hard";
    /** 软解：关闭硬解，全部走 CPU 解码。 */
    public static final String DECODE_MODE_SOFT = "soft";
    /** 自动：由 libVLC 自行挑选可用的硬解方案。 */
    public static final String DECODE_MODE_AUTO = "auto";

    /** libVLC avcodec-hw 取值：关闭硬解（软解）。 */
    public static final String AVCODEC_HW_DISABLED = "disabled";
    /** libVLC avcodec-hw 取值：强制 MediaCodec 硬解。 */
    public static final String AVCODEC_HW_MEDIACODEC = "mediacodec";
    /** libVLC avcodec-hw 取值：自动挑选可用硬解。 */
    public static final String AVCODEC_HW_ANY = "any";

    /**
     * 默认解码模式：自动（libVLC 自行挑选可用硬解，硬解不支持的流自动回退软解）。
     * 之前默认软解导致低配盒子/多通道场景"首帧久、出画面后又黑"（全通道 CPU 解码吃力）。
     * libVLC 的 avcodec-hw=any 会自动探测：能硬解就硬解，不兼容回退软解，是最安全的提速默认。
     */
    private static final String DEFAULT_DECODE_MODE = DECODE_MODE_AUTO;

    /** 合法 TCP 端口范围，用于输入校验与旧数据兜底。 */
    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;

    /** 域名解析（DNS）最长等待时间：ISAPI 请求在后台线程中可以安全阻塞等待。 */
    private static final long HOST_RESOLVE_TIMEOUT_MS = 6000L;

    // ===== DDNS 智能解析（对抗"IP 更新不及时"）=====
    /**
     * 域名解析结果的有效期（毫秒）。超过后 {@link #getConnectionHost()} 触发一次
     * 后台刷新（不阻塞、不清旧值），此后新建的 RTSP/HTTP URL 自动用上最新 IP。
     *
     * <p>60s：DDNS 场景 IP 变化通常以分钟计，30s 刷新过于频繁——每次刷新都会让
     * {@link #isHostResolutionPending()} 在起播时多等一轮解析（~200ms）。
     * 60s 在"及时感知 IP 变化"与"减少不必要刷新"之间取平衡；真 IP 变了会由
     * 连接失败后的 {@link #refreshHostResolution()} 强刷兜底，不会丢流。
     */
    private static final long HOST_RESOLVE_TTL_MS = 60_000L;
    /** 公共 DNS 直查超时（毫秒/台）。 */
    private static final int DNS_QUERY_TIMEOUT_MS = 2000;
    /** 候选 IP 的 TCP 探测超时（毫秒），探测 RTSP 端口（NVR 必开）。 */
    private static final int PROBE_TIMEOUT_MS = 1500;
    /** 直查的公共递归 DNS（阿里/腾讯/114）：绕过系统缓存与运营商 LocalDNS 的过期记录。 */
    private static final String[] PUBLIC_DNS_SERVERS = {"223.5.5.5", "119.29.29.29", "114.114.114.114"};
    /** 上次连接成功的 IP（与其对应的主机名一起存，换域名后自动失效）。 */
    private static final String KEY_LAST_GOOD_IP = "dns_last_good_ip";
    private static final String KEY_LAST_GOOD_HOST = "dns_last_good_host";

    /**
     * RTSP/HTTP URL 的 userinfo 中允许原样出现的字符。
     * 取自 RFC 3986 的 unreserved + sub-delims + ":"（userinfo 允许冒号）。
     * 其余字符一律百分号编码，避免破坏 URL 结构。
     */
    private static final String USER_INFO_SAFE_CHARS = "-._~!$&'()*+,;=:";

    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<String> channelNames = new ArrayList<>();
    private boolean channelNamesLoaded = false;

    /**
     * 域名预解析结果（IPv4 字面量）。
     * 部分 Android 设备的 getaddrinfo 会优先返回 AAAA 记录，而 NVR 通常只有 IPv4，
     * 表现为"填域名就连不上、填 IP 就正常"。这里在 Java 层主动解析并优先取 IPv4。
     * 未解析到 IPv4 时保持为 null，回退使用用户填写的原始主机（可能是纯 IPv6 环境）。
     */
    private volatile String resolvedHost = null;
    /** 当前正在进行的域名解析线程，用于在后台线程中等待其结果。 */
    private Thread resolveThread = null;
    /** 解析批次号：主机变化后，旧解析线程的结果必须丢弃，避免覆盖新主机。 */
    private volatile int resolveGeneration = 0;
    /** 最近一次解析针对的主机（用于区分"换域名需清空旧值"与"TTL 刷新保留旧值"）。 */
    private volatile String resolveTarget = null;
    /** 最近一次解析成功的时间戳（毫秒），用于 TTL 刷新判断。 */
    private volatile long resolvedHostTimeMs = 0L;
    /** 最近一次 TTL 触发后台刷新的时间（毫秒）：解析失败时结果时间戳不更新，
     *  必须单独节流，防止每次建 URL 都开一个解析线程。 */
    private volatile long lastResolveKickMs = 0L;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadChannelNames();
        startHostResolution();
    }

    // --- Connection config ---

    /**
     * 获取已保存的主机地址（IP 或域名）。
     * 读取时再次清洗，防止历史脏数据（例如带 http:// 前缀）拼接出畸形 URL。
     */
    public String getHost() {
        String host = normalizeHost(prefs.getString(KEY_HOST, DEFAULT_HOST));
        return host.isEmpty() ? DEFAULT_HOST : host;
    }

    /**
     * 保存主机地址。会先做清洗（去 scheme、去路径、去 userinfo），
     * 并立即在后台重新做一次域名预解析。
     */
    public void setHost(String host) {
        String normalized = normalizeHost(host);
        if (normalized.isEmpty()) {
            normalized = DEFAULT_HOST;
        }
        prefs.edit().putString(KEY_HOST, normalized).apply();
        startHostResolution();
    }

    public int getRtspPort() {
        return clampPort(prefs.getInt(KEY_RTSP_PORT, DEFAULT_RTSP_PORT), DEFAULT_RTSP_PORT);
    }

    public void setRtspPort(int port) {
        prefs.edit().putInt(KEY_RTSP_PORT, port).apply();
    }

    /**
     * ISAPI（HTTP）端口：通道列表与缩略图抓图使用该端口。
     * 老版本没有这个配置项，读取缺失时返回 80，与历史硬编码行为一致。
     */
    public int getHttpPort() {
        return clampPort(prefs.getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT), DEFAULT_HTTP_PORT);
    }

    public void setHttpPort(int port) {
        prefs.edit().putInt(KEY_HTTP_PORT, port).apply();
    }

    /** ISAPI 端口是否与 RTSP 端口保持一致（共用同一个外网映射端口时勾选）。 */
    public boolean isHttpPortFollowRtsp() {
        return prefs.getBoolean(KEY_HTTP_PORT_FOLLOW_RTSP, DEFAULT_HTTP_PORT_FOLLOW_RTSP);
    }

    public void setHttpPortFollowRtsp(boolean follow) {
        prefs.edit().putBoolean(KEY_HTTP_PORT_FOLLOW_RTSP, follow).apply();
    }

    /**
     * 实际用于 ISAPI 请求的端口：跟随模式下直接复用 RTSP 端口。
     */
    public int getEffectiveHttpPort() {
        if (isHttpPortFollowRtsp()) {
            return getRtspPort();
        }
        return getHttpPort();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, DEFAULT_USERNAME);
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD);
    }

    public void setPassword(String password) {
        prefs.edit().putString(KEY_PASSWORD, password).apply();
    }

    public boolean isUseSubStream() {
        return prefs.getBoolean(KEY_USE_SUB_STREAM, DEFAULT_USE_SUB_STREAM);
    }

    public void setUseSubStream(boolean use) {
        prefs.edit().putBoolean(KEY_USE_SUB_STREAM, use).apply();
    }

    // ===== 解码模式（软解 / 硬解 / 自动）=====

    /**
     * 获取已保存的解码模式。非法值（含 null）统一回落为软解，保持历史行为。
     *
     * @return {@link #DECODE_MODE_HARD} / {@link #DECODE_MODE_SOFT} / {@link #DECODE_MODE_AUTO}
     */
    public String getDecodeMode() {
        return normalizeDecodeMode(prefs.getString(KEY_DECODE_MODE, DEFAULT_DECODE_MODE));
    }

    /** 保存解码模式，非法值按软解存储。 */
    public void setDecodeMode(String mode) {
        prefs.edit().putString(KEY_DECODE_MODE, normalizeDecodeMode(mode)).apply();
    }

    /**
     * 归一化解码模式：只接受 hard / auto，其余（含 null、空串、旧脏数据）一律按 soft 处理。
     */
    public static String normalizeDecodeMode(String mode) {
        if (DECODE_MODE_HARD.equals(mode) || DECODE_MODE_AUTO.equals(mode)) {
            return mode;
        }
        return DECODE_MODE_SOFT;
    }

    /**
     * 当前设置对应的 libVLC avcodec-hw 取值。
     * 播放侧直接拿返回值拼参数即可，避免各处重复写映射。
     *
     * @return {@link #AVCODEC_HW_DISABLED} / {@link #AVCODEC_HW_MEDIACODEC} / {@link #AVCODEC_HW_ANY}
     */
    public String getAvcodecHwValue() {
        return resolveAvcodecHw(getDecodeMode());
    }

    /**
     * 把解码模式映射为 libVLC 的 avcodec-hw 取值。
     *
     * 注意两种写法的前缀由调用方决定：
     *   - LibVLC 实例选项：前缀 "--"，如 "--avcodec-hw=disabled"
     *   - Media 级别选项：前缀 ":"，如 ":avcodec-hw=disabled"
     */
    public static String resolveAvcodecHw(String mode) {
        String normalized = normalizeDecodeMode(mode);
        switch (normalized) {
            case DECODE_MODE_HARD:
                return AVCODEC_HW_MEDIACODEC;
            case DECODE_MODE_AUTO:
                return AVCODEC_HW_ANY;
            case DECODE_MODE_SOFT:
            default:
                return AVCODEC_HW_DISABLED;
        }
    }

    // ===== 状态记忆读写（多画面 + 退出恢复）=====
    // 说明：多画面与单画面码流键分离——单画面沿用 use_sub_stream；
    // 多画面默认子码流用独立键 multi_use_sub，两者互不干扰（§5/§12 设计）。

    /** 上次视图模式："single" / "multi" / null(不恢复) */
    public String getLastViewMode() {
        return prefs.getString(KEY_LAST_VIEW_MODE, null);
    }

    public void setLastViewMode(String mode) {
        prefs.edit().putString(KEY_LAST_VIEW_MODE, mode).apply();
    }

    /** 上次单画面/全屏通道索引 */
    public int getLastChannelIndex() {
        return prefs.getInt(KEY_LAST_CHANNEL_INDEX, 0);
    }

    public void setLastChannelIndex(int index) {
        prefs.edit().putInt(KEY_LAST_CHANNEL_INDEX, index).apply();
    }

    /** 上次码流偏好："sub" / "main" / "auto" */
    public String getLastStreamType() {
        return prefs.getString(KEY_LAST_STREAM_TYPE, "auto");
    }

    public void setLastStreamType(String streamType) {
        prefs.edit().putString(KEY_LAST_STREAM_TYPE, streamType).apply();
    }

    /** 上次多画面宫格大小：1 / 4 / 6 / 9 / 16 */
    public int getLastMultiLayout() {
        int layout = prefs.getInt(KEY_LAST_MULTI_LAYOUT, 4);
        if (layout != 1 && layout != 4 && layout != 6 && layout != 9 && layout != 16) {
            return 4;
        }
        return layout;
    }

    public void setLastMultiLayout(int layout) {
        // commit() 而非 apply()：apply() 异步写盘，新进程（Activity 被系统重建时）
        // 可能读到旧值导致布局重置为默认 9 宫格。commit() 同步写盘保证立即可见。
        prefs.edit().putInt(KEY_LAST_MULTI_LAYOUT, layout).commit();
    }

    /** 多画面是否使用子码流：统一用全局 use_sub_stream，与设置页同步。 */
    public boolean isMultiUseSub() {
        return isUseSubStream();
    }

    public void setMultiUseSub(boolean useSub) {
        setUseSubStream(useSub);
    }

    /**
     * 解析上次码流偏好为实际的子码流布尔值。
     * "sub"  → true；"main" → false；"auto" → 回退单画面全局 use_sub_stream。
     */
    public boolean resolveLastStreamTypeToSub() {
        String type = getLastStreamType();
        if ("sub".equals(type)) {
            return true;
        }
        if ("main".equals(type)) {
            return false;
        }
        return isUseSubStream();
    }

    // --- Channel info ---

    public synchronized List<String> getChannelNames() {
        if (!channelNamesLoaded) {
            return new ArrayList<>();
        }
        return new ArrayList<>(channelNames);
    }

    public synchronized int getChannelCount() {
        return channelNames.size();
    }

    public synchronized String getChannelName(int index) {
        if (index >= 0 && index < channelNames.size()) {
            String name = channelNames.get(index);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return "通道" + (index + 1);
    }

    /**
     * Get RTSP URL for a channel index (0-based).
     * Hikvision format: rtsp://user:pass@host:port/Streaming/Channels/{channel}{stream}
     * Channel 1 main = 101, sub = 102; Channel 2 main = 201, sub = 202, etc.
     *
     * 凭据处理：不做整体 URLEncode（多数 NVR 不接受百分号编码的凭据），
     * 只对会破坏 URL 结构的字符做最小安全编码，详见 {@link #encodeUserInfo}。
     */
    public String getRtspUrl(int channelIndex) {
        return getRtspUrl(channelIndex, isUseSubStream());
    }

    /**
     * Get RTSP URL for a channel index with explicit stream selection.
     * 供多画面等"显式指定主/子码流"的场景使用，不受全局 use_sub_stream 影响。
     */
    public String getRtspUrl(int channelIndex, boolean useSub) {
        int channel = channelIndex + 1;
        int streamType = useSub ? 2 : 1;
        int channelCode = channel * 100 + streamType;
        return String.format("rtsp://%s:%s@%s/Streaming/Channels/%d",
                encodeUserInfo(getUsername()), encodeUserInfo(getPassword()),
                formatHostPort(getRtspPort()), channelCode);
    }

    /**
     * Get RTSP URL with sub stream (for fallback).
     */
    public String getRtspUrlSubStream(int channelIndex) {
        return getRtspUrl(channelIndex, true);
    }

    /**
     * 拼接 RTSP 使用的 host:port 段。
     * 主机使用 {@link #getConnectionHost()}（域名会优先解析为 IPv4）；
     * 若主机已自带端口（用户直接填 host:8080）则不再追加端口；IPv6 自动补方括号。
     */
    private String formatHostPort(int port) {
        String host = getConnectionHost();
        if (host == null || host.isEmpty()) {
            host = DEFAULT_HOST;
        }
        return NvrClient.formatAuthority(host, port);
    }

    /**
     * Get ISAPI snapshot path (not full URL) for a channel index.
     */
    public String getSnapshotPath(int channelIndex) {
        int channel = channelIndex + 1;
        int channelCode = channel * 100 + 1;
        return String.format("/ISAPI/Streaming/channels/%d/picture", channelCode);
    }

    // --- Host 清洗 / 域名预解析 / URL 编码 工具 ---

    /**
     * 清洗用户填写的主机地址，容忍常见的粘贴格式，避免拼出畸形 URL。
     *
     * 支持并自动纠正：
     *   - "http://192.168.1.23"        → "192.168.1.23"
     *   - "http://nvr.example.com:8080/ISAPI" → "nvr.example.com:8080"
     *   - "192.168.1.23/"              → "192.168.1.23"
     *   - "http://user:pass@host"      → "host"
     *
     * 注意：端口后缀会被保留（如 "host:8080"），由调用方用
     * {@link #splitHostAndPort(String)} 决定如何处理。
     */
    public static String normalizeHost(String rawHost) {
        if (rawHost == null) {
            return "";
        }
        String host = rawHost.trim();

        // 去掉 scheme 前缀
        int schemeIdx = host.indexOf("://");
        if (schemeIdx >= 0) {
            host = host.substring(schemeIdx + 3);
        }

        // 截断到 authority 结束（丢弃 path / query / fragment）
        int cut = host.length();
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        host = host.substring(0, cut);

        // 去掉可能存在的 userinfo（user:pass@host）
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }

        return host.trim();
    }

    /**
     * 拆分 "host" 与 "port"。
     *
     * @return 长度为 2 的数组：{host, port}；port 不存在时为 null。
     *         支持 "host:8080" 与 "[2001:db8::1]:8080" 两种写法。
     */
    public static String[] splitHostAndPort(String host) {
        if (host == null || host.isEmpty()) {
            return new String[]{"", null};
        }
        if (host.charAt(0) == '[') {
            int close = host.indexOf(']');
            if (close >= 0) {
                String address = host.substring(0, close + 1);
                String rest = host.substring(close + 1);
                if (rest.startsWith(":")) {
                    return new String[]{address, rest.substring(1)};
                }
                return new String[]{address, null};
            }
        }
        int first = host.indexOf(':');
        if (first >= 0 && first == host.lastIndexOf(':')) {
            // 只有一个冒号 → 判定为 host:port
            return new String[]{host.substring(0, first), host.substring(first + 1)};
        }
        // 无冒号，或多个冒号（裸 IPv6）→ 视为不带端口
        return new String[]{host, null};
    }

    /**
     * 获取用于实际网络连接的主机地址。
     * 域名解析已完成时返回解析出的 IPv4，否则返回用户配置的原始主机。
     * 该方法不会阻塞，可安全地在 UI 线程调用。
     *
     * DDNS 优化：解析结果超过 {@link #HOST_RESOLVE_TTL_MS} 后，这里会触发一次
     * 后台刷新（不阻塞、不清旧值——IP 未变时无感，变了则下一次建 URL 生效）。
     */
    public String getConnectionHost() {
        String resolved = resolvedHost;
        if (resolved != null && !resolved.isEmpty()) {
            long age = System.currentTimeMillis() - resolvedHostTimeMs;
            if (age > HOST_RESOLVE_TTL_MS) {
                long now = System.currentTimeMillis();
                if (now - lastResolveKickMs >= 5000L) {
                    lastResolveKickMs = now;
                    Log.d(TAG, "域名解析结果已过期 " + (age / 1000) + "s，触发后台刷新");
                    startHostResolution();
                }
            }
            return resolved;
        }
        return getHost();
    }

    /**
     * 阻塞等待域名预解析完成（最多 {@link #HOST_RESOLVE_TIMEOUT_MS} 毫秒）。
     * 只能在后台线程调用——UI 线程请使用 {@link #getConnectionHost()}。
     */
    public void awaitHostResolution() {
        Thread thread;
        synchronized (this) {
            thread = resolveThread;
        }
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(HOST_RESOLVE_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 主机是否是"域名且尚未解析出 IP"（即需要等待解析）。
     *
     * <p>用途：播放页在起播前据此决定是否先 {@link #awaitHostResolution()}（后台线程）。
     * 若此刻用<b>域名</b> URL 起播，等智能解析（公共 DNS 直查 + TCP 探活）完成后
     * URL 会变成 IP，调用方的 {@code isActiveFor(url)} 判定 URL 不匹配 → **推倒重建**：
     * 刚握手成功的 RTSP 会话被 TEARDOWN，白等一整轮握手 + 一次缓冲。
     * 实测每路多花 150~400ms；多路并发时第二轮 SETUP 还会撞上 NVR 并发取流上限排队，
     * 表现为"部分通道黑屏数秒"。起播前先等解析落地即可彻底消除这轮浪费。
     *
     * @return true = 需要等解析；false = 主机本来就是 IP、或已解析完成，可直接起播
     */
    public boolean isHostResolutionPending() {
        String host = getHost();
        if (isIpLiteral(host)) {
            return false;   // 本来就是 IP 字面量，无需解析
        }
        String conn = getConnectionHost();
        // 仍等于域名 = 解析尚未完成
        return conn == null || conn.isEmpty() || conn.equals(host);
    }

    /**
     * 强制重新解析域名（后台异步，不阻塞）。失败重试场景调用：连接失败很可能是
     * DDNS 的 IP 已变化而解析缓存还指旧 IP，强制走一轮"公共 DNS 直查 + 探活"，
     * 之后重建的 URL 即可拿到新 IP。
     */
    public void refreshHostResolution() {
        startHostResolution();
    }

    /**
     * 启动（或重启）后台域名预解析。
     * 主机本身就是 IP 字面量时无需解析，直接作为结果使用。
     *
     * 行为说明：
     *   - 换了主机（域名）→ 立即清空旧解析结果（旧 IP 属于旧域名，绝不能沿用）；
     *   - 同一主机的 TTL 刷新 / 失败重试 → 保留旧值直到新一轮结果产出（避免解析间隙闪断）；
     *   - 已有解析线程在跑 → 直接跳过（防失败风暴里反复开线程）。
     */
    private synchronized void startHostResolution() {
        Thread running = resolveThread;
        if (running != null && running.isAlive()) {
            return; // 上一轮还在跑：其结果（更新的 IP）稍后会自动生效
        }
        final int generation = ++resolveGeneration;
        final String target = getHost();
        if (isIpLiteral(target)) {
            resolvedHost = target;
            resolvedHostTimeMs = System.currentTimeMillis();
            resolveTarget = target;
            resolveThread = null;
            return;
        }
        // 换域名才清空旧值；同一域名的刷新保留旧值（见方法注释）
        if (!target.equals(resolveTarget)) {
            resolvedHost = null;
        }
        resolveTarget = target;
        Thread thread = new Thread(() -> {
            String ipv4 = resolveHostSmart(target);
            // 主机可能已被再次修改：丢弃过期批次的结果，防止旧线程覆盖新主机的解析结果
            if (generation != resolveGeneration) {
                Log.d(TAG, "丢弃过期的域名解析结果: " + target);
                return;
            }
            if (ipv4 != null && !ipv4.isEmpty()) {
                boolean changed = !ipv4.equals(resolvedHost);
                resolvedHost = ipv4;
                resolvedHostTimeMs = System.currentTimeMillis();
                Log.i(TAG, "域名解析就绪: " + target + " -> " + ipv4
                        + (changed ? " (IP 已更新)" : ""));
            } else {
                Log.w(TAG, "域名未解析到 IPv4 地址，回退使用原始主机: " + target
                        + (resolvedHost != null ? "（保留旧解析 " + resolvedHost + "）" : ""));
            }
        }, "host-resolve");
        thread.setDaemon(true);
        resolveThread = thread;
        thread.start();
    }

    /**
     * 智能域名解析（运行在解析线程，可阻塞）——DDNS "IP 更新不及时" 的核心对策：
     *
     * 1. 多源收集候选 IP（去重）：
     *    a) 公共 DNS 直查（3 台并行，原始 UDP 包）——绕过系统缓存与运营商 LocalDNS，
     *       拿递归服务器实时回源的最新记录；
     *    b) 系统解析器 {@code InetAddress}——兜底（公共 DNS 全不可达时仍有结果）；
     *    c) 上次连接成功的 IP（仅当仍是同一域名）——DNS 全挂时的直连保底。
     * 2. 多个候选时并行 TCP 探测 RTSP 端口（NVR 必开），选第一个真正活的：
     *    DNS 返回旧+新两条记录时，探活决定用哪条；旧 IP 已死则自动淘汰。
     * 3. 全部探活失败：仍返回首选候选（公共 DNS 优先）——端口可能被防火墙拦，
     *    DNS 结果依旧是最新的；但不写入"上次成功 IP"。
     *
     * @return 解析出的 IPv4；完全无候选返回 null
     */
    private String resolveHostSmart(String host) {
        // ---- 1. 收集候选（LinkedHashSet 保持优先序：公共 DNS > 系统 > 上次成功）----
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        long dnsStart = System.currentTimeMillis();
        ExecutorService dnsPool = Executors.newFixedThreadPool(PUBLIC_DNS_SERVERS.length);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (final String server : PUBLIC_DNS_SERVERS) {
                futures.add(dnsPool.submit(() -> DnsOverUdp.queryA(host, server, DNS_QUERY_TIMEOUT_MS)));
            }
            for (Future<List<String>> future : futures) {
                try {
                    candidates.addAll(future.get(DNS_QUERY_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS));
                } catch (Exception ignored) {
                }
            }
        } finally {
            dnsPool.shutdownNow();
        }
        List<String> publicDnsAnswers = new ArrayList<>(candidates); // 公共 DNS 结果快照（用于日志/判空）

        // 系统解析器兜底
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses != null) {
                for (InetAddress address : addresses) {
                    if (address instanceof Inet4Address) {
                        candidates.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "系统解析失败(有公共DNS直查兜底): " + host + " - " + e.getMessage());
        }

        // 上次连接成功的 IP（仅同一域名时沿用）
        String lastGoodHost = prefs.getString(KEY_LAST_GOOD_HOST, null);
        String lastGoodIp = prefs.getString(KEY_LAST_GOOD_IP, null);
        if (host.equals(lastGoodHost) && lastGoodIp != null && !lastGoodIp.isEmpty()) {
            candidates.add(lastGoodIp);
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "域名解析: 所有来源均无结果 " + host
                    + " (耗时 " + (System.currentTimeMillis() - dnsStart) + "ms)");
            return null;
        }
        Log.i(TAG, "域名解析: " + host + " 候选=" + candidates
                + " (公共DNS=" + publicDnsAnswers + " 上次成功=" + lastGoodIp
                + ", 耗时 " + (System.currentTimeMillis() - dnsStart) + "ms)");

        // ---- 2. 并行 TCP 探活（RTSP 端口，NVR 必开）----
        int probePort = getRtspPort();
        ExecutorService probePool = Executors.newFixedThreadPool(Math.max(1, candidates.size()));
        try {
            List<Future<String>> probes = new ArrayList<>();
            for (final String ip : candidates) {
                probes.add(probePool.submit(() -> probeTcp(ip, probePort, PROBE_TIMEOUT_MS) ? ip : null));
            }
            for (Future<String> probe : probes) {
                try {
                    String alive = probe.get(PROBE_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS);
                    if (alive != null) {
                        Log.i(TAG, "域名解析: 探活命中 " + alive + ":" + probePort + "，选为连接地址");
                        saveLastGoodIp(host, alive);
                        return alive;
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            probePool.shutdownNow();
        }

        // ---- 3. 全不通：返回首选候选（公共 DNS 优先），不写"上次成功" ----
        String fallback = candidates.iterator().next();
        Log.w(TAG, "域名解析: 所有候选探活失败(" + probePort + ")，按 DNS 优先序返回 " + fallback);
        return fallback;
    }

    /**
     * TCP 连接探测：能建立连接即认为该 IP 的该端口活着（NVR 的 RTSP 端口必开）。
     */
    private static boolean probeTcp(String ip, int port, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 记住"上次连接成功"的 IP：DNS 全挂（域名服务商故障/NXDOMAIN）时，
     * 下次解析仍能靠它直连，App 不断流。
     */
    private void saveLastGoodIp(String host, String ip) {
        prefs.edit()
                .putString(KEY_LAST_GOOD_HOST, host)
                .putString(KEY_LAST_GOOD_IP, ip)
                .apply();
    }

    /**
     * 判断主机是否已经是 IP 字面量（IPv4 点分十进制或 IPv6），是则无需 DNS 解析。
     */
    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            // IPv6（可能带方括号，也可能是裸地址）
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将端口限制在合法范围内，非法时返回兜底值。
     */
    private static int clampPort(int port, int fallback) {
        if (port < MIN_PORT || port > MAX_PORT) {
            return fallback;
        }
        return port;
    }

    /**
     * 对 URL 中的 userinfo（用户名 / 密码）做"最小安全编码"。
     *
     * 背景：绝大多数 NVR 的 RTSP 实现不接受整体百分号编码的凭据，因此不能
     * 直接 URLEncode。但密码中一旦出现 URL 结构字符（@ : / ? # % 等），
     * 会直接改变 URL 的语法结构，导致 Uri.parse 把 host 解析错、端口丢失，
     * 表现出的症状就是"域名填了没用 / 端口改了没用"。
     *
     * 策略：
     *   - 字母数字 + RFC3986 unreserved/sub-delims + ':' 原样保留（覆盖绝大多数密码）
     *   - ASCII 控制字符与其余会破坏语法的可打印字符（@ / ? # % [ ] \ 空格 等）→ 百分号编码
     *   - 非 ASCII（如中文密码）原样保留，维持历史行为，避免影响 NVR 兼容性
     * 逐字符处理而非对结果二次编码，因此 '%' 只会被编码一次。
     */
    private static String encodeUserInfo(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean isAlnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (isAlnum || USER_INFO_SAFE_CHARS.indexOf(c) >= 0) {
                // 安全字符：原样保留
                sb.append(c);
            } else if (c >= 0x80) {
                // 真正的非 ASCII（中文密码等，≥0x80）：保持历史行为，交由 NVR 按原字节处理
                // 注意：0x7F(DEL) 及以下均为 ASCII，含 0x7F 在内全部走百分号编码分支
                sb.append(c);
            } else {
                // ASCII 控制字符与破坏 URL 语法的字符（@ / ? # % [ ] \ 空格 …）→ %XX
                sb.append(percentEncode((byte) c));
            }
        }
        return sb.toString();
    }

    /**
     * 单字节百分号编码，如 0x40 → "%40"。
     */
    private static String percentEncode(byte b) {
        String hex = Integer.toHexString(b & 0xFF).toUpperCase(Locale.ROOT);
        return hex.length() == 1 ? "%0" + hex : "%" + hex;
    }

    // --- ISAPI channel info fetching ---

    public interface ChannelInfoCallback {
        void onChannelsLoaded(List<String> names);
        void onError(String message);
    }

    public void voidSaveDiagnostic(String text) {
        prefs.edit().putString(KEY_LAST_DIAGNOSTIC, text).apply();
    }

    public String getDiagnostic() {
        return prefs.getString(KEY_LAST_DIAGNOSTIC, "");
    }

    public void refreshChannelInfo(ChannelInfoCallback callback) {
        executor.execute(() -> {
            List<String> names = fetchChannelNames();
            // DDNS 场景自愈：配置的是域名且首轮全部失败（拿不到通道）时，很可能是
            // 解析缓存指旧 IP（IP 变了、DDNS 已更新但系统/LocalDNS 缓存未过期）。
            // 强制走一轮"公共 DNS 直查 + 探活"拿新 IP，再重试一轮。
            if (names.isEmpty() && !isIpLiteral(getHost())) {
                Log.i(TAG, "通道列表为空且配置为域名：强刷 DNS 解析后重试一轮");
                startHostResolution();
                awaitHostResolution();
                names = fetchChannelNames();
            }
            synchronized (this) {
                channelNames = names;
                channelNamesLoaded = true;
                saveChannelNames(names);
            }
            if (callback != null) {
                if (names.isEmpty()) {
                    // 提示里点明 ISAPI 端口：这是"改了端口却没生效/域名读不到数据"最常见的原因
                    callback.onError("无法获取通道信息\n请检查 NVR 地址、ISAPI 端口（当前 "
                            + getEffectiveHttpPort() + "）和用户名密码");
                } else {
                    callback.onChannelsLoaded(names);
                }
            }
        });
    }

    private List<String> fetchChannelNames() {
        List<String> names = new ArrayList<>();
        // 本方法运行在后台线程，可以安全地等待域名解析完成（避免 IPv6 优先导致连接超时）。
        awaitHostResolution();
        String connectHost = getConnectionHost();
        int httpPort = getEffectiveHttpPort();
        NvrClient client = new NvrClient(connectHost, httpPort, getUsername(), getPassword());
        StringBuilder diag = new StringBuilder();
        // 把实际使用的地址写进诊断信息，方便定位"域名/端口填了没生效"这类问题
        diag.append("ISAPI 地址: http://")
                .append(NvrClient.formatAuthority(connectHost, httpPort)).append("\n");
        diag.append("RTSP  地址: rtsp://***:***@")
                .append(formatHostPort(getRtspPort())).append("\n");
        diag.append("配置主机: ").append(getHost()).append("\n\n");

        // Strategy 1: /ISAPI/ContentMgmt/InputProxy/channels - returns IP camera channels with real names
        // This is the primary endpoint for NVRs that use IP cameras (InputProxy)
        NvrClient.Response resp = client.get("/ISAPI/ContentMgmt/InputProxy/channels");
        diag.append("=== /ISAPI/ContentMgmt/InputProxy/channels ===\n");
        diag.append("HTTP ").append(resp.code).append("\n");
        if (resp.isSuccess()) {
            String xml = resp.asString();
            diag.append("Length: ").append(xml.length()).append("\n");
            diag.append(xml, 0, Math.min(1000, xml.length())).append("\n\n");
            names = parseInputProxyChannels(xml);
            Log.i(TAG, "Strategy 1 (InputProxy): found " + names.size() + " channels");
        } else {
            diag.append("Error: ").append(resp.error).append("\n\n");
        }

        // Strategy 2: /ISAPI/System/Video/inputs/channels - returns all video input channels
        if (names.isEmpty()) {
            resp = client.get("/ISAPI/System/Video/inputs/channels");
            diag.append("=== /ISAPI/System/Video/inputs/channels ===\n");
            diag.append("HTTP ").append(resp.code).append("\n");
            if (resp.isSuccess()) {
                String xml = resp.asString();
                diag.append("Length: ").append(xml.length()).append("\n");
                diag.append(xml, 0, Math.min(1000, xml.length())).append("\n\n");
                names = parseVideoInputChannels(xml);
                Log.i(TAG, "Strategy 2 (VideoInputs): found " + names.size() + " channels");
            } else {
                diag.append("Error: ").append(resp.error).append("\n\n");
            }
        }

        // Strategy 3: /ISAPI/Streaming/channels - returns configured streaming channels
        if (names.isEmpty()) {
            resp = client.get("/ISAPI/Streaming/channels");
            diag.append("=== /ISAPI/Streaming/channels ===\n");
            diag.append("HTTP ").append(resp.code).append("\n");
            if (resp.isSuccess()) {
                String xml = resp.asString();
                diag.append("Length: ").append(xml.length()).append("\n");
                diag.append(xml, 0, Math.min(1000, xml.length())).append("\n\n");
                names = parseStreamingChannels(xml);
                Log.i(TAG, "Strategy 3 (Streaming): found " + names.size() + " channels");
            } else {
                diag.append("Error: ").append(resp.error).append("\n\n");
            }
        }

        // Strategy 4: Probe individual streaming channels (last resort)
        // Some NVRs don't support bulk listing but support per-channel queries
        if (names.isEmpty()) {
            diag.append("=== Probing individual channels ===\n");
            int consecutiveFailures = 0;
            for (int i = 1; i <= 32; i++) {
                resp = client.get("/ISAPI/Streaming/channels/" + i + "01");
                if (resp.isSuccess()) {
                    consecutiveFailures = 0;
                    String name = parseSingleChannelName(resp.asString());
                    names.add(name != null ? name : "通道" + i);
                    diag.append("Channel ").append(i).append(": OK - ").append(name).append("\n");
                } else {
                    consecutiveFailures++;
                    diag.append("Channel ").append(i).append(": FAIL (").append(resp.code).append(")\n");
                    // Skip offline channels instead of stopping entirely
                    // Some channels (e.g., 4, 5) may be offline but 6+ are online
                    if (consecutiveFailures >= 3) break;
                }
            }
            diag.append("\n");
            Log.i(TAG, "Strategy 4 (Probe): found " + names.size() + " channels");
        }

        diag.append("=== Final: ").append(names.size()).append(" channels ===\n");
        for (int i = 0; i < names.size(); i++) {
            diag.append("  [").append(i).append("] ").append(names.get(i)).append("\n");
        }

        voidSaveDiagnostic(diag.toString());
        Log.i(TAG, "Diagnostic:\n" + diag);

        return names;
    }

    /**
     * Query a single channel's name from ISAPI.
     */
    private String queryChannelName(NvrClient client, int channel) {
        NvrClient.Response resp = client.get("/ISAPI/Streaming/channels/" + channel + "01");
        if (resp.isSuccess()) {
            return parseSingleChannelName(resp.asString());
        }
        return null;
    }

    /**
     * Parse /ISAPI/System/Video/inputs/channels response.
     * Handles multiple Hikvision XML formats:
     * <VideoInput><VideoInputChannel><id>1</id><inputChannelName>Name</inputChannelName></VideoInputChannel></VideoInput>
     * <VideoInput><InputChannel><id>1</id><name>Name</name></InputChannel></VideoInput>
     */
    private List<String> parseVideoInputChannels(String xml) {
        List<String> names = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int eventType = parser.getEventType();
            String currentTag = "";
            String currentName = "";
            int currentId = 0;
            boolean inChannel = false;
            String channelContainerTag = ""; // Track the exact container tag name

            while ( eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentTag = parser.getName().toLowerCase();
                        // Only start a new channel when NOT already inside one
                        // Exclude tags containing "name", "num", "count" - they are sub-elements not containers
                        if (!inChannel && (currentTag.contains("channel") || currentTag.contains("input"))) {
                            if (!currentTag.equals("channels") && !currentTag.equals("channellist")
                                && !currentTag.equals("videoinput") && !currentTag.contains("count")
                                && !currentTag.contains("name") && !currentTag.contains("num")
                                && !currentTag.contains("desc") && !currentTag.contains("status")
                                && !currentTag.contains("type") && !currentTag.contains("list")) {
                                inChannel = true;
                                channelContainerTag = currentTag;
                                currentId = 0;
                                currentName = "";
                            }
                        }
                        break;
                    case XmlPullParser.TEXT:
                        String text = parser.getText();
                        if (text != null && inChannel) {
                            text = text.trim();
                            if (text.isEmpty()) break;
                            if (currentTag.equals("id")) {
                                try { currentId = Integer.parseInt(text); } catch (Exception ignored) {}
                            }
                            // Any tag containing "name" (inputChannelName, channelName, name)
                            else if (currentTag.contains("name")) {
                                if (currentName.isEmpty()) {
                                    currentName = text;
                                }
                            }
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        String endTag = parser.getName().toLowerCase();
                        // Only end channel when matching the container closing tag
                        if (inChannel && endTag.equals(channelContainerTag)) {
                            if (currentId > 0) {
                                while (names.size() < currentId) {
                                    names.add("通道" + (names.size() + 1));
                                }
                                if (currentId - 1 < names.size()) {
                                    if (!currentName.isEmpty()) {
                                        names.set(currentId - 1, currentName);
                                    } else {
                                        names.set(currentId - 1, "通道" + currentId);
                                    }
                                }
                            }
                            inChannel = false;
                            channelContainerTag = "";
                        }
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse VideoInputChannels error", e);
        }
        return names;
    }

    /**
     * Parse /ISAPI/Streaming/channels response.
     * Format: <StreamingChannelList><StreamingChannel><id>101</id><channelName>Name</channelName></StreamingChannel></StreamingChannelList>
     */
    private List<String> parseStreamingChannels(String xml) {
        List<String> names = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int eventType = parser.getEventType();
            String currentTag = "";
            String currentName = "";
            int currentId = 0;
            boolean inChannel = false;
            String channelContainerTag = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentTag = parser.getName().toLowerCase();
                        // Only start new channel when not already in one
                        if (!inChannel && currentTag.contains("streamingchannel") && !currentTag.contains("list")) {
                            inChannel = true;
                            channelContainerTag = currentTag;
                            currentId = 0;
                            currentName = "";
                        }
                        break;
                    case XmlPullParser.TEXT:
                        String text = parser.getText();
                        if (text != null && inChannel) {
                            text = text.trim();
                            if (text.isEmpty()) break;
                            if (currentTag.equals("id")) {
                                try { currentId = Integer.parseInt(text); } catch (Exception ignored) {}
                            } else if (currentTag.contains("name")) {
                                if (currentName.isEmpty()) currentName = text;
                            }
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        String endTag = parser.getName().toLowerCase();
                        // Only end channel when matching the container closing tag
                        if (inChannel && endTag.equals(channelContainerTag)) {
                            if (currentId > 0) {
                                // ID format: channel*100 + streamType (101=ch1 main, 102=ch1 sub, 201=ch2 main...)
                                int channelNum = currentId / 100;
                                int streamType = currentId % 100;
                                if (channelNum > 0 && streamType == 1) { // Only main streams
                                    while (names.size() < channelNum) {
                                        names.add("通道" + (names.size() + 1));
                                    }
                                    if (!currentName.isEmpty()) {
                                        names.set(channelNum - 1, currentName);
                                    }
                                }
                            }
                            inChannel = false;
                            channelContainerTag = "";
                        }
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse StreamingChannels error", e);
        }
        return names;
    }

    /**
     * Parse /ISAPI/ContentMgmt/InputProxy/channels response.
     * Format: <InputProxyChannelList><InputProxyChannel><id>1</id><name>Camera1</name>...</InputProxyChannel></InputProxyChannelList>
     * This endpoint returns the actual camera names for IP cameras added to the NVR.
     */
    private List<String> parseInputProxyChannels(String xml) {
        List<String> names = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int eventType = parser.getEventType();
            String currentTag = "";
            String currentName = "";
            int currentId = 0;
            boolean inChannel = false;
            String channelContainerTag = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentTag = parser.getName().toLowerCase();
                        // Match InputProxyChannel but not InputProxyChannelList
                        if (!inChannel && currentTag.contains("proxychannel") && !currentTag.contains("list")) {
                            inChannel = true;
                            channelContainerTag = currentTag;
                            currentId = 0;
                            currentName = "";
                        }
                        break;
                    case XmlPullParser.TEXT:
                        String text = parser.getText();
                        if (text != null && inChannel) {
                            text = text.trim();
                            if (text.isEmpty()) break;
                            if (currentTag.equals("id")) {
                                try { currentId = Integer.parseInt(text); } catch (Exception ignored) {}
                            } else if (currentTag.equals("name") || currentTag.contains("name")) {
                                if (currentName.isEmpty()) currentName = text;
                            }
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        String endTag = parser.getName().toLowerCase();
                        if (inChannel && endTag.equals(channelContainerTag)) {
                            if (currentId > 0) {
                                while (names.size() < currentId) {
                                    names.add("通道" + (names.size() + 1));
                                }
                                if (currentId - 1 < names.size()) {
                                    if (!currentName.isEmpty()) {
                                        names.set(currentId - 1, currentName);
                                    } else {
                                        names.set(currentId - 1, "通道" + currentId);
                                    }
                                }
                            }
                            inChannel = false;
                            channelContainerTag = "";
                        }
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse InputProxyChannels error", e);
        }
        return names;
    }

    private int parseInputCount(String xml) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));
            int eventType = parser.getEventType();
            String currentTag = "";
            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentTag = parser.getName().toLowerCase();
                        break;
                    case XmlPullParser.TEXT:
                        String text = parser.getText();
                        if (text != null) {
                            text = text.trim();
                            if (currentTag.contains("videoinputcount") || currentTag.contains("inputcount") || currentTag.equals("count")) {
                                try { return Integer.parseInt(text); } catch (Exception ignored) {}
                            }
                        }
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse input count error", e);
        }
        return 0;
    }

    private String parseSingleChannelName(String xml) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));
            int eventType = parser.getEventType();
            String currentTag = "";
            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentTag = parser.getName().toLowerCase();
                        break;
                    case XmlPullParser.TEXT:
                        String text = parser.getText();
                        if (text != null) {
                            text = text.trim();
                            if (!text.isEmpty() && currentTag.contains("name")) {
                                return text;
                            }
                        }
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse single channel name error", e);
        }
        return null;
    }

    // --- Persistence ---

    private void saveChannelNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(names.get(i));
        }
        prefs.edit().putString(KEY_CHANNEL_NAMES, sb.toString()).apply();
    }

    private void loadChannelNames() {
        String saved = prefs.getString(KEY_CHANNEL_NAMES, "");
        if (saved != null && !saved.isEmpty()) {
            String[] parts = saved.split("\n");
            channelNames = new ArrayList<>();
            for (String p : parts) {
                channelNames.add(p);
            }
            channelNamesLoaded = true;
        }
    }
}
