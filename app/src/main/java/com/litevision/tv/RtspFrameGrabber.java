package com.litevision.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   <li>抓帧 = 一个独立 {@link MediaPlayer} + 调用方提供的 TextureView + 共享 LibVLC 实例（懒加载、
 *       进程内一份）；TextureView 是多画面/单画面已经验证的渲染后端，不存在 GL 上下文缺失问题；</li>
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
        if (released) {
            Log.d(TAG, "抓帧器已释放，跳过抓帧");
            return null;
        }
        if (rtspUrl == null || rtspUrl.isEmpty()) {
            Log.w(TAG, "RTSP 地址为空，跳过抓帧");
            return null;
        }
        if (tv == null) {
            Log.w(TAG, "TextureView 为空，跳过抓帧");
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