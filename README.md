# Hikvision Live Monitor（海康监控直播）

基于 **libVLC 3.5.1** 的 Android 多通道 NVR 监控直播客户端，支持单画面播放、4/9/16 宫格多画面预览、缩略图预取与遥控器/触屏双操作。

## 功能特性

- **多通道实时预览**：1 / 4 / 6 / 9 / 16 宫格布局自由切换，独立引擎并行取流
- **RTSP 播放**：基于 libVLC（`org.videolan.libvlc:3.5.1`），低延迟取流参数调优（CACHING=250、解码器 fast 模式）
- **NVR ISAPI 集成**：用户名密码登录（SharedPreferences 存储，不硬编码）、设备/通道信息拉取
- **DDNS 智能解析**：内置 UDP 域名解析（223.5.5.5 / 119.29.29.29 / 114.114.114.114）+ TCP 探活，自动选择最优 IP，支持 DNS 兜底与 TTL 刷新
- **真实帧缩略图**：独立截帧排期，智能黑帧检测，播放后延迟截帧生成 480×270 真实画面缩略图，列表页优先展示，含失败重试
- **单画面 / 多画面**：双击格子在单画面与来源页间切换；全屏播放支持独立音量
- **UI 适配**：手机/平板/TV 自适应列数（默认 3 列，小屏 2 列）、遥控器焦点 + 触屏手势双操作

## 技术栈

- **语言**：Java（Android）
- **播放器**：libVLC 3.5.1（`libvlc-all`）
- **构建**：Gradle 8.14 / AGP 8.1.4，**JDK 17 必须**（18+ 会 jlink/class-version 失败）
- **minSdk** 21 / **targetSdk** 34

## 目录结构

```
app/src/main/java/com/litevision/tv/
├── MainActivity.java        # 通道列表页
├── MultiViewActivity.java   # 多画面宫格
├── PlayerActivity.java      # 单画面全屏播放
├── PlayerEngine.java        # libVLC 播放引擎封装
├── VlcOptions.java          # 低延迟取流参数（唯一来源）
├── ConfigManager.java       # 配置管理（DDNS/账号/布局，SharedPreferences）
├── NvrClient.java           # NVR ISAPI 客户端（登录/通道列表）
├── DnsOverUdp.java          # UDP DNS 解析 + TCP 探活
├── ThumbnailCapture.java    # 真实帧缩略图截帧（含黑帧检测）
├── ThumbnailCache.java      # 缩略图本地缓存
├── ChannelAdapter.java      # 列表适配器
└── ChannelTileView.java     # 宫格瓦片
```

## 构建

```bash
# JDK 17 必须
gradle :app:assembleRelease -x lint -Dorg.gradle.java.home=<你的JDK17路径>
# 产物：app/build/outputs/apk/release/监控直播.apk
```

> 依赖仓库已配置国内阿里云镜像（`settings.gradle`），无需翻墙。

## 配置说明

- 所有连接设置在 App 内「设置」页配置（DDNS 域名 / 端口 / 账号 / 密码），**不硬编码在源码**。
- 连接信息存于 SharedPreferences（私有数据），调试用 `run-as` 可读取，勿提交 keystore / local.properties（已在 .gitignore 排除）。

## 已知限制

- libVLC Java API 无 `takeSnapshot`；真机缩略图依赖播放时 `PixelCopy` 截帧。
- 部分模拟器（MuMu）合成层限制，`TextureView.getBitmap()` 取不到帧，需真实设备或 PixelCopy 窗口截帧验证。

## License

No license specified（保留所有权利）。如有需要请与作者联系。

---

*README 由项目结构与长期记忆生成，如有不准确处请指正。*