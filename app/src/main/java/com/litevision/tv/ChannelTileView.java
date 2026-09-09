package com.litevision.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * ChannelTileView — 多画面单格视图组件。
 *
 * 组合：TextureView（视频）+ TextView（通道名 overlay）+ TextView（错误/缓冲 overlay）。
 * 每个格子绑定一个 {@link PlayerEngine}，由 MultiViewActivity 统一编排生命周期。
 */
public class ChannelTileView extends FrameLayout {

    private static final String TAG = "ChannelTileView";

    private TextureView textureView;
    private TextView tvChannelName;
    private TextView tvError;
    private TextView tvStreamIndicator;

    private PlayerEngine engine;
    private int channelIndex = -1;
    private boolean useSubStream = true;
    private Surface videoSurface = null;
    private boolean surfaceReady = false;
    private boolean nameVisible = true;
    private boolean destroyed = false;

    /**
     * SurfaceTexture 的实际尺寸（VLC 设完 buffer geometry 后更新）。
     *
     * 用途：onNewVideoLayout 可能来得很晚甚至对某些码流不来，此时不能干等——
     * 一旦 SurfaceTexture 被 VLC 定尺寸，就用它兜底算 cover，保证画面立刻铺满格子，
     * 不出现"视频缩在左上角、其余是卡底颜色"的情况。回调来了再切回更精确的 visible 尺寸。
     */
    private int surfaceWidth = 0;
    private int surfaceHeight = 0;

    /**
     * 是否已成功设置过 fill 矩阵（收敛标志）。
     *
     * 用于"以帧驱动收敛"：onSurfaceTextureSizeChanged 只在 VLC 改 buffer 尺寸时触发，
     * onSurfaceTextureAvailable 只在首次创建 surface 时触发一次。翻页复用格子时，若首次
     * 摸到格子尺寸前 getWidth()==0 走 return、之后又没有新的 size changed，矩阵就永远
     * 停在默认/错误 → 画面"没填满 / 溢出"（各格子时序不同 → 同页两种错并存）。
     * 改用 onSurfaceTextureUpdated（每帧都调）补 apply，保证只要第一帧有效 buffer 尺寸
     * 就位，fill 必然收敛到正确矩阵，不受任何回调时序影响。
     */
    private boolean fillApplied = false;

    /**
     * 上次应用 fill 时的参数快照。
     *
     * 收敛机制已从"一次性标志"升级为**参数快照驱动**：每帧比较当前参数与快照，
     * 任一不同就重算矩阵。这样即使某个回调没被触发（例如格子复用时新码流 buffer 尺寸
     * 恰好不变 → onSurfaceTextureSizeChanged 不来），只要参数真的变了，下一帧也会自动
     * 重算；参数没变则直接 return，不做多余的 setTransform。
     */
    private int lastSurfW = 0, lastSurfH = 0, lastBufW = 0, lastBufH = 0;
    private int lastVisW = 0, lastVisH = 0, lastTw = 0, lastTh = 0;

    public ChannelTileView(Context context) {
        this(context, null);
    }

