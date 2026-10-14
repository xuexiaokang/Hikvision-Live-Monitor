# AGENTS.md — AI 工作台接续总纲

> 本文件供任意 AI 工作台（CodeBuddy / Claude Code / Cursor / Codex 等）在接手本项目时**先读这份**，
> 读完即可理解项目全貌、构建方式、代码红线与发布流程，无需重新摸索。
>
> 项目：海康监控直播 / **Hikvision Live Monitor**
> 包名：`com.litevision.tv` ｜ 仓库：<https://github.com/xuexiaokang/Hikvision-Live-Monitor>
> 最后更新：2026-10-14（v1.2 / build 21）

---

## 1. 项目一句话定位

基于 **libVLC 3.5.1** 的 Android 多通道 NVR 监控直播客户端：单画面播放 + 1/4/6/9/16 宫格多画面预览 +
真实帧缩略图 + DDNS 智能解析，手机 / 平板 / TV 一屏通用。

---

## 2. 环境与构建（必读，否则编译必挂）

### 2.1 版本矩阵

| 项 | 值 |
|----|----|
| 语言 / 兼容 | Java 17（`sourceCompatibility` / `targetCompatibility` = 17） |
| AGP | 8.1.4（根 `build.gradle`） |
| Gradle | 本机多版本：`C:\Android\gradle\8.0`（PATH 默认）、`8.9`、`8.14`；`gradle/wrapper` 指向 8.5（腾讯镜像，实际一般不用） |
| compileSdk / buildTools | 34 / 34.0.0 |
| minSdk / targetSdk | 21 / 33 |
| ABI | arm64-v8a、armeabi-v7a、x86、x86_64 |
| 核心依赖 | appcompat 1.6.1、recyclerview 1.3.2、material 1.9.0、`org.videolan.android:libvlc-all:3.5.1` |

### 2.2 ⚠️ JDK 必须是 17（硬性）

系统默认 Gradle JVM 是 JDK 25，AGP 的 `JdkImageTransform` 用 jlink 处理 `android-34/core-for-system-modules.jar`，
**JDK 25 的 jlink 必报错**（尤其带 `--rerun-tasks` 时）。

本机 JDK 17：`C:\Android\jdk17\jdk-17.0.20.1+1`

**规范构建命令（复制即用）**：

```bash
# Debug
gradle -Dorg.gradle.java.home="C:/Android/jdk17/jdk-17.0.20.1+1" :app:assembleDebug --rerun-tasks -x lint

# Release（需 app/keystore.properties 存在，否则走未签名）
gradle -Dorg.gradle.java.home="C:/Android/jdk17/jdk-17.0.20.1+1" :app:assembleRelease -x lint
```

产物：
- Debug：`app/build/outputs/apk/debug/监控直播.apk`
- Release：`app/build/outputs/apk/release/监控直播.apk`（输出文件名在 `app/build.gradle` 中统一覆写为「监控直播.apk」）

> PowerShell 下 `-Dorg.gradle.java.home=C:\...` 会被参数解析吃掉，改在 PowerShell 里先 `$env:JAVA_HOME="C:\Android\jdk17\jdk-17.0.20.1+1"` 再执行。

### 2.3 依赖加速

`settings.gradle` 已配置阿里云 + google + mavenCentral 镜像，国内无需代理即可解析依赖。

### 2.4 需要的本机文件（不在仓库内，需自备）

| 文件 | 说明 |
|------|------|
| `local.properties` | `sdk.dir=C\:\\Android\\Sdk`（每台机器不同，已 gitignore） |
| `app/keystore.properties` | 发布签名配置，**含明文密码**，绝不能入库 |
| `app/release.keystore` | 发布签名库（别名 `hkjkzb`），已 gitignore；备份见 `release-signing-backup-20260908/` |

`keystore.properties` 格式：

```properties
storeFile=release.keystore
storePassword=<密码>
keyAlias=hkjkzb
keyPassword=<密码>
```

---

## 3. 代码结构与职责

