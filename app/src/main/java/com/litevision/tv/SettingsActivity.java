package com.litevision.tv;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Settings activity for NVR connection configuration.
 * Includes diagnostic button to show ISAPI response.
 */
public class SettingsActivity extends AppCompatActivity {

    private ConfigManager config;
    private EditText etHost;
    private EditText etPort;
    private EditText etHttpPort;
    private CheckBox cbHttpPortFollow;
    private EditText etUsername;
    private EditText etPassword;
    private CheckBox cbSubStream;
    private RadioGroup rgDecodeMode;
    private RadioButton rbDecodeHard;
    private RadioButton rbDecodeSoft;
    private RadioButton rbDecodeAuto;
    private TextView tvDecodeModeDesc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        config = new ConfigManager(this);

        etHost = findViewById(R.id.et_host);
        etPort = findViewById(R.id.et_port);
        etHttpPort = findViewById(R.id.et_http_port);
        cbHttpPortFollow = findViewById(R.id.cb_http_port_follow);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        cbSubStream = findViewById(R.id.cb_sub_stream);
        rgDecodeMode = findViewById(R.id.rg_decode_mode);
        rbDecodeHard = findViewById(R.id.rb_decode_hard);
        rbDecodeSoft = findViewById(R.id.rb_decode_soft);
        rbDecodeAuto = findViewById(R.id.rb_decode_auto);
        tvDecodeModeDesc = findViewById(R.id.tv_decode_mode_desc);

        // Load current config
        etHost.setText(config.getHost());
        etPort.setText(String.valueOf(config.getRtspPort()));
        boolean followRtsp = config.isHttpPortFollowRtsp();
        cbHttpPortFollow.setChecked(followRtsp);
        etHttpPort.setText(String.valueOf(followRtsp ? config.getRtspPort() : config.getHttpPort()));
        etUsername.setText(config.getUsername());
        etPassword.setText(config.getPassword());
        cbSubStream.setChecked(config.isUseSubStream());
        applyDecodeMode(config.getDecodeMode());

        // 解码模式：方向键左右移动焦点（不自动选中），OK 键才选中；
        // 说明文字跟随焦点变化，遥控器上无需选中即可逐项查看。
        View.OnFocusChangeListener decodeFocusListener = (v, hasFocus) -> {
            v.setBackgroundResource(hasFocus ? R.drawable.channel_card_focused
                    : R.drawable.channel_card_normal);
            if (hasFocus) {
                updateDecodeModeDesc(decodeModeOfRadioButton(v.getId()));
            }
        };
        rbDecodeHard.setOnFocusChangeListener(decodeFocusListener);
        rbDecodeSoft.setOnFocusChangeListener(decodeFocusListener);
        rbDecodeAuto.setOnFocusChangeListener(decodeFocusListener);

