package com.litevision.tv;

import org.videolan.libvlc.Media;

import java.util.ArrayList;
import java.util.List;

/**
 * VlcOptions — libVLC 取流/解码参数的**唯一来源**（单一事实源）。
 *
 * <p>背景（历史坑）：同一套缓存/低延迟参数原本散落在 6 处——
 * {@code PlayerEngine.startMedia()}、{@code RtspFrameGrabber}、
 * {@code PlayerActivity} 的 combined / dual 两个分支（以上 per-media），
 * 以及 {@code MultiViewActivity.initLibVlc()}、{@code PlayerActivity.initEngine()}（实例级）。
 * 代码注释里只能反复警告"per-media 的 :network-caching= 会覆盖实例级，两处必须一起改，
 * 只改一处等于没改"——这种靠人工同步的约定极易漏改，且漏改后症状隐蔽（改了没变快）。
 * 本类把参数集中定义，各调用点统一取用，从根上消除漏改。
 *
 * <p>取值取向：**首帧极速化**（监控场景：局域网/DDNS 直连，延迟可控，
 * 用户偏好"点开立刻出画面"），瞬时抖动由 PlayerEngine 的 freeze/rebuffer 状态机兜底
 * （FREEZE_THRESHOLD_MS / REBUFFER_STALL_MS 等阈值保持不变）。
 *
 * <p>前缀约定：实例级用 {@code --xxx}，per-media 用 {@code :xxx}；
 * per-media 覆盖实例级，故两处必须给同一组值——由本类保证。
 */
public final class VlcOptions {

    private VlcOptions() {}

    /**
     * 网络缓存（毫秒）——**首帧速度的第一决定因素**。
     *
     * <p>含义：VLC 要先缓冲够这么多毫秒的数据才渲染第一帧。设 1000 就是先黑屏约 1 秒。
     * 监控场景延迟可控（局域网 1~5ms、DDNS 直连通常 20~60ms），250ms 足以出首帧且能
     * 吸收一个 I 帧间隔的抖动；再往下压（如 50ms）会因单个 RTP 包乱序就触发重新缓冲，
     * 反而把"加载慢"变成"加载完就卡"。瞬抖稳定性由 freeze/rebuffer 状态机兜底。
     *
     * <p>调档参考：弱网/高丢包上调到 500~800；纯局域网可降到 150。
     */
    public static final int CACHING_MS = 250;

    /**
     * avcodec-skiploopfilter 档位——H.264 去块滤波（deblocking）是**解码耗时大户**，
     * 跳过后解码显著加快（首帧与持续流畅度同时受益）。
     *
     * <p>取值（VLC avcodec 模块）：0=不跳过 / 1=默认 / 2=非参考帧 / 3=B 帧 /
     * <b>4=非关键帧</b> / 5=全部跳过。
     * <br>选 4：关键帧(I 帧)仍做完整滤波保证基准画质，只对非关键帧跳过——
     * 画质损失肉眼几乎不可察（轻微块效应），解码耗时明显下降。
     * 若真机觉得画质偏软，降到 2 或 0（首帧会相应变慢）。
     */
    private static final int SKIP_LOOP_FILTER = 4;

    /** 子码流解码线程数：分辨率低（典型 640x360），多线程同步开销 > 并行收益，1 反而更快。 */
    private static final int THREADS_SUB = 1;
    /** 主码流解码线程数：1080p 需要并行解码，4 线程收益明显。 */
    private static final int THREADS_MAIN = 4;

    // ============ 实例级（LibVLC 构造参数，-- 前缀）============

    /**
     * 追加实例级参数。
     *
     * @param options      调用方的 option 列表（本方法只追加，不清空）
     * @param avcodecHw    avcodec-hw 取值（软解 disabled / 硬解 mediacodec / 自动 any）
     * @param useSubStream 是否子码流（决定解码线程数）
     */
    public static void addInstanceOptions(List<String> options, String avcodecHw,
                                          boolean useSubStream) {
        addInstanceOptions(options, avcodecHw, useSubStream, true, true);
    }

    /**
     * @param clockSynchroOff 是否关闭音视频时钟同步等待（false=音视频同流的 combined 模式）
     * @param decodeSpeedup   是否追加解码提速参数（抓帧保画质场景传 false）
     * @see #addInstanceOptions(List, String, boolean)
     */
    public static void addInstanceOptions(List<String> options, String avcodecHw,
                                          boolean useSubStream, boolean clockSynchroOff,
                                          boolean decodeSpeedup) {
        if (options == null) return;
        options.addAll(buildOptions("--", avcodecHw, useSubStream, clockSynchroOff, decodeSpeedup));
    }

