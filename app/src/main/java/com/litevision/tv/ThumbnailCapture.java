package com.litevision.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.TextureView;
import android.view.Window;

import java.io.File;

/**
 * 缩略图截屏工具：用 {@link PixelCopy#request(Window, Rect, Bitmap, PixelCopy.OnPixelCopyFinishedListener, Handler)}
 * 把 Activity 窗口上 {@code TextureView} 所在区域的内容截成 Bitmap。
 *
 * <p><b>为什么必须用 Window PixelCopy，而不是 TextureView.getBitmap()：</b></p>
 * <ul>
 *   <li>libVLC android-display vout 的渲染路径是：解码 → SurfaceTexture/BufferQueue → SurfaceFlinger 合成。
 *       它<b>不经过 TextureView 的 Canvas/GPU 渲染路径</b>，因此
 *       {@link TextureView#getBitmap()} 实际读到的是"上一帧缓存的空 buffer"——实测返回<b>全黑</b>（见调试 2026-09-06，
 *       ch0 ch0.jpg 480x260 采样点全为 (0,0,0)，即使屏幕上画面清晰）。</li>
 *   <li>{@code PixelCopy.request(SurfaceTexture, ...)} 无此重载（仅有 SurfaceView/Surface/Window）。</li>
 *   <li>{@code PixelCopy.request(Window, srcRect, ...)} 从 SurfaceFlinger 合成层锁定该区域，拿到的就是
 *       屏幕上真实显示的帧（含 libVLC 渲染内容），是 TextureView 渲染场景下唯一可靠方案。</li>
 * </ul>
 *
 * <p><b>API 要求</b>：minSdk=21，PixelCopy 需要 API 24+，用 {@link Build.VERSION} 检查且用低版本兜底
 * （TexturView.getBitmap，虽然读不到 libVLC 帧，但至少不至于崩溃）。</p>
 */
public final class ThumbnailCapture {

    private static final String TAG = "ThumbnailCapture";

    private ThumbnailCapture() {}

    public interface CaptureCallback {
        void onSuccess(Bitmap bitmap);
        void onFailure(String reason);
    }

