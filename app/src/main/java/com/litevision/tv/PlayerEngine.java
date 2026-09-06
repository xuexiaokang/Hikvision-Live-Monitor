package com.litevision.tv;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PlayerEngine — 单个 MediaPlayer 的完整生命周期封装（可复用单元）。
 *
 * 设计（docs/system_design.md §4.1）：
 *   - 不绑定 Activity，只代理 LibVLC 实例 + 回调，单画面与多画面共用；
 *   - 内嵌连接超时、冻结检测（FREEZE_THRESHOLD_MS=10000 + 重新缓冲停滞判定，重试≤2）、主→子自动降级；
 *   - 所有 stop()/release() 必须在后台线程执行（防 VLC 死锁），play()/事件回调回主线程；
 *   - 网络/缓存选项在 LibVLC 实例级统一（Multi 用 --no-audio），引擎只按需补 per-media 选项。
 *
 * 生命周期设计（供 MultiView 池化复用）：
 *   play()           ：开始播放（首次会懒创建 MediaPlayer）；
 *   disconnect()     ：停止当前流并释放 MediaPlayer（保留 LibVLC/回调，可再次 play 复用）；
 *   releaseEngine()  ：完全释放（disconnect + 回调作废）。
 */
public class PlayerEngine {

    public interface Callback {
        /** 该通道已进入 Playing 状态（主线程回调） */
        void onPlaying(int channelIndex);

        /** 连接/播放持续失败，已放弃重试（主线程回调） */
        void onError(int channelIndex, String message);

        /** 画面冻结超过阈值（主线程回调，格子显示"画面冻结/重连中"） */
        void onFreeze(int channelIndex);

        /** 缓冲进度（主线程回调） */
        void onBuffering(int channelIndex, float pct);

        /**
         * 视频有效尺寸已更新（visible 尺寸/SAR 变化，主线程回调）。
         * 用于在 onNewVideoLayout 晚于 Playing 回调时，触发格子重新应用 center-crop，
         * 避免 transform 停留在首次 apply 时的旧比例/旧尺寸。
         */
        void onVideoSizeChanged(int channelIndex);
    }

    private static final String TAG = "PlayerEngine";
    /**
     * 连接超时（毫秒）——从 play() 开始到首帧的等待上限。
     *
     * <p>原值 15s/12s：保守设计，给慢网络留足时间。但监控场景下：
     * - 局域网直连 RTSP 握手通常 <2s，15s 纯属浪费——通道异常时用户要等 15 秒
     *   才看到"连接失败"提示；
     * - DDNS 域名直连通常 3~8s（含 DNS 解析 + TCP 探活 + RTSP 握手），8s 已足够；
     * - 真正决定首帧速度的是 RTSP 握手 + 等 I 帧，不是超时阈值——超时只是"失败判定点"。
     *
     * <p>降为 8s/6s：异常通道更快失败 → 更快走重试/降级/报错，用户感知是"闪一下就提示"
     * 而非"黑屏 15 秒"。重试逻辑（≤2 次 + 主→子降级）保证偶发慢通道不会被误杀。
     */
    private static final int CONNECT_TIMEOUT_MAIN = 8000;
    private static final int CONNECT_TIMEOUT_SUB = 6000;
    private static final int FREEZE_CHECK_INTERVAL = 2000;
    private static final int FREEZE_THRESHOLD_MS = 10000;
    private static final int REBUFFER_STALL_MS = 15000;
    private static final int MAX_RETRIES = 2;
    /**
     * Vout 重建踢延时（毫秒）。
     *
     * 宫格双击放大/退出全屏会物理搬移 ChannelTileView → TextureView detach/re-attach →
     * SurfaceTexture 被销毁并重建。VLC media 已 Playing 时只重新 setVideoSurface+attachViews
     * 不会让已开始输出的 video 管道主动把帧渲染到新 Surface（黑屏）。
     * 这里在 Surface 更换且 media 仍在播放时，延时 120ms 后按一次 pause()+play() 强制 VLC
     * 重新把自己的 vout 输出目标刷新为新 Surface（只重启渲染，不 setMedia，不重开连接，
     * 不影响 isActiveFor/池化复用语义）。120ms 让 onSurfaceTextureAvailable→attachVideoSurface
     * 先落地，避免抢在 Vout attach 完成前执行。
     */
    private static final long VOUT_KICK_DELAY_MS = 120L;