        // 跟随模式下 ISAPI 端口不可编辑，直接跟随 RTSP 端口
        applyHttpPortFollowState(followRtsp);
        cbHttpPortFollow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            applyHttpPortFollowState(isChecked);
            if (isChecked) {
                syncHttpPortFromRtsp();
            }
        });
        // RTSP 端口变化时，实时同步到 ISAPI 端口输入框（跟随模式下）
        etPort.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (cbHttpPortFollow.isChecked()) {
                    syncHttpPortFromRtsp();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> {
            if (saveConfig()) {
                Toast.makeText(this, R.string.toast_settings_saved_decode, Toast.LENGTH_LONG).show();
                // 保存成功立即返回主界面：MainActivity.onResume() 会用最新 host
                // 重新刷新标题与通道列表，无需 RESULT 回传。
                finish();
            }
        });

        Button btnTest = findViewById(R.id.btn_test);
        btnTest.setOnClickListener(v -> {
            // 只有配置合法才发起测试，避免带着错误端口去连
            if (saveConfig()) {
                testConnection();
            }
        });

        Button btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> finish());

        // Focus handling for TV
        btnSave.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.channel_card_focused);
            } else {
                v.setBackgroundResource(R.drawable.channel_card_normal);
            }
        });
        btnTest.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.channel_card_focused);
            } else {
                v.setBackgroundResource(R.drawable.channel_card_normal);
            }
        });
        btnCancel.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.channel_card_focused);
            } else {
                v.setBackgroundResource(R.drawable.channel_card_normal);
            }
        });
    }

    /**
     * 跟随模式下禁用 ISAPI 端口输入框（遥控器焦点会自动跳过 disabled 控件）。
     */
    private void applyHttpPortFollowState(boolean follow) {
        etHttpPort.setEnabled(!follow);
        etHttpPort.setAlpha(follow ? 0.4f : 1.0f);
    }

    /**
     * 回填解码模式到 RadioGroup，并刷新对应说明文字。
     */
    private void applyDecodeMode(String mode) {
        if (ConfigManager.DECODE_MODE_HARD.equals(mode)) {
            rgDecodeMode.check(R.id.rb_decode_hard);
        } else if (ConfigManager.DECODE_MODE_AUTO.equals(mode)) {
            rgDecodeMode.check(R.id.rb_decode_auto);
        } else {
            rgDecodeMode.check(R.id.rb_decode_soft);
        }
        updateDecodeModeDesc(ConfigManager.normalizeDecodeMode(mode));
    }

    /** 读取 RadioGroup 当前选中项对应的解码模式（无选中时回落软解）。 */
    private String getSelectedDecodeMode() {
        return decodeModeOfRadioButton(rgDecodeMode.getCheckedRadioButtonId());
    }

    /** RadioButton id → 解码模式。 */
    private String decodeModeOfRadioButton(int radioButtonId) {
        if (radioButtonId == R.id.rb_decode_hard) {
            return ConfigManager.DECODE_MODE_HARD;
        }
        if (radioButtonId == R.id.rb_decode_auto) {
            return ConfigManager.DECODE_MODE_AUTO;
        }
        return ConfigManager.DECODE_MODE_SOFT;
    }

    /** 显示某个解码模式的说明文字。 */
    private void updateDecodeModeDesc(String mode) {
        int resId;
        if (ConfigManager.DECODE_MODE_HARD.equals(mode)) {
            resId = R.string.settings_decode_mode_desc_hard;
        } else if (ConfigManager.DECODE_MODE_AUTO.equals(mode)) {
            resId = R.string.settings_decode_mode_desc_auto;
        } else {
            resId = R.string.settings_decode_mode_desc_soft;
        }
        tvDecodeModeDesc.setText(resId);
    }

    private void syncHttpPortFromRtsp() {
        String value = etPort.getText().toString().trim();
        if (!value.equals(etHttpPort.getText().toString().trim())) {
            etHttpPort.setText(value);
        }
    }

    /**
     * 校验并保存配置。
     *
     * @return true 保存成功；false 校验失败（已提示用户，且未写入任何配置）
     */
    private boolean saveConfig() {
        // 1) 主机清洗：容忍 http://host:port/path 这类粘贴内容
        String host = ConfigManager.normalizeHost(etHost.getText().toString());
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.error_host_empty, Toast.LENGTH_SHORT).show();
            etHost.requestFocus();
            return false;
        }

        // 2) 若用户把端口写进了地址（host:8080），提取出来作为 ISAPI 端口
        String[] hostAndPort = ConfigManager.splitHostAndPort(host);
        host = hostAndPort[0];
        String portFromHost = hostAndPort[1];

        // 3) RTSP 端口：必须显式校验，非法时提示用户，绝不静默回落
        int rtspPort = parsePort(etPort, etPort.getText().toString(), "RTSP");
        if (rtspPort < 0) {
            return false;
        }

        // 4) ISAPI 端口
        boolean follow = cbHttpPortFollow.isChecked();
        int httpPort;
        if (follow) {
            httpPort = rtspPort;
        } else {
            // 地址里带的端口优先级最高（用户显式写在地址里，意图最明确）
            String source = (portFromHost != null && !portFromHost.isEmpty())
                    ? portFromHost : etHttpPort.getText().toString();
            if (source.trim().isEmpty()) {
                httpPort = 80;
            } else {
                httpPort = parsePort(etHttpPort, source, "ISAPI");
                if (httpPort < 0) {
                    return false;
                }
            }
        }

        config.setHost(host);
        config.setRtspPort(rtspPort);
        config.setHttpPort(httpPort);
        config.setHttpPortFollowRtsp(follow);
        config.setUsername(etUsername.getText().toString().trim());
        config.setPassword(etPassword.getText().toString().trim());
        config.setUseSubStream(cbSubStream.isChecked());
        config.setDecodeMode(getSelectedDecodeMode());

        // 回填清洗后的主机与最终生效的端口，让用户看到"实际存进去了什么"
        etHost.setText(host);
        etHttpPort.setText(String.valueOf(httpPort));

        if (portFromHost != null && !portFromHost.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_port_from_host, portFromHost),
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    /**
     * 解析并校验端口。失败时提示用户并把焦点移到对应输入框，返回 -1。
     */
    private int parsePort(EditText field, String rawValue, String label) {
        String value = rawValue == null ? "" : rawValue.trim();
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            showPortError(field, label);
            return -1;
        }
        if (port < ConfigManager.MIN_PORT || port > ConfigManager.MAX_PORT) {
            showPortError(field, label);
            return -1;
        }
        return port;
    }

    private void showPortError(EditText field, String label) {
        Toast.makeText(this, getString(R.string.error_port_range, label), Toast.LENGTH_LONG).show();
        field.requestFocus();
        field.setSelection(field.getText().length());
    }

    private void testConnection() {
        Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show();

        config.refreshChannelInfo(new ConfigManager.ChannelInfoCallback() {
            @Override
            public void onChannelsLoaded(java.util.List<String> names) {
                runOnUiThread(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("连接成功！\n");
                    sb.append("通道数: ").append(names.size()).append("\n\n");
                    for (int i = 0; i < names.size(); i++) {
                        sb.append("[").append(i).append("] ").append(names.get(i)).append("\n");
                    }
                    sb.append("\n--- 诊断信息 ---\n");
                    String diag = config.getDiagnostic();
                    sb.append(diag);
                    showResult("测试结果", sb.toString());
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("连接失败: ").append(message).append("\n\n");
                    sb.append("--- 诊断信息 ---\n");
                    String diag = config.getDiagnostic();
                    if (!diag.isEmpty()) {
                        sb.append(diag);
                    } else {
                        sb.append("无诊断数据");
                    }
                    showResult("测试结果", sb.toString());
                });
            }
        });
    }

    private void showResult(String title, String message) {
        // Use a TextView in AlertDialog for scrollable text
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(12);
        tv.setPadding(40, 30, 40, 30);
        tv.setTextIsSelectable(true);

        // Make it scrollable
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton("确定", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