```
app/src/main/java/com/litevision/tv/
├── MainActivity.java        # 通道列表页（GridLayoutManager 列数自适应、顶部单行按钮栏）
├── MultiViewActivity.java   # 多画面宫格 1/4/6/9/16（差异重排 switchLayout）
├── PlayerActivity.java      # 单画面全屏播放（音量独立、双击返回来源页）
├── PlayerEngine.java        # libVLC 播放引擎封装（状态机 + 事件回调）
├── VlcOptions.java          # ★ 低延迟取流参数唯一来源
├── ConfigManager.java       # 配置管理（SharedPreferences：DDNS/账号/布局记忆/最后通道）
├── NvrClient.java           # NVR ISAPI 客户端（登录、通道列表拉取、Digest 认证）
├── DnsOverUdp.java          # UDP DNS 解析 + TCP 探活 + TTL 缓存
├── ThumbnailCapture.java    # 真实帧缩略图截帧（黑帧检测 + 5 次重试）
├── ThumbnailCache.java      # 缩略图本地缓存（强制 480×270 等比裁切）
├── ThumbnailLoader.java     # 列表缩略图加载器（已有图优先，绝不回退占位图）
├── ChannelAdapter.java      # 列表 RecyclerView 适配器
├── ChannelTileView.java     # 宫格瓦片视图
├── SettingsActivity.java    # 设置页（含底部动态版本号）
└── （15 个 Java 文件总计约 47 万字节）
```

资源要点：
- `res/values/colors.xml`：深海水域配色（bg_dark `#050D14` / bg_card `#0B1A26` / primary `#00C9DB` / text_primary `#F0F6FC`）
- `res/drawable/channel_card_focused.xml`：**全 App 焦点态唯一 drawable**（改一处全 App 生效）
- `res/drawable/channel_card_selector.xml`：给无焦点监听的 View（如 EditText）用 selector 兜底
- `res/xml/`：TV 相关配置（如有）

---

## 4. 核心机制（改代码前必须知道）

### 4.1 低延迟取流参数 — 唯一来源 `VlcOptions.java`
`CACHING=250`、`:no-sout-...` 系列、`avcodec-skiploopfilter=4`、`avcodec-fast`、`drop-late-frames`、
`THREADS_SUB=1 / THREADS_MAIN=4`。**禁止在其他文件散落硬编码 VLC 参数**，要改就改 `VlcOptions`。

### 4.2 DDNS 智能解析
`DnsOverUdp` + `ConfigManager`：向 223.5.5.5 / 119.29.29.29 / 114.114.114.114 发 UDP DNS 查询 →
TCP 探活选最优 IP → 缓存 `dns_last_good_ip` 兜底；TTL 60s；拉取为空自动强刷。

> 教训：**域名起播必须先等解析完成**，否则域名变 IP 后 `isActiveFor` 不匹配 → TEARDOWN 重建（每路 150~400ms）。
> 修复后 9 路起播 740ms → 338ms。

### 4.3 真实帧缩略图（`ThumbnailCache` + `ThumbnailCapture` + `ThumbnailLoader`）
- 进入 Playing 后**等真实画面出现**（黑帧检测）再截帧，独立排期，失败重试 5 次
- 用 `PixelCopy(Window, rect)` 截帧（**必须用 `getLocationInWindow()`，不能用 `getLocationOnScreen()`**，后者含状态栏偏移）
- 落盘 `files/thumbnails/chXX.jpg`，`ThumbnailCache.save()` 强制缩放裁切为精确 480×270（16:9）
- 列表优先读已有图，**已有图绝不被 placeholder 顶掉**
- 模拟器注意：MuMu 上 `TextureView.getBitmap()` 与离屏 `ImageReader` 都拿不到帧，**只有播放时 PixelCopy 可靠**

### 4.4 多画面差异重排（`MultiViewActivity.switchLayout`）
切换宫格时**不重建**引擎：前 `min(old,new)` 个格子复用引擎 + Tile（仅 `updateViewLayout` 挪位），
新增/多余走 `releaseSlot(int)`。切忌 `releaseAllSlots + applyLayout` 全重建（会造成全部 RTSP 重建、闪烁）。