    // ============ per-media（Media 选项，: 前缀，覆盖实例级）============

    /**
     * 追加 per-media 参数（视频-only 场景：关闭时钟同步以最快出首帧）。
     *
     * <p>必须与 {@link #addInstanceOptions} 给同一组值——per-media 覆盖实例级，
     * 两边不一致时"改了实例级却没变快"（历史坑）。由本类保证同源。
     */
    public static void addMediaOptions(Media media, String avcodecHw, boolean useSubStream) {
        addMediaOptions(media, avcodecHw, useSubStream, true, true);
    }

    /**
     * @param clockSynchroOff 是否关闭音视频时钟同步等待。
     *                        <b>true</b>=视频-only 流（音频另走独立 MP 或 --no-audio），
     *                        无需等音频时钟 → 解出帧立刻渲首帧，最快；
     *                        <b>false</b>=音视频同流（combined 模式，音频作主时钟），
     *                        必须保持同步，否则长时间播放会 A/V 漂移。
     * @param decodeSpeedup   是否追加解码提速参数（skiploopfilter/fast/drop-late-frames）。
     *                        抓帧（缩略图）传 false——它要画质而非速度，跳过滤波会让缩略图发糊。
     */
    public static void addMediaOptions(Media media, String avcodecHw, boolean useSubStream,
                                       boolean clockSynchroOff, boolean decodeSpeedup) {
        if (media == null) return;
        // Media.addOption 无批量接口，逐条下发（参数顺序无关）。
        for (String opt : buildOptions(":", avcodecHw, useSubStream, clockSynchroOff, decodeSpeedup)) {
            media.addOption(opt);
        }
    }

    // ============ 内部：单一参数表 ============

    /**
     * 生成完整参数表——实例级与 per-media 共用，只差前缀。
     * 任何调参只改这里，所有调用点自动生效。
     */
    private static List<String> buildOptions(String prefix, String avcodecHw,
                                             boolean useSubStream, boolean clockSynchroOff,
                                             boolean decodeSpeedup) {
        List<String> opts = new ArrayList<>();

        // ===== 传输 =====
        // RTSP 走 TCP：避免 UDP 丢包重传导致的首帧等待与花屏（代价是略高延迟，但更稳）。
        opts.add(prefix + "rtsp-tcp");

        // ===== 缓存（首帧速度第一决定因素）=====
        // 三个键同设：RTSP 走 live555/access 路径时实际读的是 rtsp-caching / live-caching，
        // network-caching 不保证覆盖——这正是"改了 network-caching 却没变快"的陷阱。
        opts.add(prefix + "network-caching=" + CACHING_MS);
        opts.add(prefix + "rtsp-caching=" + CACHING_MS);
        opts.add(prefix + "live-caching=" + CACHING_MS);

        // ===== 时钟 =====
        // clock-jitter=0：不等"目标抖动水位"，解出帧立刻渲染。
        opts.add(prefix + "clock-jitter=0");
        // clock-synchro=0：不做音视频时钟同步等待。
        // 仅视频-only 流可关；音视频同流（combined，音频作主时钟）必须保持，否则 A/V 漂移。
        if (clockSynchroOff) {
            opts.add(prefix + "clock-synchro=0");
        }

        // ===== 解码提速（抓帧场景跳过，保画质）=====
        if (decodeSpeedup) {
            opts.add(prefix + "avcodec-skiploopfilter=" + SKIP_LOOP_FILTER);
            // 允许非规范兼容的加速技巧（牺牲极少兼容性换解码速度）
            opts.add(prefix + "avcodec-fast");
            // 丢帧保实时：网络迟到的帧直接丢弃而非排队积压，杜绝"越播越延迟"。
            opts.add(prefix + "drop-late-frames");
            opts.add(prefix + "skip-frames");
        }

        // ===== 解码线程 =====
        // 按分辨率动态：子码流多线程同步开销 > 并行收益（更慢），主码流才需要并行。
        opts.add(prefix + "avcodec-threads=" + (useSubStream ? THREADS_SUB : THREADS_MAIN));

        // ===== 解码模式（来自用户设置）=====
        String hw = (avcodecHw == null || avcodecHw.isEmpty())
                ? ConfigManager.AVCODEC_HW_DISABLED : avcodecHw;
        opts.add(prefix + "avcodec-hw=" + hw);

        return opts;
    }
}