    public ChannelTileView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ChannelTileView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.item_tile_channel, this, true);
        textureView = findViewById(R.id.tile_texture);
        tvChannelName = findViewById(R.id.tile_channel_name);
        tvError = findViewById(R.id.tile_error);
        tvStreamIndicator = findViewById(R.id.tile_stream_indicator);

        // 底层铺深蓝卡底（与列表页 item_channel.xml 的 channel_card_normal 一致）：
        // 当视频 aspect 与格子不完全一致（center-crop 后裁掉部分），未被视频覆盖的
        // 边缘显示深蓝而非透出脏 buffer，避免底部花屏残留。中心区域被子 View（透明
        // tile_root + 视频 TextureView）覆盖，此深蓝只是底色。
        setBackgroundResource(R.drawable.channel_card_normal);

        // ★ 焦点高亮由外层接管（与列表页 item_channel.xml 外层 focusable + 监听一致）：
        //   外层 ChannelTileView 必须可聚焦，D-pad 焦点才落在这里，onFocusChangeListener
        //   才能触发 setFocused() → channel_tile_focused 白光背景。否则内层 tile_root 拿焦点，
        //   外层监听失联、白光永不显示（TV 遥控左右无白边高亮的根因）。
        // ★ 白边用 padding 而非 margin：外层设 4dp padding，白环显示在 padding 区，
        //   视频仍填满内部无缩小（margin 方案会把格子整体缩小）。
        // ★ 圆角裁剪：用 OutlineProvider + setClipToOutline 让视频内容遵守 8dp 圆角，
        //   而不是在 draw() 里手动 clipPath（Android 不会把裁剪传递给子 View）。
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dpToPx(8));
            }
        });
        setClipToOutline(true);
        setFocusable(true);
        setClickable(false);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                Log.i(TAG, "surface available ch=" + channelIndex);
                surfaceReady = true;
                surfaceWidth = width;
                surfaceHeight = height;
                fillApplied = false; // surface 尺寸就绪，需重新收敛 fill
                videoSurface = new Surface(st);
                if (engine != null) {
                    engine.setVideoSurface(videoSurface);
                }
                applyCropTransformIfNeeded();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                // VLC 定完 buffer geometry 后 SurfaceTexture 尺寸会变，此时必须重算
                surfaceWidth = width;
                surfaceHeight = height;
                fillApplied = false; // 尺寸变了，重新收敛
                applyCropTransformIfNeeded();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                Log.i(TAG, "surface destroyed ch=" + channelIndex);
                surfaceReady = false;
                fillApplied = false;
                if (engine != null) {
                    engine.releaseVideoSurface();
                }
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                destroyed = true;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (engine != null) {
                    engine.onFrameRendered();
                }
                // 关键收敛机制：onSurfaceTextureUpdated 每帧回调，每帧都交给
                // applyCropTransformIfNeeded 自行判断——内部有参数快照快速 return，
                // 所以这里不会每帧都重设矩阵；但只要任一参数变了（surface 定尺寸、
                // visible 晚到、格子尺寸变化、翻页复用），下一帧就自动重算。
                // 这样不再依赖任何回调时序，fill 必然收敛到正确矩阵。
                applyCropTransformIfNeeded();
            }
        });
    }

    /**
     * 格子尺寸变化（含首帧 layout 完成、父容器尺寸变化）时立即重算 fill。
     *
     * 解决"首次 apply 时 getWidth()==0 走 return，之后没有再触发"导致矩阵不收敛的问题：
     * 只要格子一量好尺寸，onSizeChanged 就会触发一次 applyCropTransformIfNeeded，配合
     * fillApplied 收敛标志，保证 fill 矩阵最终必然正确铺满格子。
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            applyCropTransformIfNeeded();
        }
    }

    /**
     * 格子视频适配：**fill（拉伸铺满格子、允许变形、可见内容 100% 不裁）**。
     *
     * ★ 缩放基准铁律（多轮真机踩坑的最终结论，勿再改回 reported 基准）：
     * TextureView.setTransform(matrix) 的作用坐标系是 **SurfaceTexture 实际渲染的 buffer 像素**
     * （= VLC 通过 ANativeWindow_setBuffersGeometry 真正写进去的尺寸，见 surfaceWidth/Height），
     * 而 **不是** 引擎 onNewVideoLayout 上报的 coded/visible 尺寸。屏显宽 = surfW × sx，
     * 所以 sx 必须用 surfW 算；分母用错就会整体放大或缩小：
     *   - 分母是残留的大尺寸（如旧主码流 1920，实际 640）→ 显示宽 = 640×tw/1920 ≈ tw/3 → 画面严重缩小
     *   - 分母是残留的小尺寸（如旧子码流 640，实际 1920）→ 显示宽 = 1920×tw/640 = 3tw → 放大到只看到一角
     *   这正是"有的放大、有的缩小"在同一页并存的原因（各格子残留值与时序不同）。
     *
     * ★ padding 脏行用"相对占比"裁（与 vout 缩放解耦）：
     * 引擎上报的 buffer(coded) 与 visible 之差是宏块对齐 padding（如 1080→1088 的 8 行脏数据）。
     * 但这两者是**源侧像素**，vout 可能已整体缩放，不能直接当基准；只能取相对占比
     * ratio = visible/buffer，再乘到 surface 尺寸上得到有效内容尺寸：
     *   sx = tw/(surfW×ratioW)、sy = th/(surfH×ratioH)、顶部左对齐
     * → 可见内容正好铺满格子，padding 被推到 view 右/下边界外；fill 允许变形，不产生黑边。
     */
    /** 视频适配（fill / 拉伸铺满格子 + 允许变形 + 裁 padding），见 {@link #applyCropTransformIfNeeded()}。 */
    public void applyCropIfNeeded() {
        applyCropTransformIfNeeded();
    }

    private void applyCropTransformIfNeeded() {
        if (engine == null || textureView == null) {
            return;
        }
        final int tw = textureView.getWidth();
        final int th = textureView.getHeight();
        if (tw <= 0 || th <= 0) {
            return;
        }

        // ① 取值：surface 实际渲染尺寸（基准，权威）+ 引擎上报的 coded/visible（仅取 padding 占比）
        int surfW = surfaceWidth;
        int surfH = surfaceHeight;
        if (surfW <= 0 || surfH <= 0) {
            // surface 尚未定尺寸（首帧前/复用未触发 sizeChanged）：临时退回引擎上报尺寸。
            // 参数快照会记住这次是"退回值"，只要 surface 真正定尺寸就会被下一帧检测到并重算。
            surfW = engine.getBufferWidth();
            surfH = engine.getBufferHeight();
        }
        final int bufW = engine.getBufferWidth();
        final int bufH = engine.getBufferHeight();
        final int visW = engine.getVisibleWidth();
        final int visH = engine.getVisibleHeight();

        // ② 参数快照驱动的收敛：参数与上次完全一致就无需重设 transform。
        //    覆盖所有时序（surface 更新、visible 晚到、格子尺寸变化、翻页复用），
        //    不再依赖"某个回调恰好被触发"。
        if (fillApplied
                && surfW == lastSurfW && surfH == lastSurfH
                && bufW == lastBufW && bufH == lastBufH
                && visW == lastVisW && visH == lastVisH
                && tw == lastTw && th == lastTh) {
            return;
        }

        if (surfW <= 0 || surfH <= 0) {
            // 基准未知：复位，交给每帧收敛（onSurfaceTextureUpdated → apply）。
            textureView.setClipBounds(null);
            textureView.setTransform(new Matrix());
            return;
        }

        // ③ padding 相对占比（源侧比例，与 vout 缩放无关；引擎 play() 清零、onNewVideoLayout 写回）
        float ratioW = 1f;
        float ratioH = 1f;
        if (bufW > 0 && visW > 0 && visW < bufW) {
            ratioW = visW / (float) bufW;
        }
        if (bufH > 0 && visH > 0 && visH < bufH) {
            ratioH = visH / (float) bufH;
        }

        // ④ fill：有效内容（剔除 padding 后）拉伸铺满格子，允许变形，顶部左对齐把 padding 推出 view。
        final float contentW = surfW * ratioW;
        final float contentH = surfH * ratioH;
        final float sx = tw / (contentW > 0 ? contentW : surfW);
        final float sy = th / (contentH > 0 ? contentH : surfH);
        Matrix matrix = new Matrix();
        matrix.setScale(sx, sy);
        matrix.postTranslate(0f, 0f);   // 顶部左对齐：右侧/底部 padding 被推出 view 外
        textureView.setClipBounds(null);
        textureView.setTransform(matrix);

        lastSurfW = surfW; lastSurfH = surfH;
        lastBufW = bufW; lastBufH = bufH;
        lastVisW = visW; lastVisH = visH;
        lastTw = tw; lastTh = th;
        fillApplied = true;
        Log.d(TAG, "video-fit fill: surf=" + surfW + "x" + surfH
                + " buf=" + bufW + "x" + bufH + " vis=" + visW + "x" + visH
                + " ratio=" + ratioW + "," + ratioH
                + " view=" + tw + "x" + th + " scale=" + sx + "," + sy);
    }
    /**
     * 绑定格子：设置通道索引与引擎。播放由 MultiView 在 bind 后调用 engine.play() 启动。
     */
    public void bind(int channelIndex, boolean useSub, PlayerEngine engine, String channelName) {
        this.channelIndex = channelIndex;
        this.useSubStream = useSub;
        this.engine = engine;
        this.destroyed = false;
        String name = (channelName != null && !channelName.isEmpty())
                ? channelName : "通道" + (channelIndex + 1);
        tvChannelName.setText(name);
        showName(true);
        showError(null);
        updateStreamIndicator(useSub);
        setFocused(false);
        fillApplied = false; // 换通道/复用格子，需等新帧重新收敛 fill
        // 若引擎已获知视频尺寸（复用场景），销毁后重建绑定立即校正裁剪
        post(this::applyCropTransformIfNeeded);
    }

    /** 当前绑定的通道索引。 */
    public int getChannelIndex() {
        return channelIndex;
    }

    public PlayerEngine getEngine() {
        return engine;
    }

    public boolean isSurfaceReady() {
        return surfaceReady;
    }

    /** 暴露内部 TextureView（供 {@link ThumbnailCapture} PixelCopy 取帧）。 */
    public TextureView getTextureView() {
        return textureView;
    }

    /**
     * 截图快捷入口：截当前帧并写入本地缓存。
     *
     * <p>由 {@link MultiViewActivity} 在 onPlaying 后 2.5s 调用，结果异步写入
     * {@link ThumbnailCache}，失败仅记日志不影响其他流程。</p>
     *
     * @param ctx          应用上下文
     * @param channelIndex 0-based 通道索引（与 {@code channelIndex} 一致）
     */
    public void captureThumbnailToCache(android.content.Context ctx) {
        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "captureThumbnailToCache: tv 不可用 ch=" + channelIndex);
            return;
        }
        Log.i(TAG, "🎬 截缩略图 ch" + channelIndex
                + " (tile " + textureView.getWidth() + "x" + textureView.getHeight() + ")");
        // PixelCopy 需要 Activity 宿主（MultiViewActivity.this）
        ThumbnailCapture.captureToCacheFile(ctx, textureView, channelIndex);
    }

    /**
     * 视图搬移（宫格双击全屏 / 退出全屏）后调用：检测 TextureView 的 video surface
     * 是否在 removeView/addView 过程中被 {@link #onSurfaceTextureDestroyed} 销毁
     * 而尚未由 {@code onSurfaceTextureAvailable} 重建（非必现黑闪窗口）。
     *
     * 若确实丢失，这里用 TextureView 当前的 SurfaceTexture 重新创建/重挂 Surface，
     * 让引擎恢复渲染；否则 no-op。只做"检测 + 必要时重挂"，不改绑定通道/引擎状态，
     * 不影响 {@link PlayerEngine#isActiveFor} 语义（不触发 play/setMedia）。
     */
    public void ensureSurfaceAttachedAfterMove() {
        if (engine == null) {
            return;
        }
        // 一切正常：surface 仍有效，无需重挂
        if (surfaceReady && videoSurface != null) {
            return;
        }
        SurfaceTexture st = (textureView != null) ? textureView.getSurfaceTexture() : null;
        if (st == null) {
            // 纹理也尚未就绪：稍后由 onSurfaceTextureAvailable 自然恢复
            return;
        }
        Log.i(TAG, "搬移后 Surface 丢失，用当前 SurfaceTexture 重挂 ch=" + channelIndex);
        surfaceReady = true;
        // 尺寸不在此处强取：真正渲染尺寸由 VLC 定完 buffer geometry 后触发
        // onSurfaceTextureSizeChanged/onNewVideoLayout 更新（现有收敛机制覆盖），
        // 这里只要把 surface 挂回去让引擎恢复渲染即可。
        surfaceWidth = 0;
        surfaceHeight = 0;
        fillApplied = false; // 尺寸需重新收敛 fill
        if (videoSurface == null) {
            videoSurface = new Surface(st);
        }
        if (engine != null) {
            engine.setVideoSurface(videoSurface);
        }
        applyCropTransformIfNeeded();
    }

    /** 显示/隐藏通道名。 */
    public void showName(boolean show) {
        nameVisible = show;
        if (tvChannelName != null) {
            tvChannelName.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        // 码流标识跟随通道名同步显隐
        if (tvStreamIndicator != null) {
            tvStreamIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        // 通道名显示数秒后自动消失（画面内已有摄像头 OSD 标注，避免双重遮挡）。
        // 显示时重新排期，隐藏时取消排期。
        nameHandler.removeCallbacks(hideName);
        if (show) {
            nameHandler.postDelayed(hideName, NAME_AUTO_HIDE_MS);
        }
    }

    /** 通道名自动隐藏延时（毫秒）。 */
    private static final long NAME_AUTO_HIDE_MS = 5000;
    private final android.os.Handler nameHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideName = () -> {
        nameVisible = false;
        if (tvChannelName != null) {
            tvChannelName.setVisibility(View.GONE);
        }
        // 码流标识跟随通道名一起隐藏
        if (tvStreamIndicator != null) {
            tvStreamIndicator.setVisibility(View.GONE);
        }
    };

    public boolean isNameVisible() {
        return nameVisible;
    }

    /** 显示/隐藏错误（or 缓冲）信息。 */
    public void showError(String message) {
        if (tvError != null) {
            if (message == null || message.isEmpty()) {
                tvError.setVisibility(View.GONE);
                tvError.setText("");
            } else {
                tvError.setText(message);
                tvError.setVisibility(View.VISIBLE);
            }
        }
    }

    /** 可见性变化通知（预留：实际延迟停止由 MultiView 统一调度）。 */
    public void onVisibleChanged(boolean visible) {
        // no-op：引擎生命周期由 MultiView 的可见性队列统一管理
    }

    /** 更新码流标识（主/子）显示。 */
    public void updateStreamIndicator(boolean useSubStream) {
        if (tvStreamIndicator == null) return;
        String text = useSubStream ? "子" : "主";
        tvStreamIndicator.setText(text);
        tvStreamIndicator.setVisibility(View.VISIBLE);
    }

    /**
     * 释放视图资源：解除引擎 surface 引用并释放本地 Surface。
     * 注意：引擎自身的 stop/release 由 MultiView 负责（池化语义）。
     */
    public void releaseView() {
        nameHandler.removeCallbacks(hideName); // 清掉通道名自动隐藏的排期，防泄漏
        if (engine != null) {
            engine.releaseVideoSurface();
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        surfaceReady = false;
        // 格子复用时必须把 surface 尺寸一并清零：否则 bind() 后 applyCropTransformIfNeeded()
        // 在新引擎尚未报告视频尺寸（vw/vh==0）时会拿上一轮残留的旧 surface 尺寸算 cover，
        // 造成短暂的错误 transform（等新 onSurfaceTextureAvailable 到了才修正）。
        surfaceWidth = 0;
        surfaceHeight = 0;
        fillApplied = false; // 释放后需在下次绑定/新帧时重新收敛
        engine = null;
        channelIndex = -1;
        tvChannelName.setText("");
        showError(null);
    }

    /** 焦点变化高亮：背景替换 + 前景半透明白色覆盖框（叠在最上层，不改变现有边框）。 */
    public void setFocused(boolean focused) {
        if (focused) {
            setBackgroundResource(R.drawable.channel_tile_focused);
            setForeground(getDrawable(R.drawable.channel_tile_focused_overlay));
        } else {
            setBackgroundResource(R.drawable.channel_card_normal);
            setForeground(null);
        }
    }

    private Drawable getDrawable(int resId) {
        return getContext().getResources().getDrawable(resId);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getContext().getResources().getDisplayMetrics().density);
    }
}