**GridLayout 两道校验铁律（真机实测）**：
1. `setColumnCount/setRowCount` 会校验所有 child 的 col/row < 新 count
2. `updateViewLayout` 会校验被挪 child 的新 col/row < 当前 count

**唯一安全顺序**（缩宫时）：
`releaseSlot` 掉多余格 → `if (newCols>oldCols) setColumnCount(max)` 抬列 → `updateViewLayout` 挪位 → 最后 `setColumnCount/setRowCount`。

### 4.5 分隔线与配色
- 宫格所有分隔线统一 **1.5dp margin**（`createSlot()` 与 `switchLayout()` 两处都要设，`switchLayout` 新建
  `GridLayout.LayoutParams` 时极易漏掉 margin）
- 分隔线颜色由 `grid.setBackgroundColor(0xFF0B1A26)` 透出，不依赖父层黑色

### 4.6 列表页列数自适应
`MainActivity` 用 `smallestScreenWidthDp`：`swDp < 480 → 2 列`，否则 **3 列**（默认 3 列，大屏不再跳 4 列）。

---

## 5. 🔴 红线（每次改动前核对，违反代价很高）

1. **绝不硬编码用户真实连接信息**：DDNS 域名、RTSP 端口、账号、密码、通道名等只能由用户在 App「设置」里配置
   （SharedPreferences）。当前源码 `ConfigManager.DEFAULT_*` 只有占位值（`192.168.1.23` / `admin` / 空密码），
   **保持这样，不要"为了方便验证"改成真实值**。
2. **禁止 `pm clear com.litevision.tv`**（曾两次清空用户真实账号密码，MuMu 无 Google 备份不可恢复）。
   需要"无缓存"测试路径时：① `adb install -r` 覆盖升级保留数据；② 临时改 prefs `last_view_mode`；
   ③ 实在要清，先 `run-as` 备份 `shared_prefs/` 再清，验完恢复。
3. **改 Java 源码不要用 Python heredoc / 批量脚本替换**：曾把 `setImageResource(` 写成 `[layer]`、
   `itemView.post` 写成 `petitioner.post`，整文件损坏。用编辑工具按 ASCII 唯一锚点精确替换。
   征兆：编译报"需要 ';'" → 立刻 `git checkout -- <file>` 恢复，事后 `grep -c "\[layer\]" <file>` 自检应为 0。
4. **`network-caching` 不是首帧瓶颈**（1000→250 只快约 50ms），降缓存的价值在端到端延迟，别为"加速首帧"反复折腾。
5. **MaterialButton 陷阱**：`Theme.MaterialComponents.NoActionBar` 下 `<Button>` 会被 inflate 成
   `MaterialButton` 并吞掉 `background`，必须用全限定名 `<android.widget.Button>`。
6. **minSdk 21 禁用 `<item android:inset>`**（API 23+），焦点 drawable 用 left/top/right/bottom 四属性。
7. **同一双击不能承担两种语义**：单画面双击统一为"返回来源页"，用 `mManualDblTapExit` 防止 `onStop` 覆写。
8. **敏感文件永不入库**：`*.keystore`、`keystore.properties`、`local.properties`、`.workbuddy/`、
   `build/`、`.gradle/`、`*.apk`（`release/` 下 APK 是唯一例外，用 `git add -f` 显式加入）。

---

## 6. 调试 / 验证（MuMu 模拟器）

设备：`adb connect 192.168.7.92:5555`（地址可能变，以实际为准）