    private final LibVLC libVLC;
    private final Callback callback;
    /** libVLC avcodec-hw 取值（disabled / mediacodec / any），来自用户设置的解码模式。 */
    private final String avcodecHw;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService stopExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vlc-engine-stop");
                t.setDaemon(true);
                return t;
            });

    // ===== MediaPlayer + Surface =====
    private volatile MediaPlayer mediaPlayer;
    private Surface surface;
    private boolean surfaceReady = false;

    // ===== Vout 重建（Surface 更换时强制 VLC 重渲染，防宫格全屏黑屏）=====
    /** 是否已排期 voutKickRunnable（防抖：同一 surface 短时间重复 attach 只踢一次）。 */
    private boolean voutKickScheduled = false;
    private final Runnable voutKickRunnable = new Runnable() {
        @Override
        public void run() {
            voutKickScheduled = false;
            if (released || mediaPlayer == null) return;
            // 执行前（延时窗口内）发生过：pause/setVideoSurface(新)/release/停播 → 一律跳过，
            // 避免把"已按 user 意图暂停"的流强行拉起、或把刚换的新面踢错。
            if (!expectedPlaying || !isPlayingFrame() || !surfaceReady || surface == null) {
                return;
            }
            Log.i(TAG, "ch" + currentChannel + " 播放中更换了视频 Surface，强制 Vout 重建(pause+play)");
            try {
                // 先暂停：VLC 3.x 在 media 已 Playing 时切换视频输出面，video output 管道不会
                // 自动把下一帧渲染到新 Surface（黑屏）。pause+play 是"仅重启渲染、不重连取流"
                // 的最小手段——setMedia 才会打断 RTSP 连接（TEARDOWN 重握手 = 白等一轮）。
                mediaPlayer.pause();
                mediaPlayer.play();
            } catch (Throwable t) {
                Log.w(TAG, "vout kick error: " + t.getMessage());
            }
        }
    };

    // ===== Channel/URL state =====
    private int currentChannel = -1;
    private String primaryUrl;
    private String fallbackUrl;
    private boolean useSubStream = true;
    private int retryCount = 0;
    private boolean fallbackTried = false;
    /**
     * 已 start 到 VLC 的媒体 URL（play/disconnect 时更新，重试路径同步刷新）。
     *
     * 用途：给调用方（MultiViewActivity）做"重复 start"保护。{@link #play} 内部必定走
     * setMedia()+play()，而 libvlc 的 setMedia() 会先 stop 掉当前 input（即 TEARDOWN 刚
     * 握手成功的 RTSP 会话），等于把已经建好的连接推倒重来 → 白等一整轮 RTSP 握手 +
     * 一次 network-caching 缓冲。调用方 start 前先问 {@link #isActiveFor} 即可跳过。
     */
    private volatile String currentUrl = null;

    // ===== Playback state =====
    private volatile boolean expectedPlaying = false;
    private volatile boolean userStopped = true;
    private volatile boolean released = false;
    private volatile boolean isPlayingFlag = false;
    private volatile long lastFrameTimeMs = 0;
    /**
     * 本次 play() 的起始时间戳（毫秒），用于统计首帧耗时。
     * 仅作性能观测（日志），不参与任何播放/重试逻辑。
     */
    private volatile long playStartMs = 0;
    private boolean freezeDetecting = false;
    // 重新缓冲：Playing 后再次 Buffering。进度仍在推进时对冻结检测放行（网络自愈窗口），
    // 只有进度停滞 REBUFFER_STALL_MS 才判定失败，避免"不断缓冲/反复重连"。
    private volatile boolean rebuffering = false;
    private float lastBufferingPct = -1f;
    private volatile long lastBufferingChangeMs = 0;
    // 主动重建（stopMediaAndReplay）产生的旧媒体 Stopped 事件抑制开关。
    // libvlc 的 Stopped 事件由事件线程异步派发，可能晚于主线程恢复 userStopped=false 才到达；
    // 此时若按"意外停止"处理会误走 onMediaFailure → retryCount++ → 双重重播。
    // 重建 stop() 期间置位，直到下一次真正 Playing 才清除。
    private volatile boolean suppressStopped = false;

    // ===== 延迟重建（onMediaFailure → stopMediaAndReplay）的排期与作废 =====
    /**
     * 每次 {@link #play} 自增的世代号。
     *
     * 自愈重建是异步的两段式：onMediaFailure 先 postDelayed（1s/0.5s），再丢到 stopExecutor
     * 里异步 stop，最后回主线程 startMedia。任一段窗口内若发生了换源（翻页、主/子码流切换），
     * play() 已经给引擎换上了新 URL；此时再按旧 URL 重建会把旧通道画面盖到新格子上（串台），
     * 并额外 TEARDOWN 一条刚建好的新连接。用世代号在"执行时"比对即可作废过期重建。
     *
     * 注意：不能用"URL 是否相等"来判定——主码流失败降级到子码流时，重建 URL 本来就不等于
     * 当前 URL（那是合法降级），纯 URL 比较会把降级误判成过期而跳过，导致主→子降级失效。
     */
    private volatile int playGeneration = 0;
    /** 已排期、尚未执行的延迟重建任务（pause/disconnect 时取消，避免后台偷偷自动重启取流）。 */
    private final Runnable replayRunnable = new Runnable() {
        @Override
        public void run() {
            pendingReplayScheduled = false;
            if (released) return;
            final String url = pendingReplayUrlAtSchedule;
            final int gen = pendingReplayGeneration;
            if (url == null || gen != playGeneration) {
                Log.i(TAG, "ch" + currentChannel + " 延迟重建已作废（期间换源），跳过");
                return;
            }
            stopMediaAndReplay(url, gen);
        }
    };
    /** replayRunnable 排期时快照的目标 URL（重建 URL，可能是回退流）。 */
    private String pendingReplayUrlAtSchedule = null;
    /** replayRunnable 排期时快照的世代号。 */
    private int pendingReplayGeneration = -1;
    /** 是否已有排期中的延迟重建（仅用于日志与幂等判断）。 */
    private volatile boolean pendingReplayScheduled = false;

    // ===== 视频尺寸（来自 VLC onNewVideoLayout，供 TextureView center-crop 校正防花屏）=====
    private volatile int videoWidth = 0;
    private volatile int videoHeight = 0;
    // 真实有效尺寸（剔除宏块对齐 padding，如 1080→1088；SAR 归 1 前的有效像素）。
    // 用 visible/SAR 计算 aspect 才与画面实际比例一致，避免把 padding 行算进去导致底部脏带/比例偏差。
    private volatile int visibleWidth = 0;
    private volatile int visibleHeight = 0;
    private volatile int sarNum = 1;
    private volatile int sarDen = 1;
    // VLC 渲染进 Surface 的 buffer 尺寸（含宏块对齐 padding，如 1920x1088）。
    // visible < buffer 时，底部若干行是未初始化的脏数据（花屏带）——必须通过 transform 裁掉。
    private volatile int bufferWidth = 0;
    private volatile int bufferHeight = 0;

    // ===== Runnables (均为主线程) =====
    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (released || isPlayingFlag) return;
            if (!expectedPlaying) return;
            Log.w(TAG, "连接超时 ch=" + currentChannel + " useSub=" + useSubStream);
            onMediaFailure("连接超时");
        }
    };

    private final Runnable freezeDetector = new Runnable() {
        @Override
        public void run() {
            if (released || freezeDetecting || !expectedPlaying) return;

            // 连接阶段（尚未进入 Playing）不判冻结：首帧未到是正常现象，
            // 连接超时统一由 connectTimeout 判定；否则慢通道首帧 >阈值会
            // 被误判成"画面冻结"而反复 stop+重连（=不断缓冲/加载慢）。
            if (!isPlayingFlag) {
                handler.postDelayed(this, FREEZE_CHECK_INTERVAL);
                return;
            }

            // 重新缓冲中：优先看缓冲进度是否仍在推进，推进了就不算冻结（自愈窗口）。
            if (rebuffering) {
                long bufferingStagnant = System.currentTimeMillis() - lastBufferingChangeMs;
                if (lastBufferingChangeMs > 0 && bufferingStagnant >= REBUFFER_STALL_MS) {
                    Log.w(TAG, "重新缓冲停滞 " + bufferingStagnant + "ms (pct=" + lastBufferingPct
                            + ") ch=" + currentChannel + " → 视为失败");
                    freezeDetecting = true;
                    callback.onFreeze(currentChannel);
                    onMediaFailure("重新缓冲停滞");
                } else {
                    handler.postDelayed(this, FREEZE_CHECK_INTERVAL);
                }
                return;
            }

            long stagnant = System.currentTimeMillis() - lastFrameTimeMs;
            if (lastFrameTimeMs > 0 && stagnant >= FREEZE_THRESHOLD_MS) {
                Log.w(TAG, "画面冻结 " + stagnant + "ms (ch=" + currentChannel + ")");
                freezeDetecting = true;
                callback.onFreeze(currentChannel);
                onMediaFailure("画面冻结");
            } else {
                handler.postDelayed(this, FREEZE_CHECK_INTERVAL);
            }
        }
    };

    /**
     * @param libVLC    由调用方（MultiView/Player）初始化并注入；本引擎不负责 release LibVLC。
     * @param callback  播放事件回调（主线程）。
     * @param avcodecHw libVLC avcodec-hw 取值，由 {@link ConfigManager#getAvcodecHwValue()} 解析得到
     *                  （disabled=软解 / mediacodec=硬解 / any=自动）。
     *                  引擎本身不持有 Context，故由调用方注入，null 或空串回落为软解。
     */
    public PlayerEngine(LibVLC libVLC, Callback callback, String avcodecHw) {
        if (libVLC == null) {
            throw new IllegalArgumentException("libVLC 不能为 null");
        }
        this.libVLC = libVLC;
        this.callback = callback;
        this.avcodecHw = (avcodecHw == null || avcodecHw.isEmpty())
                ? ConfigManager.AVCODEC_HW_DISABLED : avcodecHw;
    }

    // ===== Public API =====

    /**
     * 开始播放（或切换码流）。必须主线程调用。
     *
     * @param channelIndex 通道索引
     * @param url          首选 URL（按 multi_use_sub / 单画面配置生成的子/主码流）
     * @param fallback     失败后的回退 URL（主码流失败 → 子码流；null 表示不回退）
     * @param useSub       是否子码流（决定连接超时判定）
     */
    public void play(int channelIndex, String url, String fallback, boolean useSub) {
        if (released) return;
        // 世代号自增：作废在此之前排期的所有延迟重建（翻页/切码流后旧 URL 的重建一律不再执行）。
        playGeneration++;
        cancelPendingReplay();
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(freezeDetector);
        currentChannel = channelIndex;
        primaryUrl = url;
        fallbackUrl = fallback;
        useSubStream = useSub;
        retryCount = 0;
        expectedPlaying = true;
        userStopped = false;
        isPlayingFlag = false;
        freezeDetecting = false;
        suppressStopped = false;
        rebuffering = false;
        lastBufferingPct = -1f;
        lastBufferingChangeMs = 0;
        lastFrameTimeMs = System.currentTimeMillis();

        // 切换到新 URL/新媒体前，**清零上一条码流的视频尺寸**，避免旧 onNewVideoLayout 残留
        // 尺寸（如 1920×1080）在校正时被误用 → 多画面翻页后用旧基准 fill，格子被放大/缩小/没填满
        // （真机"有的画面过大、有的画面过小"根因）。新源 onNewVideoLayout 回调后会重新上报最新尺寸
        // 并触发 onVideoSizeChanged → 格子以真实 visible 尺寸重新 fill。
        this.videoWidth = 0;
        this.videoHeight = 0;
        this.visibleWidth = 0;
        this.visibleHeight = 0;
        this.bufferWidth = 0;
        this.bufferHeight = 0;
        this.sarNum = 1;
        this.sarDen = 1;

        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer(libVLC);
            mediaPlayer.setEventListener(this::handleMediaEvent);
            attachVideoSurface();
        }
        // 注意不要用 setVideoScale(SURFACE_FILL)：它会把**含底部宏块 padding 脏行**的整个 buffer
        // 一起拉伸铺满格子（如 1080→1088 的 8 行脏行），导致格子底边出现花屏带（真机复现）。
        // 正确做法在 ChannelTileView.applyCropTransformIfNeeded：手动按"引擎权威 buffer + visibleH"
        // 做 fill（sx=tw/bufW、sy=th/visibleH、顶部对齐），既保证铺满格子又把底部 padding 脏行
        // 推到 view 之外裁掉 → 无缩放不均也无底边花屏。
        Log.i(TAG, "play ch=" + channelIndex + " url=" + maskUrl(url));
        startMedia(url);
    }

    /** 暂停（不终止连接）。 */
    public void pause() {
        if (released) return;
        expectedPlaying = false;
        // 取消已排期但尚未执行的延迟重建：否则退后台后约 0.5~1s 会在后台把流重新拉起来
        // （多画面退后台仍在取流 → 偷偷耗流量/耗电）。
        cancelPendingReplay();
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(freezeDetector);
        rebuffering = false;
        lastBufferingChangeMs = 0;
        currentUrl = null;
        final MediaPlayer mp = mediaPlayer;
        if (mp != null) {
            try { mp.pause(); } catch (Throwable ignored) {}
        }
        isPlayingFlag = false;
    }

    /**
     * 停止当前流并释放 MediaPlayer（后台线程执行，防死锁）。
     * 保留 LibVLC 与回调，可再次 {@link #play} 复用（池化语义）。
     */
    public void disconnect() {
        if (released) return;
        expectedPlaying = false;
        userStopped = true;
        isPlayingFlag = false;
        suppressStopped = false;
        cancelPendingReplay();
        rebuffering = false;
        lastBufferingChangeMs = 0;
        currentUrl = null;
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(freezeDetector);
        final MediaPlayer mp = mediaPlayer;
        stopExecutor.execute(() -> {
            try {
                if (mp != null) {
                    try { mp.stop(); } catch (Throwable ignored) {}
                    // 注意：不要在此 detachViews()。
                    // 池化复用下（翻页/replay）mediaPlayer 非空而走 play()→startMedia() 复用同一个
                    // MediaPlayer；若这里 detach 了 Vout，则复用后 onNewVideoLayout 回调丢失，
                    // 新码流尺寸永远不上报 → 多画面各格子拿不到真实 visible 尺寸 → 画面被放大/缩小、
                    // 没填满（真机"有的画面过大、有的过小"根因之一）。VLC setMedia+play 换源会自然
                    // 更新 Vout 与 layout 回调，无需 detach。彻底释放（releaseEngine）时才 detach+release。
                }
            } catch (Throwable t) {
                Log.w(TAG, "disconnect stop error: " + t.getMessage());
            }
        });
    }

    /**
     * 完全释放引擎（disconnect + 回调作废）。
     * LibVLC 由调用方（MultiView/Player）统一 release。
     */
    public void releaseEngine() {
        released = true;
        expectedPlaying = false;
        userStopped = true;
        isPlayingFlag = false;
        suppressStopped = false;
        cancelPendingReplay();
        currentUrl = null;
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(freezeDetector);
        final MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        stopExecutor.execute(() -> {
            try {
                if (mp != null) {
                    try { mp.stop(); } catch (Throwable ignored) {}
                    try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
                    mp.release();
                }
            } catch (Throwable t) {
                Log.w(TAG, "release mediaPlayer error: " + t);
            }
        });
    }

    /** 由 TextureView.SurfaceTextureAvailable 回调调用，绑定渲染 Surface。 */
    public void setVideoSurface(final Surface newSurface) {
        surface = newSurface;
        surfaceReady = (newSurface != null);
        // 播放中更换了视频 Surface（宫格全屏搬移等场景）：vout 目标已变，但 VLC 不会自动把
        // 帧渲染到新面 → 排期一次"Vout 重建踢"（pause+play 重渲染，不重连）；同时作废旧踢。
        if (voutKickScheduled) {
            handler.removeCallbacks(voutKickRunnable);
            voutKickScheduled = false;
        }
        if (expectedPlaying && surfaceReady && isPlayingFlag) {
            voutKickScheduled = true;
            handler.postDelayed(voutKickRunnable, VOUT_KICK_DELAY_MS);
        }
        if (mediaPlayer != null) {
            attachVideoSurface();
        }
        if (expectedPlaying && surfaceReady) {
            // Surface 恢复后立即续播（如从后台回来）
            final MediaPlayer mp = mediaPlayer;
            if (mp != null && !isPlayingFlag) {
                try { mp.play(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 由 TextureView.SurfaceTextureDestroyed 回调调用：解除视频输出。 */
    public void releaseVideoSurface() {
        surface = null;
        surfaceReady = false;
        // Surface 已销毁：作废排期中的 Vout 重建踢（下个新 Surface attach 时再排新踢）。
        if (voutKickScheduled) {
            handler.removeCallbacks(voutKickRunnable);
            voutKickScheduled = false;
        }
        final MediaPlayer mp = mediaPlayer;
        if (mp != null) {
            try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 供 {@link #voutKickRunnable} 判定用：记录当前是否已进入 Playing 出帧态。
     * 与 isPlayingFlag 的区别：本方法同时要求非 rebuffering/期望播放，避免在 VLC 正在
     * 重新缓冲、或已被用户暂停的状态下强行 pause+play 打断自愈。
     */
    private boolean isPlayingFrame() {
        return isPlayingFlag && !rebuffering;
    }

    /** 由格子 TextureView.onSurfaceTextureUpdated 回调：每帧刷新用于冻结检测。 */
    public void onFrameRendered() {
        lastFrameTimeMs = System.currentTimeMillis();
    }

    /** 是否当前处于播放/期望播放状态。 */
    public boolean isExpectingPlayback() {
        return expectedPlaying;
    }

    /**
     * 该引擎是否已经在播（或正在连）指定的 URL。
     *
     * 调用方在启动一个槽位前先问一次：返回 true 就跳过 start，避免把已在正常连接/播放的
     * 流推倒重建（见 {@link #currentUrl} 的说明）。暂停（expectedPlaying=false）、
     * 断开、彻底失败后都会返回 false，不会挡住正常的恢复播放。
     *
     * @param url 目标 URL（主码流或子码流，与 {@link #play} 传入的 url 同一拼法）
     * @return true = 正在播或正在连该 URL，调用方应跳过重复 start
     */
    public boolean isActiveFor(String url) {
        if (released || !expectedPlaying || url == null) {
            return false;
        }
        return url.equals(currentUrl);
    }

    /** 是否已彻底释放。 */
    public boolean isReleased() {
        return released;
    }

    /** 当前视频实际宽（0=未知）。 */
    public int getVideoWidth() {
        return videoWidth;
    }

    /** 当前视频实际高（0=未知）。 */
    public int getVideoHeight() {
        return videoHeight;
    }

    /** 像素宽高比 sarNum（用于 aspect 修正，默认 1）。 */
    public int getSarNum() {
        return sarNum;
    }

    /** 像素宽高比 sarDen（用于 aspect 修正，默认 1）。 */
    public int getSarDen() {
        return sarDen;
    }

    /**
     * 真实有效宽（剔除宏块对齐 padding，0=未知）。
     *
     * 与 {@link #getBufferWidth()} 的差值就是需要裁掉的 padding 列。只用于算
     * "padding 相对占比"（纯比例，与 VLC vout 把 buffer 缩放到多大无关），
     * 不用于直接算缩放系数——缩放基准必须是 SurfaceTexture 实际渲染尺寸。
     */
    public int getVisibleWidth() {
        return visibleWidth;
    }

    /**
     * 真实有效高（剔除宏块对齐 padding，如 1088→1080，0=未知）。
     * 见 {@link #getVisibleWidth()} 关于"仅用于算 padding 占比"的说明。
     */
    public int getVisibleHeight() {
        return visibleHeight;
    }

    /** VLC 渲染 buffer 的宽（含宏块对齐 padding，0=未知）。 */
    public int getBufferWidth() {
        return bufferWidth;
    }

    /** VLC 渲染 buffer 的高（含宏块对齐 padding，0=未知）。 */
    public int getBufferHeight() {
        return bufferHeight;
    }

    // ===== Internal =====

    private void attachVideoSurface() {
        if (mediaPlayer == null || surface == null) return;
        try {
            IVLCVout vout = mediaPlayer.getVLCVout();
            vout.setVideoSurface(surface, null);
            vout.attachViews(new IVLCVout.OnNewVideoLayoutListener() {
                @Override
                public void onNewVideoLayout(IVLCVout v, int width, int height,
                                             int visibleWidth, int visibleHeight,
                                             int sarNum, int sarDen) {
                    Log.d(TAG, "onNewVideoLayout: buffer=" + width + "x" + height
                            + " visible=" + visibleWidth + "x" + visibleHeight
                            + " sar=" + sarNum + "/" + sarDen + " (ignored as layout, saved for crop)");
                    // 记录 buffer 尺寸（含 padding）与真实有效视频尺寸（剔除对齐 padding）+ SAR。
                    // width/height 常含宏块对齐(如 1080→1088)，底部 padding 行是脏数据，显示时必须裁掉。
                    if (width > 0 && height > 0) {
                        PlayerEngine.this.bufferWidth = width;
                        PlayerEngine.this.bufferHeight = height;
                    }
                    if (visibleWidth > 0 && visibleHeight > 0) {
                        PlayerEngine.this.visibleWidth = visibleWidth;
                        PlayerEngine.this.visibleHeight = visibleHeight;
                    }
                    int newW, newH, newSN = 1, newSD = 1;
                    if (visibleWidth > 0 && visibleHeight > 0) {
                        newW = visibleWidth;
                        newH = visibleHeight;
                    } else if (width > 0 && height > 0) {
                        newW = width;
                        newH = height;
                    } else {
                        newW = PlayerEngine.this.videoWidth;
                        newH = PlayerEngine.this.videoHeight;
                    }
                    if (sarNum > 0 && sarDen > 0) {
                        newSN = sarNum;
                        newSD = sarDen;
                    }
                    boolean changed = newW != PlayerEngine.this.videoWidth
                            || newH != PlayerEngine.this.videoHeight
                            || newSN != PlayerEngine.this.sarNum
                            || newSD != PlayerEngine.this.sarDen;
                    PlayerEngine.this.videoWidth = newW;
                    PlayerEngine.this.videoHeight = newH;
                    PlayerEngine.this.sarNum = newSN;
                    PlayerEngine.this.sarDen = newSD;
                    // 尺寸变化后回主线程通知格子重算 transform（onNewVideoLayout 常在 Playing 之后才到，
                    // 若此时尺寸较首次 apply 时不同，必须重新 center-crop，否则底部比例偏差→脏带残留）
                    if (changed) {
                        final int idx = PlayerEngine.this.currentChannel;
                        handler.post(() -> {
                            if (!released && callback != null) {
                                callback.onVideoSizeChanged(idx);
                            }
                        });
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "attachVideoSurface error: " + t.getMessage());
        }
    }

    private void startMedia(final String url) {
        if (mediaPlayer == null || released) return;
        try {
            currentUrl = url;
            Media media = new Media(libVLC, Uri.parse(url));
            // 低延迟/解码参数统一取自 VlcOptions（单一事实源）——
            // 原先这些参数硬编码在此处，且与 MultiView/Player/RtspFrameGrabber 的实例级
            // 参数各自维护一份，靠注释约定"两处一起改"，极易漏改且漏改后症状隐蔽。
            VlcOptions.addMediaOptions(media, avcodecHw, useSubStream);
            media.addOption(":ipv4-timeout=5000");
            mediaPlayer.setMedia(media);
            media.release();

            // 首帧耗时埋点：从 play() 调用到 Playing 事件的真实墙钟时间，
            // 用于量化"加载速度"（RTSP 握手 + 等 I 帧 + 解码 + 渲染 全链路）。
            playStartMs = System.currentTimeMillis();
            mediaPlayer.play();
            Log.i(TAG, "ch" + currentChannel + " play() url=" + maskUrl(url));

            handler.removeCallbacks(connectTimeout);
            handler.removeCallbacks(freezeDetector);
            int timeout = useSubStream ? CONNECT_TIMEOUT_SUB : CONNECT_TIMEOUT_MAIN;
            handler.postDelayed(connectTimeout, timeout);
            handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);
        } catch (Exception e) {
            Log.e(TAG, "startMedia error", e);
            onMediaFailure("播放启动失败: " + e.getMessage());
        }
    }

    private void handleMediaEvent(MediaPlayer.Event event) {
        switch (event.type) {
            // 播放位置推进 = 视频正在出帧，刷新冻结检测基准时间（多路共用引擎同样问题）。
            // 原实现只靠 Playing 事件打点 lastFrameTimeMs，播放中从不更新，
            // 正常播放满 FREEZE_THRESHOLD_MS(10s) 必被误判"画面冻结"→ 重建 → 偶发黑屏。
            case MediaPlayer.Event.TimeChanged:
                if (isPlayingFlag) {
                    lastFrameTimeMs = System.currentTimeMillis();
                    // 重新缓冲中收到播放时间推进 = 画面确实在向观众恢复（VLC 边缓冲边解码出帧）。
                    // 刷新停滞判定基准，避免"缓冲进度百分比不动/但时间在动"的缓慢恢复被误判为停滞。
                    if (rebuffering && lastBufferingChangeMs > 0) {
                        lastBufferingChangeMs = System.currentTimeMillis();
                    }
                }
                break;

            case MediaPlayer.Event.Opening:
                break;

            case MediaPlayer.Event.Buffering:
                if (!isPlayingFlag) {
                    final float pct = event.getBuffering();
                    handler.post(() -> {
                        if (!isPlayingFlag && !released && callback != null) {
                            callback.onBuffering(currentChannel, pct);
                        }
                    });
                } else {
                    // Playing 后再次 Buffering = 重新缓冲：允许 VLC 自愈，
                    // 冻结检测转入"进度停滞判定"分支（见 freezeDetector）。
                    final float pct = event.getBuffering();
                    // VLC 缓冲恢复到 ≥99% 视为已自愈：libvlc 3.x 中重新缓冲完成
                    // 并不保证会再发 Playing 事件，若不清除 rebuffering 状态，
                    // 会在 15s 后被误判"重新缓冲停滞"而重建 → 周期性黑屏重试。
                    if (pct >= 99f) {
                        if (rebuffering) {
                            Log.i(TAG, "ch" + currentChannel + " 重新缓冲完成 (pct=" + (int) pct + "%)");
                            rebuffering = false;
                            lastBufferingPct = -1f;
                            lastBufferingChangeMs = 0;
                            lastFrameTimeMs = System.currentTimeMillis();
                        }
                        break;
                    }
                    boolean becameRebuffering = !rebuffering;
                    rebuffering = true;
                    if (pct > lastBufferingPct) {
                        lastBufferingPct = pct;
                        lastBufferingChangeMs = System.currentTimeMillis();
                    } else if (lastBufferingChangeMs == 0) {
                        lastBufferingPct = pct;
                        lastBufferingChangeMs = System.currentTimeMillis();
                    }
                    if (becameRebuffering) {
                        Log.w(TAG, "ch" + currentChannel + " 重新缓冲开始 (pct=" + (int) pct + "%)");
                        handler.removeCallbacks(freezeDetector);
                        handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);
                    }
                }
                break;

            case MediaPlayer.Event.Playing:
                // 首帧耗时：play() → Playing 的真实墙钟时间（RTSP 握手 + 等 I 帧 + 解码 + 渲染）。
                // 用于量化"加载速度"，是本次低延迟优化的效果度量点。
                long firstFrameMs = (playStartMs > 0)
                        ? (System.currentTimeMillis() - playStartMs) : -1;
                Log.i(TAG, "ch" + currentChannel + " Playing 首帧耗时=" + firstFrameMs
                        + "ms useSub=" + useSubStream + " retry=" + retryCount);
                handler.removeCallbacks(connectTimeout);
                isPlayingFlag = true;
                // 新播放态已建立：解除旧媒体 Stopped 抑制，恢复正常的意外停止重试语义。
                suppressStopped = false;
                rebuffering = false;
                lastBufferingPct = -1f;
                lastBufferingChangeMs = 0;
                lastFrameTimeMs = System.currentTimeMillis();
                handler.removeCallbacks(freezeDetector);
                handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);
                handler.post(() -> {
                    if (!released && callback != null) callback.onPlaying(currentChannel);
                });
                break;

            case MediaPlayer.Event.Paused:
                Log.d(TAG, "ch" + currentChannel + " Paused");
                break;

            case MediaPlayer.Event.Stopped:
                Log.i(TAG, "ch" + currentChannel + " Stopped expectedPlaying=" + expectedPlaying
                        + " userStopped=" + userStopped + " suppressStopped=" + suppressStopped);
                isPlayingFlag = false;
                handler.removeCallbacks(freezeDetector);
                // 主动重建产生的旧媒体 Stopped（异步派发晚到）一律忽略，不当作意外停止。
                if (suppressStopped) {
                    handler.removeCallbacks(connectTimeout);
                    break;
                }
                if (!released && expectedPlaying && !userStopped) {
                    // 非用户停止的意外停止 → 走失败重试
                    onMediaFailure("流被意外终止");
                }
                break;

            case MediaPlayer.Event.EndReached:
                Log.i(TAG, "ch" + currentChannel + " EndReached — 重连中");
                isPlayingFlag = false;
                expectedPlaying = false;
                rebuffering = false;
                handler.removeCallbacks(freezeDetector);
                expectedPlaying = true;
                onMediaFailure("流结束");
                break;

            case MediaPlayer.Event.EncounteredError:
                Log.e(TAG, "ch" + currentChannel + " EncounteredError");
                isPlayingFlag = false;
                rebuffering = false;
                handler.removeCallbacks(connectTimeout);
                onMediaFailure("播放错误");
                break;

            default:
                break;
        }
    }

    /**
     * 连接失败统一处理：优先重试同 URL（≤2 次）→ 尝试回退码流 → 最终回调 onError。
     * 全部在主线程执行（由 connectTimeout / 事件回调触发）。
     */
    private void onMediaFailure(final String reason) {
        if (released) return;
        // 兜底：无论失败走重试/降级/彻底放弃，都解除旧的 Stopped 抑制，
        // 避免旧媒体的 Stopped 事件在后续重建窗口外被误吞/误放。
        suppressStopped = false;
        rebuffering = false;
        lastBufferingChangeMs = 0;
        Log.w(TAG, "失败判定: " + reason + " ch=" + currentChannel
                + " retry=" + retryCount + "/" + MAX_RETRIES + " fallbackTried=" + fallbackTried);

        // 1) 若失败发生在首选项码流上且还有重试名额 → 先重试同 URL
        if (!fallbackTried && retryCount < MAX_RETRIES) {
            retryCount++;
            scheduleReplay(primaryUrl, 1000);
            return;
        }

        // 2) 尝试回退码流（主→子自动降级；fallback 为 null 或同 URL 则跳过）
        if (!fallbackTried && fallbackUrl != null && !fallbackUrl.equals(primaryUrl)) {
            fallbackTried = true;
            retryCount = 0;
            Log.i(TAG, "主码流失败 → 降级到回退流（ch=" + currentChannel + ")");
            scheduleReplay(fallbackUrl, 500);
            return;
        }

        // 3) 彻底失败
        expectedPlaying = false;
        userStopped = true;
        isPlayingFlag = false;
        handler.removeCallbacks(freezeDetector);
        String msg = reason + "（通道离线或 RTSP 不可达）";
        Log.w(TAG, "放弃重试 ch=" + currentChannel + " reason=" + msg);
        if (callback != null) callback.onError(currentChannel, msg);
    }

    /**
     * 排期一次延迟重建，并快照当前世代号。
     *
     * 具名 Runnable（{@link #replayRunnable}）而非匿名 lambda，是为了让
     * {@link #cancelPendingReplay} 能用 handler.removeCallbacks 精确取消——
     * 匿名 lambda 每次都是新对象，退后台时无法取消，会在后台偷偷重启取流。
     *
     * @param url      重建使用的 URL（首选流或回退流）
     * @param delayMs  延迟毫秒数
     */
    private void scheduleReplay(final String url, final long delayMs) {
        if (pendingReplayScheduled) {
            Log.d(TAG, "ch" + currentChannel + " 已有排期中的重建，先取消旧的");
        }
        cancelPendingReplay();
        pendingReplayUrlAtSchedule = url;
        pendingReplayGeneration = playGeneration;
        pendingReplayScheduled = true;
        handler.postDelayed(replayRunnable, delayMs);
    }

    /** 取消尚未执行的延迟重建（pause/disconnect/releaseEngine/新 play 时调用）。 */
    private void cancelPendingReplay() {
        handler.removeCallbacks(replayRunnable);
        pendingReplayScheduled = false;
        pendingReplayUrlAtSchedule = null;
        pendingReplayGeneration = -1;
    }

    /**
     * 主动重建：异步 stop 当前流 → 回主线程按同一 URL 重新起播。
     *
     * @param url        重建使用的 URL
     * @param generation 排期时快照的世代号；若执行时已变化说明期间换过源，本次重建作废
     */
    private void stopMediaAndReplay(final String url, final int generation) {
        if (released) return;
        expectedPlaying = true;
        // 主动重建：在异步 mp.stop() 前必须置 userStopped=true，
        // 否则 VLC 的 Stopped 事件会命中 handleMediaEvent 中
        // "!userStopped → onMediaFailure('流被意外终止')" 分支，
        // 造成"重建的同时又触发一次重建"的双重重播竞态（反复缓冲）。
        userStopped = true;
        // 抑制旧媒体晚到的 Stopped 事件（事件线程异步派发，可能晚于主线程恢复 userStopped=false）。
        suppressStopped = true;
        rebuffering = false;
        lastBufferingChangeMs = 0;
        lastFrameTimeMs = System.currentTimeMillis();
        final MediaPlayer mp = mediaPlayer;
        stopExecutor.execute(() -> {
            try {
                if (mp != null) {
                    // 只 stop，不 detachViews()：detach 会摘掉 Vout 与 OnNewVideoLayoutListener，
                    // 而重建路径不会再挂回来（startMedia 不重挂，play() 只在 mediaPlayer==null 时
                    // 才 attachVideoSurface），结果 onNewVideoLayout 永久不再触发 →
                    // buffer/visible 尺寸恒为 0 → ChannelTileView 复位成 identity 矩阵 →
                    // 格子不再铺满（"有的放大有的缩小"在第一次自动重连后复发）。
                    // 与 disconnect() 的语义保持一致：VLC setMedia+play 换源会自然更新 Vout 与
                    // layout 回调，无需 detach；只有彻底释放（releaseEngine）才 detach。
                    try { mp.stop(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                Log.w(TAG, "stop error: " + t.getMessage());
            }
            handler.post(() -> {
                if (released) return;
                // 异步 stop 窗口内若发生了翻页/切码流（play() → playGeneration++），本次重建已过期：
                // 新源已经由 play() 起好了，再按旧 URL 重建会串台，并 TEARDOWN 一条刚建好的连接。
                if (generation != playGeneration) {
                    Log.i(TAG, "ch" + currentChannel + " 重建中止：期间已换源，放弃旧 URL 重建");
                    // 归还正常的"意外停止重试"语义给当前这条新流（suppressStopped 是本轮重建置位的）
                    suppressStopped = false;
                    userStopped = false;
                    return;
                }
                // 进入新的播放态前恢复 userStopped=false：
                // 之后真正意外的 Stopped 仍应走"流被意外终止"重试链。
                userStopped = false;
                startMedia(url);
            });
        });
    }

    private static String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll("://.*@", "://***@");
    }
}