    /**
     * 异步截屏：截取 Activity 窗口中 tv 所在矩形区域为 Bitmap，回调主线程。
     *
     * <p>优先用 PixelCopy(Window, rect)；API<24 或 window 不可用时降级
     * TextureView.getBitmap()（可能读不到 libVLC 帧，但保底不崩）。</p>
     *
     * @param activity 提供 Window 的宿主（PlayerActivity / MultiViewActivity）
     * @param tv       目标 TextureView
     * @param cb       结果回调（主线程）
     */
    public static void capture(final Activity activity, final TextureView tv, final CaptureCallback cb) {
        if (tv == null) {
            if (cb != null) cb.onFailure("TextureView is null");
            return;
        }
        final int w = tv.getWidth();
        final int h = tv.getHeight();
        if (w <= 0 || h <= 0) {
            if (cb != null) cb.onFailure("TextureView zero size " + w + "x" + h);
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());

        // ===== 首选：PixelCopy(Window, rect)（API 24+，能拿到 libVLC 真实帧）=====
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity != null) {
            final Window window = activity.getWindow();
            if (window != null) {
                final int[] loc = new int[2];
                // ★ 必须用 getLocationInWindow() 而非 getLocationOnScreen()：
                //   PixelCopy(Window, rect) 的坐标系是【窗口相对坐标】，从窗口左上角(0,0)起；
                //   getLocationOnScreen() 返回【屏幕坐标】，在非沉浸式(状态栏仍显示)时会整体下移
                //   一个状态栏高度，导致 rect 越过窗口底部 → 截到窗口外区域 → 全黑。
                //   状态栏是独立 Window，getLocationInWindow 才能把 video 区域换算到本窗口坐标系。
                tv.getLocationInWindow(loc);
                final Rect rect = new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
                final Bitmap bitmap;
                try {
                    bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888);
                } catch (Throwable t) {
                    if (cb != null) cb.onFailure("Bitmap create failed: " + t.getMessage());
                    return;
                }
                PixelCopy.request(window, rect, bitmap, copyResult -> {
                    if (copyResult == PixelCopy.SUCCESS) {
                        // ★ 黑帧检测：PixelCopy 能 SUCCESS 但内容可能全黑（模拟器 libVLC 视频层
                        //   不参与 Window 内缓冲 → 读到空 buffer）。全黑视同失败，不写缓存，
                        //   避免把"黑图"当成缩略图污染列表页。
                        if (isMostlyBlack(bitmap)) {
                            Log.w(TAG, "PixelCopy(Window) 返回全黑帧（模拟器视频层未合成进 Window），判为失败");
                            recycleSafe(bitmap);
                            if (cb != null) cb.onFailure("PixelCopy(Window) returned black frame");
                        } else {
                            Log.d(TAG, "PixelCopy(Window) success: " + rect.width() + "x" + rect.height()
                                    + " loc=" + loc[0] + "," + loc[1]);
                            if (cb != null) cb.onSuccess(bitmap);
                        }
                    } else {
                        Log.w(TAG, "PixelCopy(Window) failed: result=" + copyResult);
                        recycleSafe(bitmap);
                        if (cb != null) cb.onFailure("PixelCopy(Window) failed: result=" + copyResult);
                    }
                }, main);
                return;
            }
        }

        // ===== 兜底：TextureView.getBitmap()（API 24+；libVLC 场景常读回黑，仅保底不崩）=====
        main.post(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = tv.getBitmap();
            } catch (Throwable t) {
                Log.w(TAG, "getBitmap threw: " + t.getMessage());
            }
            if (bitmap == null) {
                if (cb != null) cb.onFailure("getBitmap returned null");
            } else {
                Log.w(TAG, "兜底 getBitmap 返回（libVLC 下可能为黑）: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                if (cb != null) cb.onSuccess(bitmap);
            }
        });
    }

    /** 调用方（确保 activity 为宿主）。 */
    public static void captureToCacheFile(final Context ctx, final TextureView tv,
                                          final int channelIndex) {
        if (tv == null || !(ctx instanceof Activity)) {
            Log.w(TAG, "captureToCacheFile: 需要 Activity 宿主（ctx="
                    + (ctx == null ? "null" : ctx.getClass().getSimpleName()) + "）");
            return;
        }
        capture((Activity) ctx, tv, new CaptureCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                ThumbnailCache.save(ctx, channelIndex, bitmap, success -> {
                    if (success) {
                        Log.i(TAG, "ch" + channelIndex + " 缩略图已写入缓存");
                    } else {
                        Log.w(TAG, "ch" + channelIndex + " 缩略图保存失败");
                    }
                    recycleSafe(bitmap);
                });
            }
            @Override
            public void onFailure(String reason) {
                Log.w(TAG, "ch" + channelIndex + " 截屏失败: " + reason);
            }
        });
    }

    private static void recycleSafe(Bitmap bm) {
        if (bm != null && !bm.isRecycled()) {
            bm.recycle();
        }
    }

    /**
     * 黑帧检测：采样等距网格像素，近全黑 (<12) 的比例 > 95% 判定为"读不到内容"。
     * 用于区分"真实黑画面对面"（少见，且列表页占位主题也是深色）与"缓冲未合成/视频层未进
     * 缓冲区"导致的空帧。空帧不写缓存，避免污染缩略图。
     */
    private static boolean isMostlyBlack(Bitmap bm) {
        if (bm == null || bm.isRecycled()) {
            return true;
        }
        final int w = bm.getWidth();
        final int h = bm.getHeight();
        if (w <= 0 || h <= 0) {
            return true;
        }
        long black = 0;
        long total = 0;
        // 采样步长：横向 16 格 × 纵向 16 格 ≈ 256 个采样点，足够判断
        int sx = Math.max(1, w / 16);
        int sy = Math.max(1, h / 16);
        for (int y = 0; y < h; y += sy) {
            for (int x = 0; x < w; x += sx) {
                int c = bm.getPixel(x, y);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                // 亮度过低（近似黑）阈值取 12：夜视画面虽暗但一般 > 12，纯黑 buffer 恒为 0
                if (r < 12 && g < 12 && b < 12) {
                    black++;
                }
                total++;
            }
        }
        return total > 0 && (black * 100 / total) >= 95;
    }
}