package com.litevision.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RtspFrameGrabber — RTSP 抓帧器（libVLC + 真 TextureView 渲染）。
 *
 * <p>背景：ISAPI HTTP 抓图（{@link NvrClient} + Digest）在"用户域名反代到 NVR"的站点上普遍不通
 * （反代改写认证头 / 端口未映射），而 RTSP 取流路径已被真机验证可用。本类用 libVLC 播一小段子码流，
 * 渲染到调用方传入的<b>真 TextureView</b>（与 {@link MultiViewActivity} / {@link PlayerActivity}
 * 完全相同的渲染路径），出首帧后用 {@link TextureView#getBitmap()} 拿帧，作为
 * {@link ThumbnailLoader} 的回退源。
 *
 * <p><b>历史教训（详见 2026-09-05 调试日志）：</b>
 * <ul>
 *   <li>旧实现 v1：EGL pbuffer + glReadPixels 离屏渲染 → 模拟器/MuMu 上 EGL pbuffer 不可用，失败；</li>
 *   <li>旧实现 v2：{@code new SurfaceTexture(0)} + PixelCopy 离屏抓帧 → logcat 实锤
 *       {@code "libvlc video output creation failed"} / {@code "failed to create video output"} /
 *       {@code "libvlc window: request 1 not implemented"} —— 离屏 ST 没有 GL 上下文，
 *       libVLC vout 拿到 Surface 后立即放弃渲染（连 OES 纹理都无法绑定），永远等不到首帧；</li>
 *   <li><b>最终方案</b>：复用多画面已验证的渲染路径 —— 调用方传一个 attached to Window 的 TextureView
 *       （{@code android-display} vout 认得，绑得了 OES 纹理），抓帧时 libVLC 渲染到此 TV，
 *       首帧回调后 {@link TextureView#getBitmap()} 同步拿图。</li>
 * </ul>
 *
 * <p><b>libVLC takeSnapshot/saveFrame：</b>libvlc-all 3.5.1 Java API 中并不存在该签名
 * （已用 javap 核对 aar 内 classes.jar），只能自行渲染取帧。
 *
 * <p>实现要点：
 * <ul>
 *   <li><b>离屏优先（2026-09 新增，默认路径）</b>：抓帧 = 一个独立 {@link MediaPlayer} +
 *       {@link ImageReader} 提供的离屏 Surface + 共享 LibVLC 实例（懒加载、进程内一份）；
 *       不依赖任何 View attach 到窗口，帧到达后直接从 {@link Image} 取像素转 Bitmap；</li>
 *   <li><b>TV 回退（旧路径，保底）</b>：离屏拿不到帧时退回"调用方提供的 TextureView"路径
 *       （多画面/单画面已验证的渲染后端），保证抓图能力不归零；</li>
 *   <li>取流参数统一取自 {@link VlcOptions}（与 {@link PlayerEngine}、{@link MultiViewActivity}
 *       同源的单一事实源，避免各处漏改导致行为漂移）；本类 decodeSpeedup=false 以保缩略图画质；</li>
 *   <li>整个抓帧过程（创建/play/stop/release）全在后台线程完成；stop 与 release 之间留 120ms 间隔，
 *       防 VLC 死锁；release 干净，不占 NVR 并发额度；</li>
 *   <li>任何一步失败都返回 null，由调用方决定是否保留 placeholder。</li>
 * </ul>
 */
public final class RtspFrameGrabber {

    private static final String TAG = "RtspFrameGrabber";

    /**
     * 抓帧渲染尺寸（TextureView setDefaultBufferSize 的目标尺寸，libVLC vout 按此缩放）。
     * 列表卡片缩略图区 100dp 高，640x360 足够清晰，同时把解码开销压到最低。
     */
    private static final int FRAME_WIDTH = 640;
    private static final int FRAME_HEIGHT = 360;

    /** 首帧到达后的稳定等待（毫秒）：首帧偶发纯黑/尚未完全解码，多等一小会儿再 getBitmap 更可信。 */
    private static final long SETTLE_DELAY_MS = 400L;

    /**
     * 离屏取帧：至少收到几帧才收图。
     * 前 1 帧常常尚未完全解码或偏黑，多等一帧再取，画质更可信；
     * 若流只吐出 1 帧就停，超时后会用 frameRef 里那帧兜底（不会白等）。
     */
    private static final int OFFSCREEN_MIN_FRAMES = 2;

    /** 主线程 attachViews/play 的等待上限（毫秒）：只是防止主线程卡死时后台线程永久挂起。 */
    private static final long ATTACH_TIMEOUT_MS = 1000L;

    /** stop() 与 release() 之间的间隔：VLC 的 stop 是异步派发，留一拍再释放防死锁。 */
    private static final long STOP_RELEASE_GAP_MS = 120L;

    /** getBitmap() 在主线程执行的超时（毫秒）；超时则取 latch 触发的首帧 Bitmap 兜底。 */
    private static final long GET_BITMAP_TIMEOUT_MS = 1000L;

    /** 主线程 Handler：用于注册/注销 TextureView 的 SurfaceTextureListener（必须主线程）。 */
    private final Handler mainHandler;

    private final Context appContext;
    private volatile LibVLC libVLC;
    private volatile boolean libVlcFailed = false;
    /** 已 shutdown：拒绝新抓帧（列表页销毁后不再连 NVR 抢并发额度）。 */
    private volatile boolean released = false;

    /**
     * 离屏取帧回调线程（懒加载）。
     * {@link ImageReader#setOnImageAvailableListener} 必须挂在带 Looper 的线程上；
     * 抓帧本身跑在无 Looper 的后台 executor 线程，故单独开一条。
     */
    private volatile HandlerThread imageThread;
    private volatile Handler imageHandler;

    public RtspFrameGrabber(Context context, Handler mainHandler) {
        this.appContext = (context != null) ? context.getApplicationContext() : null;
        // mainHandler 为 null 时退化为当前线程 Handler（旧调用兼容；正常情况由 ThumbnailLoader 传 Activity handler）
        this.mainHandler = mainHandler != null ? mainHandler : new Handler();
    }

    /**
     * 抓一帧到传入的 TextureView（阻塞调用，必须在后台线程）。
     *
     * <p>调用方须保证：
     * <ul>
     *   <li>{@code tv} 已 attached to Window（即 {@code tv.isAvailable() == true}，
     *       RecyclerView itemHolder 在屏幕上时已满足）；</li>
     *   <li>{@code tv} 的宽高 > 0（layout 不为 0dp） —— 离屏 ST(0) 失败的根因是
     *       "没有 GL 上下文"，真 TextureView 由系统提供，无此问题；</li>
     *   <li>调用方在抓帧期间不要触碰 tv 的 SurfaceTextureListener（grabber 内部会注册临时 listener，
     *       抓帧结束自动注销）。</li>
     * </ul>
     *
     * @param tv          已 attached to Window 的真 TextureView（与多画面同源渲染路径）
     * @param rtspUrl     RTSP 地址（来自 {@link ConfigManager#getRtspUrl(int, boolean)}）
     * @param avcodecHw   libVLC avcodec-hw 取值（{@link ConfigManager#getAvcodecHwValue()}）
     * @param timeoutMs   等首帧超时（毫秒），超时返回 null
     * @return 抓到的帧；失败/超时返回 null（不抛异常）
     */
    public Bitmap grab(final TextureView tv, final String rtspUrl, final String avcodecHw,
                       final long timeoutMs) {
        return grab(tv, rtspUrl, avcodecHw, timeoutMs, -1);
    }

    /**
     * 抓一帧（阻塞调用，必须在后台线程）。
     *
     * <p><b>离屏优先</b>：先走 {@link #grabOffscreen} —— 用 ImageReader 提供的 Surface 作
     * libVLC vout 渲染目标，全程不依赖任何 View attach 到窗口。真机实测（2026-09）列表项
     * TextureView 在 RecyclerView 里频繁 attach/detach，抓帧那一刻常常 tvAttached=false，
     * TV 路径必失败（{@code RTSP 抓帧开始 ... tvAttached=false tvAvailable=false}）；
     * 离屏路径不受此影响。
     *
     * <p><b>失败回退</b>：离屏拿不到帧时退回原 TV 路径（保底，不让抓图能力归零）。
     * 当 tv 为空或当前已失效时，TV 路径会立刻返回，几乎不额外耗时。
     *
     * @param channelIndex 仅用于日志（便于按通道排查）；-1 表示未知
     */
    public Bitmap grab(final TextureView tv, final String rtspUrl, final String avcodecHw,
                       final long timeoutMs, final int channelIndex) {
        if (released) {
            Log.d(TAG, "抓帧器已释放，跳过抓帧");
            return null;
        }
        if (rtspUrl == null || rtspUrl.isEmpty()) {
            Log.w(TAG, "RTSP 地址为空，跳过抓帧");
            return null;
        }

        // ===== ① 离屏抓帧（ImageReader，不依赖列表项 TextureView）=====
        Bitmap offscreen = grabOffscreen(rtspUrl, avcodecHw, timeoutMs, channelIndex);
        if (offscreen != null) {
            return offscreen;
        }

        // ===== ② 离屏失败 → 回退原 TextureView 路径 =====
        if (tv == null) {
            Log.w(TAG, "离屏抓帧失败且 TextureView 为空，放弃 ch" + channelIndex);
            return null;
        }
        // 严格校验：TV 必须 attached + available + 有 SurfaceTexture，三者任一不满足直接放弃。
        // 配合 ThumbnailLoader.cancelledTvs 集合，RV itemHolder 回收时不会让 libVLC
        // 写入已销毁的 SurfaceTexture，避免 "BufferQueue has been abandoned"。
        final TextureView tvFinal = tv;
        if (!tvFinal.isAttachedToWindow() || !tvFinal.isAvailable()
                || tvFinal.getSurfaceTexture() == null) {
            Log.w(TAG, "TV 无效（attached=" + tvFinal.isAttachedToWindow()
                    + " available=" + tvFinal.isAvailable()
                    + " st=" + (tvFinal.getSurfaceTexture() != null)
                    + "），跳过抓帧 url=" + maskUrl(rtspUrl));
            return null;
        }
        final LibVLC lib = ensureLibVlc(avcodecHw);
        if (lib == null) {
            return null;
        }
        final long t0 = System.currentTimeMillis();
        // ★ surface 现在由主线程 runnable 创建（ANativeWindow 必须在主线程初始化，模拟器上
        // 后台线程 new Surface(ST) 会让 vout attach 报 `request 1 not implemented`）。
        // surfaceHolder[0] 跨线程持有 surface 引用，finally 块用它做 release。
        final Surface[] surfaceHolder = new Surface[1];
        MediaPlayer mediaPlayer = null;
        Media media = null;
        Bitmap bitmap = null;
        try {
            // 真 TextureView 的 ST 由系统提供，已绑 GL 上下文，libVLC vout 拿这个 ST 能正常
            // 渲染（与多画面同路径）。getBitmap() 是 UI 线程调用。
            // ★ 关键（2026-09-06）：ChannelViewHolder 构造时已设置永驻 STListener（仿 ChannelTileView），
            // 此时 tv.getSurfaceTexture() 返回的是"已被 listener onSurfaceTextureAvailable 回调
            // 处理过"的 ST —— 之前未设 listener 时返回的 ST 是"无消费者"状态，导致 SurfaceFlinger
            // 把它的 BufferQueue 视为 abandoned，libVLC vout attach 必报 request 1 not implemented。
            final SurfaceTexture surfaceTexture = tv.getSurfaceTexture();
            if (surfaceTexture == null) {
                Log.w(TAG, "tv.getSurfaceTexture() 为空，跳过抓帧");
                return null;
            }
            Log.i(TAG, "抓帧前 ST 状态 st=" + surfaceTexture
                    + " (TV 已 attached=" + tv.isAttachedToWindow() + " available=" + tv.isAvailable() + ")");

            mediaPlayer = new MediaPlayer(lib);
            final AtomicBoolean encounteredError = new AtomicBoolean(false);
            mediaPlayer.setEventListener(event -> {
                if (event.type == MediaPlayer.Event.EncounteredError) {
                    Log.w(TAG, "VLC EncounteredError，抓帧中止: " + maskUrl(rtspUrl));
                    encounteredError.set(true);
                }
            });

            // 首帧信号：临时 STListener（必须在主线程注册），拿到首帧就 latch.countDown。
            final CountDownLatch firstFrameLatch = new CountDownLatch(1);
            final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>();

            // 【关键】libVLC 的 IVLCVout 操作必须在主线程调（与多画面 ChannelTileView / PlayerEngine
            // 走同一线程模型——它们在 onSurfaceTextureAvailable 回调里 setVideoSurface，那个回调
            // 一定在主线程）。早一版本在后台线程调，导致 libVLC vout 报错
            // "libvlc window: request 1 not implemented"，永远拿不到首帧。
            final SurfaceTextureListenerProxy listenerProxy = new SurfaceTextureListenerProxy();
            listenerProxy.firstFrameLatch = firstFrameLatch;
            listenerProxy.bitmapRef = bitmapRef;
            listenerProxy.tv = tv;

            // 回到稳定版本：先 setMedia + play，再注册临时 listener 等首帧。
            //   ChannelTileView 那种"listener.onSurfaceTextureAvailable 里 setVideoSurface"
            //   路径在 RV itemHolder TV 上被 libVLC 反复报 `request 1 not implemented`
            //   （详见抓帧器顶部注释块关于 abandoned BufferQueue 的多轮调试结论）。
            //   抓帧器走"setMedia + play 先行，listener 仅作首帧通知"的简化路径，与多画面
            //   PlayerEngine.play → startMedia(setMedia+play) 后 listener 仅作 onFrameRendered
            //   通知的语义最接近。
            Media mediaEarly = new Media(lib, Uri.parse(rtspUrl));
            applyMediaOptions(mediaEarly, avcodecHw);
            mediaPlayer.setMedia(mediaEarly);
            mediaEarly.release();

            final MediaPlayer mpForMain = mediaPlayer;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!tv.isAttachedToWindow() || !tv.isAvailable()) {
                            Log.w(TAG, "主线程 vout 操作时 TV 已失效（attached=" + tv.isAttachedToWindow()
                                    + " available=" + tv.isAvailable() + "），跳过抓帧 url=" + maskUrl(rtspUrl));
                            firstFrameLatch.countDown();
                            return;
                        }
                        // Surface(ST) 在主线程创建（与 ChannelTileView.onSurfaceTextureAvailable 同源）
                        surfaceHolder[0] = new Surface(surfaceTexture);
                        // 注册临时 listener 等首帧回调（与多画面 ChannelTileView 的 onSurfaceTextureUpdated 语义一致）
                        tv.setSurfaceTextureListener(listenerProxy);
                        final IVLCVout vout = mpForMain.getVLCVout();
                        vout.setVideoSurface(surfaceHolder[0], null);
                        vout.setWindowSize(FRAME_WIDTH, FRAME_HEIGHT);
                        vout.attachViews();
                        mpForMain.play();
                    } catch (Throwable t) {
                        Log.e(TAG, "主线程 setVideoSurface/attachViews/play 异常: " + t.getMessage());
                        firstFrameLatch.countDown();
                    }
                }
            });

            // 等首帧（latch 由 UI 线程的 onSurfaceTextureUpdated 触发）
            boolean arrived = false;
            try {
                arrived = firstFrameLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (!arrived) {
                Log.w(TAG, "抓帧超时 " + timeoutMs + "ms error=" + encounteredError.get()
                        + " url=" + maskUrl(rtspUrl));
                return null;
            }

            // 首帧后再稳定一小会儿（首帧偶发纯黑/未完全解码），抓帧结果更可信
            try {
                Thread.sleep(SETTLE_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // 重新取一次最新一帧的 Bitmap（在 UI 线程同步执行，避免 getBitmap 在后台线程出问题）
            final Bitmap[] latestBitmap = new Bitmap[1];
            final CountDownLatch getLatch = new CountDownLatch(1);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        latestBitmap[0] = tv.getBitmap();
                    } catch (Throwable t) {
                        Log.w(TAG, "TextureView.getBitmap（第二轮）失败: " + t.getMessage());
                    } finally {
                        getLatch.countDown();
                    }
                }
            });
            try {
                getLatch.await(GET_BITMAP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            bitmap = latestBitmap[0];
            if (bitmap == null) {
                // 退化：用 latch 触发的首帧 bitmap（即使它可能不够新但至少有图）
                bitmap = bitmapRef.get();
            }
            return bitmap;
        } catch (Throwable t) {
            Log.e(TAG, "抓帧异常: " + t.getMessage(), t);
            return null;
        } finally {
            if (media != null) {
                try { media.release(); } catch (Throwable ignored) {}
            }
            if (mediaPlayer != null) {
                try { mediaPlayer.stop(); } catch (Throwable ignored) {}
                try { mediaPlayer.getVLCVout().detachViews(); } catch (Throwable ignored) {}
                try {
                    Thread.sleep(STOP_RELEASE_GAP_MS);
                } catch (Throwable ignored) {}
                try { mediaPlayer.release(); } catch (Throwable ignored) {}
            }
            if (surfaceHolder[0] != null) {
                try { surfaceHolder[0].release(); } catch (Throwable ignored) {}
            }
            // surfaceTexture 不 release（它是 tv 的 ST，TV 释放时统一处理）
            // 注销临时 STListener
            if (tv != null) {
                try {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            tv.setSurfaceTextureListener(null);
                        }
                    });
                } catch (Throwable ignored) {}
            }
            final long cost = System.currentTimeMillis() - t0;
            if (bitmap != null) {
                Log.i(TAG, "抓帧成功 " + cost + "ms " + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " url=" + maskUrl(rtspUrl));
            } else {
                Log.w(TAG, "抓帧失败/超时 " + cost + "ms url=" + maskUrl(rtspUrl));
            }
        }
    }

    /**
     * 离屏抓帧：用 {@link ImageReader} 提供的 Surface 作为 libVLC vout 的渲染目标，
     * 帧到达后直接从 {@link Image} 取像素转 Bitmap —— 全程不需要任何 View attach 到窗口。
     *
     * <p><b>为什么需要它</b>：列表项 TextureView（原路径）在 RecyclerView 里频繁
     * attach/detach，真机实测抓帧那一刻 {@code tvAttached=false / tvAvailable=false}，
     * 抓帧必失败；而 ThumbnailLoader 里"发起时 TV 就绪"的检查（waitTvAndStartGrab）
     * 无法保证抓帧期间 TV 一直有效。
     *
     * <p><b>格式选择（2026-09 真机结论）</b>：请求 {@link ImageFormat#YUV_420_888} ——
     * 视频产流端普遍支持的格式，转 Bitmap 走手写 BT.601 满量程转换（零额外依赖）。
     * 曾用 {@link PixelFormat#RGBA_8888}，真机 9 路全部 frames=0（vout 一帧都没渲进
     * BufferQueue），故改走 YUV；若产流端实际给的是 RGBA，{@link #imageToBitmap} 的
     * 默认分支 {@link #rgbaToBitmap} 仍会自动接管。两处格式只是这一行常量，可单点切换。
     *
     * @return 抓到的帧；失败/超时返回 null（不抛异常）
     */
    public Bitmap grabOffscreen(final String rtspUrl, final String avcodecHw,
                                final long timeoutMs, final int channelIndex) {
        if (released) {
            return null;
        }
        final LibVLC lib = ensureLibVlc(avcodecHw);
        if (lib == null) {
            Log.w(TAG, "离屏抓帧失败 ch" + channelIndex + " 原因=LibVLC 不可用");
            return null;
        }
        final Handler frameHandler = ensureImageHandler();
        if (frameHandler == null) {
            Log.w(TAG, "离屏抓帧失败 ch" + channelIndex + " 原因=取帧回调线程不可用");
            return null;
        }

        final long t0 = System.currentTimeMillis();
        ImageReader reader = null;
        MediaPlayer mediaPlayer = null;
        Bitmap bitmap = null;
        String failReason = "unknown";
        try {
            // 格式：YUV_420_888。
            // RGBA_8888 经真机验证（2026-09 MuMu）9 路全部 frames=0 —— vout 一帧都没渲进
            // BufferQueue，说明产流端不接受 RGBA_8888 的 BufferQueue 几何。
            // YUV_420_888 是视频产流端普遍支持的格式，改这里即可单点切换回退。
            reader = ImageReader.newInstance(FRAME_WIDTH, FRAME_HEIGHT, ImageFormat.YUV_420_888, 3);
            final ImageReader finalReader = reader;
            final CountDownLatch frameLatch = new CountDownLatch(1);
            final AtomicReference<Bitmap> frameRef = new AtomicReference<>();
            final AtomicBoolean counted = new AtomicBoolean(false);
            final AtomicInteger frameCount = new AtomicInteger(0);

            finalReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image image = null;
                    try {
                        image = r.acquireLatestImage();
                        if (image == null) {
                            return;
                        }
                        Bitmap b = imageToBitmap(image);
                        if (b != null) {
                            // 只保留最新一帧（旧帧会被后续覆盖）
                            frameRef.set(b);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "离屏取帧异常: " + t.getMessage());
                    } finally {
                        if (image != null) {
                            try { image.close(); } catch (Throwable ignored) {}
                        }
                        // 前几帧可能尚未完全解码/偏黑，攒够 OFFSCREEN_MIN_FRAMES 再收图
                        if (frameCount.incrementAndGet() >= OFFSCREEN_MIN_FRAMES
                                && counted.compareAndSet(false, true)) {
                            frameLatch.countDown();
                        }
                    }
                }
            }, frameHandler);

            mediaPlayer = new MediaPlayer(lib);
            mediaPlayer.setEventListener(event -> {
                if (event.type == MediaPlayer.Event.EncounteredError) {
                    Log.w(TAG, "离屏抓帧 VLC EncounteredError ch" + channelIndex
                            + " url=" + maskUrl(rtspUrl));
                }
            });

            Media mediaEarly = new Media(lib, Uri.parse(rtspUrl));
            applyMediaOptions(mediaEarly, avcodecHw);
            mediaPlayer.setMedia(mediaEarly);
            mediaEarly.release();

            // libVLC 的 IVLCVout 操作必须在主线程（与原 TV 路径、多画面 ChannelTileView 同源）
            final Surface frameSurface = finalReader.getSurface();
            final MediaPlayer mpForMain = mediaPlayer;
            final CountDownLatch attachLatch = new CountDownLatch(1);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        final IVLCVout vout = mpForMain.getVLCVout();
                        vout.setVideoSurface(frameSurface, null);
                        vout.setWindowSize(FRAME_WIDTH, FRAME_HEIGHT);
                        vout.attachViews();
                        // 诊断：区分"vout 压根没 attach 上"和"attach 了但产流端不出帧"。
                        // frames=0 + areViewsAttached=false → vout 创建/绑定失败（该换 vout 参数）；
                        // frames=0 + areViewsAttached=true  → vout 正常但没人往 Surface 写帧。
                        final boolean attached = vout.areViewsAttached();
                        mpForMain.play();
                        Log.i(TAG, "离屏 vout attach 结果 ch" + channelIndex
                                + " areViewsAttached=" + attached
                                + " surfaceNonNull=" + (frameSurface != null)
                                + " size=" + FRAME_WIDTH + "x" + FRAME_HEIGHT
                                + " format=YUV_420_888");
                    } catch (Throwable t) {
                        Log.e(TAG, "离屏 attachViews/play 异常 ch" + channelIndex
                                + ": " + t.getMessage());
                        frameLatch.countDown();
                    } finally {
                        attachLatch.countDown();
                    }
                }
            });
            try {
                attachLatch.await(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            boolean arrived = false;
            try {
                arrived = frameLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (!arrived) {
                failReason = "timeout(frames=" + frameCount.get() + ")";
            }
            // 超时但已攒到部分帧时，用 frameRef 里最新那帧兜底（不白等一整轮）
            bitmap = frameRef.get();
            if (bitmap == null) {
                failReason = arrived ? "frame-null" : failReason;
            }
        } catch (Throwable t) {
            failReason = "exception:" + t.getMessage();
            Log.e(TAG, "离屏抓帧异常: " + t.getMessage(), t);
        } finally {
            if (mediaPlayer != null) {
                try { mediaPlayer.stop(); } catch (Throwable ignored) {}
                try { mediaPlayer.getVLCVout().detachViews(); } catch (Throwable ignored) {}
                try { Thread.sleep(STOP_RELEASE_GAP_MS); } catch (Throwable ignored) {}
                try { mediaPlayer.release(); } catch (Throwable ignored) {}
            }
            if (reader != null) {
                try { reader.setOnImageAvailableListener(null, null); } catch (Throwable ignored) {}
                // reader.close() 同时释放它 own 的 Surface（不要再单独 surface.release()）
                try { reader.close(); } catch (Throwable ignored) {}
            }
            final long cost = System.currentTimeMillis() - t0;
            if (bitmap != null) {
                Log.i(TAG, "离屏抓帧成功 ch" + channelIndex + " 耗时=" + cost + "ms "
                        + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " url=" + maskUrl(rtspUrl));
            } else {
                Log.w(TAG, "离屏抓帧失败 ch" + channelIndex + " 原因=" + failReason
                        + " 耗时=" + cost + "ms");
            }
        }
        return bitmap;
    }

    /**
     * 把 {@link ImageReader} 拿到的 {@link Image} 转成 Bitmap。
     *
     * <p>按 {@code image.getFormat()} 分派：YUV_420_888 走手动 YUV→RGB；
     * 其余（RGBA_8888 / FLEX_RGBA_8888）直接按行取 int 像素。
     */
    private static Bitmap imageToBitmap(Image image) {
        if (image == null) {
            return null;
        }
        if (image.getFormat() == ImageFormat.YUV_420_888) {
            return yuv420ToBitmap(image);
        }
        return rgbaToBitmap(image);
    }

    /**
     * RGBA_8888 → ARGB_8888 Bitmap。
     *
     * <p>{@link Image} 的像素在内存里是 R,G,B,A 字节序；小端读成 int 是 0xAABBGGRR，
     * 而 Bitmap 需要 0xAARRGGBB，故做一次 R/B 通道交换。
     */
    private static Bitmap rgbaToBitmap(Image image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();
        if (pixelStride != 4) {
            Log.w(TAG, "RGBA 取帧 pixelStride=" + pixelStride + " 非 4，仍按 4 字节解析");
        }
        final int[] pixels = new int[width * height];
        int rowOffset = 0;
        for (int y = 0; y < height; y++) {
            int offset = rowOffset;
            final int dstRow = y * width;
            for (int x = 0; x < width; x++) {
                final int v = buffer.getInt(offset);
                pixels[dstRow + x] = (v & 0xFF000000)
                        | ((v & 0x000000FF) << 16)
                        | (v & 0x0000FF00)
                        | ((v & 0x00FF0000) >> 16);
                offset += pixelStride;
            }
            rowOffset += rowStride;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    /**
     * YUV_420_888 → ARGB_8888 Bitmap（手动转换，不引入 RenderScript 依赖）。
     *
     * <p>标准 BT.601 满量程转换；640×360 量级在本方法的耗时是毫秒级，可接受。
     */
    private static Bitmap yuv420ToBitmap(Image image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        final Image.Plane[] planes = image.getPlanes();
        final ByteBuffer yBuf = planes[0].getBuffer();
        final ByteBuffer uBuf = planes[1].getBuffer();
        final ByteBuffer vBuf = planes[2].getBuffer();
        final int yRowStride = planes[0].getRowStride();
        final int yPixelStride = planes[0].getPixelStride();
        final int uRowStride = planes[1].getRowStride();
        final int uPixelStride = planes[1].getPixelStride();
        final int vRowStride = planes[2].getRowStride();
        final int vPixelStride = planes[2].getPixelStride();

        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            final int yRowOffset = y * yRowStride;
            final int uvRowOffset = (y >> 1);
            final int uRow = uvRowOffset * uRowStride;
            final int vRow = uvRowOffset * vRowStride;
            final int dstRow = y * width;
            for (int x = 0; x < width; x++) {
                final int yVal = (yBuf.get(yRowOffset + x * yPixelStride) & 0xFF) - 16;
                final int uVal = (uBuf.get(uRow + (x >> 1) * uPixelStride) & 0xFF) - 128;
                final int vVal = (vBuf.get(vRow + (x >> 1) * vPixelStride) & 0xFF) - 128;
                int r = (int) (1.164f * yVal + 1.596f * vVal);
                int g = (int) (1.164f * yVal - 0.392f * uVal - 0.813f * vVal);
                int b = (int) (1.164f * yVal + 2.017f * uVal);
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                pixels[dstRow + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    /**
     * 抓帧用临时 STListener：仅在首帧到达时回调一次（latch.countDown），后续帧不影响。
     * 单独抽出来作为内部类，是因为 TextureView.SurfaceTextureListener 是 interface，
     * 内嵌 Runnable 里 new 会反复创建 listener 对象，效率低且容易 leak。
     */
    private static final class SurfaceTextureListenerProxy implements TextureView.SurfaceTextureListener {
        CountDownLatch firstFrameLatch;
        AtomicReference<Bitmap> bitmapRef;
        TextureView tv;
        // 防 latch 已释放后还误调 tv.getBitmap()（latch 触发 onSurfaceTextureUpdated 多次时）
        private final AtomicBoolean consumed = new AtomicBoolean(false);

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            // no-op（vout setup 已在抓帧器主线程 runnable 内完成 —— 详见 grab() 注释）
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            // no-op
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            // 抓帧结束前不允许 TV 被销毁；返回 true = 我们接管生命周期（不会发生）
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            if (consumed.compareAndSet(false, true) && firstFrameLatch.getCount() > 0) {
                // 校验 TV 在收到首帧回调时仍有效（防 RV 在中途回收）
                if (tv == null || !tv.isAttachedToWindow() || !tv.isAvailable()) {
                    firstFrameLatch.countDown();
                    return;
                }
                Bitmap b = null;
                try {
                    b = tv.getBitmap();
                } catch (Throwable t) {
                    Log.w(TAG, "TextureView.getBitmap 失败: " + t.getMessage());
                }
                if (b != null) {
                    bitmapRef.set(b);
                }
                firstFrameLatch.countDown();
            }
        }
    }

    /** 释放共享的 LibVLC 实例（后台线程调用）；此后 {@link #grab} 一律直接返回 null。 */
    public void release() {
        released = true;
        final LibVLC lib;
        synchronized (this) {
            lib = libVLC;
            libVLC = null;
        }
        if (lib != null) {
            try {
                lib.release();
                Log.i(TAG, "缩略图抓帧 LibVLC 实例已释放");
            } catch (Throwable t) {
                Log.w(TAG, "LibVLC release 错误: " + t.getMessage());
            }
        }
        synchronized (this) {
            if (imageThread != null) {
                try { imageThread.quitSafely(); } catch (Throwable ignored) {}
                imageThread = null;
                imageHandler = null;
            }
        }
    }

    /**
     * 离屏取帧回调线程（懒加载）：ImageReader 的 onImageAvailable 必须挂在带 Looper 的线程上，
     * 而抓帧本身跑在无 Looper 的后台 executor 线程上，故单独开一条守护线程。
     */
    private synchronized Handler ensureImageHandler() {
        if (imageHandler != null) {
            return imageHandler;
        }
        if (released || imageThread != null) {
            return imageHandler;
        }
        try {
            final HandlerThread t = new HandlerThread("thumb-frame-io");
            t.setDaemon(true);
            t.start();
            imageThread = t;
            imageHandler = new Handler(t.getLooper());
        } catch (Throwable t) {
            Log.w(TAG, "取帧回调线程创建失败: " + t.getMessage());
            return null;
        }
        return imageHandler;
    }

    // ===== libVLC 实例（懒加载、进程内共享一份）=====

    private synchronized LibVLC ensureLibVlc(final String avcodecHw) {
        if (libVLC != null) {
            return libVLC;
        }
        if (libVlcFailed || appContext == null || released) {
            return null;
        }
        try {
            final String hw = (avcodecHw == null || avcodecHw.isEmpty())
                    ? ConfigManager.AVCODEC_HW_DISABLED : avcodecHw;
            final List<String> options = new ArrayList<>();
            options.add("--no-audio");                    // 抓帧不需要音频
            options.add("--vout=android-display");        // 复用多画面已验证过的渲染后端
            // 低延迟参数取自 VlcOptions（与播放侧同源，避免两处漏改）。
            // decodeSpeedup=false：抓帧要的是**画质**而非速度，
            // 跳过 deblocking(skiploopfilter) 会让缩略图发糊，故不启用解码提速类参数。
            VlcOptions.addInstanceOptions(options, hw, true, true, false);
            libVLC = new LibVLC(appContext, options);
            Log.i(TAG, "缩略图抓帧 LibVLC 实例已创建 avcodec-hw=" + hw);
            return libVLC;
        } catch (Throwable t) {
            libVlcFailed = true;
            Log.e(TAG, "缩略图抓帧 LibVLC 初始化失败: " + t.getMessage(), t);
            return null;
        }
    }

    private static void applyMediaOptions(Media media, String avcodecHw) {
        final String hw = (avcodecHw == null || avcodecHw.isEmpty())
                ? ConfigManager.AVCODEC_HW_DISABLED : avcodecHw;
        // 低延迟参数取自 VlcOptions（与实例级同源，避免两处漏改）。
        // decodeSpeedup=false：缩略图要画质，不跳过 deblocking（否则发糊）。
        VlcOptions.addMediaOptions(media, hw, true, true, false);
        media.addOption(":ipv4-timeout=5000");
    }

    private static String maskUrl(String url) {
        if (url == null) {
            return "";
        }
        // 隐藏 :password@ 段，避免密码落到 logcat
        int at = url.indexOf('@');
        if (at < 0) {
            return url;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0 || scheme + 3 > at) {
            return url;
        }
        return url.substring(0, scheme + 3) + "***@" + url.substring(at + 1);
    }
}