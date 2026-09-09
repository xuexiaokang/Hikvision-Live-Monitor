package com.litevision.tv;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupWindow;

import androidx.appcompat.app.AppCompatActivity;

import org.videolan.libvlc.LibVLC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MultiViewActivity — 多画面宫格预览页（1/4/6/9/16）。
 *
 * 设计（docs/system_design.md）：
 *   - 单一 LibVLC 实例（实例级 --no-audio，多画面全静音）+ N 个 MediaPlayer 池；
 *   - 每格一个 ChannelTileView + PlayerEngine（槽位 index 与通道 index 一致；
 *     触屏翻页时格子的通道会偏移，但槽位/引擎保持一一对应）；
 *   - 布局切换：先释放全部旧槽位（释放 NVR 连接）→ 调整 Grid 行列 → 新建槽位（只建不播）→ 可见后才 start；
 *   - 可见性策略：失焦/不可见槽位延迟 {SLOT_STOP_DELAY_MS} 断开（scheduleStopSlot 防抖），
 *     焦点格保活；onWindowFocusChanged(false) 立即全暂停；
 *   - 持久化：切换布局写 last_multi_layout，切子/主写 multi_use_sub（默认子码流），
 *     退出（onStop）写 last_view_mode=multi；
*   1→4→6→9→16→1；MENU 短按 = 切换子/主码流；MENU 长按 = 返回 Main；BACK = 返回并记忆；
     *     触屏设备走手势（单击=更新焦点+弹布局浮窗选4/9/16、双击=独立单画面高清取流全屏、左右滑动=翻页、上下滑=调音）。
     *   注：双击不再走"宫格内嵌全屏"（TextureView 搬移导致黑屏的根源），统一改为拉独立 PlayerActivity；
     *     进全屏必须双击，单击不触发（用户要求，避免手滑误进全屏）；
     *     在单画面页再双击即 finish() 返回本页（见 PlayerActivity.dispatchTouchEvent）。
     */
public class MultiViewActivity extends AppCompatActivity {

    private static final String TAG = "MultiViewActivity";

    public static final String EXTRA_LAYOUT = "layout";
    /** 自动预热模式：进入后强制 9 宫格播全部通道，逐路截帧写入缩略图缓存，完成后自动 finish 回列表。 */
    public static final String EXTRA_AUTOCAPTURE = "autocapture";

    private static final int MAX_SLOTS = 16;
    private static final int SLOT_STOP_DELAY_MS = 2000;
    private static final long LONG_PRESS_MS = 800;
    private static final int SWIPE_THRESHOLD = 100;
    /**
     * 缩略图截图延时（毫秒）：onPlaying 后兜底延时。
     *  onVideoSizeChanged 后 500ms 是首选（首帧已渲染），但若该回调迟迟不来，
     *  onPlaying 的兜底路径会保证即便没有首帧信号也能触发截帧（靠 ThumbnailCapture
     *  内部黑帧重试，最多 3 次总间隔 5.4s，覆盖绝大多数首帧延迟）。
     */
    private static final long THUMB_CAPTURE_DELAY_MS = 4000L;
    /**
     * 顶部手势提示文字显示时长（毫秒）。显示后自动消失，避免长期遮挡画面——
     * 手机/平板上尤其碍眼，电视端遥控器用户也只需要看一次提示。
     */
    private static final int HINT_DURATION_MS = 5000;
    private static final int SWIPE_VELOCITY_THRESHOLD = 100;

    /**
     * 由屏幕尺寸自适应计算的滑动阈值（像素）。手机窄->阈值小才划得动；平板宽->阈值大避免误触。
     * 取屏幕短边的 8%：手机约 50~60px、平板约 80~100px。与单画面 PlayerActivity 同一套设计。
     */
    private int swipeThreshold = SWIPE_THRESHOLD;

    // ===== 滑动翻页灵敏度（慢速滑动兜底）=====
    /** 本次手势是否已触发翻页/音量（onFling 或 UP 兜底），防止双重执行。 */
    private boolean swipeHandledInGesture = false;
    /** 本次手势 ACTION_DOWN 的屏幕绝对坐标（UP 兜底判定位移用）。 */
    private float gestureDownRawX = 0f;
    private float gestureDownRawY = 0f;

    // ===== 引擎与配置 =====
    private ConfigManager config;
    private LibVLC libVLC;

    // ===== 槽位池：slots[i] 视图 / engines[i] 引擎 一一对应 =====
    private final ChannelTileView[] slots = new ChannelTileView[MAX_SLOTS];
    private final PlayerEngine[] engines = new PlayerEngine[MAX_SLOTS];
    private final boolean[] slotActive = new boolean[MAX_SLOTS];
    /** 每格缩略图截图守门（避免 onPlaying 反复触发重复写盘）。与 slotActive 同生命周期。 */
    private final boolean[] slotThumbCaptured = new boolean[MAX_SLOTS];
    /** 每格"截图 Runnable"引用：用于 onPause/onDestroy 时按槽位精确取消排期。 */
    private final Runnable[] slotThumbRunnable = new Runnable[MAX_SLOTS];
    /** 每格是否已收到 onVideoSizeChanged（首帧渲染信号），用于智能截帧判断。 */
    private final boolean[] slotVideoSizeChanged = new boolean[MAX_SLOTS];

    private GridLayout grid;
    private TextView tvHint;
    /** 底部页码指示（轻量、不可点击）：滑动翻页为主交互，仅提示当前页/总页数。 */
    private TextView tvPageIndicator;
    private int layoutSize = 4;          // 1/4/6/9
    private int focusedSlot = 0;
    private boolean useSubStream = true; // 多画面码流（独立键 multi_use_sub）
    /** 自动预热模式（列表页"刷新缩略图"按钮触发）：强制 9 宫格 + 截帧后自动回列表。 */
    private boolean autoCapture = false;
    /** 自动预热兜底超时（毫秒）：9 路播放 + 4s 兜底截帧 + 8 次重试（每 1.8s）。
     *  最坏情况 9 路都慢：onPlaying → 4s 截帧 → 黑帧重试 1.8s×7 = 12.6s，合计约 17s。
     *  给 30s 留足余量，避免慢通道还没走完重试就被强制 finish。 */
    private static final long AUTO_CAPTURE_TIMEOUT_MS = 30000L;
    // ===== 预热进度条（"刷新缩略图"）：让用户看到 n/total 进度，避免误以为卡死而中断 =====
    private View layoutCaptureProgress;
    private android.widget.ProgressBar pbCapture;
    private TextView tvCaptureProgress;
    /** 本轮需要截帧的总路数（= min(宫格数, 有效通道数)）。 */
    private int captureTotal = 0;
    /** 已发起截帧的路数（发起即计数，失败也算已处理，保证进度能走完不卡住）。 */
    private int captureProgress = 0;
    /** 已全部完成并正在退出，避免超时回调与完成回调重复 finish。 */
    private boolean captureFinishing = false;
    /** 预热失败通道列表（记录通道索引） */
    private final java.util.Set<Integer> captureFailedChannels = new java.util.HashSet<>();
    /** 预热成功通道计数 */
    private int captureSuccessCount = 0;

    // ===== 宫格内嵌全屏（双击）状态 =====
    /** 当前内嵌全屏的格子索引；-1 = 普通宫格模式。格子内的 ChannelTileView 被临时移到全屏容器。 */
    private int fullScreenSlot = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ===== 可见性/启动集合 =====
    /** 当前布局内"应播放"的槽位（P0：布局内全部可见全部播） */
    private final Set<Integer> activeSlots = new HashSet<>();
    /** 已进入 Playing 的槽位（用于保活判断） */
    private final Set<Integer> startedSlots = new HashSet<>();
    /** 已调度延迟停止、尚未执行的槽位（防抖） */
    private final Set<Integer> pendingStop = new HashSet<>();

