package com.litevision.tv;

import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen RTSP player using libVLC with DUAL-MediaPlayer architecture.
 *
 * ARCHITECTURE: One LibVLC instance, two independent MediaPlayers:
 *   - Video MP: plays RTSP with ":no-audio" — video only, proven stable
 *   - Audio MP: plays same RTSP with ":no-video" — audio only, best-effort
 *
 * Because each MediaPlayer has its own decoder/output threads, an audio
 * deadlock in the Audio MP CANNOT block the Video MP. This is the fix for
 * "enabling audio freezes video" on the Xiaomi box.
 *
 * Toggle audio = start/stop Audio MP only (instant, no video interruption).
 *
 * HARD-WON LESSONS:
 * 1. SurfaceTexture managed by us (setVideoSurface) — VLC must not touch View layout.
 * 2. Freeze detection uses expectedPlaying (not VLC's isPlaying which lies during deadlocks).
 * 3. All stop/release on background threads — stop() blocks when deadlocked.
 * 4. AudioFocus required on some Android TV boxes or AudioTrack won't initialize.
 */
public class PlayerActivity extends AppCompatActivity {

    // ===== Intent 参数协议（与 MultiViewActivity / MainActivity 共享）=====
    /** 通道索引（0-based），可选，默认 0 */
    public static final String EXTRA_CHANNEL_INDEX = "channel_index";
    /** 码流偏好："sub"/"main"/"auto"（auto=回退全局 use_sub_stream） */
    public static final String EXTRA_STREAM_TYPE = "stream_type";
    /** 是否来自多画面全屏进入（true：双击退出返回多画面；false：普通单画面无此行为） */
    public static final String EXTRA_FROM_MULTI = "from_multi";

    private static final String TAG = "LiteVisionPlayer";
    private static final int OVERLAY_DURATION = 3000;
    private static final int MAIN_TIMEOUT = 15000;
    private static final int SUB_TIMEOUT = 12000;
    private static final int FREEZE_CHECK_INTERVAL = 2000;
    private static final int FREEZE_THRESHOLD_MS = 10000;
    /** 重新缓冲（Playing 后又 Buffering）时，缓冲进度连续停滞多久判定"卡死"并触发重建。 */
    private static final int REBUFFER_STALL_MS = 15000;
    private static final int MAX_FREEZE_RETRIES = 2;
    private static final int AUDIO_START_TIMEOUT = 8000;
    private static final int AUDIO_START_DELAY_MS = 4000; // 视频PLAYING后延迟启动音频，避免双RTSP同时握手

    // ===== Video engine (proven stable with :no-audio) =====
    private volatile LibVLC libVLC = null;
    private volatile MediaPlayer videoMediaPlayer = null;

    // ===== Audio engine — Android MediaPlayer (NOT VLC) =====
    // VLC's audio output modules (opensles/audiotrack) both fail on Xiaomi Box 5:
    //   - opensles: SELinux blocks vendor prop access → AudioTrack stop, 0 frames delivered
    //   - audiotrack: creates AudioTrack but never writes PCM → buffer timeout
    // Android MediaPlayer handles audio output via the system framework, bypassing
    // VLC's broken audio modules entirely. Video stays on VLC (proven stable).
    private android.media.MediaPlayer audioAndroidMP = null;
    private boolean audioEnabled = true;       // DEFAULT ON — audio enabled by default
    private volatile boolean audioStarted = false;   // Audio got prepared/playing
    private volatile boolean audioFailed = false;     // Audio errored/stopped

    // ===== Fallback: SINGLE combined MP (video + audio in one MP) =====
    // Used when:
    //   a) Hikvision NVR rejects 2nd RTSP connection (per-channel conn limit)
    //   b) Separate audio LibVLC fails on this device
    // When enabled: we rebuild videoMP WITHOUT :no-audio, so VLC plays both,
    // then disable audioMP entirely (risk: audio may still cause freezes,
    // so we only do this as user-initiated opt-in via long-press INFO key).
    private volatile boolean useCombinedMode = false;  // DEFAULT=dual-MP: video stable, audio separate

    // ===== Surface =====
    private TextureView surfaceView;
    private Surface mVideoSurface = null;
    private boolean mSurfaceReady = false;
    private boolean mPendingPlay = false;

    // ===== 视频尺寸（onNewVideoLayout，供单画面"等比 contain + 裁 padding 脏行"）=====
    // libVLC 会把含宏块对齐 padding（如 1080→1088，底部若干行为脏数据）的解码 buffer 拉伸铺满
    // Surface，直接 1:1 显示就会在底部露出花屏带（多画面已修，单画面同根因）。
    private volatile int mVideoBufW = 0;
    private volatile int mVideoBufH = 0;
    private volatile int mVideoVisW = 0;
    private volatile int mVideoVisH = 0;
    private volatile int mVideoSarNum = 1;
    private volatile int mVideoSarDen = 1;

    /**
     * SurfaceTexture 的实际渲染尺寸（VLC 设完 buffer geometry 后更新）。
     *
     * 单画面同样有多画面那条"reported 尺寸残留"的坑：切主/子码流时 onNewVideoLayout 可能晚到或带旧值，
     * 用它算 cover 会把实际 surface 缩小 → 没铺满 + 底部 padding 花屏带。因此 cover 的基准必须用
     * SurfaceTexture 真实尺寸（= 屏幕上真正显示的东西），与多画面 ChannelTileView 同一套修法。
     */
    private volatile int mSurfaceW = 0;
    private volatile int mSurfaceH = 0;

    /** 共享的 onNewVideoLayout 监听：记录尺寸并触发画面适配。 */
    private final IVLCVout.OnNewVideoLayoutListener videoLayoutListener = new IVLCVout.OnNewVideoLayoutListener() {
        @Override
        public void onNewVideoLayout(IVLCVout v, int width, int height,
                                     int visibleWidth, int visibleHeight,
                                     int sarNum, int sarDen) {
            Log.d(TAG, "onNewVideoLayout: buffer=" + width + "x" + height
                    + " visible=" + visibleWidth + "x" + visibleHeight
                    + " sar=" + sarNum + "/" + sarDen);
            if (width > 0 && height > 0) {
                mVideoBufW = width;
                mVideoBufH = height;
            }
            if (visibleWidth > 0 && visibleHeight > 0) {
                mVideoVisW = visibleWidth;
                mVideoVisH = visibleHeight;
            }
            if (sarNum > 0 && sarDen > 0) {
                mVideoSarNum = sarNum;
                mVideoSarDen = sarDen;
            }
            runOnUiThread(PlayerActivity.this::applyVideoFit);
        }
    };

    // ===== 触控支持（手机/平板/车机）=====
    // 触屏设备没有遥控器，只能用触摸手势操作：
    //   单击 = 显示/隐藏通道信息, 双击 = 播放/暂停,
    //   左右滑动 = 上/下一个通道, 上下滑动 = 音量 +/-
    private GestureDetector gestureDetector = null;
    private static final int SWIPE_THRESHOLD = 100;
    private static final int SWIPE_VELOCITY_THRESHOLD = 100;

    /**
     * 由屏幕尺寸自适应计算的滑动阈值（像素）。
     *
     * 手机屏幕窄（如 720px 宽），固定 100px 阈值会导致"划不动"——用户得用力划很远才被识别；
     * 平板/折叠屏大，阈值太小又会误触。取屏幕短边的 8%：手机约 50~60px、平板约 80~100px，
     * 既容易触发又避免误触。
     */
    private int swipeThreshold = SWIPE_THRESHOLD;

    // ===== 多画面进入的独立单画面页：双击退出返回多画面 =====
    /** 由多画面独立取流进入（EXTRA_FROM_MULTI=true）。仅该值时，单画面页双击才 finish() 返回多画面。 */
    private boolean fromMulti = false;

    // 手写双击检测（与 MultiViewActivity 同一套：Activity 顶部 dispatchTouchEvent，绝不被子 View 拦截）
    private long mDLastTimeMs = 0L;
    private float mDLastDownX = -1f;
    private float mDLastDownY = -1f;
    private static final long DBL_TAP_WINDOW_MS = 320L;   // 两击间隔上限
    private static final float DBL_TAP_SLOP_PX = 120f;     // 两击坐标偏移容忍
    private long mManualDoubleTapAtMs = 0L;                // 手写双击命中时刻（抑制第一击的单击 overlay）
    private long mLastDblDownTime = -1L;                    // 同一物理事件去重

    // ===== 缩略图截图（"真实播放帧做缩略图"方案）=====
    /**
     * Playing 后 2.5s 用 PixelCopy 截当前帧 → {@link ThumbnailCache}。
     * 延时的目的：让首帧/缓冲彻底结束，避免截到过渡帧；2.5s 是经验值
     * （首帧耗时 + 缓冲恢复；缓存档位见 {@link VlcOptions#CACHING_MS}）。
     */
    private static final long THUMB_CAPTURE_DELAY_MS = 2500L;
    /** 本 Activity 实例只截一次（避免换源/onResume 重触发反复写盘）。 */
    private boolean thumbnailCaptured = false;
    private final Runnable thumbnailCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (isReleased || thumbnailCaptured) {
                return;
            }
            if (surfaceView == null || !surfaceView.isAvailable()) {
                Log.w(TAG, "缩略图截图跳过：surfaceView 不可用");
                return;
            }
            thumbnailCaptured = true;
            Log.i(TAG, "🎬 截缩略图 ch" + currentChannelIndex
                    + " (surface " + surfaceView.getWidth() + "x" + surfaceView.getHeight() + ")");
            ThumbnailCapture.captureToCacheFile(PlayerActivity.this, surfaceView, currentChannelIndex);
        }
    };

    // ===== Config & state =====
    private ConfigManager config;
    private int currentChannelIndex = 0;
    private TextView overlayChannel;
    private TextView overlayError;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isPlaying = false;
    private boolean useSubStream = false;
    private boolean isReleased = false;
    private boolean mainStreamFailed = false;
    private int connectStartTime = 0;
    private int mFreezeRetryCount = 0;

    // Playback state (NOT VLC's isPlaying, which lies during deadlocks)
    private volatile boolean expectedPlaying = false;
    private volatile boolean userStop = false;

    // Frame freeze detection
    private volatile long lastFrameTimeMs = 0;
    private boolean freezeDetected = false;

    // 主动重建（stopVideoAsync→playChannel）产生的旧媒体 Stopped 事件抑制开关。
    // libvlc 的 Stopped 事件异步派发，可能晚于主线程恢复 userStop=false 才到达；
    // 此时若按"意外停止"处理会误走重试链 → 双重重播（同 PlayerEngine 修复）。
    // stopVideoAsync 期间置位，直到下一次真正 Playing 才清除。
    private volatile boolean suppressStopped = false;

    // ===== 重新缓冲（Playing 后再次 Buffering）状态 =====
    // 网络瞬卡/上游抖动时 VLC 会进入"重新缓冲"并自愈：此时若仍按 6s 无帧就判定冻结，
    // 会把"刚恢复中的流"反复掐断重建，表现为"不断缓冲/画面反复重连"。
    // 修复：缓冲进度仍在推进时对冻结检测放行；只有缓冲进度停滞 REBUFFER_STALL_MS 才重建。
    private volatile boolean rebuffering = false;
    private float lastBufferingPct = -1f;
    private volatile long lastBufferingChangeMs = 0;

    // Audio focus
    private AudioManager audioManager;
    // NON-NULL focus change listener — critical for Xiaomi TV boxes which REJECT
    // focus requests that pass null as listener (silently muted!).
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            Log.i(TAG, "🎧 AudioFocus: GAIN — unmuting, volume max");
                            try {
                                if (audioManager != null) {
                                    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
                                }
                                if (audioAndroidMP != null) { try { audioAndroidMP.setVolume(1.0f, 1.0f); } catch (Throwable ignored) {} }
                                if (useCombinedMode && videoMediaPlayer != null) videoMediaPlayer.setVolume(100);
                            } catch (Throwable ignored) {}
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            Log.w(TAG, "🎧 AudioFocus: LOSS (transient/muted by system)");
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            Log.w(TAG, "🎧 AudioFocus: LOSS_TRANSIENT (ducking expected)");
                            break;
                        default:
                            Log.d(TAG, "🎧 AudioFocus: change=" + focusChange);
                            break;
                    }
                }
            };

    // ===== Runnables =====

    private final Runnable hideOverlay = () -> {
        if (overlayChannel != null) overlayChannel.setVisibility(View.GONE);
    };

    private final Runnable audioStartTimeout = new Runnable() {
        @Override
        public void run() {
            if (audioEnabled && !audioStarted && !audioFailed && !isReleased) {
                Log.w(TAG, "Audio start timeout — audio backend may not work on this device, stopping audio MP");
                // Force-stop the stuck audio MP so it cannot drag anything down
                stopAudioAsync(null);
                audioFailed = true;
                handler.post(() -> {
                    if (audioEnabled && !isReleased) {
                        Toast.makeText(PlayerActivity.this,
                                "音频不可用（视频正常播放）\n短按菜单键可重试",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    };

    // Delayed audio starter — runs only AFTER video Playing event fires
    private final Runnable startAudioDelayed = new Runnable() {
        @Override
        public void run() {
            if (isReleased || !audioEnabled || !expectedPlaying) return;
            // In combined mode, audio comes from same MP — do NOT start separate audioMP.
            if (useCombinedMode) {
                Log.d(TAG, "startAudioDelayed: SKIP (combined mode, audio from single MP)");
                return;
            }
            try {
                String url = useSubStream
                    ? config.getRtspUrlSubStream(currentChannelIndex)
                    : config.getRtspUrl(currentChannelIndex);
                Log.i(TAG, "Video stable for " + AUDIO_START_DELAY_MS + "ms — starting audio MP");
                startAudio(url);
            } catch (Exception e) {
                Log.w(TAG, "startAudioDelayed error: " + e.getMessage());
                audioFailed = true;
            }
        }
    };

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (isReleased || (expectedPlaying && isPlaying)) return;
            if (isPlaying) return;
            Log.w(TAG, "Connection timeout (useSubStream=" + useSubStream + ")");
            userStop = true;
            stopVideoAsync(null);

            if (!useSubStream && !config.isUseSubStream() && !mainStreamFailed) {
                Log.i(TAG, "Main stream timeout, trying sub stream");
                mainStreamFailed = true;
                mFreezeRetryCount = 0;
                playChannel(currentChannelIndex, true);
            } else {
                Log.i(TAG, "All streams failed (timeout)");
                String name = config.getChannelName(currentChannelIndex);
                overlayError.setText("连接超时\n\n" + name + " 无法连接\n\n"
                        + "RTSP目标 " + NvrClient.formatAuthority(config.getConnectionHost(), config.getRtspPort())
                        + "\n请检查设置中的地址与 RTSP 端口\n\n"
                        + "确认键重试 | 返回键退出");
                overlayError.setVisibility(View.VISIBLE);
                // DDNS：连接失败很可能是 IP 已变而缓存指旧 IP，强刷解析后"确认键重试"即用新 IP
                config.refreshHostResolution();
            }
        }
    };

    private final Runnable connectTimer = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying && !isReleased && overlayError != null && overlayError.getVisibility() == View.VISIBLE) {
                int elapsed = (int) (System.currentTimeMillis() / 1000 - connectStartTime);
                String name = config.getChannelName(currentChannelIndex);
                String streamType = useSubStream ? "子码流" : "主码流";
                String audioState = audioEnabled ? (audioStarted ? " · 有声" : " · 音频连接中") : " · 无声";
                overlayError.setText("连接中... (" + elapsed + "s)\n" + name + " · " + streamType + audioState);
                handler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable freezeDetector = new Runnable() {
        @Override
        public void run() {
            if (isReleased || freezeDetected || !expectedPlaying) return;
            // 连接阶段（尚未进入 Playing）不判冻结：第一次帧还没来是正常现象，
            // 首连超时统一由 connectTimeout 判定；冻结检测只在 Playing 之后生效，
            // 否则慢通道首帧 >6s 会被误判成"画面冻结"而反复掐断重连。
            if (!isPlaying) {
                handler.postDelayed(this, FREEZE_CHECK_INTERVAL);
                return;
            }
            long now = System.currentTimeMillis();
            long stagnantDuration = now - lastFrameTimeMs;

            // 重新缓冲中优先看缓冲进度是否仍在推进：进度动了就不算"画面冻结"，
            // 等 VLC 自己缓冲完恢复播放（首次连接超时仍由 connectTimeout 兜底）。
            if (rebuffering) {
                if (isRebufferingStalled(now)) {
                    Log.w(TAG, "重新缓冲停滞 " + (now - lastBufferingChangeMs)
                            + "ms（pct=" + lastBufferingPct + "）→ 重建播放器");
                    rebuildOnRebufferStall();
                } else {
                    handler.postDelayed(this, FREEZE_CHECK_INTERVAL);
                }
                return;
            }

            Log.d(TAG, "Freeze check: no frames for " + stagnantDuration + "ms (isPlaying=" + isPlaying + ")");

            if (lastFrameTimeMs > 0 && stagnantDuration >= FREEZE_THRESHOLD_MS) {
                Log.e(TAG, "FREEZE DETECTED! No frames for " + stagnantDuration + "ms");
                freezeDetected = true;
                handleFreeze();
            } else {
                handler.postDelayed(this, FREEZE_CHECK_INTERVAL);
            }
        }
    };

    /**
     * 重新缓冲是否已停滞（进度推进了则不算停滞，给 VLC 自愈时间）。
     */
    private boolean isRebufferingStalled(long now) {
        if (lastBufferingChangeMs <= 0) {
            return false;
        }
        return (now - lastBufferingChangeMs) >= REBUFFER_STALL_MS;
    }

    /** 重新缓冲停滞：走冻结/重试链（同一套 MAX_FREEZE_RETRIES 与主→子降级），但禁止无帧时间戳被旧帧污染。 */
    private void rebuildOnRebufferStall() {
        lastFrameTimeMs = 0;
        handleFreeze();
    }

    // ===== Lifecycle =====

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrashLog(throwable);
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(thread, throwable);
        });

        setContentView(R.layout.activity_player);
        if (getSupportActionBar() != null) {
            try { getSupportActionBar().hide(); } catch (Exception ignored) {}
        }

        config = new ConfigManager(this);
        currentChannelIndex = getIntent().getIntExtra(EXTRA_CHANNEL_INDEX, 0);
        // 码流偏好：EXTRA 显式指定（from_multi/恢复场景）优先，否则回退全局配置
        String streamExtra = getIntent().getStringExtra(EXTRA_STREAM_TYPE);
        if ("sub".equals(streamExtra)) {
            useSubStream = true;
        } else if ("main".equals(streamExtra)) {
            useSubStream = false;
        } else {
            // auto / null：跟随全局 use_sub_stream
            useSubStream = config.isUseSubStream();
        }
        boolean fromMulti = getIntent().getBooleanExtra(EXTRA_FROM_MULTI, false);
        this.fromMulti = fromMulti;
        Log.i(TAG, "onCreate: channel=" + currentChannelIndex + " useSubStream=" + useSubStream
                + " fromMulti=" + fromMulti + " streamExtra=" + streamExtra);

        surfaceView = findViewById(R.id.surface_view);
        overlayChannel = findViewById(R.id.overlay_channel);
        overlayError = findViewById(R.id.overlay_error);

        overlayError.setText("连接中...");
        overlayError.setVisibility(View.VISIBLE);

        // Request AudioFocus — Xiaomi TV boxes SILENTLY MUTE if you pass null listener!
        // Always pass the real audioFocusListener instance (non-null).
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            // AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK is safer on TV (less aggressive to other apps),
            // but we want exclusive so try GAIN first and fall back if rejected.
            int focusResult = audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muting = audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0);
            // If HDMI audio stream is marked muted (common on TV after standby), force unmute.
            try {
                // adjustStreamVolume flag 0 = none; ADJUST_UNMUTE = 4 (unfortunately @hide, try reflection-free direct)
                if (muting) audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            } catch (Throwable ignored) {}
            Log.i(TAG, "🎧 onCreate: AudioFocus result=" + focusResult
                    + " (1=AUDIOFOCUS_REQUEST_GRANTED, 0=FAILED, 2=DELAYED)"
                    + " streamMusicVol: " + curVol + "/" + maxVol
                    + " muted=" + muting
                    + " (now forced to MAX)");
            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "🎧 onCreate: AudioFocus NOT GRANTED — trying TRANSIENT_MAY_DUCK as fallback");
                int r2 = audioManager.requestAudioFocus(audioFocusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                Log.w(TAG, "🎧 onCreate: Fallback focus request result=" + r2);
            }
        } catch (Throwable t) {
            Log.e(TAG, "🎧 onCreate: Audio setup THREW: " + t.getMessage(), t);
        }

        // ===== 设备自适应：触屏设备（手机/平板/车机）默认 combined 模式 =====
        // 原因：Android MediaPlayer 播 RTSP 在部分设备上解复用器常失败 → OnError → "音频连接失败"无声音。
        //       LibVLC 单 MP 音视频同流在任何标准 Android 设备上远比 Android MediaPlayer 播 RTSP 稳定。
        // 电视/盒子（非触摸）保持双 MP 架构（针对小米电视盒子 5 已验证最稳）。
        if (useCombinedMode || isTouchScreenDevice()) {
            if (!useCombinedMode) {
                useCombinedMode = true;   // 手机/平板/车机：LibVLC 单 MP 音视频同流，绕开 Android MediaPlayer 播 RTSP 的不可靠
                Log.i(TAG, "设备为触屏设备（手机/平板/车机）→ 默认使用 combined 模式（LibVLC 音视频同流）");
            }
        }

        if (!initEngine()) {
            return;
        }

        // 触屏设备绑定触摸手势（电视/盒子不设置，避免影响遥控器）
        initTouchSupport();

        surfaceView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                Log.i(TAG, "SurfaceTexture available: " + width + "x" + height);
                mSurfaceW = width;
                mSurfaceH = height;
                setupVideoSurface(surfaceTexture);
                // surface 一就绪就立刻算 cover：否则等到 onNewVideoLayout 之前
                // 会按 VLC 默认拉伸渲染，短暂出现底部花屏（与多画面 ChannelTileView 同理）。
                applyVideoFit();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "SurfaceTexture size changed: " + width + "x" + height);
                // VLC 定完 buffer geometry 后 SurfaceTexture 尺寸会变，此时必须重算 cover
                mSurfaceW = width;
                mSurfaceH = height;
                applyVideoFit();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Log.i(TAG, "SurfaceTexture destroyed");
                MediaPlayer mp = videoMediaPlayer;
                if (mp != null && !isReleased) {
                    try {
                        userStop = true;
                        if (mp.isPlaying()) mp.stop();
                        mp.getVLCVout().detachViews();
                    } catch (Exception e) {
                        Log.w(TAG, "Error detaching views: " + e.getMessage());
                    }
                }
                if (mVideoSurface != null) {
                    mVideoSurface.release();
                    mVideoSurface = null;
                }
                mSurfaceReady = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                if (!isReleased) {
                    lastFrameTimeMs = System.currentTimeMillis();
                }
            }
        });

        mPendingPlay = true;
    }

    // ===== Engine initialization (TWO SEPARATE LibVLC instances) =====
    // Video LibVLC: handles video only (:no-audio) — must NEVER be used for audio!
    // Audio LibVLC: handles audio only (:no-video) — COMPLETELY ISOLATED, clean options.
    //   CRITICAL: audioLibVLC must NEVER reference the video LibVLC, because the
    //   video engine was started with --no-audio which PERMANENTLY disables audio
    //   at the libvlc core level (even for new MediaPlayers).

    private boolean initEngine() {
        // ---- Video/Combined LibVLC options ----
        ArrayList<String> videoOptions = new ArrayList<>();
        if (!useCombinedMode) {
            videoOptions.add("--no-audio");
            // 视频-only（音频另走独立 MediaPlayer）：无需等音视频时钟同步（由下方 VlcOptions 关闭）。
        } else {
            Log.w(TAG, "initEngine: COMBINED mode — video engine has AUDIO enabled (audiotrack backend)");
            // combined 模式音视频同流、音频作主时钟：保持 clock-synchro，否则长时间播放会 A/V 漂移。
        }
        // Use audiotrack backend (default, most compatible)
        videoOptions.add("--aout=audiotrack");
        videoOptions.add("--vout=android-display");
        // 低延迟/解码参数统一取自 VlcOptions（与下方 per-media **同源**）。
        // 原先在此硬编码一份、靠注释约定"两处必须一起改"——漏改时症状隐蔽（改了没变快）。
        // clock-synchro 由 VlcOptions 按 combined(保持)/dual(关闭) 自动处理。
        VlcOptions.addInstanceOptions(videoOptions, config.getAvcodecHwValue(),
                useSubStream, !useCombinedMode, true);

        // Audio options removed — audio now uses Android MediaPlayer (created on-demand in startAudio).

        boolean videoOk = false;
        boolean audioOk = false;

        // 1) Create VIDEO LibVLC first (always succeeds — no audio, less memory)
        try {
            libVLC = new LibVLC(this, videoOptions);
            createVideoPlayer();
            videoOk = true;
            Log.i(TAG, "Video LibVLC created (--no-audio, isolated)");
        } catch (Exception e) {
            Log.e(TAG, "Video LibVLC FAILED: " + e.getMessage(), e);
        }

        // 2) Audio uses Android MediaPlayer (created on-demand in startAudio).
        //    No LibVLC audio instance needed — VLC audio modules fail on Xiaomi Box 5.
        if (videoOk) {
            audioOk = true;
            Log.i(TAG, "🎧 Audio engine: Android MediaPlayer (created on-demand)");
        }

        isReleased = false;

        if (videoOk) {
            Log.i(TAG, "Engine init summary: videoOk=true audioOk=" + audioOk
                    + " audioEngine=AndroidMediaPlayer");
            return true;
        } else {
            try {
                // Last resort: minimal shared LibVLC (no --no-audio) for BOTH.
                // Risk: audio can freeze video, but better than no video at all.
                Log.w(TAG, "Video LibVLC failed — trying SHARED minimal LibVLC as last resort");
                ArrayList<String> fallbackOptions = new ArrayList<>();
                // 解码模式来自设置（软解 disabled / 硬解 mediacodec / 自动 any）
                fallbackOptions.add("--avcodec-hw=" + config.getAvcodecHwValue());
                libVLC = new LibVLC(this, fallbackOptions);
                createVideoPlayer();
                Log.i(TAG, "Fallback: shared LibVLC (no audio isolation) created");
                return true;
            } catch (Exception e2) {
                Log.e(TAG, "All LibVLC init paths failed", e2);
                overlayError.setText("播放引擎初始化失败\nLibVLC: " + e2.getMessage()
                    + "\n\n请尝试重新安装应用");
                overlayError.setVisibility(View.VISIBLE);
                return false;
            }
        }
    }

    // ===== 设备类型检测 =====
    // 电视/盒子/非触摸设备 → 保留双 MP 架构（已验证最稳）
    // 手机/平板/车机等触屏设备 → 默认 combined 模式 + 触控手势
    private boolean isTouchScreenDevice() {
        Configuration conf = getResources().getConfiguration();
        int uiMode = conf.uiMode & Configuration.UI_MODE_TYPE_MASK;
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return false;   // 电视/盒子
        // UI_MODE_TYPE_CAR(车机)/UI_MODE_TYPE_NORMAL(手机平板)等 → 视为支持触摸
        boolean touch = conf.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH;
        return touch;
    }

    // ===== 触控支持（仅触屏设备启用，电视端不设置避免影响遥控）=====
    // 手势说明（与遥控键位语义一一对应）：
    //   单击      = KEYCODE_DPAD_CENTER 短按 → 显示/隐藏通道信息
    //   双击      = 播放/暂停（isPlaying + videoMediaPlayer.pause()/play()）
    //   左右滑动   = KEYCODE_DPAD_LEFT / RIGHT → 上/下一个通道
    //   上下滑动   = 音量 +/-
    private void initTouchSupport() {
        if (!isTouchScreenDevice()) {
            Log.i(TAG, "设备非触摸屏（电视/盒子）→ 不绑定触控手势，保持遥控器操作");
            gestureDetector = null;
            return;
        }
        // 滑动阈值按屏幕尺寸自适应：手机窄->阈值小才划得动；平板宽->阈值大避免误触。
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
            swipeThreshold = Math.max(40, (int) (shortSide * 0.08f));
            Log.i(TAG, "触屏滑动阈值自适应: " + swipeThreshold + "px（屏幕短边 " + shortSide + "px）");
        } catch (Throwable t) {
            swipeThreshold = SWIPE_THRESHOLD;
        }
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                // 返回 true：让 onFling/onSingleTapConfirmed/onDoubleTap 都能收到事件
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 抑制：手写双击（exitSingleToMulti）命中时，其第一击/后续单击确认会晚到，
                // 且设备可能先触发本确认再执行 dispatchTouchEvent 的 finish——此时页已在退出中，
                // 不再切换 overlay（避免"想退出却抖出通道信息"）。
                if (fromMulti && Math.abs(System.currentTimeMillis() - mManualDoubleTapAtMs) < 600L) {
                    Log.d(TAG, "抑制单画面双击第一击的单击 overlay");
                    return true;
                }
                // 单击：播放中 -> 切换 overlay 通道信息 显示/隐藏；
                //      暂停中 -> 直接恢复播放（比双击更快，也与暂停提示「单击屏幕恢复」一致）
                Log.i(TAG, "👆 单击");
                if (isReleased) return true;
                try {
                    if (!isPlaying && videoMediaPlayer != null) {
                        // 已暂停：单击恢复播放
                        expectedPlaying = true;
                        userStop = false;
                        videoMediaPlayer.play();
                        isPlaying = true;
                        if (useCombinedMode) {
                            try { videoMediaPlayer.setVolume(audioEnabled ? 100 : 0); } catch (Throwable ignored) {}
                        }
                        handler.removeCallbacks(freezeDetector);
                        handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);
                        if (overlayChannel != null) {
                            overlayChannel.setText(config.getChannelName(currentChannelIndex));
                            overlayChannel.setVisibility(View.VISIBLE);
                            overlayError.setVisibility(View.GONE);
                            handler.removeCallbacks(hideOverlay);
                            handler.postDelayed(hideOverlay, OVERLAY_DURATION);
                        }
                        Toast.makeText(PlayerActivity.this, "▶ 已恢复播放", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    if (overlayChannel != null && overlayChannel.getVisibility() == View.VISIBLE) {
                        overlayChannel.setVisibility(View.GONE);
                        handler.removeCallbacks(hideOverlay);
                    } else {
                        overlayChannel.setText(config.getChannelName(currentChannelIndex));
                        overlayChannel.setVisibility(View.VISIBLE);
                        overlayError.setVisibility(View.GONE);
                        handler.removeCallbacks(hideOverlay);
                        handler.postDelayed(hideOverlay, OVERLAY_DURATION);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "触屏单击 overlay 切换异常: " + t.getMessage());
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // 双击 → 播放/暂停
                Log.i(TAG, "👆 双击 → 播放/暂停");
                if (isReleased || videoMediaPlayer == null) return true;
                try {
                    if (isPlaying && videoMediaPlayer.isPlaying()) {
                        expectedPlaying = false;
                        videoMediaPlayer.pause();
                        isPlaying = false;
                        // 双MP模式下同步暂停单独音频，避免音画不同步
                        android.media.MediaPlayer amp = audioAndroidMP;
                        if (amp != null) {
                            try { if (amp.isPlaying()) amp.pause(); } catch (Throwable ignored) {}
                        }
                        Toast.makeText(PlayerActivity.this, "⏸ 已暂停（单击屏幕恢复 / 双击切换）", Toast.LENGTH_SHORT).show();
} else {
                    expectedPlaying = true;
                    userStop = false;
                    videoMediaPlayer.play();
                    isPlaying = true;
                    if (useCombinedMode) {
                        try { videoMediaPlayer.setVolume(audioEnabled ? 100 : 0); } catch (Throwable ignored) {}
                    }
                    // 暂停期间 freezeDetector 因 !expectedPlaying 已自断，恢复后重新调度冻结检测
                    handler.removeCallbacks(freezeDetector);
                    handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);
                    Toast.makeText(PlayerActivity.this, "▶ 已播放", Toast.LENGTH_SHORT).show();
                }
                } catch (Throwable t) {
                    Log.w(TAG, "双击播放/暂停异常: " + t.getMessage());
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (isReleased || e1 == null || e2 == null) return true;
                try {
                    float dx = e2.getX() - e1.getX();
                    float dy = e2.getY() - e1.getY();
                    if (Math.abs(dx) < swipeThreshold && Math.abs(dy) < swipeThreshold
                    && Math.abs(velocityX) < SWIPE_VELOCITY_THRESHOLD
                    && Math.abs(velocityY) < SWIPE_VELOCITY_THRESHOLD) return true;
                    int channelCount = config.getChannelCount();
                    if (Math.abs(dx) > Math.abs(dy)) {
                        // 左右滑动 → 切换通道
                        if (dx > 0) {
                            if (currentChannelIndex > 0) {
                                mainStreamFailed = false;
                                mFreezeRetryCount = 0;
                                playChannel(currentChannelIndex - 1);
                            } else {
                                Toast.makeText(PlayerActivity.this, "已经是第一个通道", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            if (currentChannelIndex < channelCount - 1) {
                                mainStreamFailed = false;
                                mFreezeRetryCount = 0;
                                playChannel(currentChannelIndex + 1);
                            } else {
                                Toast.makeText(PlayerActivity.this, "已经是最后一个通道", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } else {
                        // 上下滑动 → 音量 +/-
                        if (audioManager == null) audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                        int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                        if (dy < 0) {
                            // 向上滑 → 音量+
                            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                    AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                            Toast.makeText(PlayerActivity.this,
                                    "🔊 音量 " + (cur + 1) + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            // 向下滑 → 音量-
                            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                    AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                            Toast.makeText(PlayerActivity.this,
                                    "🔊 音量 " + (cur - 1) + "/" + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "触屏滑动操作异常: " + t.getMessage());
                }
                return true;
            }
        });

        if (surfaceView != null) {
            surfaceView.setOnTouchListener((v, event) -> {
                if (gestureDetector != null) gestureDetector.onTouchEvent(event);
                return true;  // 消费事件，避免与 TV 端焦点逻辑冲突
            });
        }
        Log.i(TAG, "已检测到触屏设备 → 触控手势已启用（单击/双击/滑动）");
    }

    private void createVideoPlayer() {
        videoMediaPlayer = new MediaPlayer(libVLC);
        videoMediaPlayer.setEventListener(event -> {
            MediaPlayer mp = videoMediaPlayer;
            if (mp == null) return;
            handleVideoEvent(event);
        });
        if (mSurfaceReady && mVideoSurface != null) {
            IVLCVout vout = videoMediaPlayer.getVLCVout();
            vout.setVideoSurface(mVideoSurface, null);
            vout.attachViews(videoLayoutListener);
        }
    }

    private void createAudioPlayer() {
        // No-op: audio uses Android MediaPlayer, created on-demand in startAudio()
    }

    // ===== Video event handler (freeze/error recovery) =====

    private void handleVideoEvent(MediaPlayer.Event event) {
        switch (event.type) {
            // 播放位置推进 = 视频正在出帧，刷新冻结检测基准时间。
            // 【关键】原实现只靠 Playing 事件打点 lastFrameTimeMs，播放中从不更新，
            // 导致正常播放满 FREEZE_THRESHOLD_MS(10s) 后必被误判"画面冻结"→ 重建 → 黑屏，
            // 正是"播放一会就黑屏又播放、反复出现"的根因。TimeChanged 是"画面在动"的最可靠信号。
            case MediaPlayer.Event.TimeChanged:
                if (isPlaying) {
                    lastFrameTimeMs = System.currentTimeMillis();
                    // 重新缓冲中收到播放时间推进 = 正在自愈（VLC 边缓冲边出帧）。
                    // 刷新停滞判定基准，避免"百分比不动但时间在动"的缓慢恢复被误判为停滞。
                    if (rebuffering && lastBufferingChangeMs > 0) {
                        lastBufferingChangeMs = System.currentTimeMillis();
                    }
                }
                break;

            case MediaPlayer.Event.Opening:
                Log.i(TAG, "Video: Opening media");
                handler.post(() -> {
                    if (overlayError != null && !isPlaying) {
                        String name = config.getChannelName(currentChannelIndex);
                        overlayError.setText("连接中... (0s)\n" + name);
                    }
                });
                break;

            case MediaPlayer.Event.Buffering:
                final float bufPct = event.getBuffering();
                handler.post(() -> {
                    // 已进入 Playing 后再次收到 Buffering = 重新缓冲：允许 VLC 自愈，
                    // 冻结检测转入"进度停滞判定"分支，不再因无帧而掐断。
                    // VLC 缓冲恢复到 ≥99% 视为已自愈：libvlc 3.x 重新缓冲完成
                    // 不保证会再发 Playing 事件，若不退出 rebuffering，会在 15s 后
                    // 被误判"重新缓冲停滞"而重建 → 周期性黑屏重试。
                    if (isPlaying && rebuffering && bufPct >= 99f) {
                        rebuffering = false;
                        lastBufferingPct = -1f;
                        lastBufferingChangeMs = 0;
                        lastFrameTimeMs = System.currentTimeMillis();
                        handler.removeCallbacks(freezeDetector);
                        handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);
                        Log.i(TAG, "视频重新缓冲完成（pct=" + (int) bufPct + "%）");
                        return;
                    }
                    boolean becameRebuffering = false;
                    if (isPlaying && !rebuffering) {
                        rebuffering = true;
                        becameRebuffering = true;
                        Log.i(TAG, "视频重新缓冲开始（pct=" + (int) bufPct + "%）→ 冻结检测转入进度停滞判定");
                    }
                    if (rebuffering) {
                        if (bufPct > lastBufferingPct) {
                            lastBufferingPct = bufPct;
                            lastBufferingChangeMs = System.currentTimeMillis();
                        } else if (lastBufferingChangeMs == 0) {
                            lastBufferingPct = bufPct;
                            lastBufferingChangeMs = System.currentTimeMillis();
                        }
                        if (overlayError != null && overlayError.getVisibility() == View.VISIBLE) {
                            String name = config.getChannelName(currentChannelIndex);
                            overlayError.setText("缓冲中... " + (int) bufPct + "%\n" + name);
                        }
                    }
                    if (becameRebuffering) {
                        handler.removeCallbacks(freezeDetector);
                        handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);
                    }
                });
                break;

            case MediaPlayer.Event.Playing:
                Log.i(TAG, "Video: Playing — starting freeze detector"
                        + (useCombinedMode ? " (COMBINED mode = audio from same MP)"
                                           : " (DUAL mode, scheduling audio in " + AUDIO_START_DELAY_MS + "ms)"));
                isPlaying = true;
                handler.removeCallbacks(connectTimeout);
                handler.removeCallbacks(connectTimer);
                // 新播放态已建立：解除旧媒体 Stopped 抑制（旧事件已过，后续真正的意外停止恢复重试语义）。
                suppressStopped = false;
                handler.post(() -> {
                    if (overlayError != null) overlayError.setVisibility(View.GONE);
                });
                lastFrameTimeMs = System.currentTimeMillis();
                freezeDetected = false;
                // Playing → 重新缓冲状态复位（若还残留上一轮状态会被上一次 playChannel/stopVideoAsync 清掉）
                rebuffering = false;
                lastBufferingPct = -1f;
                lastBufferingChangeMs = 0;
                handler.removeCallbacks(freezeDetector);
                handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);

                // ===== 排期缩略图截图：Playing 后 2.5s 截当前帧写本地缓存 =====
                // 单 Activity 实例只截一次（thumbnailCaptured 守门）。
                // onPause/onDestroy 会 removeCallbacks，避免切后台白截。
                handler.removeCallbacks(thumbnailCaptureRunnable);
                handler.postDelayed(thumbnailCaptureRunnable, THUMB_CAPTURE_DELAY_MS);

                if (useCombinedMode) {
                    // Combined-mode: audio decoded inside this same video MP.
                    audioStarted = audioEnabled;
                    audioFailed = false;

                    // Same POST-PLAYING OUTPUT-PATH recovery as dual-mode (CRITICAL for HDMI on TV)
                    handler.post(() -> {
                        if (isReleased) return;
                        StringBuilder sb = new StringBuilder(256);
                        sb.append("✅ Combined-mode VIDEO_PLAYING. Post-playing audio diagnostics:\n");
                        try {
                            MediaPlayer vmp = videoMediaPlayer;
                            if (audioManager != null) {
                                int fr = audioManager.requestAudioFocus(audioFocusListener,
                                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                                int maxV = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                int curV = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                                boolean muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
                                if (curV != maxV) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxV, 0);
                                if (muted) try { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, 0, 0); } catch (Throwable ignored) {}
                                sb.append("   focusResult=").append(fr)
                                        .append(" streamMusicVol=").append(curV).append("/").append(maxV)
                                        .append(" muted=").append(muted).append(" → forced MAX\n");
                            }
                            if (vmp != null && audioEnabled) {
                                int vBefore = vmp.getVolume();
                                int vSet = vmp.setVolume(100);
                                int vAfter = vmp.getVolume();
                                sb.append("   VLCvideoMP_vol: before=").append(vBefore)
                                        .append(" set(").append(vSet).append(") after=").append(vAfter).append("\n");
                                if (vAfter == 0) {
                                    for (int v : new int[]{50, 75, 100}) {
                                        int r = vmp.setVolume(v);
                                        Log.d(TAG, "🎧 combined vol ramp(" + v + ")=" + r + " → " + vmp.getVolume());
                                    }
                                }
                            } else if (vmp != null) {
                                vmp.setVolume(0);
                                sb.append("   audioDisabled: VLCvideoMP muted via setVolume(0)\n");
                            }
                        } catch (Throwable t) {
                            sb.append("   DIAG THREW: ").append(t.getMessage()).append("\n");
                        }
                        Log.i(TAG, sb.toString());
                    });

                    if (audioEnabled) {
                        handler.post(() -> Toast.makeText(this,
                                "🔊 音频已启用(单MP混合模式)\n若仍无声/卡画面 → 长按菜单键切双MP模式",
                                Toast.LENGTH_LONG).show());
                    }
                } else {
                    // ===== KEY FIX: Start audio ONLY after video is stable =====
                    // Avoids dual RTSP handshake congestion + gives video decoder full CPU priority.
                    // If audioEnabled=false or user already stopped, startAudioDelayed will no-op.
                    handler.removeCallbacks(startAudioDelayed);
                    handler.postDelayed(startAudioDelayed, AUDIO_START_DELAY_MS);
                }
                break;

            case MediaPlayer.Event.Paused:
                Log.i(TAG, "Video: Paused");
                break;

            case MediaPlayer.Event.Stopped:
                Log.i(TAG, "Video: Stopped (userStop=" + userStop + " expectedPlaying=" + expectedPlaying
                        + " suppressStopped=" + suppressStopped + ")");
                isPlaying = false;
                rebuffering = false;
                lastBufferingPct = -1f;
                lastBufferingChangeMs = 0;
                handler.removeCallbacks(freezeDetector);
                // 主动重建（stopVideoAsync→playChannel）产生的旧媒体 Stopped 是异步晚到事件，
                // 可能在新流 userStop=false 已恢复后才到达：此时若按"意外停止"处理会误走重试链，
                // 造成"重建的同时又触发一次重建"的双重重播（与 PlayerEngine 同一套竞态修复）。
                if (suppressStopped) {
                    Log.i(TAG, "Video: Stopped suppressed（主动重建的旧媒体事件）");
                    break;
                }
                if (!isReleased && expectedPlaying && !userStop) {
                    Log.w(TAG, "Unexpected video stop — retrying");
                    if (mFreezeRetryCount < MAX_FREEZE_RETRIES) {
                        mFreezeRetryCount++;
                        handler.postDelayed(() -> {
                            if (!isReleased) playChannel(currentChannelIndex, useSubStream);
                        }, 1000);
                    }
                }
                userStop = false;
                break;

            case MediaPlayer.Event.EndReached:
                Log.i(TAG, "Video: End reached — reconnecting");
                isPlaying = false;
                expectedPlaying = false;
                rebuffering = false;
                handler.removeCallbacks(freezeDetector);
                handler.postDelayed(() -> {
                    if (!isReleased) playChannel(currentChannelIndex, useSubStream);
                }, 1000);
                break;

            case MediaPlayer.Event.EncounteredError:
                Log.e(TAG, "Video: Error encountered");
                isPlaying = false;
                expectedPlaying = false;
                rebuffering = false;
                handler.removeCallbacks(connectTimeout);
                handler.removeCallbacks(connectTimer);
                handler.removeCallbacks(freezeDetector);
                final boolean wasSubStream = useSubStream;
                handler.post(() -> {
                    if (overlayError != null && !isReleased) {
                        if (!wasSubStream && !config.isUseSubStream() && !mainStreamFailed) {
                            Log.i(TAG, "Main stream error, trying sub stream");
                            mainStreamFailed = true;
                            mFreezeRetryCount = 0;
                            playChannel(currentChannelIndex, true);
                        } else {
                            String name = config.getChannelName(currentChannelIndex);
                            overlayError.setText("播放错误\n\n" + name + " 无法播放\n"
                                + "RTSP目标 " + NvrClient.formatAuthority(config.getConnectionHost(), config.getRtspPort())
                                + "\n请检查地址/端口，或该通道可能离线\n\n"
                                + "确认键重试 | 返回键退出");
                            overlayError.setVisibility(View.VISIBLE);
                            // DDNS：失败即强刷解析（公共 DNS 直查+探活），重试时拿新 IP
                            config.refreshHostResolution();
                        }
                    }
                });
                break;

            default:
                break;
        }
    }

    // ===== Audio event handler (LOG EVERYTHING for "no sound" diagnosis) =====
    // Detects NVR conn-limit (Buffering forever at 0% -> Timeout -> Error path)
    // and offers single-MP combined fallback via long-press INFO key.

    private int audioFailCount = 0;  // consecutive audio failures this session
    private long lastAudioBufferPctTs = 0;
    private float lastAudioBufferPct = -1;

    private void handleAudioEvent(MediaPlayer.Event event) {
        // No-op: audio now uses Android MediaPlayer, events handled via listeners in startAudio()
    }

    // ===== Surface setup =====

    /**
     * 单画面视频适配（与多画面 ChannelTileView 同一套修法）：
     * **cover（等比铺满整个屏幕，裁掉超出部分）** + 精确裁掉底部宏块对齐 padding 脏行。
     *
     * 为什么是 cover 而不是 contain：用户要求画面完全填满窗口。contain 会在 4:3 等非 16:9 画面上下留黑边，
     * 既不铺满，黑边区域还会露出底部 padding 花屏带。cover 让画面铺满、多余部分被屏幕边界裁掉。
     *
     * cover 的基准必须用 SurfaceTexture 实际渲染尺寸（mSurfaceW/mSurfaceH）：
     * onNewVideoLayout 报的 visible 尺寸可能是切主/子码流时的残留值，用它算 cover 会把实际 surface
     * 缩小 → 没铺满 + 底部 padding 花屏带放大显现（真机已复现）。SurfaceTexture 尺寸才是屏幕上真正显示的东西。
     *
     * padding 脱离：H.264 宏块对齐的脏行总在 buffer 底部，按 surface 与 reported visible 的差精确扣除，
     * 使其必然落在屏幕底边之外。不再依赖 setClipBounds（部分设备不可靠）。
     */
    private void applyVideoFit() {
        if (surfaceView == null || !mSurfaceReady) {
            return;
        }
        int tw = surfaceView.getWidth();
        int th = surfaceView.getHeight();
        if (tw <= 0 || th <= 0) {
            return;
        }
        int surfW = mSurfaceW;
        int surfH = mSurfaceH;
        int repVw = mVideoVisW > 0 ? mVideoVisW : mVideoBufW;
        int repVh = mVideoVisH > 0 ? mVideoVisH : mVideoBufH;

        // 基准尺寸：优先 SurfaceTexture 实际渲染尺寸；surface 也未知才退回 reported。
        int vw, vh;
        if (surfW > 0 && surfH > 0) {
            vw = surfW;
            vh = surfH;
        } else if (repVw > 0 && repVh > 0) {
            vw = repVw;
            vh = repVh;
        } else {
            surfaceView.setClipBounds(null);
            return;
        }

        // 底部 padding 脱离量（源 buffer 行数）。
        // 关键：必须用 **buffer 高与 visible 高的差**（来自 onNewVideoLayout）来判定，
        // 不能用 surface 高与 visible 的差。原因——VLC 可能把含 padding 的 buffer 拉伸/压缩进
        // **visible 尺寸**的 Surface：此时 surfaceHeight == visibleHeight，按 surface 差会判为
        // "无 padding"而漏裁，但 padding 脏行已被挤到画面底部 → 花屏带依旧（真机已复现）。
        int bufH = mVideoBufH;
        int visH = repVh;
        int padCrop;
        if (visH > 0 && bufH > visH) {
            // padding 是**源侧**的行数，vout 可能已把整个 buffer 缩放后写进 Surface，
            // 所以不能把"源侧绝对行数"直接减到 surface 高上（会多裁有效画面）。
            // 改成按相对占比折算到 surface 尺度：padCrop = vh × (1 − visH/bufH)。
            // VLC 未缩放时（vh == bufH）结果与原来的绝对行数完全一致。
            padCrop = (int) Math.ceil(vh * (1f - visH / (float) bufH));
        } else if (visH > 0 && bufH > 0) {
            padCrop = 4;                    // buffer == visible：无宏块 padding，
                                            // 但裁 4 行防底部边缘脏行/未初始化行
        } else {
            padCrop = 16;                   // onNewVideoLayout 未到，按最多一个宏块兜底
        }
        int effH = vh - padCrop;
        if (effH <= 0) {
            effH = vh;
            padCrop = 0;
        }

        float sarN = mVideoSarNum > 0 ? mVideoSarNum : 1f;
        float sarD = mVideoSarDen > 0 ? mVideoSarDen : 1f;
        // 用"已扣除 padding 的可见高度"做 cover：effH·sy ≥ th 恒成立 → padding 行必然在屏幕底边之外。
        float dispW = vw * sarN / sarD;
        float dispH = effH;
        float s = Math.max(tw / dispW, th / dispH);
        float sx = s * sarN / sarD;
        float sy = s;
        float w = vw * sx;
        float h = effH * sy;
        float x0 = (tw - w) / 2f;
        float y0 = (th - h) / 2f;
        Matrix matrix = new Matrix();
        matrix.setScale(sx, sy);
        matrix.postTranslate(x0, y0);
        surfaceView.setTransform(matrix);
        surfaceView.setClipBounds(null);
        Log.d(TAG, "video-fit cover: surf=" + surfW + "x" + surfH
                + " padCrop=" + padCrop + " effH=" + effH
                + " repVisible=" + repVw + "x" + repVh
                + " buf=" + mVideoBufW + "x" + mVideoBufH
                + " view=" + tw + "x" + th + " sar=" + sarN + "/" + sarD
                + " scale=" + sx + "," + sy + " trans=" + x0 + "," + y0);
    }

    private void setupVideoSurface(SurfaceTexture surfaceTexture) {
        if (mVideoSurface != null) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
        mVideoSurface = new Surface(surfaceTexture);

        MediaPlayer mp = videoMediaPlayer;
        if (mp != null) {
            IVLCVout vlcVout = mp.getVLCVout();
            vlcVout.setVideoSurface(mVideoSurface, null);
            vlcVout.attachViews(videoLayoutListener);
        }

        mSurfaceReady = true;
        Log.i(TAG, "Video surface attached via setVideoSurface()");

        if (mPendingPlay) {
            mPendingPlay = false;
            playChannel(currentChannelIndex, useSubStream);
        }
    }

    // ===== Channel playback (starts BOTH video and audio MPs) =====

    private void playChannel(int channelIndex) {
        mainStreamFailed = false;
        mFreezeRetryCount = 0;
        playChannel(channelIndex, useSubStream);
    }

    private void playChannel(int channelIndex, boolean subStream) {
        if (isReleased) return;

        if (videoMediaPlayer == null || libVLC == null) {
            Log.w(TAG, "Engine not ready — queuing play request");
            mPendingPlay = true;
            currentChannelIndex = channelIndex;
            useSubStream = subStream;
            return;
        }

        if (!mSurfaceReady) {
            Log.w(TAG, "Surface not ready — queuing play request");
            mPendingPlay = true;
            currentChannelIndex = channelIndex;
            useSubStream = subStream;
            return;
        }

        currentChannelIndex = channelIndex;
        useSubStream = subStream;
        // 切通道：重置截图守门，允许新通道 Playing 后再次截图。
        // 同一通道被暂停/恢复不需要重置（Playing 事件被 suppressStopped 等机制管控，不会重触发）。
        thumbnailCaptured = false;

        String url;
        try {
            url = subStream ? config.getRtspUrlSubStream(channelIndex) : config.getRtspUrl(channelIndex);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get RTSP URL", e);
            overlayError.setText("获取通道URL失败\n" + e.getMessage());
            overlayError.setVisibility(View.VISIBLE);
            return;
        }

        String name = config.getChannelName(channelIndex);
        String safeUrl = url.replaceAll("://.*@", "://***@");

        // 记录实际连接目标：域名场景会被预解析成 IPv4，日志里要看得到用的是哪个地址
        Log.i(TAG, "Playing: " + name + " index=" + channelIndex
                + " subStream=" + subStream + " audio=" + audioEnabled + " URL: " + safeUrl
                + " | RTSP目标=" + NvrClient.formatAuthority(config.getConnectionHost(), config.getRtspPort())
                + " (配置主机=" + config.getHost() + ")");

        overlayChannel.setText(name);
        overlayChannel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, OVERLAY_DURATION);

        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(connectTimer);
        handler.removeCallbacks(freezeDetector);
        handler.removeCallbacks(audioStartTimeout);

        connectStartTime = (int) (System.currentTimeMillis() / 1000);
        String streamType = subStream ? "子码流" : "主码流";
        String audioState = audioEnabled ? " · 音频连接中" : " · 无声(短按菜单键开声音)";
        overlayError.setText("连接中... (0s)\n" + name + " · " + streamType + audioState);
        overlayError.setVisibility(View.VISIBLE);
        isPlaying = false;
        freezeDetected = false;
        rebuffering = false;
        lastBufferingPct = -1f;
        lastBufferingChangeMs = 0;
        lastFrameTimeMs = System.currentTimeMillis();
        expectedPlaying = true;
        userStop = false;
        // 主动重建场景：旧媒体的 Stopped 异步晚到，需由 stopVideoAsync 置位 suppressStopped 抑制；
        // 新流真正 Playing 后解除。此处复位只用于清除上一轮残留（防止吞掉后续真意外停止）。
        suppressStopped = false;
        audioStarted = false;
        audioFailed = false;

        final String finalUrl = url;

        // Start VIDEO MediaPlayer (:no-audio — proven stable)
        // AUDIO is NOT started here. It is triggered by handleVideoEvent Playing
        // after AUDIO_START_DELAY_MS (see startAudioDelayed runnable).
        stopVideoAsync(() -> handler.postDelayed(() -> {
            if (isReleased || videoMediaPlayer == null || libVLC == null) return;

            try {
                Media videoMedia = new Media(libVLC, Uri.parse(finalUrl));
                if (useCombinedMode) {
                    // ===== SINGLE COMBINED MP MODE =====
                    // No :no-audio — VLC decodes both video + audio from ONE RTSP stream.
                    // Let VLC auto-select all codecs (including araw for G.711).
                    // 低延迟/解码参数取自 VlcOptions（clock-synchro=false：
                    // 音视频同流、音频作主时钟，必须保持同步，否则长时间播放 A/V 漂移）。
                    VlcOptions.addMediaOptions(videoMedia, config.getAvcodecHwValue(),
                            subStream, false, true);
                    videoMedia.addOption(":ipv4-timeout=5000");
                    videoMedia.addOption(":audio-time-stretch=0");
                    Log.i(TAG, "Combined-mode: playing WITHOUT :no-audio (video+audio in single RTSP)");
                } else {
                    // ===== DUAL-MP MODE (default, proven safe for video) =====
                    videoMedia.addOption(":no-audio");
                    // 低延迟/解码参数取自 VlcOptions（clock-synchro=true：
                    // 视频-only 流，音频另走独立 MP，无需等音频时钟 → 解出帧立刻渲首帧）。
                    VlcOptions.addMediaOptions(videoMedia, config.getAvcodecHwValue(),
                            subStream, true, true);
                    videoMedia.addOption(":ipv4-timeout=5000");
                    Log.i(TAG, "Dual-mode: playing video-only (audio deferred to separate MP)");
                }
                videoMediaPlayer.setMedia(videoMedia);
                videoMedia.release();

                videoMediaPlayer.play();
                Log.i(TAG, (useCombinedMode ? "Combined MP" : "Video MP") + " play() called for " + name);

                handler.postDelayed(connectTimer, 1000);
                int timeout = subStream ? SUB_TIMEOUT : MAIN_TIMEOUT;
                handler.postDelayed(connectTimeout, timeout);
                handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);

            } catch (Exception e) {
                Log.e(TAG, "Failed to play video", e);
                writeCrashLog(e);
                overlayError.setText("播放失败: " + e.getMessage()
                        + "\n\n按返回键返回");
                overlayError.setVisibility(View.VISIBLE);
            }
        }, 150));

        // Cancel any pending delayed audio start from a previous channel
        handler.removeCallbacks(startAudioDelayed);
    }

    // ===== Audio playback (best-effort, isolated from video) =====
    // Uses Android MediaPlayer (android.media.MediaPlayer). Audio is completely
    // isolated from the VLC video engine — audio errors CANNOT affect video.
    //
    // FIXES FOR "NO SOUND":
    //   1) REMOVED broken ":codec=avcodec" (wrong syntax = all audio decoders disabled!)
    //      Correct per-codec VLC syntax is module-name lists, we use avcodec-hw=disabled instead
    //   2) Added "demuxdump first 64KB"-style diagnostics via Media meta/logs
    //   3) NVR conn-limit fallback: if audio errors 2x, user can try single-MP mode

    private void startAudio(final String url) {
        if (isReleased) {
            Log.w(TAG, "startAudio skipped: released");
            audioFailed = true;
            return;
        }

        audioStarted = false;
        audioFailed = false;
        handler.removeCallbacks(audioStartTimeout);

        final String safeUrl = url.replaceAll("://.*@", "://***@");
        Log.i(TAG, "🎧 startAudio: Android MediaPlayer RTSP (url=" + safeUrl + ")");

        // Stop previous audio, then start new on UI thread (MediaPlayer needs UI thread)
        stopAudioAsync(() -> {
            if (isReleased) return;
            handler.post(() -> {
                if (isReleased) return;
                try {
                    audioAndroidMP = new android.media.MediaPlayer();
                    audioAndroidMP.setOnPreparedListener(mp -> {
                        if (isReleased || audioAndroidMP != mp) return;
                        Log.i(TAG, "🎧 Android Audio: prepared — starting playback");
                        mp.start();
                        audioStarted = true;
                        audioFailed = false;
                        handler.removeCallbacks(audioStartTimeout);
                        handler.post(() -> {
                            if (!isReleased) {
                                Toast.makeText(this, "🔊 音频已连接", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                    audioAndroidMP.setOnErrorListener((mp, what, extra) -> {
                        Log.e(TAG, "🎧 Android Audio ERROR: what=" + what + " extra=" + extra);
                        audioFailed = true;
                        audioStarted = false;
                        handler.post(() -> {
                            if (!isReleased) {
                                Toast.makeText(this, "音频连接失败 (" + what + "/" + extra + ")",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        // ===== 音频失败自动降级（兜底）=====
                        // 这台 Android MediaPlayer 播不了这条 RTSP → 很可能 LibVLC 能播。
                        // 连续失败 2 次且当前非 combined → 自动切到 combined 模式（VLC 音视频同流）。
                        // 手机/平板/车机已默认 combined 模式，主要场景不会触发；此兜底主要保护
                        // 电视/盒子上 RTSP 第二个连接不稳定或音频解码器异常的情况。
                        audioFailCount++;
                        if (audioFailCount >= 2 && !useCombinedMode && !isReleased) {
                            Log.w(TAG, "音频连续失败(" + audioFailCount + ") → 自动切换到 combined 模式（VLC 音视频同流）");
                            handler.post(() -> {
                                if (!isReleased) toggleCombinedMode();
                            });
                        }
                        return true;
                    });
                    audioAndroidMP.setOnCompletionListener(mp -> {
                        Log.w(TAG, "🎧 Android Audio: completion (unexpected for RTSP)");
                        audioStarted = false;
                    });
                    audioAndroidMP.setDataSource(url);
                    audioAndroidMP.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    audioAndroidMP.prepareAsync();
                    Log.i(TAG, "🎧 Android Audio: prepareAsync() called for RTSP");

                    handler.removeCallbacks(audioStartTimeout);
                    handler.postDelayed(audioStartTimeout, AUDIO_START_TIMEOUT);
                } catch (Exception e) {
                    Log.e(TAG, "🎧 Android Audio start exception", e);
                    audioFailed = true;
                }
            });
        });
    }

    private void stopAudioAsync(final Runnable then) {
        final android.media.MediaPlayer amp = audioAndroidMP;
        if (amp == null) {
            if (then != null) then.run();
            return;
        }
        audioAndroidMP = null;
        audioStarted = false;
        new Thread(() -> {
            try { amp.stop(); } catch (Throwable ignored) {}
            try { amp.release(); } catch (Throwable ignored) {}
            Log.d(TAG, "🎧 Android Audio: stopped and released");
            if (then != null) handler.post(then);
        }, "audio-stop").start();
    }

    // ===== Video stop/rebuild =====

    private void stopVideoAsync(final Runnable then) {
        final MediaPlayer vmp = videoMediaPlayer;
        // Cancel any pending audio start — if video is stopping, audio should too.
        handler.removeCallbacks(startAudioDelayed);
        handler.removeCallbacks(audioStartTimeout);
        if (vmp == null) {
            if (then != null) then.run();
            return;
        }
        userStop = true;
        // 主动重建：异步 stop() 的 Stopped 事件晚到，置位抑制，避免被误判"意外停止"→ 双重重播。
        suppressStopped = true;
        new Thread(() -> {
            try {
                if (vmp.isPlaying()) vmp.stop();
            } catch (Throwable t) {
                Log.w(TAG, "Video async stop error: " + t);
            }
            if (then != null) handler.post(then);
        }, "video-stop").start();
    }

    private void rebuildEngine(final boolean playAfter) {
        final LibVLC oldLib = libVLC;
        final android.media.MediaPlayer oldAudio = audioAndroidMP;
        final MediaPlayer oldVideo = videoMediaPlayer;
        libVLC = null;
        videoMediaPlayer = null;
        audioAndroidMP = null;
        isPlaying = false;
        expectedPlaying = false;
        userStop = true;
        audioStarted = false;
        handler.removeCallbacks(freezeDetector);
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(connectTimer);
        handler.removeCallbacks(audioStartTimeout);
        handler.removeCallbacks(startAudioDelayed);

        new Thread(() -> {
            try {
                if (oldVideo != null) {
                    try { if (oldVideo.isPlaying()) oldVideo.stop(); } catch (Throwable ignored) {}
                    try { oldVideo.getVLCVout().detachViews(); } catch (Throwable ignored) {}
                    oldVideo.release();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Release video MP error: " + t);
            }
            try {
                if (oldAudio != null) {
                    try { if (oldAudio.isPlaying()) oldAudio.stop(); } catch (Throwable ignored) {}
                    try { oldAudio.release(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                Log.w(TAG, "Release audio MP error: " + t);
            }
            try {
                if (oldLib != null) oldLib.release();
            } catch (Throwable t) {
                Log.w(TAG, "Release video LibVLC error: " + t);
            }
            Log.i(TAG, "Old engine released (rebuild)");

            handler.post(() -> {
                if (isReleased) return;
                if (initEngine()) {
                    if (playAfter) {
                        playChannel(currentChannelIndex, useSubStream);
                    }
                }
            });
        }, "vlc-rebuild").start();
    }

    // ===== Freeze handling (video-only, audio can't cause freezes anymore) =====

    private void handleFreeze() {
        if (isReleased) return;

        Log.w(TAG, "Handling video freeze: useSubStream=" + useSubStream
                + " retryCount=" + mFreezeRetryCount + "/" + MAX_FREEZE_RETRIES);

        isPlaying = false;
        rebuffering = false;
        lastBufferingPct = -1f;
        lastBufferingChangeMs = 0;
        handler.removeCallbacks(freezeDetector);

        if (mFreezeRetryCount < MAX_FREEZE_RETRIES) {
            mFreezeRetryCount++;
            Log.i(TAG, "Retrying same stream (attempt " + mFreezeRetryCount + "/" + MAX_FREEZE_RETRIES + ")");
            stopVideoAsync(() -> handler.postDelayed(() -> {
                if (!isReleased) playChannel(currentChannelIndex, useSubStream);
            }, 1000));
        } else if (!useSubStream && !config.isUseSubStream()) {
            Log.i(TAG, "Main stream keeps freezing — trying sub stream");
            mFreezeRetryCount = 0;
            mainStreamFailed = true;
            stopVideoAsync(() -> handler.postDelayed(() -> {
                if (!isReleased) playChannel(currentChannelIndex, true);
            }, 1000));
        } else {
            Log.i(TAG, "All streams failed — showing freeze error");
            String name = config.getChannelName(currentChannelIndex);
            overlayError.setText("画面冻结\n\n" + name + " 视频流停滞\n"
                + "已多次重试仍无法恢复\n\n"
                + "确认键重试 | 返回键退出");
            overlayError.setVisibility(View.VISIBLE);
            // DDNS：反复冻结也可能是 IP 切换期的旧连接，强刷解析备着重试
            config.refreshHostResolution();
        }
    }

    // ===== Key handling =====

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int channelCount = config.getChannelCount();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (currentChannelIndex < channelCount - 1) {
                    mainStreamFailed = false;
                    mFreezeRetryCount = 0;
                    playChannel(currentChannelIndex + 1);
                } else {
                    Toast.makeText(this, "已经是最后一个通道", Toast.LENGTH_SHORT).show();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (currentChannelIndex > 0) {
                    mainStreamFailed = false;
                    mFreezeRetryCount = 0;
                    playChannel(currentChannelIndex - 1);
                } else {
                    Toast.makeText(this, "已经是第一个通道", Toast.LENGTH_SHORT).show();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Handled by dispatchKeyEvent (short/long press detection).
                // Here just return true so it's not consumed by default logic.
                return true;

            case KeyEvent.KEYCODE_DPAD_UP: {
                int next = Math.min(currentChannelIndex + 5, channelCount - 1);
                if (next != currentChannelIndex) {
                    mainStreamFailed = false;
                    mFreezeRetryCount = 0;
                    playChannel(next);
                }
                return true;
            }

            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int prev = Math.max(currentChannelIndex - 5, 0);
                if (prev != currentChannelIndex) {
                    mainStreamFailed = false;
                    mFreezeRetryCount = 0;
                    playChannel(prev);
                }
                return true;
            }

            case KeyEvent.KEYCODE_M:
                toggleAudio();
                return true;

            case KeyEvent.KEYCODE_MENU:
                // Handled by dispatchKeyEvent (short/long press detection)
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===== 多画面进入的单画面页：双击退出返回多画面 =====

    /**
     * DOWN 时手写双击判定（与 MultiViewActivity 同套：Activity 级 dispatchTouchEvent 最底层，
     * 绝不被内层 view 拦截）。仅 fromMulti（多画面独立取流进入）时双击才 finish() 返回多画面；
     * 普通进入（单机/列表）保持原有行为不变。同一物理事件（downTime）只判定一次，防多入口自误判。
     */
    private boolean detectExitDoubleTapDown(MotionEvent e) {
        if (!fromMulti) return false;
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        if (e.getDownTime() == mLastDblDownTime) return false;
        mLastDblDownTime = e.getDownTime();
        float rx = e.getRawX(), ry = e.getRawY();
        long now = System.currentTimeMillis();
        boolean hit = mDLastTimeMs != 0
                && (now - mDLastTimeMs) <= DBL_TAP_WINDOW_MS
                && Math.abs(rx - mDLastDownX) <= DBL_TAP_SLOP_PX
                && Math.abs(ry - mDLastDownY) <= DBL_TAP_SLOP_PX;
        // 更新上一击记录（无论是否命中都刷新，用于下次判定）
        mDLastTimeMs = now;
        mDLastDownX = rx;
        mDLastDownY = ry;
        if (hit) {
            mDLastTimeMs = 0L; // 抑制连击
            mManualDoubleTapAtMs = System.currentTimeMillis(); // 抑制第一击触发的单击 overlay
            Log.i(TAG, "单画面双击命中 → 返回多画面 ch=" + currentChannelIndex);
            exitSingleToMulti();
            return true;
        }
        return false;
    }

    /** 双击退出单画面返回多画面：写回 multi 记忆后 finish()，回归 MultiViewActivity 宫格。 */
    private void exitSingleToMulti() {
        try {
            if (config != null) {
                config.setLastViewMode("multi");
                // 保留上次布局记忆（MultiViewActivity.onStop 也会写，这里双保险），
                // 通道索引保持当前值，下次冷启动回多画面宫格而非单画面。
            }
        } catch (Throwable t) {
            Log.w(TAG, "双击返回写 multi 记忆异常: " + t.getMessage());
        }
        finish();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        // 只 DOWN 判双击；其它动作原样下发，保证滑动/长按等手势不受影响。
        if (action == MotionEvent.ACTION_DOWN) {
            boolean consumed = detectExitDoubleTapDown(ev);
            if (consumed) {
                // 已命中双击并执行返回：直接消费这一击 DOWN，避免再触发 surface 层的单击 overlay。
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // ===== Menu key: short press = toggle audio, long press = toggle dual/combined MP mode =====
    private long menuKeyDownTime = 0L;
    private boolean menuKeyHandled = false;
    // ===== OK/center key: short press = confirm, long press = toggle audio =====
    private long centerKeyDownTime = 0L;
    private boolean centerKeyHandled = false;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // --- MENU / 三横线 键: 长按 → 切换双MP/组合模式（遥控器上没有信息键所以用这个） ---
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    menuKeyDownTime = System.currentTimeMillis();
                    menuKeyHandled = false;
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!menuKeyHandled) {
                    menuKeyHandled = true;
                    long duration = System.currentTimeMillis() - menuKeyDownTime;
                    if (duration >= 800) {
                        // 长按菜单键 ≥800ms → 切换 双MP分离 ↔ 单MP混合
                        toggleCombinedMode();
                    } else {
                        toggleAudio();
                    }
                }
                return true;
            }
        }

        // --- 中心OK/确认键 (KEYCODE_DPAD_CENTER / KEYCODE_ENTER): 长按 → 切换音频 ---
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_I) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    centerKeyDownTime = System.currentTimeMillis();
                    centerKeyHandled = false;
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!centerKeyHandled) {
                    centerKeyHandled = true;
                    long duration = System.currentTimeMillis() - centerKeyDownTime;
                    if (duration >= 800) {
                        // 长按OK键 ≥800ms → 开关音频
                        toggleAudio();
                    } else {
                        // 短按OK键 = 原有行为
                        if (overlayError.getVisibility() == View.VISIBLE) {
                            mainStreamFailed = false;
                            mFreezeRetryCount = 0;
                            playChannel(currentChannelIndex);
                        } else {
                            overlayChannel.setVisibility(View.VISIBLE);
                            handler.removeCallbacks(hideOverlay);
                            handler.postDelayed(hideOverlay, OVERLAY_DURATION);
                        }
                    }
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ===== Audio toggle: instant, no video rebuild! =====
    private void toggleAudio() {
        if (isReleased) return;

        if (useCombinedMode) {
            // --- COMBINED MODE: audio is decoded inside video MP, toggle via volume ---
            audioEnabled = !audioEnabled;
            try {
                if (videoMediaPlayer != null) {
                    videoMediaPlayer.setVolume(audioEnabled ? 100 : 0);
                }
                audioStarted = audioEnabled;
                audioFailed = false;
            } catch (Throwable t) {
                Log.w(TAG, "Combined mode setVolume error: " + t.getMessage());
            }
            Toast.makeText(this,
                    "音频：" + (audioEnabled ? "开(单MP混合模式)" : "关(单MP混合模式)")
                            + (audioEnabled ? "\n⚠ 若画面卡顿：长按菜单键切回双MP模式" : ""),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // --- DUAL MODE (default): start / stop separate audio MP ---
        if (audioEnabled) {
            audioEnabled = false;
            audioFailed = false;
            handler.removeCallbacks(audioStartTimeout);
            handler.removeCallbacks(startAudioDelayed);
            stopAudioAsync(null);
            Toast.makeText(this, "音频：关(双MP分离模式)", Toast.LENGTH_SHORT).show();
        } else {
            audioEnabled = true;
            audioFailed = false;
            audioStarted = false;
            try {
                if (isPlaying) {
                    String url = useSubStream
                        ? config.getRtspUrlSubStream(currentChannelIndex)
                        : config.getRtspUrl(currentChannelIndex);
                    startAudio(url);
                    Toast.makeText(this, "音频：开(双MP分离模式，连接中...)", Toast.LENGTH_SHORT).show();
                } else {
                    // Not yet playing — delay starter will fire after video Playing
                    Toast.makeText(this, "音频：开(等待视频就绪)", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "音频启动失败", Toast.LENGTH_SHORT).show();
                audioEnabled = false;
            }
        }
    }

    // ===== Toggle combined mode (long-press INFO / MENU key triggers) =====
    // Flips useCombinedMode, rebuilds engine (video engine --no-audio flag changes),
    // and re-plays current channel.
    private void toggleCombinedMode() {
        if (isReleased) return;
        useCombinedMode = !useCombinedMode;
        audioStarted = false;
        audioFailed = false;
        audioFailCount = 0;
        handler.removeCallbacks(startAudioDelayed);
        handler.removeCallbacks(audioStartTimeout);
        Log.i(TAG, "⚠ MODE TOGGLE — useCombinedMode=" + useCombinedMode
                + " → rebuilding engine and replaying channel");
        Toast.makeText(this,
                useCombinedMode
                    ? "切换到：单MP混合模式(音视频同流)\n适合：NVR限制连接数/双MP无声\n⚠ 若卡画面：长按菜单键切回"
                    : "切换到：双MP分离模式(推荐)\n视频优先稳定，音频分离解码",
                Toast.LENGTH_LONG).show();
        rebuildEngine(true);  // initEngine() re-reads useCombinedMode for its engine flags
    }

    // ===== Activity lifecycle =====

    @Override
    protected void onResume() {
        super.onResume();
        // Re-request audio focus (with REAL listener — NEVER null on TV!)
        if (audioManager != null) {
            try {
                int fr = audioManager.requestAudioFocus(audioFocusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                int maxV = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxV, 0);
                boolean muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
                Log.i(TAG, "🎧 onResume: AudioFocus=" + fr + " (1=GRANTED), vol forced to "
                        + maxV + ", muted=" + muted);
            } catch (Throwable t) {
                Log.w(TAG, "🎧 onResume audio setup error: " + t.getMessage());
            }
        }
        MediaPlayer vmp = videoMediaPlayer;
        if (vmp != null && !isReleased && isPlaying) {
            try {
                if (!vmp.isPlaying()) vmp.play();
            } catch (Exception ignored) {}
            // 回到前台且仍在播放态：重新武装冻结检测（onPause 已把它移除并复位计时）。
            lastFrameTimeMs = System.currentTimeMillis();
            handler.removeCallbacks(freezeDetector);
            handler.postDelayed(freezeDetector, FREEZE_CHECK_INTERVAL);
        }
        // Combined mode: re-enable volume after resume
        if (useCombinedMode && audioEnabled && vmp != null) {
            try { vmp.setVolume(100); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 进入后台：停止冻结/重新缓冲计时——后台无媒体帧是正常现象，
        // 若让计时延续，恢复前台时可能立刻误判"重新缓冲停滞"而重建播放器（后台耗流量）。
        // 注意与下方 userStop=true 互不冲突（userStop 只影响 Stopped 事件重试语义）。
        handler.removeCallbacks(freezeDetector);
        // 退出/后台：作废排期中的缩略图截图（否则后台偷偷截图，浪费电量且可能截到错误画面）
        handler.removeCallbacks(thumbnailCaptureRunnable);
        rebuffering = false;
        lastBufferingPct = -1f;
        lastBufferingChangeMs = 0;
        MediaPlayer vmp = videoMediaPlayer;
        if (vmp != null && !isReleased) {
            try {
                userStop = true;
                vmp.pause();
            } catch (Exception ignored) {}
        }
        android.media.MediaPlayer amp = audioAndroidMP;
        if (amp != null && !isReleased) {
            try {
                if (amp.isPlaying()) amp.pause();
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // ===== 退出记忆：写 last_view_mode=single + 通道 + 码流偏好 =====
        // 设计 §5：单画面退出后下次冷启动直进单画面对应通道。
        // 例外：从多画面进入的本页（fromMulti=true），无论双击返回（exitSingleToMulti 已写 multi）、
        //       系统返回键、Home 杀进程还是直接退出（只走 onStop），都必须统一把 last_view_mode
        //       覆写为 "multi"——兜底 MultiViewActivity.startPlayer 进入时无条件写的 "single"，
        //       否则下次冷启动会错误直进单画面而不是回多画面宫格。
        try {
            if (config != null) {
                if (fromMulti) {
                    // 统一兜底：任意退出路径下次都回多画面
                    config.setLastViewMode("multi");
                } else {
                    config.setLastViewMode("single");
                    config.setLastChannelIndex(currentChannelIndex);
                }
                // 当前实际使用的码流写入偏好：sub/main/auto（由 useSubStream 反推当前全局设置）
                if (useSubStream == config.isUseSubStream()) {
                    config.setLastStreamType("auto");
                } else {
                    config.setLastStreamType(useSubStream ? "sub" : "main");
                }
                Log.i(TAG, "onStop: 保存上次状态 " + (fromMulti ? "multi(多画面进入，统一回多画面)" : "single")
                        + " ch=" + currentChannelIndex
                        + " streamType=" + config.getLastStreamType());
            }
        } catch (Throwable t) {
            Log.w(TAG, "onStop 保存状态异常: " + t.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isReleased = true;
        expectedPlaying = false;
        handler.removeCallbacks(hideOverlay);
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(connectTimer);
        handler.removeCallbacks(freezeDetector);
        handler.removeCallbacks(audioStartTimeout);
        handler.removeCallbacks(thumbnailCaptureRunnable);
        releasePlayerAsync();
    }

    private void releasePlayerAsync() {
        final MediaPlayer oldVideo = videoMediaPlayer;
        final android.media.MediaPlayer oldAudio = audioAndroidMP;
        final LibVLC oldLib = libVLC;
        videoMediaPlayer = null;
        audioAndroidMP = null;
        libVLC = null;
        isPlaying = false;
        new Thread(() -> {
            try {
                if (oldVideo != null) {
                    try { if (oldVideo.isPlaying()) oldVideo.stop(); } catch (Throwable ignored) {}
                    try { oldVideo.getVLCVout().detachViews(); } catch (Throwable ignored) {}
                    oldVideo.release();
                }
            } catch (Throwable t) {
                Log.w(TAG, "release video MP error: " + t);
            }
            try {
                if (oldAudio != null) {
                    try { if (oldAudio.isPlaying()) oldAudio.stop(); } catch (Throwable ignored) {}
                    try { oldAudio.release(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                Log.w(TAG, "release audio MP error: " + t);
            }
            try {
                if (oldLib != null) oldLib.release();
            } catch (Throwable t) {
                Log.w(TAG, "release video LibVLC error: " + t);
            }
            try {
                if (mVideoSurface != null) {
                    mVideoSurface.release();
                    mVideoSurface = null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "release surface error: " + t);
            }
            try {
                if (audioManager != null) {
                    audioManager.abandonAudioFocus(null);
                }
            } catch (Throwable ignored) {}
        }, "vlc-release").start();
    }

    private void writeCrashLog(Throwable t) {
        try {
            File dir = new File(getFilesDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault());
            File logFile = new File(dir, "crash_" + sdf.format(new Date()) + ".txt");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            FileWriter fw = new FileWriter(logFile);
            fw.write("Time: " + time + "\n");
            fw.write("ChannelIndex: " + currentChannelIndex + "\n");
            fw.write("AudioEnabled: " + audioEnabled + "\n");
            fw.write("Error:\n");
            fw.write(sw.toString());
            fw.close();
        } catch (Exception ignored) {}
    }
}