**adb 铁律**：
- 多步 adb 操作必须串成**单条命令**（调用间隔 daemon 可能重置），每条命令开头先 `adb connect ... >/dev/null 2>&1;`
- `adb root` 必断 daemon → 读私有数据用 `run-as com.litevision.tv sh -c '...'`
- `adb push` 目标路径须 `cygpath -w` 转 Windows 路径
- 写入私有文件：`adb push → /data/local/tmp → run-as cp` 二步式（`cat >` 经 stdin 会损坏二进制）
- 冷启动导航陷阱：`am start .MainActivity` 后会跳到 `MultiViewActivity`；要进设置页须先
  `input keyevent 4` 回 MainActivity 再点「设置」（直接 `am start SettingsActivity` 因 `exported=false` 被拦）
- 双击验证：两条 `input tap` 必须串在同一条命令（不加 sleep）才能压进 320ms 窗口

首帧埋点：`PlayerEngine.playStartMs` → Playing 日志「首帧耗时=XXms」，可 grep 量化调参效果。

---

## 7. 发布流程

1. 改版本号：`app/build.gradle` 的 `versionCode` / `versionName`（**设置页底部版本号是动态读取的，无需另改**）
2. 用第 2.2 节的 release 命令构建（需 `app/keystore.properties`）
3. 校验：`apksigner verify --print-certs 监控直播.apk`
4. 分发（**重要教训**）：
   - ❌ **不要**用 GitHub Release 的 asset 上传通道放大文件 —— 曾因系统代理变量残留（旧失效代理
     `127.0.0.1:27617`，正确为 `127.0.0.1:20808`）导致 85MB APK 被改写，用户下载后无法安装
     （size 与本地不符、`apksigner verify` 失败），反复重试均损坏
   - ✅ 正确做法：`git add -f release/HikvisionLiveMonitor-vX.Y.apk` 入库，用 git 的 SHA-1 + zlib 校验保真，
     页面提供 raw 下载链接；README 附 SHA256 供用户校验
5. 同步远端：`git push origin main`（已配 `credential.helper manager`）

---

## 8. 当前状态

| 项 | 值 |
|----|----|
| 版本 | v1.2（versionCode 21） |
| 发布包 | `release/HikvisionLiveMonitor-v1.2.apk`（85,497,429 字节，81.5MB） |
| SHA256 | `76764ec85a388c026d6554bf6263a59ec235cf1a3fa178e726dc1f6b70abccd3` |
| 下载 | <https://raw.githubusercontent.com/xuexiaokang/Hikvision-Live-Monitor/main/release/HikvisionLiveMonitor-v1.2.apk> |
| Git 远端 | <https://github.com/xuexiaokang/Hikvision-Live-Monitor.git>（公开仓库，已做敏感清理） |
| 工作区状态 | 2026-10-14 已清理构建产物（`app/build`、`.gradle`、`build/`），工作区干净 |

### 版本历史
- **v1.2 (21)** 2026-09：宫格分隔线统一 1.5dp + 深蓝底；修复 16 宫格记忆/切换分隔线丢失；修复缩略图进度
  12/12→9/9 与成功误报失败；深海水域配色；列表默认 3 列自适应；设置页动态版本号；项目英文名改为 Hikvision Live Monitor
- **v1.1 (20)** 2026-09：截帧隐藏叠加层、5 次重试、手机列数自适应、全屏音量独立、TV 遥控器焦点修复

### 已知限制
- libVLC Java API 无 `takeSnapshot`，缩略图只能靠播放时 `PixelCopy`
- MuMu 模拟器合成层限制，`TextureView.getBitmap()` 拿不到帧
- ISAPI 当前固定 `http://`，未支持 443 HTTPS

---

## 9. 给接手 AI 的工作建议

1. **先读**：本文件 → `README.md` → `docs/system_design.md` → `ai-context/MEMORY.md`（备份包内，含全部踩坑记录）
2. **再跑**：第 2.2 节的 debug 构建命令确认环境通
3. **后改**：对照第 5 节红线；改 UI 优先改 `colors.xml` / `channel_card_focused.xml` 等单点生效文件
4. **验证**：真机 > MuMu；缩略图类改动必须在真机验证（模拟器截帧不可靠）
5. **提交**：中文 commit message 亦可；push 前确认无敏感文件（`git status` + 第 5.8 条清单）