    // ===== 后台停止线程（防 VLC 死锁：stop/release 必须后台执行）=====
    private final ExecutorService stopExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "multi-vlc-stop");
                t.setDaemon(true);
                return t;
            });

    // ===== 输入映射状态 =====
    private boolean isTouchDevice = false;
    private GestureDetector gestureDetector = null;
    private long okKeyDownTime = 0L;
    private boolean okKeyHandled = false;
    private long menuKeyDownTime = 0L;
    private boolean menuKeyHandled = false;
    /** 双击第二击抬起坐标缓存（onDoubleTap 命不中时用真实抬起位置兜底命中）。 */
    private float doubleTapUpRawX = -1f;
    private float doubleTapUpRawY = -1f;
    /** 已识别为双击、但尚未在第二击 UP 时落 enterFullScreen 的待办标记。 */
    private boolean pendingDoubleTapEnter = false;

    // ===== 手写双击检测（双保险：部分 Android 版本/盒子 GestureDetector 不允许 onDoubleTap）=====
    // 不依赖 GestureDetector 状态机，直接按两次 DOWN 的时间戳与坐标判定双击，
    // 命中即进/退全屏，绕开设备差异导致的 onDoubleTap 不触发。
    private long mDLastTimeMs = 0L;
    private float mDLastDownX = -1f;
    private float mDLastDownY = -1f;
    private static final long DBL_TAP_WINDOW_MS = 320L;   // 两击间隔上限
    private static final float DBL_TAP_SLOP_PX = 120f;     // 两击坐标偏移容忍（屏对角线/适配）
    private boolean mDblConsumedThisDown = false;
    /** 手写双击命中时刻：用于抑制该双击第一击触发的 onSingleTapConfirmed 单击动作。 */
    private long mManualDoubleTapAtMs = 0L;
    /** Activity 级双击执行节流时间戳：手写双击与 GestureDetector 双途径复用同一入口，防 enter→exit 反转。 */
    private long performDoubleTapLastMs = 0L;

    /** 最近一次参与双击判定的物理 DOWN 的 downTime（同一事件多次经过不同 listener 时去重用）。 */
    private long lastDblDownTime = -1L;

    /**
     * 在 DOWN 时先做手写双击判定：命中返回 true（已消费，避免再喂 GestureDetector 触发单击）。
     *
     * ★ 幂等保护：一个物理 DOWN 事件会依次经过 Activity.dispatchTouchEvent → grid → tile
     * 等多个 listener，都会调到这里。用 e.getDownTime()（同一物理事件的唯一标识）做去重，
     * 保证"同一击"只被判定/记录一次，否则会把同一个事件二次判定成"双击不重录"（自误判）。
     */
    private boolean detectDoubleTapDown(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        // 同一物理事件（downTime 相同）只处理一次，避免多入口重复调用导致 自误判为双击。
        if (e.getDownTime() == lastDblDownTime) {
            return false;
        }
        lastDblDownTime = e.getDownTime();
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
            Log.i(TAG, "手写双击命中 upDown=" + e.getDownTime()
                    + " raw=" + rx + "," + ry + " fullScreenSlot=" + fullScreenSlot);
            performDoubleTap(rx, ry);
            mDLastTimeMs = 0L; // 抑制连击
            mManualDoubleTapAtMs = System.currentTimeMillis(); // 抑制本双击第一击的单击进页
            return true;
        }
        return false;
    }

    /**
     * 双击执行的唯一入口（手写双击与 GestureDetector 双途径都走到这里）。
     * 命中格/焦点格 → 拉独立单画面 PlayerActivity 高清取流（绕开 TextureView 搬移黑屏路径）；
     * 不再进入宫格内嵌全屏，因此 fullScreenSlot 恒为 -1（内嵌套状机废弃）。
     * 带 600ms 节流去重，杜绝"手写 + GestureDetector 双触发"导致重复跳页。
     */
    private void performDoubleTap(float rawX, float rawY) {
        long now = System.currentTimeMillis();
        if (now - performDoubleTapLastMs < 600L) {
            Log.d(TAG, "双击节流跳过（防双途径重复触发）");
            return;
        }
        performDoubleTapLastMs = now;

        // 双击 → 独立单画面取流（不再进入内嵌全屏，彻底绕开搬移 TextureView 的黑屏根源）
        ChannelTileView t = hitTest(rawX, rawY);
        if (t == null) {
            // 坐标异常/跨格 miss：退回当前焦点格，保证双击不会毫无反应
            Log.w(TAG, "双击 hitTest 未命中 raw=" + rawX + "," + rawY
                    + "，退回焦点格 slot=" + focusedSlot);
            int fb = focusedSlot;
            if (fb >= 0 && fb < layoutSize && slots[fb] != null) {
                t = slots[fb];
            }
        }
        if (t != null) {
            int ch = t.getChannelIndex();
            Log.i(TAG, "双击命中 → 独立单画面取流 ch=" + ch);
            startPlayer(ch);
        } else {
            Log.w(TAG, "双击无效：既无点击格也无焦点格，忽略");
        }
    }

    // ===== 生命周期 =====

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi);
        if (getSupportActionBar() != null) {
            try { getSupportActionBar().hide(); } catch (Exception ignored) {}
        }

        config = new ConfigManager(this);
        grid = findViewById(R.id.grid_multi);
        fullScreenLayer = findViewById(R.id.fullscreen_layer);
        tvHint = findViewById(R.id.tv_hint);

        // 底部页码指示（不可点击，事件穿透到宫格；翻页靠左右滑动/遥控器方向键到边）
        tvPageIndicator = findViewById(R.id.tv_page_indicator);

        // ===== 自动预热模式（列表页"刷新缩略图"按钮）=====
        autoCapture = getIntent().getBooleanExtra(EXTRA_AUTOCAPTURE, false);

        // 启动参数优先（Main 恢复路由会传 EXTRA_LAYOUT），否则取记忆
        int requested = getIntent().getIntExtra(EXTRA_LAYOUT, -1);
        if (autoCapture) {
            layoutSize = 9; // 强制 9 宫格覆盖全部通道
        } else {
            layoutSize = clampLayout(requested > 0 ? requested : config.getLastMultiLayout());
        }
        useSubStream = config.isMultiUseSub();
        Log.i(TAG, "onCreate: layout=" + layoutSize + " useSubStream=" + useSubStream
                + " autoCapture=" + autoCapture);

        // ===== 单一 LibVLC 实例（实例级 --no-audio：所有格静音）=====
        if (!initLibVlc()) {
            Toast.makeText(this, "多画面引擎初始化失败", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 只建不播：先建网格与槽位，等 onResume + Surface 就绪后再 start
        applyLayout(layoutSize);

        isTouchDevice = isTouchScreenDevice();
        // 手势无条件初始化：TV 模式盒子/触屏一体机也常 report TELEVISION，仍应响应双击全屏。
        // isTouchDevice 仅用于提示文案与 pager 显示，不参与手势是否建立。
        initTouchSupport();
        if (autoCapture) {
            // ★ 修复（2026-09-08）：预热模式下 tv_hint 是 FrameLayout 里盖在 GridLayout
            //   之上的顶部覆盖条（gravity=top|center_horizontal），PixelCopy(Window) 截
            //   第一排格子时会把"正在生成缩略图…"整条提示一起截进缩略图（用户反馈
            //   "截图混乱 / 把提示文字也截取了"）。
            //   改为 Toast 提示（Toast 是独立 Window，不参与本窗口合成，不会被 PixelCopy 截到），
            //   并隐藏顶部提示条，保证缩略图里只有视频画面。
            if (tvHint != null) tvHint.setVisibility(View.GONE);
            // 底部页码指示条同理（gravity=bottom），会盖住最后一排格子 → 一并隐藏
            if (tvPageIndicator != null) tvPageIndicator.setVisibility(View.GONE);
            Toast.makeText(this, getString(R.string.refresh_thumbs_hint), Toast.LENGTH_SHORT).show();

            // ===== 预热进度条：贴屏显示 n/total，避免用户以为卡死而手动中断 =====
            // ★ 进度条是窗口覆盖层，会被 PixelCopy 截进缩略图 → 注册到 ThumbnailCapture，
            //   每次截帧瞬间自动隐藏、截完恢复（"看得见进度，又不污染缩略图"）。
            layoutCaptureProgress = findViewById(R.id.layout_capture_progress);
            pbCapture = findViewById(R.id.pb_capture);
            tvCaptureProgress = findViewById(R.id.tv_capture_progress);
            if (layoutCaptureProgress != null) {
                // 分母 = min(宫格数, 有效通道数)；通道列表未拉到(getChannelCount()==0)时按宫格数算
                int totalCh = config.getChannelCount();
                captureTotal = Math.max(1,
                        Math.min(layoutSize, totalCh > 0 ? totalCh : layoutSize));
                captureProgress = 0;
                captureSuccessCount = 0;
                captureFailedChannels.clear();
                if (pbCapture != null) {
                    pbCapture.setMax(captureTotal);
                    pbCapture.setProgress(0);
                }
                if (tvCaptureProgress != null) {
                    tvCaptureProgress.setText(getString(R.string.capture_progress, 0, captureTotal));
                }
                layoutCaptureProgress.setVisibility(View.VISIBLE);
                ThumbnailCapture.addHideOnCapture(layoutCaptureProgress);
            }

            // 兜底超时：无论截帧是否全部成功，到点都 finish 回列表，避免卡死在多画面。
            handler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed() && !captureFinishing) {
                    Log.i(TAG, "自动预热超时（" + AUTO_CAPTURE_TIMEOUT_MS + "ms），finish 回列表");
                    finish();
                }
            }, AUTO_CAPTURE_TIMEOUT_MS);
        } else {
            setHint(isTouchDevice
                    ? getString(R.string.multi_hint_touch)
                    : getString(R.string.multi_hint));
        }
        refreshPager();
    }

    private boolean initLibVlc() {
        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--no-audio");            // 多画面全静音（设计 §3.1）
            options.add("--vout=android-display");// 复用已验证的渲染后端
            // 低延迟/解码参数统一取自 VlcOptions（与 PlayerEngine 的 per-media **同源**）。
            // 原先此处硬编码一份、靠注释约定"两处必须一起改"——漏改时症状隐蔽（改了没变快），
            // 现由单一事实源保证实例级与 per-media 永远一致。
            VlcOptions.addInstanceOptions(options, config.getAvcodecHwValue(), useSubStream);
            libVLC = new LibVLC(this, options);
            Log.i(TAG, "LibVLC 创建成功（--no-audio 多画面静音实例）");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "LibVLC 初始化失败: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (libVLC == null) return;
        // 回到前台：重建可见格播放
        startVisibleSlots();
        Log.i(TAG, "onResume: layout=" + layoutSize + " slots=" + layoutSize);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseAllSlots();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // ===== 退出记忆（设计 §5）：last_view_mode=multi + 布局 =====
        // 自动预热模式不写记忆：用户从列表点"刷新缩略图"进来，回列表后仍应停留列表，
        // 否则下次冷启动会被 restoreLastSession 直接拽回多画面。
        if (!autoCapture) {
            try {
                config.setLastViewMode("multi");
                config.setLastMultiLayout(layoutSize);
                Log.i(TAG, "onStop: 保存上次状态 multi layout=" + layoutSize);
            } catch (Throwable t) {
                Log.w(TAG, "保存 multi 状态失败: " + t.getMessage());
            }
        }
        stopAllSlots();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        // 注销"截帧时临时隐藏"的注册，避免静态表持有已销毁 Activity 的 View
        if (layoutCaptureProgress != null) {
            ThumbnailCapture.removeHideOnCapture(layoutCaptureProgress);
            layoutCaptureProgress = null;
        }
        releaseAllSlots();
        final LibVLC oldLib = libVLC;
        libVLC = null;
        stopExecutor.execute(() -> {
            if (oldLib != null) {
                try { oldLib.release(); } catch (Throwable t) {
                    Log.w(TAG, "LibVLC release 错误: " + t.getMessage());
                }
            }
            Log.i(TAG, "LibVLC 已释放 (onDestroy)");
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            pauseAllSlots();
        } else {
            startVisibleSlots();
        }
    }

    // ===== 布局/槽位 =====

    private static int clampLayout(int layout) {
        if (layout == 1 || layout == 4 || layout == 6 || layout == 9 || layout == 16) {
            return layout;
        }
        return 4;
    }

    /** 布局对应的 Grid 行列：{rows, cols}。 */
    private static int[] gridDimension(int layout) {
        switch (layout) {
            case 1: return new int[]{1, 1};
            case 4: return new int[]{2, 2};
            case 6: return new int[]{2, 3};
            case 9: return new int[]{3, 3};
            case 16: return new int[]{4, 4};
            default: return new int[]{2, 2};
        }
    }

    private String layoutName(int layout) {
        switch (layout) {
            case 1: return getString(R.string.layout_1);
            case 4: return getString(R.string.layout_4);
            case 6: return getString(R.string.layout_6);
            case 9: return getString(R.string.layout_9);
            case 16: return getString(R.string.layout_16);
            default: return layout + "";
        }
    }

    /** 重置网格：移除全部旧子 View，重建布局容器。槽位随后由 createSlot() 增补。 */
    private void applyLayout(final int layout) {
        // 布局切换前先退出全屏：抬起在全屏层的格子不在 grid 里，直接 removeAllViews 会把它
        // 连带着删掉且引擎未释放，留下悬空引用（releaseAllSlots 之后 slots[i] 已为 null）。
        exitFullScreen();
        grid.removeAllViews();
        int[] dim = gridDimension(layout);
        grid.setColumnCount(dim[1]);
        grid.setRowCount(dim[0]);

        for (int i = 0; i < MAX_SLOTS; i++) {
            slotActive[i] = false;
            slotThumbCaptured[i] = false;
            slotVideoSizeChanged[i] = false;
            // 旧引擎由调用方（switchLayout/onDestroy）负责释放
        }
        activeSlots.clear();
        startedSlots.clear();
        pendingStop.clear();
        focusedSlot = 0;

        // 为当前布局创建槽位（只建不播）
        int[] dim0 = gridDimension(layout);
        for (int i = 0; i < layout; i++) {
            int row = i / dim0[1];
            int col = i % dim0[1];
            createSlot(i, i, row, col);
        }

        // 焦点落在首格（若存在）
        if (layout > 0 && slots[0] != null) {
            slots[0].requestFocus();
        }
        refreshPager();
    }

    /** 创建单个槽位：ChannelTileView + PlayerEngine（只建不播）。 */
    private void createSlot(final int index, final int channelIndex, int row, int col) {
        if (index >= MAX_SLOTS) return;

        ChannelTileView tile = new ChannelTileView(this);
        // GridLayout 权重平分：width/height=0 + spec(weight=1f) + Gravity.FILL
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(row, 1f),
                GridLayout.spec(col, 1f));
        lp.width = 0;
        lp.height = 0;
        lp.setGravity(Gravity.FILL);
        // 添加 1dp margin 形成黑色分隔线（通过 GridLayout 的负间距实现）
        int margin = (int) (1 * getResources().getDisplayMetrics().density);
        lp.setMargins(margin, margin, margin, margin);
        grid.addView(tile, lp);

        // 解码模式由外部注入（引擎不持有 Context），多画面所有格子共用同一设置
        PlayerEngine engine = new PlayerEngine(libVLC, new PlayerEngine.Callback() {
            @Override
            public void onPlaying(int ch) {
                runOnUiThread(() -> {
                    if (index >= 0 && index < MAX_SLOTS && slotActive[index] && slots[index] != null) {
                        startedSlots.add(index);
                        slots[index].showError(null);
                        // 视频尺寸已就绪 → 校正 center-crop，避免底部花屏
                        slots[index].applyCropIfNeeded();

                        // ===== 排期缩略图截图（"真实播放帧做缩略图"方案）=====
                        // onPlaying 后延迟截帧，给慢通道更多起播时间。
                        // 每槽位只截一次（slotThumbCaptured 守门）。
                        if (!slotThumbCaptured[index] && autoCapture) {
                            final int slotIndex = index;
                            final ChannelTileView tile = slots[index];
                            // 先清旧 runnable（防并发翻页同一槽位多次排期）
                            Runnable old = slotThumbRunnable[slotIndex];
                            if (old != null) {
                                handler.removeCallbacks(old);
                            }
                            final int finalChannelIndex = ch;
                            Runnable r = new Runnable() {
                                @Override
                                public void run() {
                                    if (isFinishing() || isDestroyed()) {
                                        return;
                                    }
                                    if (slotThumbCaptured[slotIndex]) {
                                        return;
                                    }
                                    if (slotIndex < 0 || slotIndex >= MAX_SLOTS
                                            || slots[slotIndex] == null
                                            || !slotActive[slotIndex]) {
                                        return;
                                    }
                                    slotThumbCaptured[slotIndex] = true;
                                    slots[slotIndex].captureThumbnailToCache(MultiViewActivity.this);
                                    // 预热模式：推进进度条（发起即计数，失败也算已处理，保证进度走完）
                                    onCaptureProgressed();
                                }
                            };
                            slotThumbRunnable[slotIndex] = r;
                            handler.postDelayed(r, THUMB_CAPTURE_DELAY_MS);
                        }
                    }
                });
            }

            @Override
            public void onError(int ch, String msg) {
                // DDNS：引擎放弃重试（连接失败）时强刷域名解析（公共 DNS 直查+探活）。
                // startHostResolution 内部有"线程在跑即跳过"守门，9 格连环失败也不会开线程风暴。
                config.refreshHostResolution();
                runOnUiThread(() -> {
                    if (index >= 0 && index < MAX_SLOTS && slots[index] != null) {
                        slots[index].showError(getString(R.string.multi_tile_error, msg));
                    }
                });
            }

            @Override
            public void onFreeze(int ch) {
                runOnUiThread(() -> {
                    if (index >= 0 && index < MAX_SLOTS && slots[index] != null) {
                        slots[index].showError("画面冻结，重连中…");
                    }
                });
            }

            @Override
            public void onBuffering(int ch, float pct) {
                runOnUiThread(() -> {
                    if (index >= 0 && index < MAX_SLOTS && slots[index] != null) {
                        slots[index].showError("缓冲中… " + (int) pct + "%");
                    }
                });
            }

            @Override
            public void onVideoSizeChanged(int ch) {
                // VLC 真实视频尺寸/SAR 更新（可能晚于 Playing）：重算本格 center-crop，
                // 避免 transform 停留在旧比例导致底部脏带残留（"摄像头不统一"异构码流场景）。
                runOnUiThread(() -> {
                    if (index >= 0 && index < MAX_SLOTS && slots[index] != null) {
                        slots[index].applyCropIfNeeded();
                        slotVideoSizeChanged[index] = true;
                        // ===== ★ 智能截帧：视频尺寸确定意味着首帧已渲染，此时截帧最可靠 =====
                        // 优先级高于 onPlaying 的兜底路径。
                        if (!slotThumbCaptured[index] && autoCapture) {
                            final int slotIndex = index;
                            final ChannelTileView tile = slots[index];
                            Runnable old = slotThumbRunnable[slotIndex];
                            if (old != null) handler.removeCallbacks(old);
                            final Runnable r = new Runnable() {
                                @Override
                                public void run() {
                                    if (isFinishing() || isDestroyed()) return;
                                    if (slotThumbCaptured[slotIndex]) return;
                                    if (slotIndex < 0 || slotIndex >= MAX_SLOTS
                                            || slots[slotIndex] == null
                                            || !slotActive[slotIndex]) return;
                                    slotThumbCaptured[slotIndex] = true;
                                    slots[slotIndex].captureThumbnailToCache(MultiViewActivity.this);
                                    onCaptureProgressed();
                                }
                            };
                            slotThumbRunnable[slotIndex] = r;
                            // 首帧渲染后再等 800ms 让画面完全稳定
                            handler.postDelayed(r, 800L);
                        }
                    }
                });
            }
        }, config.getAvcodecHwValue());

        slotActive[index] = true;
        slots[index] = tile;
        engines[index] = engine;
        tile.bind(channelIndex, useSubStream, engine, config.getChannelName(channelIndex));

        // ★ 注册截帧回调：追踪每路成功/失败，用于预热完成后失败通道提示
        final int slotIdxForCallback = index;
        tile.setCaptureResultCallback(new ChannelTileView.CaptureResultCallback() {
            @Override
            public void onCaptureSuccess(int channelIndex) {
                runOnUiThread(() -> {
                    if (autoCapture) {
                        captureSuccessCount++;
                        Log.i(TAG, "预热成功 ch" + channelIndex + " (总成功=" + captureSuccessCount + ")");
                    }
                });
            }
            @Override
            public void onCaptureFailure(int channelIndex, String reason) {
                runOnUiThread(() -> {
                    if (autoCapture) {
                        captureFailedChannels.add(channelIndex);
                        Log.w(TAG, "预热失败 ch" + channelIndex + " 原因=" + reason
                                + " (累计失败=" + captureFailedChannels.size() + ")");
                    }
                });
            }
        });

        tile.setOnFocusChangeListener((v, hasFocus) -> {
            ChannelTileView t = (ChannelTileView) v;
            t.setFocused(hasFocus);
            if (hasFocus) {
                focusedSlot = indexOfSlot(t); // 槽位索引
                ensureSlotActive(focusedSlot); // 焦点格保活
            }
        });
        // ❌ 不在此设 setOnClickListener：它会让 tile clickable，触屏"双击"的第一击就会被
        //    当成"单击"触发 onClick→startChannel→跳进独立播放页，抢先打断 GestureDetector 的
        //    双击判定 → 双击永远不生效（真机重）。（触屏单击已由 onDoubleTap 之外的
        //    onSingleTapConfirmed 处理、遥控器选择已由 D-pad 焦点处理，onClick 无需再用。）
        // 触屏设备：格子消费触摸事件，必须把手势转发给检测器，否则宫格容器上的
        // OnTouchListener 收不到任何事件（滑动/双击全部失效——真机已复现）。
        // 返回 true 消费事件：单击由此走 onSingleTapConfirmed（进全屏）。
        tile.setOnTouchListener((v, event) -> {
            // 诊断日志：确认触摸事件是否真正到达格子层（双击失效排查用）。
            final int a = event.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP
                    || a == MotionEvent.ACTION_POINTER_DOWN || a == MotionEvent.ACTION_POINTER_UP) {
                Log.d(TAG, "[tile] touch ch=" + ((ChannelTileView) v).getChannelIndex()
                        + " act=" + a + " x=" + (int) event.getX() + " y=" + (int) event.getY()
                        + " et=" + event.getEventTime() + " dt=" + event.getDownTime()
                        + " gd=" + (gestureDetector != null));
            }
            // 手写双击优先：DOWN 命中双击则直接 handleDoubleTap 并吞掉事件。
            if (detectDoubleTapDown(event)) {
                return true;
            }
            return feedGesture(event);
        });
    }

    /** 通过视图引用反查槽位索引。 */
    private int indexOfSlot(ChannelTileView tile) {
        if (tile == null) return -1;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slots[i] == tile) {
                return i;
            }
        }
        return -1;
    }

    /** 布局切换：复用仍有效的槽位引擎（避免重建全部 RTSP 连接）。
     *  前 N 个槽位（N = min(oldLayout, newLayout)）的 index→channel 映射不变，
     *  引擎 + Tile 全部复用，只更新 Grid 布局参数；多余槽位释放，新增槽位创建。 */
    private void switchLayout(int newLayout) {
        newLayout = clampLayout(newLayout);
        if (newLayout == layoutSize) return;
        int oldLayout = layoutSize;
        Log.i(TAG, "切换布局: " + oldLayout + " → " + newLayout);

        // 布局切换前先退出全屏：抬起在全屏层的格子不在 grid 里，需先放回
        exitFullScreen();

        int[] newDim = gridDimension(newLayout);
        int minLayout = Math.min(oldLayout, newLayout);

        // ===== 释放多余槽位 =====
        if (newLayout < oldLayout) {
            for (int i = newLayout; i < oldLayout; i++) {
                releaseSlot(i);
            }
        }

        // ===== GridLayout 校验铁律（真机崩溃教训，勿再踩）=====
        // GridLayout 有两道校验，任一不满足都会抛
        //   IllegalArgumentException: column indices (start + span) mustn't exceed the column count
        //   ① setColumnCount/setRowCount：校验「所有 child」的 col/row < 新 count
        //   ② ViewGroup.updateViewLayout：内部走 checkLayoutParams()，校验「被挪的这个 child」
        //      的 col/row < 当前 count（ViewGroup.java:5121）
        //      —— 所以"先 updateViewLayout 再 setColumnCount"在扩宫格时必崩：
        //         4宫格(2列)→9宫格(3列) 要把 slot2 挪到 col=2，而当前 columnCount 仍是 2，
        //         2+1 > 2 → 崩溃（本行代码正是曾经的崩溃点）。
        //   反过来"先 setColumnCount 再 updateViewLayout"在缩宫格时也会崩：
        //         9宫格(3列)→4宫格(2列)，残留 child 有 col=2，setColumnCount(2) 直接挂。
        //
        // 安全顺序（两个方向都成立）：
        //   step1 缩宫格时先释放多余槽位（已从 grid 移除）；
        //   step2 先把列数抬到 max(旧列, 新列)——旧 child 位置全部合法，抬列永远安全；
        //   step3 再把复用 child 挪到新位置——此时列数已 ≥ 新列，新位置必然合法；
        //   step4 最后收到新列/行数——所有 child 位置都已 < 新列数，必然合法。
        int oldCols = grid.getColumnCount();
        int newCols = newDim[1];
        if (newCols > oldCols) {
            // 扩列：先抬高列数，否则 step3 挪到更大 col 会被 ② 拦下
            grid.setColumnCount(newCols);
        }
        for (int i = 0; i < minLayout; i++) {
            ChannelTileView tile = slots[i];
            if (tile == null) continue;
            int row = i / newCols;
            int col = i % newCols;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(col, 1f));
            lp.width = 0;
            lp.height = 0;
            lp.setGravity(Gravity.FILL);
            grid.updateViewLayout(tile, lp);
        }

        // 现在所有 child 的 col/row 均 < newDim，安全改行列数
        grid.setColumnCount(newCols);
        grid.setRowCount(newDim[0]);

        // 扩大布局：创建新增槽位（index >= oldLayout）
        if (newLayout > oldLayout) {
            for (int i = oldLayout; i < newLayout; i++) {
                int row = i / newDim[1];
                int col = i % newDim[1];
                createSlot(i, i, row, col);
            }
        }

        layoutSize = newLayout;
        config.setLastMultiLayout(layoutSize);

        // 焦点保持合理
        if (focusedSlot >= layoutSize) {
            focusedSlot = 0;
        }
        updateFocus();
        refreshPager();

        // 布局内可见槽启动播放（复用槽位由 startSlotFor 的 isActiveFor 守门自动跳过）
        startVisibleSlots();

        Toast.makeText(this, getString(R.string.toast_switched_layout, layoutName(newLayout)),
                Toast.LENGTH_SHORT).show();
    }

    private void updateFocus() {
        ChannelTileView t = slots[focusedSlot];
        if (t != null) {
            t.requestFocus();
        }
    }

    // ===== 布局选择浮窗（单击触发）=====

    /**
     * 在被点击格子下方弹出布局选择浮窗（4宫格 / 9宫格 / 16宫格）。
     * 用户点哪个按钮就调用 switchLayout 切换对应布局；点浮窗外自动关闭。
     *
     * @param anchor 被点击的格子（仅用于 attach window token，定位用 rawX/rawY 触摸坐标）
     * @param rawX   触摸点屏幕 X（getRawX）
     * @param rawY   触摸点屏幕 Y（getRawY）
     */
    private void showLayoutPicker(ChannelTileView anchor, float rawX, float rawY) {
        View popupView = LayoutInflater.from(this).inflate(R.layout.dialog_layout_picker, null);
        final PopupWindow popup = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true); // 外部点击关闭

        // 4宫格
        popupView.findViewById(R.id.btn_layout_4).setOnClickListener(v -> {
            Log.i(TAG, "布局选择: 4宫格");
            switchLayout(4);
            popup.dismiss();
        });
        // 9宫格
        popupView.findViewById(R.id.btn_layout_9).setOnClickListener(v -> {
            Log.i(TAG, "布局选择: 9宫格");
            switchLayout(9);
            popup.dismiss();
        });
        // 16宫格
        popupView.findViewById(R.id.btn_layout_16).setOnClickListener(v -> {
            Log.i(TAG, "布局选择: 16宫格");
            switchLayout(16);
            popup.dismiss();
        });

        // 用触摸原始坐标定位浮窗：水平居中于触摸点，垂直放在触摸点下方。
        // 不能用 Gravity.BOTTOM | Gravity.CENTER：那个 gravity 把浮窗底边对齐格子底边，
        // 浮窗会**向上**探、压在被点格子本身甚至上方格子上，按钮被遮/难点 → "点4宫格无效"。
        // 也不能用 anchor.getLocationOnScreen + anchor.getWidth()/getHeight()：
        // switchLayout 刚做完美 updateViewLayout，宽高可能尚未重测返回 0，导致浮窗乱跑。
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupW = popupView.getMeasuredWidth();
        int popupH = popupView.getMeasuredHeight();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = (int) rawX - popupW / 2;
        int y = (int) rawY;
        // 防止浮窗左右超出屏幕
        if (x < 0) x = 0;
        if (x + popupW > dm.widthPixels) x = dm.widthPixels - popupW;
        // 防止浮窗下半截超出屏幕底边 → 翻到触摸点上方
        if (y + popupH > dm.heightPixels) {
            y = (int) rawY - popupH;
            if (y < 0) y = 0; // 屏幕太短也放不下则贴顶
        }
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);

        // 自动消失：5 秒无操作自动关闭，避免用户忘记点也一直挡着画面
        handler.postDelayed(() -> {
            if (popup.isShowing()) {
                Log.d(TAG, "布局选择浮窗自动消失（5秒无操作）");
                popup.dismiss();
            }
        }, 5000L);
    }

    // ===== 播放调度 =====

    /** 启动当前布局内所有 active 槽（Surface 未就绪也能先 play，VLC 就绪后自动上屏）。 */
    private void startVisibleSlots() {
        // ===== 起播前先让域名解析落地（消除"先域名起播 → 后 IP 推倒重建"）=====
        // 主机是 DDNS 域名且解析未完成时，此刻只能用**域名** URL 起播；等 ConfigManager 的
        // 智能解析（公共 DNS 直查 + TCP 探活）完成后 URL 变成 IP，startSlotFor 里的
        // isActiveFor(url) 判定 URL 不匹配 → 推倒重建：刚握手成功的 RTSP 会话被 TEARDOWN，
        // 白等一整轮握手 + 一次缓冲。实测每路多花 150~400ms；多路并发时第二轮 SETUP 还会
        // 撞上 NVR 并发取流上限排队，表现就是"部分通道黑屏数秒才出帧"。
        // 这里在**后台线程**阻塞等解析（通常 <200ms；若已完成则立即返回）再起播，
        // 把这笔浪费从"每次冷启动必现"降为零。
        if (config.isHostResolutionPending()) {
            Log.i(TAG, "域名解析未完成 — 起播前先等待解析落地（避免域名→IP 推倒重建）");
            new Thread(() -> {
                config.awaitHostResolution();
                runOnUiThread(() -> {
                    // 等待窗口内 Activity 可能已销毁（用户返回/旋转），此时起播无意义且会 NPE
                    if (isFinishing() || isDestroyed()) return;
                    startVisibleSlotsInternal();
                });
            }, "mv-await-dns").start();
            return;
        }
        startVisibleSlotsInternal();
    }

    /** 真正执行起播（域名已解析为 IP、或主机本来就是 IP 字面量时）。 */
    private void startVisibleSlotsInternal() {
        // ===== 槽位启动错峰 =====
        // 串行逐个 start 时，NVR 看到的 RTSP SETUP 是"一路一路到达"——
        // 前面的握手完成才轮到后面的开始，9 路/16 路串行 = 后面的通道要等前面的握手，
        // 容易撞上 NVR 并发取流上限排队（"部分通道黑屏数秒"）。
        // 加 60ms 错峰：让所有 SETUP 在 ~0.5s 内几乎同时到达 NVR，
        // 减少排队，整体首帧更早出。
        for (int i = 0; i < layoutSize; i++) {
            final int idx = i;
            if (i > 0) {
                handler.postDelayed(() -> startSlotFor(idx), i * 60L);
            } else {
                startSlotFor(idx);
            }
        }
    }

    /** 启动单个槽位（引擎可复用）。 */
    private void startSlotFor(int index) {
        if (index < 0 || index >= MAX_SLOTS || !slotActive[index]) return;
        ChannelTileView tile = slots[index];
        PlayerEngine engine = engines[index];
        if (tile == null || engine == null || engine.isReleased()) {
            if (tile == null || engine == null) {
                // 槽未创建或已被释放（switchLayout/onDestroy 后）
                return;
            }
            // 引擎已释放（重建场景）→ 就地重建槽位
            Log.w(TAG, "startSlotFor: engine released, recreate slot " + index);
            recreateSlot(index);
            return;
        }
        int ch = tile.getChannelIndex();
        // 越界守卫只在"明确知道通道总数、且该索引确实越界"时才生效。
        // count==0 说明 ISAPI 通道列表没拉取到（典型：域名 DDNS 未刷新导致解析到旧 IP，
        // 或拉取失败为空列表）——此时若按 index>=count 一刀切，会把 0..layoutSize 全部判为
        // 越界并跳过播，造成"点一次翻页/重连后整页全部黑屏"。
        // RTSP 播放只用通道序号（channelIndex+1）拼 URL，不依赖列表数量，故 count==0 时放行。
        int totalCh = config.getChannelCount();
        if (ch < 0 || (totalCh > 0 && ch >= totalCh)) return;

        String url = useSubStream ? config.getRtspUrlSubStream(ch) : config.getRtspUrl(ch, false);
        String fallback = useSubStream ? null : config.getRtspUrlSubStream(ch);

        // ===== 重复 start 保护（首帧"部分通道黑屏数秒"的主因，勿删）=====
        // engine.play() 内部必定 setMedia()+play()，而 libvlc 的 setMedia() 会先 stop 掉当前
        // input（= TEARDOWN 刚握手成功的 RTSP 会话），把已建好的连接推倒重来。
        // 而 startSlotFor() 在一次冷启动里会被反复调用：onResume() → startVisibleSlots()、
        // onWindowFocusChanged(true) → startVisibleSlots()、applyLayout() 里 slots[0]
        // .requestFocus() 触发的 ensureSlotActive(0)……每路流等于被起 2~3 遍，
        // 第二轮 9 路 SETUP 撞在 NVR 并发取流上限上排队 → 排在队尾的通道就黑屏好几秒才出帧，
        // 排在前面的先出 → 表现就是"只有部分通道慢"。
        // 已在播/正在连同一 URL 时直接跳过；切码流/翻页 URL 会变，不受影响。
        if (engine.isActiveFor(url)) {
            Log.d(TAG, "startSlotFor: slot " + index + " 已在播/正在连同一 URL，跳过重复 start");
            return;
        }
        // 首帧前立即给文字反馈：格子默认是纯黑底，无任何文案会让用户误判成"卡死"。
        if (tile != null) {
            tile.showError("连接中…");
        }
        engine.play(ch, url, fallback, useSubStream);
    }

    /** 重建单个槽位（在引擎释放后复用场景）。 */
    private void recreateSlot(int index) {
        if (index >= layoutSize) return;
        int[] dim = gridDimension(layoutSize);
        int row = index / dim[1];
        int col = index % dim[1];
        int ch = index;
        ChannelTileView old = slots[index];
        if (old != null) {
            grid.removeView(old);
            old.releaseView();
        }
        createSlot(index, ch, row, col);
    }

    /** 立即停止一个槽（后台断开）。 */
    private void stopSlotNow(final int index) {
        if (index < 0 || index >= MAX_SLOTS) return;
        PlayerEngine e = engines[index];
        if (e != null) {
            e.disconnect();
        }
        ChannelTileView t = slots[index];
        if (t != null) {
            t.showError(null);
            // 显示"已断开"？置空刚好
        }
        startedSlots.remove(index);
        pendingStop.remove(index);
    }

    /** 延迟停止（防抖）：失焦/不可见 → 2s 后仍在不可见态则断开。 */
    private void scheduleStopSlot(final int index) {
        if (index < 0 || index >= MAX_SLOTS) return;
        if (pendingStop.contains(index)) return;
        pendingStop.add(index);
        Log.d(TAG, "调度停止槽位 " + index + " (+" + SLOT_STOP_DELAY_MS + "ms)");
        handler.postDelayed(() -> {
            pendingStop.remove(index);
            if (!isVisible(index)) {
                stopSlotNow(index);
            } else {
                Log.d(TAG, "取消停止（槽 " + index + " 重新可见）");
            }
        }, SLOT_STOP_DELAY_MS);
    }

    private boolean isVisible(int index) {
        return index >= 0 && index < layoutSize;
    }

    /** 暂停全部槽（不销毁引擎，用于 onPause/失焦）。 */
    private void pauseAllSlots() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            PlayerEngine e = engines[i];
            if (e != null) {
                e.pause();
            }
        }
        startedSlots.clear();
    }

    /** 停止（断开）全部槽 + 释放引擎资源（保留 LibVLC）。 */
    private void stopAllSlots() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            PlayerEngine e = engines[i];
            if (e != null) {
                e.disconnect();
            }
        }
        startedSlots.clear();
        pendingStop.clear();
    }

    /** 释放单个槽位（引擎断开 + 视图清理）。供 switchLayout 缩小布局 / releaseAllSlots 复用。 */
    private void releaseSlot(int index) {
        if (index < 0 || index >= MAX_SLOTS) return;
        PlayerEngine e = engines[index];
        if (e != null) {
            e.disconnect();
        }
        ChannelTileView t = slots[index];
        if (t != null) {
            grid.removeView(t);
            t.releaseView();
        }
        slots[index] = null;
        engines[index] = null;
        slotActive[index] = false;
        slotThumbCaptured[index] = false;
        Runnable oldR = slotThumbRunnable[index];
        if (oldR != null) {
            handler.removeCallbacks(oldR);
            slotThumbRunnable[index] = null;
        }
    }

    /** 释放全部槽位（引擎释放 + 视图清理）。 */
    private void releaseAllSlots() {
        handler.removeCallbacksAndMessages(null);
        // 若在全屏状态，先把格子从全屏层摘下放回 grid，保证随后的 removeView 能正确摘除
        // （否则 grid.removeView(t) 找不到实际父容器是 fullScreenLayer 的那个 view）。
        if (fullScreenSlot >= 0 && fullScreenLayer != null) {
            ChannelTileView t = slots[fullScreenSlot];
            if (t != null && t.getParent() == fullScreenLayer) {
                fullScreenLayer.removeView(t);
            }
            fullScreenSlot = -1;
        }
        for (int i = 0; i < MAX_SLOTS; i++) {
            releaseSlot(i);
        }
        grid.removeAllViews();
        activeSlots.clear();
        startedSlots.clear();
        pendingStop.clear();
    }

    /** 确保指定槽位 active → 启动（供焦点保活）。 */
    private void ensureSlotActive(int index) {
        if (index < layoutSize && slotActive[index]) {
            startSlotFor(index);
        }
    }

    // ===== 码流切换（多画面专用键） =====

    /** 切换子/主码流：更新 multi_use_sub + 重建正在播放的格。 */
    private void toggleStreamType() {
        // 宫格内嵌全屏中不切换码流：目标格的 ChannelTileView 已抬离 grid，统一处理会遗漏/串台
        if (fullScreenSlot >= 0) {
            Log.d(TAG, "toggleStreamType: 宫格内嵌全屏中，忽略码流切换（双击返回宫格后再切）");
            return;
        }
        useSubStream = !useSubStream;
        config.setMultiUseSub(useSubStream);
        String streamName = useSubStream ? getString(R.string.stream_sub) : getString(R.string.stream_main);
        Log.i(TAG, "切换码流: " + streamName + "（已记忆 multi_use_sub=" + useSubStream + "）");
        Toast.makeText(this,
                getString(R.string.toast_switched_stream, streamName,
                        getString(R.string.toast_stream_saved, streamName)),
                Toast.LENGTH_SHORT).show();

        // 更新每个格 bind + 重新播放可见格。
        // 注意：不能在此先 stopAllSlots() 再 start——disconnect() 的 stop 在后台线程异步执行且不等待，
        // 会与紧随 play() 形成竞态把新流停掉（同"翻页黑屏"根因）。VLC setMedia+play 本身会切换码流，
        // 直接逐格 play 即可。
        for (int i = 0; i < layoutSize; i++) {
            ChannelTileView t = slots[i];
            if (t != null) {
                t.bind(t.getChannelIndex(), useSubStream, engines[i], config.getChannelName(t.getChannelIndex()));
            }
        }
        startVisibleSlots();
    }

    // ===== 进入单画面 =====

    private void startPlayer(int channelIndex) {
        if (channelIndex < 0 || channelIndex >= config.getChannelCount()) {
            Toast.makeText(this, "通道不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        // 内嵌全屏状态下单击/OK 不进入独立播放器（双击已覆盖全屏/返回交互），避免两个"全屏"叠加
        if (fullScreenSlot >= 0) {
            Log.d(TAG, "startPlayer: 宫格内嵌全屏中，忽略进入独立播放器（双击返回宫格）");
            return;
        }
        // 记忆：多画面 → 单画面全屏时写 single + 通道 + 码流（设计 §9.3）
        config.setLastViewMode("single");
        config.setLastChannelIndex(channelIndex);
        config.setLastStreamType(useSubStream ? "sub" : "main");
        Log.i(TAG, "进入单画面 ch=" + channelIndex + " stream=" + (useSubStream ? "sub" : "main"));
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channelIndex);
        intent.putExtra(PlayerActivity.EXTRA_STREAM_TYPE, useSubStream ? "sub" : "main");
        intent.putExtra(PlayerActivity.EXTRA_FROM_MULTI, true);
        startActivity(intent);
    }

    // ===== 按键 =====

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 焦点已在最左列 → 翻上一页；否则网格内左移焦点
                if (focusAtColumnEdge(false)) {
                    shiftChannels(-layoutSize);
                } else {
                    moveFocus(-1, 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 焦点已在最右列 → 翻下一页；否则网格内右移焦点
                if (focusAtColumnEdge(true)) {
                    shiftChannels(layoutSize);
                } else {
                    moveFocus(1, 0);
                }
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                shiftChannels(layoutSize);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                shiftChannels(-layoutSize);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveFocus(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveFocus(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                // 交给 dispatchKeyEvent 处理长短按
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** 焦点是否在网格的最左列（last=false）或最右列（last=true）——到边后再按方向键即翻页。 */
    private boolean focusAtColumnEdge(boolean last) {
        int[] dim = gridDimension(layoutSize);
        int cols = dim[1];
        int cur = (focusedSlot >= 0 && focusedSlot < layoutSize) ? focusedSlot : 0;
        int col = cur % cols;
        return last ? (col == cols - 1) : (col == 0);
    }

    /** 网格内移动焦点。 */
    private void moveFocus(int dx, int dy) {
        int[] dim = gridDimension(layoutSize);
        int cols = dim[1];
        int rows = dim[0];
        int cur = (focusedSlot >= 0 && focusedSlot < layoutSize) ? focusedSlot : 0;
        int row = cur / cols;
        int col = cur % cols;

        int targetCol = Math.max(0, Math.min(cols - 1, col + dx));
        int targetRow = Math.max(0, Math.min(rows - 1, row + dy));
        int target = targetRow * cols + targetCol;
        if (target >= layoutSize) {
            target = layoutSize - 1; // 防止 6 宫格末行越界
        }
        if (target != cur) {
            focusedSlot = target;
            ChannelTileView t = slots[target];
            if (t != null) {
                t.requestFocus();
            }
            // 焦点格保活 + 旧格继续播放（P0 布局内全部可见全部播，不做离焦断开）
            ensureSlotActive(target);
        }
    }

    /**
     * 窗口级触摸分发入口（在任一 View 分发之前被调用，最底层、绝不可能被内层 view 拦截）。
     *
     * 手写双击检测 + 单击进全屏集中放在这里，作为对各 tile/listener 的兜底：
     * 任何一层（内层 tile_root clickable、GestureDetector 未触发、focus 变化等）吞掉事件，
     * 窗口头部都能先捕获到两次 DOWN。用 raw 屏幕绝对坐标 + 时间戳，命中即执行 performDoubleTap。
     * 单击（非双击的第二击）仍交给原调用链（super.dispatchTouchEvent→grid/tile listener）。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        // 只在 DOWN 时做手写双击判定；其它动作原样下发，保证滑动/长按等手势不受影响。
        if (action == MotionEvent.ACTION_DOWN) {
            boolean consumedByDblTap = detectDoubleTapDown(ev);
            if (consumedByDblTap) {
                // 已命中双击并执行全屏切换：直接消费这一击 DOWN，避免它再触发 grid/tile 的单击逻辑。
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();

        // --- OK/CENTER：短按=进全屏，长按=切换布局 ---
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    okKeyDownTime = System.currentTimeMillis();
                    okKeyHandled = false;
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!okKeyHandled) {
                    okKeyHandled = true;
                    long duration = System.currentTimeMillis() - okKeyDownTime;
                    if (duration >= LONG_PRESS_MS) {
                        // 长按 → 切换布局 1→4→6→9→1
                        int next;
                        switch (layoutSize) {
                            case 1: next = 4; break;
                            case 4: next = 6; break;
                            case 6: next = 9; break;
                            default: next = 1; break;
                        }
                        switchLayout(next);
                    } else {
                        // 短按 → 当前焦点格进入单画面全屏
                        if (focusedSlot >= 0 && focusedSlot < layoutSize && slots[focusedSlot] != null) {
                            startPlayer(slots[focusedSlot].getChannelIndex());
                        }
                    }
                }
                return true;
            }
        }

        // --- MENU：短按弹出宫格选择浮窗，长按切换子/主码流 ---
        if (code == KeyEvent.KEYCODE_MENU) {
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
                    if (duration >= LONG_PRESS_MS) {
                        // 长按 → 切换子/主码流
                        toggleStreamType();
                    } else {
                        // 短按 → 弹出宫格布局选择浮窗（4/9/16）
                        if (slots[focusedSlot] != null) {
                            showLayoutPicker(slots[focusedSlot], 0f, 0f);
                        }
                    }
                }
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    // ===== 触屏 =====
    /** 全屏容器：宫格层之上、翻页栏之下。双击全屏时把 ChannelTileView 移到这里铺满。 */
    private FrameLayout fullScreenLayer;

    /**
     * 判定是否为可触屏设备。
     *
     * 注意（真机教训）：不能只信 {@code configuration.touchscreen} —— 很多安卓电视盒子 / 触屏一体机
     * 系统会把它报成 {@code TOUCHSCREEN_NOTOUCH}，但仍需支持触控。这里只把明确的 TV 模式判为无触摸，
     * 其余一概视为可触屏，并在 getResources 取不到可靠值时回退为触屏，避免"滑动翻页静默失效"。
     */
    private boolean isTouchScreenDevice() {
        try {
            Configuration conf = getResources().getConfiguration();
            int uiMode = conf.uiMode & Configuration.UI_MODE_TYPE_MASK;
            if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
                return false;
            }
            // 屏幕模式非 TV：视为可触屏（含触屏劣化的盒子/一体机）。
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "isTouchScreenDevice 异常，按触屏处理: " + t.getMessage());
            return true;
        }
    }

    private void initTouchSupport() {
        // 滑动阈值按屏幕尺寸自适应：手机窄->阈值小才划得动；平板宽->阈值大避免误触。
        // 短边 6%（旧 8% 反馈"不灵敏"）：慢速滑动必须靠 UP 位移兜底判定，阈值略低更好划。
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
            swipeThreshold = Math.max(40, (int) (shortSide * 0.06f));
            Log.i(TAG, "触屏滑动阈值自适应: " + swipeThreshold + "px（屏幕短边 " + shortSide + "px）");
        } catch (Throwable t) {
            swipeThreshold = SWIPE_THRESHOLD;
        }
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 抑制：若这是手写双击命中的第一击所触发的单击，忽略之（双击已进入全屏）。
                if (Math.abs(System.currentTimeMillis() - mManualDoubleTapAtMs) < 450L) {
                    Log.d(TAG, "抑制双击第一击的单击进页");
                    return true;
                }
                // 单击 = 更新焦点格 + 弹出布局选择浮窗（4/9/16）。
                // 用户要求：单击不进全屏（双击才进），但单击时弹浮窗选布局。
                // 必须用屏幕绝对坐标：事件现在从格子转发（本地坐标），hitTest 比较的是全局矩形
                ChannelTileView t = hitTest(e.getRawX(), e.getRawY());
                if (t != null) {
                    int ch = t.getChannelIndex();
                    if (ch >= 0 && ch < config.getChannelCount()) {
                        int slot = indexOfSlot(t);
                        if (slot >= 0 && slot < layoutSize) {
                            focusedSlot = slot;
                            updateFocus();
                        }
                    }
                    // 弹出布局选择浮窗。
                    // 用触摸原始坐标定位（比 anchor.getLocationOnScreen 更可靠——
                    // anchor 的宽高可能因刚做完 switchLayout 的 updateViewLayout 而尚未重测，
                    // getWidth()==0 会导致浮窗位置乱跑）。
                    showLayoutPicker(t, e.getRawX(), e.getRawY());
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // 双击 = 宫格内嵌全屏（此回调触发说明识别成功）。
                // 注意：部分 Android 版本 onDoubleTap 给的是第一击 DOWN 的坐标，位置可能
                // 落在上一击的按点而非第二击实际位置，直接 hitTest 可能 miss——故只在此
                // 记待办，真正落全屏放到 onDoubleTapEvent(ACTION_UP) 用第二击抬起坐标。
                pendingDoubleTapEnter = true;
                doubleTapUpRawX = e.getRawX();
                doubleTapUpRawY = e.getRawY();
                Log.i(TAG, "onDoubleTap 识别: fullScreenSlot=" + fullScreenSlot
                        + " raw=" + e.getRawX() + "," + e.getRawY());
                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (pendingDoubleTapEnter) {
                        pendingDoubleTapEnter = false;
                        doubleTapUpRawX = e.getRawX();
                        doubleTapUpRawY = e.getRawY();
                        handleDoubleTap(doubleTapUpRawX, doubleTapUpRawY);
                        return true;
                    }
                }
                return super.onDoubleTapEvent(e);
            }

            /** GestureDetector 双击命中 → 转发到 Activity 级统一入口（与手写双击共用，去重节流）。 */
            private void handleDoubleTap(float rawX, float rawY) {
                performDoubleTap(rawX, rawY);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return true;
                // 事件可能来自格子转发（本地坐标）也可能来自 grid（全局坐标），统一用 raw 绝对坐标，
                // 差值不受坐标系影响，且与 hitTest 的全局矩形判定一致，杜绝"跨格滑动 e1 坐标系错位"问题。
                float dx = e2.getRawX() - e1.getRawX();
                float dy = e2.getRawY() - e1.getRawY();
                // 快速滑动即便距离偏小也判为翻页；慢速画挥但位移够大同样翻页。
                // 速度阈值放开（200/400 px per s），避免某些设备 velocity 缺失/偏小而误判"滑动无效"。
                boolean fast = Math.max(Math.abs(velocityX), Math.abs(velocityY)) > 200f;
                if (!fast && Math.abs(dx) < swipeThreshold && Math.abs(dy) < swipeThreshold) {
                    return true; // 距离、速度都小 → 轻点/按住，不翻页
                }
                Log.i(TAG, "onFling dx=" + dx + " dy=" + dy
                        + " vx=" + velocityX + " vy=" + velocityY
                        + " threshold=" + swipeThreshold);
                swipeHandledInGesture = true; // 本手势已处理，UP 兜底不再重复执行
                if (Math.abs(dx) > Math.abs(dy)) {
                    // 左右滑动 → 翻页（通道整体平移 layoutSize 个）
                    shiftChannels(dx < 0 ? layoutSize : -layoutSize);
                } else {
                    // 上下滑动 → 音量
                    adjustVolume(dy < 0);
                }
                return true;
            }
        });
        grid.setOnTouchListener((v, event) -> {
            final int ga = event.getActionMasked();
            if (ga == MotionEvent.ACTION_DOWN || ga == MotionEvent.ACTION_UP) {
                Log.d(TAG, "[grid] touch act=" + ga + " x=" + (int) event.getX() + " y=" + (int) event.getY());
            }
            // 手写双击优先
            if (detectDoubleTapDown(event)) {
                return true;
            }
            feedGesture(event);
            return true;
        });
        fullScreenLayer.setOnTouchListener((v, event) -> {
            // 手写双击优先：全屏下双击返回宫格
            if (detectDoubleTapDown(event)) {
                return true;
            }
            feedGesture(event);
            return true;
        });
    }

    /**
     * 手势统一入口（滑动灵敏度核心）：先喂 GestureDetector——快速滑动由 onFling
     * 立即翻页；ACTION_UP 时若 onFling 未触发（慢速滑动速度低于系统最小 fling
     * 速度时 onFling 根本不会回调，这是"滑动不灵敏"的根因），按"按下→抬起"
     * 的位移兜底判定翻页/调音量。
     * swipeHandledInGesture 旗标保证同一手势只执行一次。
     */
    private boolean feedGesture(MotionEvent event) {
        if (gestureDetector == null) return false;
        final int a = event.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            swipeHandledInGesture = false;
            gestureDownRawX = event.getRawX();
            gestureDownRawY = event.getRawY();
        }
        boolean handled = gestureDetector.onTouchEvent(event);
        if (a == MotionEvent.ACTION_UP) {
            if (!swipeHandledInGesture) {
                float dx = event.getRawX() - gestureDownRawX;
                float dy = event.getRawY() - gestureDownRawY;
                if (Math.abs(dx) >= swipeThreshold || Math.abs(dy) >= swipeThreshold) {
                    swipeHandledInGesture = true;
                    Log.i(TAG, "慢速滑动兜底翻页 dx=" + (int) dx + " dy=" + (int) dy
                            + " threshold=" + swipeThreshold);
                    if (Math.abs(dx) > Math.abs(dy)) {
                        shiftChannels(dx < 0 ? layoutSize : -layoutSize);
                    } else {
                        adjustVolume(dy < 0);
                    }
                    handled = true;
                }
            }
        }
        return handled;
    }

    // ===== 宫格内嵌全屏（双击）=====

    /**
     * 进入宫格内嵌全屏：把目标格的 ChannelTileView 从原格子抬到全屏容器铺满。
     *
     * 遵循池化铁律，不新建/不重连/不 restart 引擎：
     *   - 该格 engine 已经在播则原样继续，view 尺寸(tw/th)变大由 onSizeChanged 自动触发
     *     applyCropTransformIfNeeded 重新铺满（新尺寸在下一帧收敛，无需手动干预）；
     *   - 若尚未在播（如刚翻页、Surface 未就绪），才按现有逻辑补一次 start（幂等守卫
     *     isActiveFor 保证不会重复 TEARDOWN）；
     *   - 其它格 ENGINE 全部保留继续播放（各格独立 Engine+TextureView），回来时即原位恢复。
     */
    private void enterFullScreen(int slot) {
        if (slot < 0 || slot >= layoutSize || slot >= MAX_SLOTS) return;
        if (fullScreenSlot == slot) return;
        ChannelTileView tile = slots[slot];
        PlayerEngine engine = engines[slot];
        if (tile == null || engine == null) return;

        // 全屏容器本身在 XML 里是 gone；一旦有格抬上去就必须可见，否则 addView 不显示。
        fullScreenLayer.setVisibility(View.VISIBLE);
        // 从原宫格中摘除（GridLayout 会在下次 requestLayout 时更新占位）
        grid.removeView(tile);
        fullScreenLayer.addView(tile, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        fullScreenSlot = slot;
        focusedSlot = slot;
        // 全屏格拿到焦点；被隐藏的格统一失焦
        for (int i = 0; i < layoutSize; i++) {
            ChannelTileView t = slots[i];
            if (t != null && t != tile) {
                t.setFocused(false);
                t.setVisibility(View.GONE);
            }
        }
        // 搬移后兜底：removeView/addView 可能触发 TextureView.onSurfaceTextureDestroyed
        // 把 video surface 释放掉；若新的 onSurfaceTextureAvailable 迟到，这里立即重挂防黑闪。
        tile.ensureSurfaceAttachedAfterMove();
        tile.setFocused(true);
        tile.requestFocus();

        // 若目标格还没在播，补起一次（isActiveFor 幂等，不会重连已连接路径）
        int ch = tile.getChannelIndex();
        if (ch >= 0) {
            String url = useSubStream ? config.getRtspUrlSubStream(ch) : config.getRtspUrl(ch, false);
            if (!engine.isActiveFor(url)) {
                startSlotFor(slot);
            }
        }

        // 隐藏提示条与页码指示，避免遮挡全屏画面
        tvHint.setVisibility(View.GONE);
        if (tvPageIndicator != null) {
            tvPageIndicator.setVisibility(View.GONE);
        }
        Log.i(TAG, "宫格内嵌全屏: slot=" + slot + " channel=" + ch);
    }

    /** 退出宫格内嵌全屏：把全屏格放回原格子位置，恢复其它格。 */
    private void exitFullScreen() {
        if (fullScreenSlot < 0) return;
        int slot = fullScreenSlot;
        ChannelTileView tile = slots[slot];
        fullScreenSlot = -1;
        if (tile == null) {
            // 槽在退出期间被布局切换/释放清掉，仅恢复显示状态
            for (int i = 0; i < layoutSize; i++) {
                ChannelTileView t = slots[i];
                if (t != null) {
                    t.setVisibility(View.VISIBLE);
                }
            }
            // 槽在退出期间被清掉：页码显隐交给 refreshPager 按页数决定
            refreshPager();
            return;
        }

        // 从全屏层摘下，放回原 GridLayout 槽位（GridLayout 会按 spec 恢复位置尺寸）
        fullScreenLayer.removeView(tile);
        fullScreenLayer.setVisibility(View.GONE);
        int[] dim = gridDimension(layoutSize);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(slot / dim[1], 1f),
                GridLayout.spec(slot % dim[1], 1f));
        lp.width = 0;
        lp.height = 0;
        lp.setGravity(Gravity.FILL);
        grid.addView(tile, slot, lp);

        // 其它格恢复可见
        for (int i = 0; i < layoutSize; i++) {
            ChannelTileView t = slots[i];
            if (t != null) {
                t.setVisibility(View.VISIBLE);
            }
        }
        // 搬移回宫格后的兜底：同 enterFullScreen，防 TextureView surface 被销毁而新回调迟到 → 黑闪
        tile.ensureSurfaceAttachedAfterMove();
        if (focusedSlot >= 0 && focusedSlot < layoutSize && slots[focusedSlot] != null) {
            slots[focusedSlot].requestFocus();
        }

        // 恢复提示条/页码指示（提示条仅在仍可见时重新编排，页码按当前页数决定显隐）
        setHint(isTouchDevice ? getString(R.string.multi_hint_touch) : getString(R.string.multi_hint));
        refreshPager();
        Log.i(TAG, "退出宫格内嵌全屏: slot=" + slot);
    }

    /** 宫格内嵌全屏是否激活。 */
    private boolean isFullScreen() {
        return fullScreenSlot >= 0;
    }

    private ChannelTileView hitTest(float x, float y) {
        for (int i = 0; i < layoutSize; i++) {
            ChannelTileView t = slots[i];
            if (t != null) {
                Rect r = new Rect();
                t.getGlobalVisibleRect(r);
                if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                    return t;
                }
            }
        }
        return null;
    }

    /** 通道整体平移 delta（0-based），保持布局不变。以整页为基准翻页，末页自动回贴尾部避免通道重复。 */
    private void shiftChannels(int delta) {
        // 宫格内嵌全屏中不允许翻页：当前格已抬离 grid，翻页会与全屏状态叠加混乱
        if (fullScreenSlot >= 0) {
            Log.d(TAG, "shiftChannels: 宫格内嵌全屏中，忽略翻页（双击返回宫格后再翻）");
            return;
        }
        int total = config.getChannelCount();
        // 列表总数 <= 0（典型：DDNS 域名解析到旧 IP 导致 ISAPI 拉取失败、通道列表为空）时，
        // 仍允许用通道序号翻页——RTSP 播放只依赖序号（channelIndex+1），不依赖列表条目。
        // 用一个较大的兜底上限，避免"列表没拉出来就一格都不给翻、且一翻就整页黑屏"。
        int maxChannel = 64; // 常规 NVR 通道数上限兜底
        int effectiveTotal = total > 0 ? total : maxChannel;
        int perPage = Math.max(1, layoutSize);
        int pages = Math.max(1, (int) Math.ceil(effectiveTotal / (double) perPage));
        int maxBase = Math.max(0, effectiveTotal - layoutSize);
        int curBase = (slots[0] != null) ? slots[0].getChannelIndex() : 0;
        int newBase = Math.max(0, Math.min(maxBase, curBase + delta));
        if (newBase == curBase) {
            // 已在第一页/最后一页：提示后不动，避免空翻
            Toast.makeText(this, (delta > 0 ? "已经是最后一页" : "已经是第一页")
                            + " （第 " + ((curBase / perPage) + 1) + " / " + pages + " 页）",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (total <= 0) {
            // 通道列表为空：提示用户，但不中断后续（仍按序号翻页，视觉连续）
            Log.w(TAG, "翻页时通道列表为空（可能 DDNS/DNS 未更新），按广播序号继续: base " + newBase);
        }
        for (int i = 0; i < layoutSize; i++) {
            ChannelTileView t = slots[i];
            if (t != null) {
                int newCh = Math.min(effectiveTotal - 1, newBase + i);
                // 显示真切通道名（列表缺失时回退"通道N"）
                String name = (total > 0 && newCh < total) ? config.getChannelName(newCh) : "通道" + (newCh + 1);
                t.bind(newCh, useSubStream, engines[i], name);
            }
        }
        // ===== 翻页播放调度（修复"切页后整页黑屏"竞态，勿回退成 stopAllSlots+startAll）=====
        // 旧实现：先 stopAllSlots() 再 startVisibleSlots()。
        // 但 PlayerEngine.disconnect() 内部是 stopExecutor(后台线程)异步执行 mp.stop()，并不等待；
        // 立即 start 会对同一 VLC MediaPlayer 在主线程 setMedia+play()，与后台 stop 并发——
        // 后台 stop 常在 play 之后才完成，把刚换上的新码流又停掉 → 翻页后所有格子不播
        // （第一页没有此竞态，故第一页正常、切页才全黑，与真机现象完全吻合）。
        // VLC 的 setMedia()+play() 本身会停旧码流并切换到新源，故翻页只需"逐个引擎直接 play(new)"，
        // 无需先 disconnect。切换由 VLC 内部串行处理，无竞争。
        startVisibleSlots();
        refreshPager();
        Log.i(TAG, "翻页: base " + curBase + " → " + newBase
                + " 第 " + ((newBase / perPage) + 1) + " / " + pages + " 页"
                + " effectiveTotal=" + effectiveTotal);
    }

    private void adjustVolume(boolean raise) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    raise ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        }
    }

    /**
     * 刷新底部页码指示（不可点击，仅提示）。
     * 仅当通道列表已拉取（total > 0）且总页数 > 1 时显示"‹ 当前/总页 ›"；
     * 列表为空（DDNS 更新期）或只有一页时隐藏——滑动翻页本身不受影响（shiftChannels 有 64 兜底）。
     */
    private void refreshPager() {
        if (tvPageIndicator == null) return;
        int total = config.getChannelCount();
        if (total <= 0) {
            tvPageIndicator.setVisibility(View.GONE);
            return;
        }
        int perPage = Math.max(1, layoutSize);
        int pages = Math.max(1, (int) Math.ceil(total / (double) perPage));
        if (pages <= 1) {
            tvPageIndicator.setVisibility(View.GONE);
            return;
        }
        // 当前页序号：以 slots[0] 绑定的通道换算（第 1 页 = 通道 0..perPage-1）
        int curBase = (slots[0] != null) ? slots[0].getChannelIndex() : 0;
        int curPage = Math.min(pages, (curBase / perPage) + 1);
        tvPageIndicator.setText("‹ " + curPage + " / " + pages + " ›");
        tvPageIndicator.setVisibility(isFullScreen() ? View.GONE : View.VISIBLE);
        Log.d(TAG, "页码指示刷新: total=" + total + " perPage=" + perPage
                + " 页 " + curPage + "/" + pages);
    }

    /**
     * 显示顶部手势提示文字，HINT_DURATION_MS 后自动隐藏。
     * 每次显示都会重新排期，避免重叠调用导致永不消失。
     */
    /**
     * 预热模式（"刷新缩略图"）进度推进：每发起一路截帧调用一次。
     *
     * <p>发起即计数（截帧失败也算已处理），保证进度条一定能走完，不卡在半途让用户以为
     * 死机而手动中断。全部完成后短暂停留显示"完成"，再回列表。
     * 若有失败通道，在退出前 Toast 提示。</p>
     */
    private void onCaptureProgressed() {
        if (!autoCapture) return;
        captureProgress++;
        if (captureProgress > captureTotal) {
            captureTotal = captureProgress; // 兜底：实际路数多于预估时同步放大分母
            if (pbCapture != null) pbCapture.setMax(captureTotal);
        }
        if (pbCapture != null) pbCapture.setProgress(captureProgress);
        if (tvCaptureProgress != null) {
            tvCaptureProgress.setText(getString(R.string.capture_progress,
                    captureProgress, captureTotal));
        }
        Log.i(TAG, "预热进度: " + captureProgress + "/" + captureTotal);
        if (captureProgress >= captureTotal && !captureFinishing) {
            captureFinishing = true;
            if (tvCaptureProgress != null) {
                tvCaptureProgress.setText(R.string.capture_done);
            }
            // 显示失败通道提示
            if (!captureFailedChannels.isEmpty()) {
                final java.util.List<String> failedNames = new java.util.ArrayList<>();
                for (int ch : captureFailedChannels) {
                    String name = config.getChannelName(ch);
                    failedNames.add(name != null ? name : "通道" + (ch + 1));
                }
                final String msg = "以下通道缩略图生成失败：" + String.join("、", failedNames);
                Log.w(TAG, "预热完成但有 " + captureFailedChannels.size() + " 路失败: " + msg);
                handler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(MultiViewActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
            // 让用户看到"完成"再返回，避免一闪而过
            handler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) finish();
            }, 700);
        }
    }

    private void setHint(String text) {
        if (tvHint == null) return;
        tvHint.setText(text);
        tvHint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, HINT_DURATION_MS);
    }

    private final Runnable hideHint = () -> {
        if (tvHint != null) {
            tvHint.setVisibility(View.GONE);
        }
    };
}