# Hikvision Live Monitor（海康监控直播）

基于 **libVLC 3.5.1** 的 Android 多通道 NVR 监控直播客户端 —— 单画面播放、4/9/16 宫格多画面预览、真实帧缩略图、DDNS 智能解析，手机 / 平板 / TV 一屏通用。

![Android](https://img.shields.io/badge/Android-21%2B-3DDC84?logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-orange)
![libVLC](https://img.shields.io/badge/libVLC-3.5.1-blue)
![Gradle](https://img.shields.io/badge/Gradle-8.14-green)
![License](https://img.shields.io/badge/License-Proprietary-lightgrey)

---

## ✨ 功能特性

| 特性 | 说明 |
|------|------|
| 🖥 **多通道实时预览** | 1 / 4 / 6 / 9 / 16 宫格自由切换，独立播放引擎并行取流；宫格布局自动记忆，下次进入恢复 |
| 📺 **RTSP 播放** | 基于 libVLC 3.5.1，低延迟取流参数调优（`CACHING=250`、解码器 fast 模式、丢帧跳过） |
| 🛰 **DDNS 智能解析** | 内置 UDP DNS 解析（223.5.5.5 / 119.29.29.29 / 114.114.114.114）+ TCP 探活，自动选择最优 IP，TTL 60s 自动刷新、失败兜底 |
| 🖼 **真实帧缩略图** | 独立截帧排期 + 智能黑帧检测，等真实画面出现后再截帧（480×270），失败自动重试 5 次；已有缩略图优先展示、绝不回退占位图 |
| 🔄 **宫格切换零重建** | 布局切换采用**差异重排**：复用引擎与瓦片、仅挪位置，消除 RTSP 重建开销（9 路切布局无闪烁） |
| 🔐 **NVR ISAPI 集成** | 用户名 / 密码登录（SharedPreferences 私有存储，**不硬编码**）、设备与通道列表拉取、Digest 认证 |
| 📱 **多端 UI 适配** | 手机 / 平板 / TV 自适应列数（默认 3 列，小屏 2 列）；遥控器焦点环 + 触屏手势双操作；单画面双击返回来源页 |
| 🔊 **全屏独立音量** | 播放页独立音量调节，不影响系统铃声音量 |

## 🛠 技术栈

- **语言**：Java（Android）
- **播放器**：libVLC 3.5.1（`org.videolan.android:libvlc-all`）
- **构建**：Gradle 8.14 / AGP 8.1.4（**JDK 17 必须**，JDK 18+ 会 jlink/class-version 失败）
- **最低版本**：minSdk 21（Android 5.0）/ targetSdk 34
- **ABI**：arm64-v8a / armeabi-v7a / x86 / x86_64

## 🚀 快速开始

### 构建 APK

```bash
# JDK 17 必须（本机示例，替换为自己的 JDK17 路径）
gradle :app:assembleRelease -x lint -Dorg.gradle.java.home="C:/Android/jdk17/jdk-17.0.20.1+1"
```

产物：`app/build/outputs/apk/release/监控直播.apk`

> 依赖仓库已在 `settings.gradle` 配置国内阿里云镜像，无需科学上网。

### 运行

1. 安装 APK 到 Android 手机 / 电视盒子 / 平板；
2. 打开 App → **设置** → 填入 NVR 信息（DDNS 域名 / RTSP 端口 / HTTP 端口 / 账号 / 密码）；
3. 返回列表页，点击通道即可播放；点「多画面」进入宫格预览。

## ⚙️ 配置说明

- 所有连接信息在 App 内「设置」页配置（域名 / 端口 / 账号 / 密码），**不硬编码在源码**，杜绝凭据泄漏。
- 连接信息存于 SharedPreferences（应用私有目录）。调试可用 `run-as com.litevision.tv` 读取/备份，**切勿**提交 `keystore` / `local.properties` / `keystore.properties`（已全部纳入 `.gitignore`）。
- 内置参数唯一来源：`VlcOptions.java`（低延迟取流参数集中管理，禁止散落硬编码）。

## 🧱 目录结构

```
app/src/main/java/com/litevision/tv/
├── MainActivity.java          # 通道列表页（列数自适应、顶部按钮栏）
├── MultiViewActivity.java     # 多画面宫格（1/4/6/9/16，差异重排）
├── PlayerActivity.java        # 单画面全屏播放
├── PlayerEngine.java          # libVLC 播放引擎封装（状态机 + 事件）
├── VlcOptions.java            # 低延迟取流参数（唯一来源）
├── ConfigManager.java         # 配置管理（DDNS/账号/布局记忆/缩略图，SharedPreferences）
├── NvrClient.java             # NVR ISAPI 客户端（登录/通道列表）
├── DnsOverUdp.java            # UDP DNS 解析 + TCP 探活 + 缓存
├── ThumbnailCapture.java      # 真实帧缩略图截帧（黑帧检测 + 重试）
├── ThumbnailCache.java        # 缩略图本地缓存（480×270）
├── ThumbnailLoader.java       # 列表缩略图加载器
├── ChannelAdapter.java        # 列表 RecyclerView 适配器
└── ChannelTileView.java       # 宫格瓦片视图
```

## 📋 版本历史

### v1.2 (build 21) — 2026-09
- 4/9/16 宫格分隔线统一 1.5dp（深蓝分隔线，`#0B1A26`）
- 修复：16 宫格记忆丢失、宫格切换分隔线丢失、多画面切换分隔线丢失
- 修复：缩略图刷新进度错误（12/12→9/9）、成功通道被误报失败
- 深海水域配色方案 A（`#050D14` 底 / `#00C9DB` 主 / `#F0F6FC` 字）
- 列表页默认 3 列自适应（`swDp<480` → 2 列），标题与按钮智能同行
- 设置页底部动态显示版本号

### v1.1 (build 20) — 2026-09
- 截图隐藏叠加层、5 次重试、手机列数自适应、全屏音量独立、TV 遥控器焦点修复

## ⚠️ 已知限制

- libVLC Java API 无 `takeSnapshot`，缩略图依赖播放时 `PixelCopy` 窗口截帧
- 部分模拟器（如 MuMu）合成层限制，`TextureView.getBitmap()` 拿不到帧，需真实设备或 PixelCopy 验证
- 当前固定 `http://` 接入（ISAPI 443 HTTPS 未支持）

## License

**保留所有权利（All Rights Reserved）**。本仓库为作者个人项目，未开放商用授权；如需使用、二次开发或合作，请联系作者。

---

*README 由项目实际结构与长期记忆整理，如有不准确处，欢迎提 issue 指正。*