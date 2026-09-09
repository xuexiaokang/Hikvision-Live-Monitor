package com.litevision.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async thumbnail loader for the channel grid.
 *
 * <p>两级取图策略：
 * <ol>
 *   <li><b>ISAPI HTTP 抓图</b>（{@link NvrClient} + Digest 认证 + 路径探测）—— 快、不带宽压力；
 *       但在"域名反代到 NVR"的站点上常不可用（反代改写认证头/端口未映射），此时会全通道失败。</li>
 *   <li><b>RTSP 抓帧回退</b>（{@link RtspFrameGrabber}，libVLC 离屏取一帧）—— HTTP 抓图失败时自动启用，
 *       取流参数与播放侧一致，已被真机验证可用，保证"每个格子都出图"。</li>
 * </ol>
 *
 * <p>失败策略：同一 key 失败后 10s 内不再重试（onResume 触发的 re-bind 是天然重试入口），
 * 累计失败 {@link #MAX_AUTO_RETRY} 次后彻底停止自动重试，防止无限打 NVR。
 */
public class ThumbnailLoader {
    private static final String TAG = "ThumbnailLoader";

    /** HTTP 抓图线程数（NvrClient 内部已串行化，无需更多）。 */
    private static final int MAX_THREADS = 2;
    /**
     * RTSP 抓帧并发上限：2。
     * 与 HTTP 抓图分开独立线程池，避免慢速抓帧把 HTTP 快通道堵死。
     *
     * <p>为什么从 4 降到 2：{@link #onSuccess} 已改为"抓到即落盘"（写
     * {@code thumbnails/chXX.jpg}），同一通道第二次进入列表直接走磁盘分支，
     * 真正需要抓帧的只剩"从未出过图"的通道，抓取量级大幅下降 —— 此时降并发
     * 换取对"用户正在多画面播放的 N 路 RTSP"更小的干扰，性价比更高。
     *
     * <p>调试结论（2026-09-06）：本值与 libVLC vout "BufferQueue abandoned" 现象无关——
     * 串行（MAX_RTSP_THREADS=1）单路抓帧同样报 `request 1 not implemented`，属模拟器层
     * ST 兼容性问题，改并发不解决问题。
     */
    private static final int MAX_RTSP_THREADS = 2;

    /**
     * RTSP 抓帧超时（毫秒）。
     * 主码流码率高但 I 帧出现快（1-3s 拿图），给 4s；子码流可能被 NVR 转码延迟拖慢，给 4s。
     * 两轮加起来最多 8s，确保 9 路在 {@link #MAX_RTSP_THREADS}=2 并发下（分 5 批）
     * 仍能在合理时间内出图。
     */
    private static final long RTSP_MAIN_TIMEOUT_MS = 4000L;
    private static final long RTSP_SUB_TIMEOUT_MS = 4000L;

    /** 自动重试的基础退避时长（毫秒）；实际等待按 {@link #retryDelayFor} 指数增长。 */
    private static final long RETRY_INTERVAL_MS = 4000L;

    /** 自动重试退避上限（毫秒）：4s → 8s → 16s → 32s → 60s 封顶。 */
    private static final long MAX_RETRY_INTERVAL_MS = 60000L;

    /** 同一 key 的自动重试上限；达到后不再自动重试（配置变更/列表刷新会清零）。 */
    private static final int MAX_AUTO_RETRY = 5;

    /**
     * HTTP 抓图熔断时长：连续 N 个通道失败且从未成功过 → 判定本站点 ISAPI 抓图不可用，
     * 5 分钟内后续通道直接走 RTSP（否则 9 个格子要各自空转 4 条候选路径才回退）。
     * 阈值取 2：用户反代域名场景下 ISAPI 全不可用，让前 2 路失败就尽快熔断走 RTSP，
     * 节省 8s × 7 路 ≈ 56 秒等待时间。
     */
    private static final long HTTP_BREAK_MS = 5 * 60 * 1000L;
    private static final int HTTP_BREAK_THRESHOLD = 2;

    private final ExecutorService executor;
    private final ExecutorService rtspExecutor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> cache = new ConcurrentHashMap<>();
    /**
     * 磁盘缩略图（{@code getFilesDir()/thumbnails/chXX.jpg}）的解码结果内存缓存，按 channelIndex 存。
     *
     * <p><b>为什么必须独立于 {@link #cache}</b>：{@link #cache} 以 {@code host:port_ch} 为键，
     * 且会被 {@link #refresh()} / {@link #setConfig()} 的 {@code cache.clear()} 清空；
     * 而磁盘 jpg 的解码结果与 host/port 无关，跟着一起清掉只会导致每次刷新都要重新异步解码。
     *
     * <p><b>为什么必须存在</b>：{@link ThumbnailCache#loadInto} 是异步的（IO 线程 decodeFile
     * → post 回主线程 setImageBitmap），在解码完成前格子是"VISIBLE 但无内容"状态，
     * 会露出外层 FrameLayout 的 #000000 黑底 —— 这正是用户看到的"缩略图丢失/黑块"。
     * 缓存解码结果后，同一个通道再次 bind 直接同步 setImageBitmap，零空白窗口。
     */
    // ★★★ static：内存缩略图缓存必须全局共享。原因——列表页 ChannelAdapter 用
    //   new ThumbnailLoader(context) 自己的实例（需 Activity context 挂 offscreen 抓帧池），
    //   而预热流程 ThumbnailCapture 走 getInstance() 单例（application context）。
    //   若这里非 static，两实例 diskBitmapCache 互不共享 → primeDiskCache 写进单例、列表页
    //   读的是适配器实例 → 读不到 → 只能异步 decodeFile → 回列表短暂空白/占位。
    public static final Map<Integer, Bitmap> diskBitmapCache = new ConcurrentHashMap<>();
    /** 应用上下文（getFilesDir 用于本地缩略图缓存）。 */
    private final Context appContext;
    /** 在途抓图去重：re-bind 会重复触发 load()，同 key 并发只抓一次 */
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /**
     * 在途 key 的回填目标集合。
     *
     * RecyclerView 滚动回收会让同一个 key 先后绑到不同 ViewHolder 的 ImageView 上，
     * 若"已在途就 return"只回填首次那个实例，换过 ViewHolder 的格子会一直空白到下次 re-bind。
     * 这里把该 key 的所有待回填格子都登记下来，抓取完成后统一回填（tag 校验防错位）。
     */
    private final Map<String, Set<ImageView>> pendingViews = new ConcurrentHashMap<>();
    /** 每 key 累计失败次数（达到 MAX_AUTO_RETRY 后不再自动重试）。 */
    private final Map<String, Integer> failCount = new ConcurrentHashMap<>();
    /** 每 key 下次允许重试的时间戳（毫秒）。 */
    private final Map<String, Long> nextRetryAt = new ConcurrentHashMap<>();
    /** 离屏 RTSP 抓帧器（内部共享一个懒加载的 LibVLC 实例）。 */
    private final RtspFrameGrabber grabber;
    private ConfigManager config;
    private NvrClient client;
    /** 当前 client 建立时使用的主机/端口，用于判断是否需要在域名解析完成后重建。 */
    private String clientHost = "";
    private int clientPort = -1;

    /** HTTP 抓图是否曾成功过（成功过就不再熔断，说明站点支持 ISAPI 抓图）。 */
    private volatile boolean httpSucceeded = false;
    /** 连续 HTTP 抓图失败计数（任一成功即清零）。并发线程共享，故用原子整数。 */
    private final AtomicInteger httpConsecutiveFailures = new AtomicInteger(0);
    /** HTTP 抓图熔断截止时间戳（毫秒）；0=未熔断。 */
    private volatile long httpBreakUntil = 0L;
    /** 已 shutdown：停止一切取图与 UI 回填（列表页销毁后不再连 NVR 抢并发额度）。 */
    private volatile boolean stopped = false;

    /** 主线程 Handler：抓帧器需要在主线程上注册/注销 TextureView 的 SurfaceTextureListener。 */
    private final Handler mainHandler;

    /**
     * 抓帧用的 TextureView 来源：直接复用 RV itemHolder 的 tv_thumbnail（item_channel.xml 里那个）。
     *
     * <p><b>历史方案淘汰（2026-09-06）</b>：offscreen-but-attached TV 池方案
     * （在 Activity content view 下挂一个 alpha=0 / translationY=1000000 的 Container +
     * N 个内部 TV）被 SurfaceFlinger 全部判 abandoned（无论 alpha=0 / 1×1 / translationY=任意值），
     * libVLC vout 创建永远失败 —— 这是 Android 系统层面的硬约束：
     * <b>不在屏幕可见区域的 view 的 BufferQueue 会被系统标记为 abandoned</b>。
     *
     * <p><b>最终方案</b>：回到 RV itemHolder 的 TextureView。该 TV 在 RV 完成 layout pass 后会
     * 被 attached 并获得 SurfaceTexture（isAvailable()==true），与多画面已验证的渲染路径完全一致。
     * <ul>
     *   <li>ChannelAdapter 在 onBindViewHolder 时<b>不立刻</b>触发抓帧，而是用
     *       {@code holder.itemView.post(...)} 投递到下一帧，等 RV 完成 layout pass
     *       → TV isAvailable()==true 后再启动抓帧；</li>
     *   <li>ChannelAdapter 在 onViewRecycled 时通过 {@link #cancelByTextureView} 取消该 TV 的
     *       在途抓帧任务，防 RV 复用销毁 SurfaceTexture 时 libVLC 还在写入引发 abandoned；</li>
     *   <li>{@link RtspFrameGrabber} 内部对 TV 做严格的有效性校验（isAttachedToWindow +
     *       isAvailable + getSurfaceTexture != null），任一不满足直接放弃当次抓帧。</li>
     * </ul>
     */
    private final java.util.Set<android.view.TextureView> cancelledTvs =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<android.view.TextureView, Boolean>()));

    /** 单例（全局共享磁盘位图缓存 + 抓帧状态）。 */
    private static volatile ThumbnailLoader sInstance;

    /**
     * 获取（或惰性创建）应用级单例。任何调用方拿到的实例都共享同一份 {@link #diskBitmapCache}，
     * 适合"在 Activity 外把 bitmap 推进内存缓存"的场景（如 {@code ThumbnailCapture}
     * 在 IO 回调里直接 primeDiskCache）。
     */
    public static ThumbnailLoader getInstance(Context ctx) {
        ThumbnailLoader inst = sInstance;
        if (inst != null) return inst;
        synchronized (ThumbnailLoader.class) {
            inst = sInstance;
            if (inst == null) {
                Context app = ctx.getApplicationContext();
                inst = new ThumbnailLoader(app, new Handler(Looper.getMainLooper()));
                sInstance = inst;
                Log.i("ThumbnailLoader", "getInstance: 创建应用级单例");
            }
            return inst;
        }
    }

    public ThumbnailLoader(Context context) {
        this(context, null);
    }

    /**
     * @param context     应用上下文（无 Offscreen 池了，不再强制 Activity）
     * @param mainHandler 主线程 Handler（用于抓帧器在 TextureView 上注册监听），
     *                    推荐传入 Activity 的 handler；为 null 时退化为当前线程 Handler。
     */
    public ThumbnailLoader(Context context, Handler mainHandler) {
        this.appContext = (context != null) ? context.getApplicationContext() : null;
        this.mainHandler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(MAX_THREADS, newNamedFactory("thumb-http"));
        this.rtspExecutor = Executors.newFixedThreadPool(MAX_RTSP_THREADS, newNamedFactory("thumb-rtsp"));
        this.grabber = new RtspFrameGrabber(context, this.mainHandler);
    }

    /**
     * 已废弃：offscreen 池方案被淘汰（见上方字段注释）。保留空方法仅防编译错误。
     */
    @Deprecated
    private boolean initOffscreenTvPool(Activity activity) {
        return false;
    }

    private static ThreadFactory newNamedFactory(final String prefix) {
        final AtomicInteger index = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + index.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 绑定配置。此处只清空缓存、置空 client、重置重试/熔断状态，
     * 真正的 NvrClient 在后台线程按需创建，避免在 UI 线程上做域名解析。
     */
    public void setConfig(ConfigManager config) {
        this.config = config;
        invalidateClient();
        resetRetryState();
        synchronized (cache) {
            cache.clear();
        }
    }

    /**
     * 确保 NvrClient 使用最新的主机与 ISAPI 端口，并返回可用的实例。
     * 必须在后台线程调用（内部会等待域名解析完成）。
     */
    private NvrClient ensureClient() {
        if (config == null) {
            return null;
        }
        // 注意：等待域名解析可能在最坏情况下耗时数秒，必须在 synchronized 之外进行，
        // 否则会一直持有 this 锁，把 UI 线程上的 refresh()/setConfig() 卡成 ANR。
        config.awaitHostResolution();
        synchronized (this) {
            String host = config.getConnectionHost();
            int port = config.getEffectiveHttpPort();
            if (client == null || clientPort != port || !host.equals(clientHost)) {
                client = new NvrClient(host, port, config.getUsername(), config.getPassword());
                clientHost = host;
                clientPort = port;
                Log.i(TAG, "NvrClient 已建立: http://" + NvrClient.formatAuthority(host, port));
            }
            return client;
        }
    }

    /**
     * 发起一次异步取图（HTTP → 失败回退 RTSP）。
     *
     * <p>RTSP 抓帧渲染目标 = 调用方提供的 {@code tv}（RV itemHolder 的 tv_thumbnail），
     * 复用多画面已验证的渲染路径。调用方负责保证：
     * <ul>
     *   <li>此调用是<b>在 RV 完成 layout pass 之后</b>触发的（用 {@code itemView.post} 投递），</li>
     *   <li>{@code tv.isAvailable() == true} 且 {@code tv.getSurfaceTexture() != null}；</li>
     *   <li>在 {@code onViewRecycled} 时调用 {@link #cancelByTextureView} 取消该 TV 的在途抓帧。</li>
     * </ul>
     *
     * @param imageView    目标格子（用 tag 校验防 RecyclerView 复用错位）
     * @param tv           RV itemHolder 的 TextureView（item_channel.xml 的 tv_thumbnail）
     * @param channelIndex 通道索引
     */
    /**
     * TV 的 SurfaceTexture 已由系统创建（ChannelViewHolder 永驻 listener 回调）。
     * 缓存 ST 引用，供后续抓帧器使用——用"系统创建后立即缓存的 ST"代替
     * "tv.getSurfaceTexture() 后置获取的 ST"，避免 libVLC attach 时 ST 状态 race。
     *
     * <p>调试发现（2026-09-06）：TV 没有永驻 STListener 时，vout attach 必失败——
     * SurfaceFlinger 把"无 listener 的 ST"视为 abandoned BufferQueue。
     */
    private final Map<android.view.TextureView, android.graphics.SurfaceTexture> tvStCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void onTvSurfaceAvailable(android.view.TextureView tv,
                                     android.graphics.SurfaceTexture st, int w, int h) {
        tvStCache.put(tv, st);
        Log.i(TAG, "TV ST 缓存 ch(stSize=" + w + "x" + h + ") cacheSize=" + tvStCache.size());
    }

    public void onTvSurfaceDestroyed(android.view.TextureView tv) {
        android.graphics.SurfaceTexture removed = tvStCache.remove(tv);
        Log.i(TAG, "TV ST 缓存清除 removed=" + (removed != null) + " cacheSize=" + tvStCache.size());
    }

    /**
     * 由 ChannelAdapter.bind 调：尝试按 channelIndex 启动一次抓图。
     * 现在 TV 的 ST 由 ChannelViewHolder 永驻 listener 缓存，TV 一旦 ST available
     * 就可立即抓帧（不再需要 post + 等 isAvailable 轮询）。
     */
    /** 是否已有该通道的本地缓存缩略图（getFilesDir()/thumbnails/chXX.jpg）。 */
    public boolean hasCached(int channelIndex) {
        return appContext != null && ThumbnailCache.hasCached(appContext, channelIndex);
    }

    public void load(ImageView imageView, android.view.TextureView tv, int channelIndex) {
        if (stopped || config == null) {
            imageView.setImageResource(R.drawable.thumbnail_placeholder);
            return;
        }
        // 从取消集合里清除（已废弃：取消机制改用 RtspFrameGrabber.grab 内 isAttachedToWindow 校验）

        // 缓存键带上 ISAPI 端口：端口变化后不应命中旧图
        String key = config.getConnectionHost() + ":" + config.getEffectiveHttpPort() + "_" + channelIndex;

        // 复用格子换了通道：当前图属于别的通道，先贴占位图。
        // 不处理会短暂串图；只清空不贴图又会露出下层黑底，所以必须"清 + 贴"一起做。
        if (imageView.getDrawable() != null && !key.equals(String.valueOf(imageView.getTag()))) {
            imageView.setImageResource(R.drawable.thumbnail_placeholder);
        }

        // ===== 取图优先级（硬约束：命中 1/2/3 任意一个都绝不允许发起网络抓取）=====

        // 1) 内存 cache（本次会话内已抓到 / 已解码）
        Bitmap cached = cache.get(key);
        if (cached != null) {
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageBitmap(cached);
            return;
        }

        // 2) 磁盘图已解码结果 → 同步立即显示，零延迟、零网络
        Bitmap decodedDisk = diskBitmapCache.get(channelIndex);
        if (decodedDisk != null && !decodedDisk.isRecycled()) {
            imageView.setVisibility(View.VISIBLE);
            imageView.setTag(key);
            imageView.setImageBitmap(decodedDisk);
            return;
        }

        // 3) 磁盘有 jpg（播放时截帧 或 onSuccess 抓到即落盘）但本次会话尚未解码
        //    → 异步解码并回写 diskBitmapCache；命中即返回，绝不触发 HTTP/RTSP 抓帧。
        if (appContext != null && ThumbnailCache.hasCached(appContext, channelIndex)) {
            imageView.setVisibility(View.VISIBLE);
            imageView.setTag(key);
            showDiskOrPlaceholder(imageView, channelIndex, key);
            return;
        }

        // 4) 三者都没有 → 才发起 HTTP/RTSP 抓取（这是唯一会产生网络请求的分支）
        // Clear and mark
        imageView.setVisibility(View.VISIBLE);
        imageView.setImageResource(R.drawable.thumbnail_placeholder);
        imageView.setTag(key);

        loadAsync(imageView, tv, channelIndex, key);
    }

    /**
     * 尽最大努力让格子<b>立刻就有图</b>，绝不留"VISIBLE 但无内容"的空白窗口
     * （那会露出外层 FrameLayout 的 #000000 黑底，即用户投诉的黑块）：
     * <ol>
     *   <li>该通道的磁盘图已经解码过 → 同步 {@code setImageBitmap}，零延迟、零闪烁；</li>
     *   <li>否则 → 先保证有占位图兜底，再异步解码（解码结果会回写 {@link #diskBitmapCache}）。</li>
     * </ol>
     *
     * <p>调用前必须已设置 {@code imageView.setTag(key)}，异步分支靠 tag 校验防 RV 错位。
     */
    private void showDiskOrPlaceholder(ImageView imageView, int channelIndex, String key) {
        imageView.setVisibility(View.VISIBLE);
        Bitmap diskBm = diskBitmapCache.get(channelIndex);
        if (diskBm != null && !diskBm.isRecycled()) {
            imageView.setImageBitmap(diskBm);
            return;
        }
        if (imageView.getDrawable() == null) {
            imageView.setImageResource(R.drawable.thumbnail_placeholder);
        }
        loadDiskInto(imageView, channelIndex, key);
    }

    /**
     * 读取磁盘缩略图（{@code chXX.jpg}）回填到格子，并把解码结果写进 {@link #diskBitmapCache}，
     * 使该通道之后的每次 bind 都能同步立即出图。
     *
     * <p>tag 校验失败（格子已被 RV 复用给别的通道）时<b>仍然缓存</b>该 Bitmap —— 它属于这个
     * channelIndex，下次绑到任意格子都能直接用；只是不回填当前 IV，避免串图。
     */
    private void loadDiskInto(ImageView imageView, int channelIndex, String key) {
        if (appContext == null) {
            return;
        }
        ThumbnailCache.loadInto(appContext, channelIndex, imageView, key, bm -> {
            if (bm != null && !bm.isRecycled()) {
                diskBitmapCache.put(channelIndex, bm);
                return;
            }
            if (bm == null) {
                // 解码失败 = jpg 存在但已损坏。必须删掉坏文件，否则 hasCached() 永远返回 true，
                // load() 永远停在第 3 级（不发起网络、也永远没图），格子永久停在 placeholder，
                // 正是用户投诉的"缩略图不显示"。删掉后下次 bind 自然回落到第 4 级重抓 → 自愈。
                // 注意只在确实解码失败时删，bm != null 的正常流程行为完全不变。
                diskBitmapCache.remove(channelIndex);
                boolean deleted = ThumbnailCache.getFile(appContext, channelIndex).delete();
                Log.w(TAG, "删除损坏缩略图 ch" + channelIndex + " deleted=" + deleted
                        + "（下次 bind 将回落到网络重抓）");
            }
        });
    }

    /**
     * 已废弃：之前的取消机制（cancelByTextureView）在 View attach/detach 反复触发时
     * 会把所有 TV 加入取消集合（包括合法 holder 的 TV），导致正常抓帧任务被误取消。
     *
     * <p>现改用更直接的方案：抓帧循环内检查 {@code tv.isAttachedToWindow()}，不满足
     * 就丢弃结果。RtspFrameGrabber 内部也会做同样校验（更早拦截）。本方法保留为 no-op
     * 仅防 ChannelAdapter 编译错误。
     */
    @Deprecated
    public void cancelByTextureView(android.view.TextureView tv) {
        // no-op：取消逻辑已下沉到 RtspFrameGrabber.grab 内的 tv.isAttachedToWindow() 实时校验
    }

    /**
     * 已废弃：同 cancelByTextureView。保留 no-op 防 ChannelAdapter 编译错误。
     */
    @Deprecated
    public void uncancelByTextureView(android.view.TextureView tv) {
        // no-op
    }

    /**
     * 发起一次异步取图（HTTP → 失败回退 RTSP）。
     */
    private void loadAsync(ImageView imageView, android.view.TextureView tv, int channelIndex, String key) {
        if (stopped) {
            return;
        }
        // 重试节流：失败后 10s 内不重试；失败 3 次后彻底停止自动重试（防无限打 NVR）
        if (!canAttempt(key)) {
            return;
        }

        // 登记回填目标（无论是否已在途）：换 ViewHolder 实例的格子也能拿到结果
        pendingViews.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(imageView);

        // Load async（在途去重：同 key 已在抓取中则跳过，避免重复请求 NVR）
        if (!inFlight.add(key)) {
            return;
        }
        executor.execute(() -> {
            // 关键：无论成功/失败/异常，都必须归还 inFlight。
            // 交由 RTSP 回退继续处理的场景（handedOff=true）由回退任务自己归还。
            boolean handedOff = false;
            try {
                final long t0 = System.currentTimeMillis();
                Log.i(TAG, "抓图开始 ch" + channelIndex + " key=" + key
                        + " (httpEnabled=" + isHttpEnabled() + ")");
                Bitmap bitmap = null;
                try {
                    if (isHttpEnabled()) {
                        bitmap = fetchSnapshot(channelIndex, key);
                    } else {
                        Log.d(TAG, "ISAPI HTTP 抓图已熔断，直接走 RTSP 回退: " + key);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "HTTP 抓图异常: " + t.getMessage());
                }
                if (bitmap != null) {
                    onSuccess(key, bitmap, channelIndex);
                    return;
                }
                Log.i(TAG, "HTTP 抓图未出图 ch" + channelIndex + "，回退 RTSP 抓帧");
                // ===== HTTP 抓图失败 → RTSP 抓帧回退（独立线程池，并发 ≤ 4）=====
                rtspExecutor.execute(() -> {
                    try {
                        Bitmap frame = fetchRtspFrame(tv, channelIndex);
                        if (frame != null) {
                            onSuccess(key, frame, channelIndex);
                        } else {
                            onFailure(key, channelIndex);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "RTSP 回退异常 ch" + channelIndex, t);
                        onFailure(key, channelIndex);
                    } finally {
                        inFlight.remove(key);
                    }
                });
                handedOff = true;
            } catch (Throwable t) {
                Log.w(TAG, "RTSP 回退提交失败: " + t.getMessage());
                onFailure(key, channelIndex);
            } finally {
                if (!handedOff) {
                    inFlight.remove(key);
                }
            }
        });
    }

    /**
     * 成功：进内存缓存 + <b>落盘</b> + 回填所有登记在该 key 上的格子（tag 校验防 RecyclerView 复用错位）。
     *
     * <p>落盘是"抓一次永久复用"的关键：写进 {@code thumbnails/chXX.jpg} 之后，
     * 即便 refresh()/setConfig()/冷启动清空了内存 {@link #cache}，列表页也能走磁盘分支秒出
     * 历史图，不再把 9 路全部重抓一遍。
     */
    private void onSuccess(String key, Bitmap bitmap, int channelIndex) {
        synchronized (cache) {
            cache.put(key, bitmap);
        }
        persistToDisk(channelIndex, bitmap);
        failCount.remove(key);
        nextRetryAt.remove(key);
        final Set<ImageView> targets = pendingViews.remove(key);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        final Set<ImageView> snapshot = new HashSet<>(targets);
        handler.post(() -> {
            if (stopped) {
                return;
            }
            for (ImageView view : snapshot) {
                if (key.equals(view.getTag())) {
                    // ★ 显出来再贴 bitmap：IV 在 XML 默认 gone，抓帧成功也得显出来用户才看得到图
                    view.setVisibility(View.VISIBLE);
                    view.setImageBitmap(bitmap);
                }
            }
        });
    }

    /**
     * 抓到即落盘：写 {@code getFilesDir()/thumbnails/chXX.jpg}。
     *
     * <p>{@link ThumbnailCache#save} 内部走后台 IO 线程并自动缩放到 480×270 压缩，
     * 不阻塞主线程也不占空间。同时把 Bitmap 同步写进 {@link #diskBitmapCache} ——
     * save() 是异步的，diskBitmapCache 让"刚抓到"的图在同一次会话内立刻就能同步出图。
     */
    private void persistToDisk(int channelIndex, Bitmap bitmap) {
        if (appContext == null || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        diskBitmapCache.put(channelIndex, bitmap);
        ThumbnailCache.save(appContext, channelIndex, bitmap, null);
    }

    /** 预热（列表页"刷新缩略图"流程）截帧成功后同步落盘缓存：让回列表时 holder 直接命中
     * 内存缓存同步出图，避免 RecyclerView 异步 decodeFile 的短暂空白窗口被误判成"没图"。
     *
     * <p>背景：{@link com.litevision.tv.ThumbnailCapture#captureToCacheFile} 走的是
     * {@link ThumbnailCache#save} 异步 IO 路径，<b>不经过</b>本类的 {@link #persistToDisk}，
     * 因此 {@link #diskBitmapCache} 不会同步更新，回列表后 holder 只能异步 decodeFile →
     * setImageBitmap，主线程看到的是占位块的过渡帧（v6 修复时已经实测 &lt;50ms，但实际取决于
     * 磁盘速度，频繁闪屏/低端机可能 200ms+）。这里把截到的 bitmap 同步写进内存缓存，
     * 下次回列表 holder 直接命中缓存、同步 setImageBitmap，彻底消除"回列表看到几个
     * 格子没图"的现象。</p>
     *
     * @param channelIndex 0-based 通道索引
     * @param bitmap       截到的原始帧（不会被 recycle，由调用方继续复用）
     */
    public void primeDiskCache(int channelIndex, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || appContext == null) return;
        Bitmap scaled = ThumbnailCache.scaleToTargetFix16x9(bitmap, ThumbnailCache.THUMB_WIDTH,
                ThumbnailCache.THUMB_HEIGHT);
        if (scaled == null) return;
        diskBitmapCache.put(channelIndex, scaled);
        Log.d("ThumbnailLoader", "primeDiskCache ch" + channelIndex
                + " " + scaled.getWidth() + "x" + scaled.getHeight());
    }

    /** 失败：指数退避冷却 + 排期一次自动重试；有历史图或达上限则不再自动重试（防无限打 NVR）。 */
    private void onFailure(String key, int channelIndex) {
        if (stopped) {
            return;
        }
        int count = failCount.containsKey(key) ? failCount.get(key) : 0;
        count++;
        failCount.put(key, count);
        final long delayMs = retryDelayFor(count);
        nextRetryAt.put(key, System.currentTimeMillis() + delayMs);
        if (count >= MAX_AUTO_RETRY) {
            pendingViews.remove(key);
            Log.w(TAG, "缩略图放弃自动重试 ch" + channelIndex + " key=" + key
                    + "（已失败 " + count + " 次，HTTP 与 RTSP 均未出图）");
            return;
        }
        // 已有历史图（磁盘 jpg 或本次会话已解码的 Bitmap）→ 不再自动重试。
        // 有图可显示就够了，绝不为了"刷新得更清晰"而反复打 NVR（硬约束：有历史图不打网络）。
        if (hasHistory(channelIndex)) {
            Log.i(TAG, "已有历史缩略图 ch" + channelIndex
                    + "，停止自动重试（不为了刷新而占用 NVR 并发额度）");
            return;
        }
        Log.w(TAG, "缩略图获取失败 ch" + channelIndex + " key=" + key
                + "（第 " + count + " 次，" + (delayMs / 1000) + "s 后自动重试）");
        // 自动重试：仅重试 HTTP ISAPI 一次（RTSP 主+子码流均已失败过，再试 RTSP 没意义，
        // 且 retry 路径没有 TextureView reference——TextureView 由 ChannelAdapter 在 onBindViewHolder
        // 时按需传入，retry 时旧 ViewHolder 可能已被 RecyclerView 复用/销毁，抓帧目标已不可靠）。
        // 格子被 RecyclerView 复用后 tag 会换成新 key，天然作废，不会跨通道乱抓。
        handler.postDelayed(() -> {
            if (stopped || cache.get(key) != null) {
                return;
            }
            // 自动重试只验 HTTP：HTTP 抓到就 onSuccess；HTTP 再失败也不进 RTSP（避免 retry 用到旧 TV）。
            executor.execute(() -> {
                if (stopped || cache.get(key) != null) return;
                if (!inFlight.add(key)) return;
                try {
                    Log.i(TAG, "自动重试 HTTP 抓图 ch" + channelIndex + " key=" + key);
                    Bitmap b = null;
                    try { b = fetchSnapshot(channelIndex, key); } catch (Throwable ignored) {}
                    if (b != null) {
                        onSuccess(key, b, channelIndex);
                    } else {
                        // HTTP 仍失败，不进 RTSP：触发一次失败计数让 onFailure 把占位符盖在所有 pending 视图上。
                        // 注意：不递归加 failCount（会达到 MAX_AUTO_RETRY 阈值后停止）。
                        final Set<ImageView> targets = pendingViews.get(key);
                        if (targets != null) {
                            final Set<ImageView> snapshot = new HashSet<>(targets);
                            handler.post(() -> {
                                for (ImageView view : snapshot) {
                                    if (key.equals(view.getTag())) {
                                        // ★ 显出来 + 贴 placeholder：兜底永远不让用户看到纯黑矩形
                                        if (appContext != null && ThumbnailCache.hasCached(appContext, channelIndex)) {
                                            showDiskOrPlaceholder(view, channelIndex, key);
                                        } else {
                                            view.setVisibility(View.VISIBLE);
                                            view.setImageResource(R.drawable.thumbnail_placeholder);
                                        }
                                    }
                                }
                            });
                        }
                    }
                } finally {
                    inFlight.remove(key);
                }
            });
        }, delayMs);
    }

    /**
     * 该通道是否已有历史缩略图（磁盘 jpg，或本次会话已解码的 Bitmap）。
     * 有历史图时不再为"刷新"发起任何网络抓取。
     */
    private boolean hasHistory(int channelIndex) {
        Bitmap bm = diskBitmapCache.get(channelIndex);
        if (bm != null && !bm.isRecycled()) {
            return true;
        }
        return appContext != null && ThumbnailCache.hasCached(appContext, channelIndex);
    }

    /**
     * 第 {@code failCount} 次失败后的自动重试等待时长：指数退避 4s → 8s → 16s → 32s，封顶 60s。
     *
     * <p>原来固定 4s 会让一个失败通道在 20 秒内连打 NVR 5 次；指数退避把前期压力降下来，
     * 同时保留后续自愈机会（NVR 冷启动 / 网络抖动场景下仍能恢复）。
     */
    private static long retryDelayFor(int failCount) {
        long delay = RETRY_INTERVAL_MS;
        for (int i = 1; i < failCount && delay < MAX_RETRY_INTERVAL_MS; i++) {
            delay <<= 1;
        }
        return Math.min(delay, MAX_RETRY_INTERVAL_MS);
    }

    /**
     * 是否允许为指定 key 发起一次取图尝试。
     * 失败次数达上限 → 否；仍处于冷却期 → 否；其余 → 是。
     */
    private boolean canAttempt(String key) {
        Integer failed = failCount.get(key);
        if (failed != null && failed >= MAX_AUTO_RETRY) {
            return false;
        }
        Long retryAt = nextRetryAt.get(key);
        return retryAt == null || System.currentTimeMillis() >= retryAt;
    }

    /** ISAPI HTTP 抓图是否仍可尝试（熔断判定）。 */
    private boolean isHttpEnabled() {
        return httpSucceeded || System.currentTimeMillis() >= httpBreakUntil;
    }

    /** 清空重试与熔断状态（配置变更/通道列表刷新时调用，给一次全新机会）。 */
    private void resetRetryState() {
        failCount.clear();
        nextRetryAt.clear();
        httpSucceeded = false;
        httpConsecutiveFailures.set(0);
        httpBreakUntil = 0L;
    }

    // ===== HTTP 抓图（ISAPI）=====

    /**
     * 已探测成功的快照路径类型（-1=未探测）。
     *
     * 海康 NVR 的快照路径因通道类型而异：
     *   - 模拟通道: /ISAPI/Streaming/channels/{通道号*100+1}/picture
     *   - IP 代理通道(InputProxy): /ISAPI/ContentMgmt/InputProxy/channels/{ID}/picture，
     *     且 IP 通道 ID 在多数固件上从 33 起（模拟通道占 1-32）。
     * 通道名列表来自 InputProxy 端点，说明本机是 IP 通道；旧代码固定拼 Streaming/101 路径
     * -> 全部 404 -> 缩略图全部不显示。此处按候选探测，成功后记住类型，后续通道直接复用。
     */
    private volatile int snapshotPathKind = -1;

    private String pathFor(int kind, int channelIndex) {
        switch (kind) {
            case 0:  return "/ISAPI/ContentMgmt/InputProxy/channels/" + (channelIndex + 1) + "/picture";
            case 1:  return "/ISAPI/Streaming/channels/" + ((channelIndex + 1) * 100 + 1) + "/picture";
            case 2:  return "/ISAPI/ContentMgmt/InputProxy/channels/" + (channelIndex + 33) + "/picture";
            default: return "/ISAPI/Streaming/channels/" + ((channelIndex + 33) * 100 + 1) + "/picture";
        }
    }

    /** 按候选路径依次探测，第一个成功的记住类型并返回。 */
    private NvrClient.Response fetchWithProbe(NvrClient client, int channelIndex) {
        int known = snapshotPathKind;
        if (known >= 0) {
            NvrClient.Response r = client.get(pathFor(known, channelIndex));
            if (r.isSuccess()) return r;
            Log.w(TAG, "snapshot path kind=" + known + " failed ch" + channelIndex
                    + " code=" + r.code + ", re-probing");
            snapshotPathKind = -1;
        }
        for (int kind = 0; kind <= 3; kind++) {
            if (kind == known) continue;
            String p = pathFor(kind, channelIndex);
            Log.i(TAG, "probing snapshot path kind=" + kind + " ch" + channelIndex + ": " + p);
            NvrClient.Response r = client.get(p);
            if (r.isSuccess()) {
                snapshotPathKind = kind;
                Log.i(TAG, "snapshot path resolved: kind=" + kind);
                return r;
            }
            Log.w(TAG, "snapshot candidate kind=" + kind + " ch" + channelIndex
                    + " code=" + r.code + " err=" + r.error);
        }
        return new NvrClient.Response(-1, null, "all snapshot paths failed", null);
    }

    private Bitmap fetchSnapshot(int channelIndex, String cacheKey) {
        final long t0 = System.currentTimeMillis();
        try {
            Log.i(TAG, "HTTP 抓图开始 ch" + channelIndex + " (host=" + config.getConnectionHost()
                    + ":" + config.getEffectiveHttpPort() + ")");
            // 后台线程：等待域名解析完成后再建 client，保证抓图走与通道列表相同的地址
            NvrClient localClient = ensureClient();
            if (localClient == null) {
                Log.w(TAG, "NvrClient 不可用，跳过抓图: " + cacheKey);
                noteHttpFailure(channelIndex, "NvrClient 不可用");
                return null;
            }

            // Use path (not full URL) so NvrClient's Digest auth uses correct URI
            NvrClient.Response resp;
            // NvrClient 内部缓存了 Digest nonce 等状态，不可并发使用（串行化，防 401 锁账号）。
            synchronized (localClient) {
                resp = fetchWithProbe(localClient, channelIndex);
            }

            if (resp.isSuccess()) {
                byte[] data = resp.data;
                Log.i(TAG, "HTTP 抓图成功 ch" + channelIndex + " " + data.length
                        + " bytes 耗时=" + (System.currentTimeMillis() - t0) + "ms");

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                if (bitmap != null) {
                    Log.i(TAG, "解码成功 " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    // 站点支持 ISAPI 抓图 → 永久保留 HTTP 通道（不清零其它状态）
                    httpSucceeded = true;
                    httpConsecutiveFailures.set(0);
                } else {
                    noteHttpFailure(channelIndex, "图片解码失败");
                }
                return bitmap;
            } else {
                Log.w(TAG, "ISAPI 抓图失败 ch" + channelIndex + ": code=" + resp.code
                        + " err=" + resp.error + " 耗时=" + (System.currentTimeMillis() - t0)
                        + "ms（将回退 RTSP 抓帧）");
                noteHttpFailure(channelIndex, "code=" + resp.code + " err=" + resp.error);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fetch error for channel " + channelIndex, e);
            noteHttpFailure(channelIndex, "异常: " + e.getMessage());
        }
        return null;
    }

    /** HTTP 抓图失败记账 + 熔断判定。 */
    private void noteHttpFailure(int channelIndex, String reason) {
        Log.w(TAG, "HTTP 抓图失败 ch" + channelIndex + " 原因=" + reason);
        if (httpSucceeded) {
            return;
        }
        // 原子自增：HTTP 线程池有 2 条线程并发，volatile++ 会丢计数导致阈值判断漂移
        int failures = httpConsecutiveFailures.incrementAndGet();
        if (failures >= HTTP_BREAK_THRESHOLD) {
            httpBreakUntil = System.currentTimeMillis() + HTTP_BREAK_MS;
            Log.w(TAG, "ISAPI HTTP 抓图连续失败 " + failures
                    + " 次（本站点可能不支持/反代不通），熔断 "
                    + (HTTP_BREAK_MS / 1000) + "s，后续通道直接走 RTSP 抓帧回退");
        }
    }

    // ===== RTSP 抓帧回退 =====

    /**
     * RTSP 抓帧：调用方提供的 TextureView（RV itemHolder 的 tv_thumbnail）。
     * libVLC 渲染到该 TV 上，出首帧后用 {@link android.view.TextureView#getBitmap()} 拿图。
     * TV 状态校验交给 {@link RtspFrameGrabber#grab} 内部完成（每次回调检查
     * isAttachedToWindow/isAvailable）。
     */
    private Bitmap fetchRtspFrame(android.view.TextureView tv, int channelIndex) {
        if (config == null) {
            return null;
        }
        if (tv == null) {
            Log.w(TAG, "TV 为空，跳过 RTSP 抓帧 ch" + channelIndex);
            return null;
        }
        // 与取流播放一致：先等域名解析，保证抓帧走与多画面相同的地址
        config.awaitHostResolution();

        // 主码流（4s）：码率高但 I 帧出现快
        String mainUrl = config.getRtspUrl(channelIndex, false);
        long start = System.currentTimeMillis();
        Log.i(TAG, "RTSP 抓帧开始 ch" + channelIndex + " (main) url=" + maskUrl(mainUrl)
                + " tvAttached=" + tv.isAttachedToWindow()
                + " tvAvailable=" + tv.isAvailable()
                + " tvSt=" + (tv.getSurfaceTexture() != null));
        Bitmap bitmap = grabber.grab(tv, mainUrl, config.getAvcodecHwValue(),
                RTSP_MAIN_TIMEOUT_MS, channelIndex);
        if (bitmap != null) {
            Log.i(TAG, "RTSP 主码流抓帧成功 ch" + channelIndex + " 耗时=" + (System.currentTimeMillis() - start) + "ms "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            return bitmap;
        }
        Log.w(TAG, "RTSP 主码流抓帧失败/超时 ch" + channelIndex + " 耗时=" + (System.currentTimeMillis() - start)
                + "ms，尝试子码流");

        // 子码流（4s）：码率低，但部分 NVR 关闭/转码延迟会拖到 5s+
        String subUrl = config.getRtspUrlSubStream(channelIndex);
        long startSub = System.currentTimeMillis();
        Log.i(TAG, "RTSP 抓帧开始 ch" + channelIndex + " (sub) url=" + maskUrl(subUrl));
        bitmap = grabber.grab(tv, subUrl, config.getAvcodecHwValue(),
                RTSP_SUB_TIMEOUT_MS, channelIndex);
        long cost = System.currentTimeMillis() - startSub;
        if (bitmap != null) {
            Log.i(TAG, "RTSP 子码流抓帧成功 ch" + channelIndex + " 耗时=" + cost + "ms "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
        } else {
            Log.w(TAG, "RTSP 主+子码流均抓帧失败 ch" + channelIndex + " 总耗时="
                    + (System.currentTimeMillis() - start) + "ms（格子保持 placeholder）");
        }
        return bitmap;
    }

    private static String maskUrl(String url) {
        if (url == null) {
            return "null";
        }
        return url.replaceAll("://[^/@]*@", "://***@");
    }

    public void refresh() {
        synchronized (cache) {
            cache.clear();
        }
        // 不在此处创建 client：ChannelAdapter 在 UI 线程调用本方法，
        // 而建 client 需要等待域名解析。置空即可，后台线程会按需重建。
        invalidateClient();
        resetRetryState();
    }

    /** 丢弃当前 client，下次抓图时按最新配置重建。 */
    private synchronized void invalidateClient() {
        client = null;
        clientHost = "";
        clientPort = -1;
    }

    /**
     * 彻底停止缩略图加载（列表页销毁时调用，由 ChannelAdapter 转调）。
     *
     * <p>必须调用的意义：抓帧任务每路最多占用 NVR 一条取流连接达 8s，若退到多画面后仍在跑，
     * 会与多画面的 9 路取流抢 NVR 并发额度；且共享的 LibVLC 实例若不 release 会一直占着资源。
     *
     * <p>停止后置 stopped=true：清空回填登记、丢弃排队任务、所有 UI 回填不再生效，
     * 并异步释放 LibVLC（VLC 的 release 有阻塞，不能在主线程执行）。
     */
    public void shutdown() {
        stopped = true;
        pendingViews.clear();
        inFlight.clear();
        // 只丢弃引用、不 recycle：这些 Bitmap 可能仍被 ImageView 持有，交给 GC 回收。
        // 注意：refresh()/setConfig() 不清理本缓存（磁盘 jpg 与 host/port 无关，清了必然重现黑块）。
        diskBitmapCache.clear();
        // shutdownNow：丢弃排队等待的抓图/抓帧任务（在途任务自然跑完，但不再回填 UI）
        executor.shutdownNow();
        rtspExecutor.shutdownNow();
        Thread releaser = new Thread(() -> grabber.release(), "thumb-vlc-release");
        releaser.setDaemon(true);
        releaser.start();
        Log.i(TAG, "缩略图加载器已停止（丢弃排队任务并释放 LibVLC）");
    }
}
