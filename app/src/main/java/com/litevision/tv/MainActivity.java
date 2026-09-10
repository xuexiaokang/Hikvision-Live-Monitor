package com.litevision.tv;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Main activity - displays a grid of camera channels.
 * Fetches channel info from NVR via ISAPI.
 */
public class MainActivity extends AppCompatActivity implements ChannelAdapter.OnChannelClickListener {

    private ConfigManager config;
    private ChannelAdapter adapter;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvStatus;
    /** 本次启动是否已执行过状态恢复（仅 onCreate 后第一次 onResume 可恢复，避免返回列表页时反复跳转） */
    private boolean restoredThisLaunch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        config = new ConfigManager(this);

        recyclerView = findViewById(R.id.recycler_channels);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);

        // ===== 列数自适应：默认 3 列，小屏缩减为 2 列，大屏保持 3 列 =====
        // 基准：smallestScreenWidthDp < 480 (手机窄屏) → 2 列；>= 480 (手机宽屏/平板/TV) → 3 列。
        int swDp = getResources().getConfiguration().smallestScreenWidthDp;
        int spanCount = (swDp < 480) ? 2 : 3;
        Log.i("MainActivity", "通道列表列数: " + spanCount + " (smallestScreenWidthDp=" + swDp + ")");
        GridLayoutManager layoutManager = new GridLayoutManager(this, spanCount);
        recyclerView.setLayoutManager(layoutManager);

        adapter = new ChannelAdapter(this, config, this);
        recyclerView.setAdapter(adapter);

        Button btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });

        btnSettings.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.channel_card_focused);
            } else {
                v.setBackgroundResource(R.drawable.channel_card_normal);
            }
        });

        // ===== 多画面入口按钮 =====
        Button btnMultiView = findViewById(R.id.btn_multi_view);
        if (btnMultiView != null) {
            btnMultiView.setOnClickListener(v -> {
                Intent intent = new Intent(this, MultiViewActivity.class);
                startActivity(intent);
            });
            btnMultiView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.channel_card_focused);
                } else {
                    v.setBackgroundResource(R.drawable.channel_card_normal);
                }
            });
        }

        // ===== 刷新缩略图按钮（预热：自动 9 宫格轮播 + 逐路截帧后自动回列表）=====
        Button btnRefreshThumbs = findViewById(R.id.btn_refresh_thumbs);
        if (btnRefreshThumbs != null) {
            btnRefreshThumbs.setOnClickListener(v -> {
                Log.i("MainActivity", "点击刷新缩略图 → 启动自动预热多画面");
                Intent intent = new Intent(this, MultiViewActivity.class);
                intent.putExtra(MultiViewActivity.EXTRA_AUTOCAPTURE, true);
                startActivity(intent);
            });
            btnRefreshThumbs.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.channel_card_focused);
                } else {
                    v.setBackgroundResource(R.drawable.channel_card_normal);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Update title
        TextView tvTitle = findViewById(R.id.tv_title);
        tvTitle.setText(getString(R.string.main_title, config.getHost()));

        // Refresh channels from NVR
        loadChannels();

        // ===== 启动恢复：仅 onCreate 后第一次 onResume 执行 =====
        // 避免从 Player/Multi 返回列表页时再次跳转；restoredThisLaunch 为一次性标志。
        if (!restoredThisLaunch) {
            restoredThisLaunch = true;
            restoreLastSession();
        }
    }

    /**
     * 按上次退出状态恢复：single → 直达单画面；multi → 直达多画面；无记忆 → 停留列表。
     * 恢复前先 setContentView 已在 onCreate 完成，不会黑屏（§14 R6）。
     */
    private void restoreLastSession() {
        String mode = config.getLastViewMode();
        if ("single".equals(mode)) {
            int channel = config.getLastChannelIndex();
            Log.i("MainActivity", "恢复上次状态: single channel=" + channel
                    + " streamType=" + config.getLastStreamType());
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channel);
            intent.putExtra(PlayerActivity.EXTRA_STREAM_TYPE, config.getLastStreamType());
            startActivity(intent);
        } else if ("multi".equals(mode)) {
            int layout = config.getLastMultiLayout();
            Log.i("MainActivity", "恢复上次状态: multi layout=" + layout);
            Intent intent = new Intent(this, MultiViewActivity.class);
            intent.putExtra(MultiViewActivity.EXTRA_LAYOUT, layout);
            startActivity(intent);
        } else {
            Log.i("MainActivity", "无上次状态，停留在通道列表");
        }
    }

    private void loadChannels() {
        // Show cached names first
        List<String> cached = config.getChannelNames();
        if (cached.isEmpty()) {
            // ★ 手机端兜底（2026-09-08 修复）：即使无缓存也先显示 9 个 "通道 X" 占位，
            // list 结构立刻可见，避免 RecyclerView GONE 留下黑底 FrameLayout（被用户
            // 误判为 "列表无法显示"）。ISAPI 成功后再用真实通道名覆盖。
            adapter.setChannelNames(defaultChannelNames(9));
            progressBar.setVisibility(View.VISIBLE);
            tvStatus.setText("正在连接录像机...");
            tvStatus.setVisibility(View.VISIBLE);
        } else {
            adapter.setChannelNames(cached);
            progressBar.setVisibility(View.GONE);
            tvStatus.setVisibility(View.GONE);
        }

        // Fetch latest from NVR
        config.refreshChannelInfo(new ConfigManager.ChannelInfoCallback() {
            @Override
            public void onChannelsLoaded(List<String> names) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setVisibility(View.GONE);
                    adapter.setChannelNames(names);
                    // Focus first item
                    recyclerView.post(() -> {
                        if (recyclerView.getChildCount() > 0) {
                            View firstChild = recyclerView.getChildAt(0);
                            if (firstChild != null) {
                                firstChild.requestFocus();
                            }
                        }
                    });
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (config.getChannelCount() == 0) {
                        // ★ 保留占位列表（用户至少看到 list 结构），只覆盖状态文字 + Toast
                        tvStatus.setText("无法连接录像机\n\n" + message
                                + "\n\n请按菜单键进入设置，或下拉刷新");
                        tvStatus.setVisibility(View.VISIBLE);
                    } else {
                        // Has cached data, just show toast
                        Toast.makeText(MainActivity.this, "刷新通道列表失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /**
     * 生成 N 个 "通道 X" 占位名。
     *
     * <p>手机端（典型 smallestScreenWidthDp=360）首次启动时 NVR 不可达 → cached 空 →
     * 旧逻辑会 {@code recyclerView.setVisibility(View.GONE)}，黑底 FrameLayout 直接露出
     * 被误判为 "列表无法显示"。这里兜底 9 个占位让 list 永远可见。</p>
     */
    private static List<String> defaultChannelNames(int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            list.add("通道" + i);
        }
        return list;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 缩略图抓帧每路会占用 NVR 一条取流连接（最长 8s），页面销毁必须立即停止：
        // 否则退到多画面/单画面后，后台抓帧仍在连 NVR，与播放取流抢并发额度，
        // 且共享的 LibVLC 实例永不释放。停止后置位标志，在途结果不再回填 UI。
        if (adapter != null) {
            adapter.shutdown();
        }
    }

    @Override
    public void onChannelClick(int channelIndex) {
        // 记录单画面上次状态（用户从列表进入单画面 → 下次冷启动恢复回单画面该通道）
        config.setLastViewMode("single");
        config.setLastChannelIndex(channelIndex);
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channelIndex);
        startActivity(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
