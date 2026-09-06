package com.litevision.tv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder> {

    private static final String TAG = "ChannelAdapter";

    private List<String> channelNames = new ArrayList<>();
    private final ConfigManager config;
    private final OnChannelClickListener listener;
    private final ThumbnailLoader thumbnailLoader;

    public interface OnChannelClickListener {
        void onChannelClick(int channelIndex);
    }

    /**
     * @param context Activity context（**必须是 Activity，不能是 Application**）。
     *                ThumbnailLoader 需要 Activity 来挂载 offscreen-but-attached 的
     *                TextureView 池作为 RTSP 抓帧渲染目标（脱离 RecyclerView 的不稳定生命周期）。
     * @param config  配置（ISAPI/RTSP 地址、凭据、解码模式）
     * @param listener 点击回调
     */
    public ChannelAdapter(Context context, ConfigManager config, OnChannelClickListener listener) {
        this.config = config;
        this.listener = listener;
        // 抓帧器需要在主线程上注册/注销 TextureView 的 SurfaceTextureListener。
        // 列表页 RecyclerView 的 onBindViewHolder 在主线程，但 ChannelAdapter 自身不知道当前
        // 是不是在主线程——为稳妥起见用 Looper.getMainLooper() 拿主线程 Handler 显式传入。
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        // 注意：必须传 Activity context（不是 getApplicationContext()），让 ThumbnailLoader
        // 能在 Activity content view 下挂 offscreen TextureView 池。
        this.thumbnailLoader = new ThumbnailLoader(context, mainHandler);
        this.thumbnailLoader.setConfig(config);
    }

    public void setChannelNames(List<String> names) {
        List<String> newNames = names != null ? new ArrayList<>(names) : new ArrayList<>();
        if (newNames.equals(this.channelNames)) {
            // 相同列表：不 refresh()（保留已成功的缩略图缓存，不重建 NvrClient、不全量重拉），
            // 但仍 re-bind 一次——已成功的缩略图直接命中缓存零闪烁，
            // 首次拉取失败（冷启动时 NVR/网络未就绪）的格子借此获得重试机会。
            notifyDataSetChanged();
            return;
        }
        this.channelNames = newNames;
        thumbnailLoader.refresh();
        notifyDataSetChanged();
    }

    public void refreshConfig() {
        thumbnailLoader.setConfig(config);
        notifyDataSetChanged();
    }

    /**
     * 停止缩略图加载并释放抓帧用的 LibVLC 实例。
     *
     * 由 Activity 的 onDestroy 调用：抓帧每路最多占用 NVR 一条取流连接 8s，
     * 列表页销毁后必须立即停止，否则会与多画面的取流抢 NVR 并发额度。
     */
    public void shutdown() {
        thumbnailLoader.shutdown();
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new ChannelViewHolder(view, listener, thumbnailLoader);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        String name = position < channelNames.size() ? channelNames.get(position) : "通道" + (position + 1);
        holder.bind(name, position);
    }

    @Override
    public void onViewRecycled(@NonNull ChannelViewHolder holder) {
        super.onViewRecycled(holder);
        // RV 回收 itemHolder 时立刻取消该 TV 的在途抓帧任务，防 RV 后续 layout pass 销毁
        // SurfaceTexture 时 libVLC 还在写入引发 "BufferQueue has been abandoned"。
        holder.cancelInFlight();
    }

    @Override
    public int getItemCount() {
        return channelNames.size();
    }

    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        TextView tvChannelName;
        ImageView ivThumbnail;
        TextureView tvThumbnail;
        final OnChannelClickListener listener;
        final ThumbnailLoader thumbnailLoader;

        ChannelViewHolder(@NonNull View itemView, OnChannelClickListener listener, ThumbnailLoader loader) {
            super(itemView);
            this.listener = listener;
            this.thumbnailLoader = loader;
            tvChannelName = itemView.findViewById(R.id.tv_channel_name);
            ivThumbnail = itemView.findViewById(R.id.iv_thumbnail);
            tvThumbnail = itemView.findViewById(R.id.tv_thumbnail);

            // ★ 关键（2026-09-06 调试结论）：TextureView 必须有"永驻"SurfaceTextureListener。
            // 调试发现：列表页 TV 即使 isAttachedToWindow()+isAvailable()=true，libVLC vout
            // attach 时仍报 `request 1 not implemented` + `video output creation failed`，
            // 4 秒反复尝试后超时——根因是"ST 没有 listener 监听"：系统创建 ST 后若
            // 没有任何 listener 注册过，SurfaceFlinger 认为该 ST 的 BufferQueue 处于
            // "未挂载消费者"状态 → 后续 libVLC attach 必失败（多画面 ChannelTileView
            // 已验证：init 时立刻设 listener，vout 永远成功）。
            //
            // 这里设一个"缓存 ST + 通知 ThumbnailLoader"的永驻 listener：
            // - onSurfaceTextureAvailable：缓存 st，通知 loader（用于抓帧器拿到系统原
            //   生 ST 而非 tv.getSurfaceTexture() 后置获取，避免 race）
            // - onSurfaceTextureDestroyed：清缓存，告知抓帧器 ST 已销毁（及时停掉
            //   libVLC 写入，防 BufferQueue abandoned）
            // - onSurfaceTextureUpdated：no-op（无播放语义，仅缩略图静态显示）
            tvThumbnail.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    Log.i(TAG, "TV ST available ch=" + getBindingAdapterPosition()
                            + " size=" + w + "x" + h);
                    if (thumbnailLoader != null) {
                        thumbnailLoader.onTvSurfaceAvailable(tvThumbnail, st, w, h);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    // no-op（缩略图固定 buffer 尺寸即可）
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    Log.i(TAG, "TV ST destroyed ch=" + getBindingAdapterPosition());
                    if (thumbnailLoader != null) {
                        thumbnailLoader.onTvSurfaceDestroyed(tvThumbnail);
                    }
                    // 返回 true = 我们接管 ST 生命周期，不让系统释放
                    // （RV itemHolder 复用时会再次触发 onSurfaceTextureAvailable）
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    // no-op
                }
            });

            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onChannelClick(pos);
                }
            });

            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.channel_card_focused);
                    tvChannelName.setTextColor(0xFFFFFFFF);
                } else {
                    v.setBackgroundResource(R.drawable.channel_card_normal);
                    tvChannelName.setTextColor(0xCCFFFFFF);
                }
            });
        }

        /**
         * RV 完成 layout pass 后再触发抓帧。
         *
         * <p>关键时序：{@code itemView.post} 投递到下一帧，但此时 TV 仍可能
         * {@code isAvailable()==false}（SurfaceTexture 尚未创建）。直接调
         * {@link ThumbnailLoader#load} 会被 RtspFrameGrabber 拒绝（"TV 无效"）。
         *
         * <p>正确做法：post 后主线程轮询 {@code tv.isAvailable() + tv.isAttachedToWindow()}，
         * 每 ~16ms 一帧，最长 3 秒（实测 RV 9 格冷启动通常 < 200ms 即可 stable）。
         * 满足条件后立刻调 loader.load；超时则放弃当次抓帧（用户冷启动后滚动列表时
         * 也可能撞上，等待 recycle 时取消即可）。
         */
        void bind(String name, int channelIndex) {
            tvChannelName.setText(name);
            if (thumbnailLoader == null) {
                ivThumbnail.setImageResource(R.color.bg_dark);
                return;
            }
            // ★ 立即让 IV 可见 + 显示 placeholder，避免渲染前置阶段用户只看到纯黑底
            //（之前 XML 把 IV 默认 visibility="gone"，让 TV 露出来给 libVLC 渲染用，但模拟器
            //  下 TV 永远渲不出，叠加 FrameLayout 黑底 → 用户看到纯黑矩形，体验差）。
            // 现在 IV 常驻 VISIBLE + placeholder：抓帧进行中是深蓝色块；抓帧成功后 Bitmap 覆盖；
            // 抓帧最终失败也是 placeholder，永远不黑屏。
            ivThumbnail.setVisibility(View.VISIBLE);
            ivThumbnail.setImageResource(R.drawable.thumbnail_placeholder);
            final ImageView ivFinal = ivThumbnail;
            final TextureView tvFinal = tvThumbnail;
            itemView.post(() -> waitTvAndStartGrab(ivFinal, tvFinal, channelIndex, System.currentTimeMillis()));
        }

        private void waitTvAndStartGrab(final ImageView ivFinal, final TextureView tvFinal,
                                        final int channelIndex, final long startMs) {
            if (thumbnailLoader == null) return;
            if (tvFinal == null) return;
            // RV itemHolder 已被回收（在途等待中就 recycle 了）→ 不再启动抓帧
            if (getBindingAdapterPosition() == RecyclerView.NO_POSITION) {
                return;
            }
            // TV 已 attached 且 SurfaceTexture 已创建 → 启动抓帧
            if (tvFinal.isAttachedToWindow() && tvFinal.isAvailable()
                    && tvFinal.getSurfaceTexture() != null) {
                Log.i(TAG, "TV ready ch=" + channelIndex + "（耗时 "
                        + (System.currentTimeMillis() - startMs) + "ms），启动抓帧");
                thumbnailLoader.load(ivFinal, tvFinal, channelIndex);
                return;
            }
            // 仍未就绪：最多等 3 秒，每 ~16ms 检查一次
            if (System.currentTimeMillis() - startMs < 3000L) {
                itemView.postDelayed(() -> waitTvAndStartGrab(ivFinal, tvFinal, channelIndex, startMs), 16L);
            } else {
                Log.w(TAG, "等 TV available 超时（3s） ch=" + channelIndex
                        + " attached=" + tvFinal.isAttachedToWindow()
                        + " available=" + tvFinal.isAvailable()
                        + " st=" + (tvFinal.getSurfaceTexture() != null));
            }
        }

        /**
         * 已废弃：取消机制改在 RtspFrameGrabber.grab 内通过 tv.isAttachedToWindow() 实时校验。
         * 保留为 no-op 防编译错误。
         */
        @Deprecated
        void cancelInFlight() {
            // no-op
        }
    }
}
