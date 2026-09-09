package com.litevision.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * 缩略图本地缓存（"真实播放帧做缩略图"方案）。
 *
 * <p>设计：</p>
 * <ul>
 *   <li>存储位置：App 私有目录 {@code getFilesDir()/thumbnails/chXX.jpg}（卸载时清除，无外部权限）</li>
 *   <li>写盘：后台 IO 线程（{@link #ioHandler}），不阻塞 UI / libVLC 事件线程</li>
 *   <li>读图：后台线程 decode 成 Bitmap 后 post 回主线程给 ImageView 设图</li>
 *   <li>截帧来源：{@link ThumbnailCapture}（PixelCopy）— libVLC 3.5.1 Java API 无 takeSnapshot</li>
 * </ul>
 *
 * <p>何时更新：</p>
 * <ul>
 *   <li>PlayerActivity / MultiViewActivity 收到 Playing 后 2.5s（让画面稳定）触发截图</li>
 *   <li>每条流只截一次（Activity 生命周期内），避免反复写盘</li>
 * </ul>
 */
public final class ThumbnailCache {

    private static final String TAG = "ThumbnailCache";
    private static final String DIR_NAME = "thumbnails";
    public static final int THUMB_WIDTH = 480;   // 列表卡片宽度的 1.5x，留点富余
    public static final int THUMB_HEIGHT = 270;  // 16:9
    private static final int JPEG_QUALITY = 80;

    /** 后台 IO 线程：写盘 / 读图解码（独立线程，避免压 libVLC 事件 / 主线程） */
    private static HandlerThread ioThread;
    private static Handler ioHandler;
    private static Handler mainHandler;

    private static synchronized void ensureIo(Context ctx) {
        if (ioThread == null) {
            ioThread = new HandlerThread("thumbnail-cache-io");
            ioThread.start();
            ioHandler = new Handler(ioThread.getLooper());
            mainHandler = new Handler(Looper.getMainLooper());
        }
    }

    private ThumbnailCache() {}

    /** 缩略图缓存目录（不存在则创建）。 */
    public static File getDir(Context ctx) {
        ensureIo(ctx);
        File dir = new File(ctx.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 单通道缓存文件。{@code channelIndex} 是 0-based（与 ThumbnailLoader / ConfigManager 一致）。 */
    public static File getFile(Context ctx, int channelIndex) {
        return new File(getDir(ctx), "ch" + channelIndex + ".jpg");
    }

    /** 是否已有该通道的本地缓存文件。 */
    public static boolean hasCached(Context ctx, int channelIndex) {
        File f = getFile(ctx, channelIndex);
        return f.exists() && f.length() > 0;
    }

    /**
     * 异步写盘：把传入的 Bitmap（来自 PixelCopy）压缩为 JPEG 写到 {@link #getFile}。
     *
     * @param ctx          用于解析 App 私有目录
     * @param channelIndex 0-based 通道索引
     * @param bitmap       来自 PixelCopy 的 Bitmap（必须 mutable 且 ARGB_8888）
     * @param onDone       写盘完成回调（主线程；success=true=写入成功，false=失败）。可 null。
     */
    public static void save(final Context ctx, final int channelIndex, final Bitmap bitmap,
                            final SaveCallback onDone) {
        ensureIo(ctx);
        if (bitmap == null || bitmap.isRecycled()) {
            // ★ 修复：bitmap 无效时不写文件，避免污染缓存
            if (onDone != null) mainHandler.post(() -> onDone.onResult(false));
            return;
        }
        ioHandler.post(() -> {
            boolean ok = false;
            File out = getFile(ctx, channelIndex);
            try {
                // ★ 修复：先压缩到内存再写盘，避免写坏文件
                Bitmap scaled = scaleToTargetFix16x9(bitmap, THUMB_WIDTH, THUMB_HEIGHT);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
                if (scaled != bitmap) {
                    scaled.recycle();
                }
                if (ok) {
                    // 压缩成功才写盘
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(baos.toByteArray());
                    java.nio.channels.FileChannel fc = new java.io.FileOutputStream(out).getChannel();
                    try {
                        fc.write(buf);
                    } finally {
                        fc.close();
                    }
                    Log.i(TAG, "saved ch" + channelIndex + " ok=" + ok
                            + " size=" + out.length() + " bytes");
                } else {
                    Log.w(TAG, "ch" + channelIndex + " JPEG compress failed");
                }
            } catch (IOException e) {
                Log.w(TAG, "save ch" + channelIndex + " failed: " + e.getMessage());
                ok = false;
                // ★ 修复：IO 异常时删除可能残留的坏文件
                if (out.exists()) out.delete();
            } finally {
                final boolean finalOk = ok;
                if (onDone != null) {
                    mainHandler.post(() -> onDone.onResult(finalOk));
                }
            }
        });
    }

    /**
     * 异步读盘：读取 {@code chXX.jpg} 并在主线程把 Bitmap 设到传入的 ImageView。
     *
     * <p>复用 tag 反查防 RV 错位（传入的 iv 应已设过 tag = 缓存 key）。</p>
     *
     * @param ctx          用于解析 App 私有目录
     * @param channelIndex 0-based 通道索引
     * @param targetIv     列表页的 ImageView（必须有与 key 对应的 tag）
     * @param key          期望 tag（与 targetIv.setTag(key) 的值一致，校验防 RV 复用错位）
     */
    public static void loadInto(final Context ctx, final int channelIndex,
                                 final android.widget.ImageView targetIv, final String key) {
        loadInto(ctx, channelIndex, targetIv, key, null);
    }

    /**
     * 带解码回调的异步读盘，供 {@link ThumbnailLoader} 把解码结果缓存复用（消除异步空白窗口）。
     *
     * <p>与三参版唯一的语义差别：<b>传了 callback 时，Bitmap 生命周期移交给调用方</b> ——
     * 本方法不再在 tag 不匹配时 {@code recycle()}，因为调用方要缓存它供后续 bind 复用。
     * 不传 callback（即走三参版）时行为与改造前完全一致。</p>
     *
     * @param callback 解码完成回调（主线程派发；bitmap 为 null 表示解码失败）。可 null。
     */
    public static void loadInto(final Context ctx, final int channelIndex,
                                 final android.widget.ImageView targetIv, final String key,
                                 final LoadCallback callback) {
        ensureIo(ctx);
        final File f = getFile(ctx, channelIndex);
        if (!f.exists() || f.length() == 0 || targetIv == null) {
            return;
        }
        ioHandler.post(() -> {
            Bitmap bm = null;
            try {
                // inSampleSize=1 即可（THUMB_WIDTH/HEIGHT 本来就不大，无需再缩）
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bm = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            } catch (Throwable t) {
                Log.w(TAG, "load ch" + channelIndex + " failed: " + t.getMessage());
            }
            final Bitmap finalBm = bm;
            mainHandler.post(() -> {
                boolean tagOk = finalBm != null && targetIv.getTag() != null
                        && key.equals(targetIv.getTag().toString());
                if (tagOk) {
                    targetIv.setImageBitmap(finalBm);
                }
                if (callback != null) {
                    // 生命周期移交调用方：即使 tag 不匹配也回传 —— Bitmap 属于该 channelIndex，
                    // 下次绑到任意格子都能直接用
                    callback.onDecoded(finalBm);
                } else if (!tagOk && finalBm != null) {
                    // 无回调（旧行为）：格子已复用且无人接管 → 自己回收避免泄漏
                    finalBm.recycle();
                }
            });
        });
    }

    /** 解码完成回调（主线程派发）。 */
    public interface LoadCallback {
        void onDecoded(Bitmap bitmap);
    }

    /** 等比例缩放到不超过 maxW × maxH 的范围（保持纵横比）。 */
    private static Bitmap scaleToTarget(Bitmap src, int maxW, int maxH) {
        if (src.getWidth() <= maxW && src.getHeight() <= maxH) {
            return src;
        }
        float ratio = Math.min(
                maxW / (float) src.getWidth(),
                maxH / (float) src.getHeight());
        int nw = Math.max(1, Math.round(src.getWidth() * ratio));
        int nh = Math.max(1, Math.round(src.getHeight() * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    /**
     * 等比缩放 + 中心裁切，输出精确目标尺寸（cover 语义）。
     */
    public static Bitmap scaleToTargetFix16x9(Bitmap src, int targetW, int targetH) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw == targetW && sh == targetH) {
            return src;
        }
        float scaleW = (float) targetW / sw;
        float scaleH = (float) targetH / sh;
        float scale = Math.max(scaleW, scaleH);
        int scaledW = Math.max(1, Math.round(sw * scale));
        int scaledH = Math.max(1, Math.round(sh * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);
        int cropX = Math.max(0, (scaledW - targetW) / 2);
        int cropY = Math.max(0, (scaledH - targetH) / 2);
        int cropW = Math.min(targetW, scaledW - cropX);
        int cropH = Math.min(targetH, scaledH - cropY);
        Bitmap result = Bitmap.createBitmap(scaled, cropX, cropY, cropW, cropH);
        if (scaled != src) {
            scaled.recycle();
        }
        return result;
    }

    /** 写盘完成回调（在主线程派发）。 */
    public interface SaveCallback {
        void onResult(boolean success);
    }

    /**
     * 删除所有缓存（用于设置页"清除缩略图缓存"或调试）。同步删除。
     */
    public static int clearAll(Context ctx) {
        File dir = getDir(ctx);
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (f.delete()) n++;
        }
        Log.i(TAG, "clearAll removed " + n + " cached thumbnails");
        return n;
    }

    /** 调试用：当前缓存数量。 */
    public static int count(Context ctx) {
        File dir = getDir(ctx);
        String[] names = dir.list();
        return names == null ? 0 : names.length;
    }

    /** 诊断日志（Activity onResume 时打印一行）。 */
    public static void logStats(Context ctx) {
        int n = count(ctx);
        long bytes = 0;
        File dir = getDir(ctx);
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) bytes += f.length();
        }
        Log.i(TAG, "缓存统计: " + n + " 张, " + (bytes / 1024) + " KB");
    }

    /** 防止泄漏：App 退出时调用一次。 */
    public static synchronized void shutdown() {
        if (ioThread != null) {
            ioThread.quitSafely();
            ioThread = null;
            ioHandler = null;
            mainHandler = null;
        }
    }
}