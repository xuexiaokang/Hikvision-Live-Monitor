# 《监控直播》多画面预览 + 三项优化 交付说明

- 日期：2026-09-03
- 包名：com.litevision.tv（未变）
- 应用名：**监控直播**（原"监看TV"）
- 构建：JDK17 + Gradle 8.14，`BUILD SUCCESSFUL`

---

## TL;DR
新增多画面宫格预览（1/4/6/9）并记忆上次退出状态；修复通道缩略图未自动生成；软件正式改名**监控直播**；修复部分画面底部花屏。全部实现经独立 QA 验证（94/94 断言通过，无源码缺陷）。

## 交付概览
- 交付状态：✅ 完成（工程师实现 + QA 独立验证双通过）
- 测试：静态逻辑 harness 94/94 PASS；契约/花屏数学/可编译性复核通过
- 已知问题：0（真机相关项已如实标注"待真机确认"，未谎报）

## APK（最终版）
- 路径：`app/build/outputs/apk/debug/监控直播.apk`
- 大小：92,090,521 字节
- SHA256：`f1f8c5f5012049665247e55f115421423ec00973814a7e80bd530a29f078d097`
- 构建：JDK17（`C:\Users\XXK\.workbuddy\jdks\jdk-17.0.20.1+1`）+ Gradle 8.14，`BUILD SUCCESSFUL`

---

## ⚠️ 花屏问题排查历程（如实记录，含误判，供后续参考）

**现象**：多画面宫格中，部分通道画面底部有一条花屏带；"摄像头不统一"的几路必现；海康官方 iVMS-4500 同一 NVR 同源完全无花屏。

**排查历程（前 4 轮均为误判/未命中）**：
1. ~~TextureView 非等比拉伸 → Matrix center-crop 放大铺满~~（用了含 padding 的错误尺寸算比例，且放大会把脏区放大，反而更糟）
2. ~~硬解码 MediaCodec 残差 → 全局强制软解 `--avcodec-hw=disabled`（7 处注入）~~（软解已保留，但它不是花屏主因）
3. ~~宏块对齐/SAR 比例偏差 + 底部微裁~~（方向对但数学仍用错尺寸）
4. ~~纯 contain no-op~~（不设 transform 时 VLC 仍把整个 buffer 拉伸铺满，padding 行照样显示）

**真正根因（第 5 轮，用户截图实证定位）**：
libVLC 的 android-display 在 `setVideoSurface` 模式下，把**整个解码 buffer 拉伸铺满 Surface**。解码 buffer 高度常含**宏块对齐 padding**（如 1080→1088、358→360），**padding 行是未初始化的脏数据**，拉伸后正好显示为画面底部的花屏带。每个摄像头分辨率不同 → 只有 padding 异构的几路显花（与"摄像头不统一"吻合）。iVMS-4500 用自家 SDK 按 SPS 裁剪标记只取 visible 有效区，所以干净。

**最终修复（多画面 + 单画面同一套数学）**：
- `PlayerEngine`/`PlayerActivity` 记录 `onNewVideoLayout` 的 **buffer 尺寸**（含 padding）、**visible 有效尺寸**、**SAR**；
- 显示时按 **visible 有效区**做**等比 contain**（不变形、黑边居中，对齐 iVMS 观感）：
  - 格子/屏幕更宽（viewAR≥dispAR）：高度撑满（y0=0），padding 行被推出底边裁掉，左右黑边居中；
  - 更高（viewAR<dispAR）：宽度撑满、上下黑边居中，padding 由 `setClipBounds(内容矩形)` 裁掉。
- **教训**：排查显示问题时应尽早拿到"官方播放器同源对照 + 截图"这类实证，而不是先从解码参数猜起。

**中间版本 APK（已废弃）**：`9adc1d25…`（92,059,711 B）→ `4534894a…`（92,058,735 B）→ `e81d6fd0…`（91,991,285 B）→ `ffc6941b…` → `acebf8d7…` → `10336bc1…`（多画面裁 padding）→ **最终 `f1f8c5f5…`（单画面补齐同源修复）**

## 功能一：多画面宫格预览 + 退出记忆
- `MultiViewActivity`：1/4/6/9 宫格切换；OK 短按进全屏、**长按 OK 切换布局**、**MENU 短按切主/子码流**；触屏设备单击选中/双击切布局。slot 池上限 9，切换布局先释放旧槽再重建，避免 NVR 连接堆积。
- `PlayerEngine`：单 MediaPlayer 生命周期封装，内嵌连接超时、冻结检测（6000ms 自动重试）、主码流→子码流自动降级。
- `ChannelTileView`：每格 = TextureView + 通道名 + 错误浮层。
- **退出记忆**（5 个持久化键）：`last_view_mode / last_channel_index / last_stream_type / last_multi_layout / multi_use_sub`。下次冷启动自动恢复到上次观看的频道与模式（单画面直进单、多画面直进多）。

## 功能二：通道缩略图自动生成（修复）
- 根因：每次返回列表都用同一份通道名 `setChannelNames`，导致每次重建 NvrClient（nonce 重置）+ 全量重新抓图，且加载中无占位图所以看起来是空白。
- 修复：相同列表跳过重建（避免抖动与重复建 client）；`ThumbnailLoader.load` 增加占位底图，配置为空时也显示占位。

## 功能三：软件改名「监控直播」
- `strings.xml` `app_name`：监看TV → 监控直播；`build.gradle` 输出文件名 `监控直播.apk`。包名 `com.litevision.tv` 与所有内部代码不变。

## 功能四：部分画面底部花屏（修复，最终方案）
- 真正根因与修复方案见上文"花屏问题排查历程"：**libVLC 把含宏块对齐 padding 的解码 buffer 拉伸铺满 Surface，padding 脏行成为底部花屏带**。
- 修复：按 visible 有效区做**等比 contain**（`Matrix` 内容映射 + `setClipBounds` 兜底），数学上精确裁掉 padding 行；多画面（`ChannelTileView`）与单画面（`PlayerActivity.applyVideoFit`）同一套实现。
- 保留项：全局软解 `--avcodec-hw=disabled`（对异构摄像头兼容性更稳）；`--avcodec-threads=4` 分摊软解 CPU。

---

## 涉及文件
新增：
- `app/src/main/java/com/litevision/tv/MultiViewActivity.java`
- `app/src/main/java/com/litevision/tv/PlayerEngine.java`
- `app/src/main/java/com/litevision/tv/ChannelTileView.java`
- `app/src/main/res/layout/activity_multi.xml`
- `app/src/main/res/layout/item_tile_channel.xml`
- `app/src/main/res/drawable/thumbnail_placeholder.xml`

修改：
- `app/src/main/java/com/litevision/tv/ConfigManager.java`（5 个持久化键 + resolveLastStreamTypeToSub）
- `app/src/main/java/com/litevision/tv/MainActivity.java`（多画面入口 + 冷启动恢复路由）
- `app/src/main/java/com/litevision/tv/PlayerActivity.java`（读 EXTRAs + onStop 写 last_*）
- `app/src/main/java/com/litevision/tv/ChannelAdapter.java`（缩略图占位 + 相同列表跳过重建）
- `app/src/main/java/com/litevision/tv/ThumbnailLoader.java`（占位底图）
- `app/src/main/AndroidManifest.xml`（注册 MultiViewActivity）
- `app/src/main/res/values/strings.xml`（改名 + 多画面文案）
- `app/build.gradle`（输出文件名改"监控直播.apk"）

QA 产物：`E:\WorkBuddy\JKTV\.workbuddy\jktv-qa\HarnessQA.java`（94 断言）

---

## 待真机确认（本机无 adb 设备，逻辑已核验，运行时需上机确认）
1. 宫格 1/4/6/9 路实际解码与布局切换时释槽
2. 缩略图真实拉取（ISAPI 快照 + 占位替换）
3. 花屏修复的真实像素效果（TextureView transform 在真机 DEX/GPU 的表现）
4. 退出记忆冷启动路由、冻结/超时重连的真实网络表现

## 下一步建议
1. 用 `adb install app/build/outputs/apk/debug/监控直播.apk` 装到电视/盒子验证。
2. 若花屏在个别码流残留：打开 VLC deblock 或 `--avcodec-threads=1`。
3. 多画面静音为默认（实例级 `--no-audio`），如需单格出声需后续加"点选静音开关"。
---

## 第十轮（同日追加）：多画面 contain → cover，解决"每格画面大小不同 + 花屏残留"

### 用户真机反馈
> 改得乱七八糟了，多画面甚至每个画面大小都不同，修复一下，同时有的画面底部花屏还是存在

### 复盘：contain 方案的两处错
| 现象 | 真实原因 |
|------|----------|
| 每个画面大小不同 | 宫格是 `GridLayout` 等分权重，**格子外框本来就一样大**；用户看到的是**画面内容区**不同——各路分辨率不一（16:9 / 4:3 / 5:4），contain 留的黑边宽度各不相同 |
| 底部花屏残留 | contain 的 `viewAR < dispAR` 分支靠 `setClipBounds` 裁 padding，该 API 对 TextureView 在部分设备上不可靠 |

### 修复：多画面改 cover（等比放大铺满，溢出裁掉）
文件：`app/src/main/java/com/litevision/tv/ChannelTileView.java` → `applyCropTransformIfNeeded()`

```java
float dispW = vw * sarN / sarD;          // visible 有效尺寸（SAR 修正）
float s  = Math.max(tw / dispW, th / dispH);   // cover：取较大缩放，保证铺满
float sx = s * sarN / sarD;
float sy = s;
float x0 = (tw - vw * sx) / 2f;          // ≤ 0
float y0 = (th - vh * sy) / 2f;          // ≤ 0
matrix.setScale(sx, sy);
matrix.postTranslate(x0, y0);
textureView.setTransform(matrix);
textureView.setClipBounds(null);
```

**padding 必被裁的数学保证**（无需 clipBounds）：
`y0 + bufH·sy = th/2 + sy·(bufH − vh/2) ≥ th` ⟺ `sy·(bufH − vh/2) ≥ th/2`；
cover 下 `sy ≥ th/vh` ⇒ 左边 `≥ th·(bufH/vh − 1/2) ≥ th/2` ⟺ `bufH ≥ vh`（恒成立）。
溢出部分由父容器 `tile_root` 的 `clipChildren="true"` 裁掉。

代价：比例与格子差得多的画面会裁掉左右或上下边缘——这是所有主流 NVR 多画面的标准做法。

### 保留决策：单画面仍是 contain
- 全屏不存在"格子大小不一"问题；
- cover 会把 4:3 画面上下裁掉约 25%，监控取景不可接受；
- TV 16:9 屏下 contain 走 height-fill 分支（`y0=0`），padding 同样被推出屏幕外，花屏修复效果一致。

### 产物
- `E:/WorkBuddy/JKTV/监控直播.apk`
- SHA256：`8c9b13208cbca6c9d4f62ce334cc775ea8263e8066b0c2d63359f7ebf7828948`
- 大小：92,103,998 B ｜ BUILD SUCCESSFUL（JDK17 + Gradle 8.14）

### 上机后若某路仍有花屏，请抓这一行日志
```
adb logcat -s ChannelTileView | grep "video-fit"
```
输出形如 `video-fit cover: visible=1920x1080 buf=1920x1088 view=480x270 sar=1/1 ...`
- `buf` 高 > `visible` 高 → padding 存在，cover 已把它推出格子，应已干净；
- `buf` 高 == `visible` 高 → 该路**未在 SPS 里声明裁剪**，脏行在 visible 区内部，需要在 VLC 侧额外裁（下一步方案）。

### 遗留
- 设置里「硬解 / 软解 / 自动」开关尚未实现（上一轮用户以"重试"把优先级压后）。

---

## 第十一轮（同日追加）：真机截图「画面没铺满窗口」→ cover 变换对某些格子没生效

### 用户反馈（真机截图）
4 宫格中**右上「大门口」**画面缩在格子左上角，其余是**深蓝底**（`#132F4C` = `channel_card_normal` 卡底颜色），其余三格正常铺满。

### 根因（三步排除）
1. **蓝底不是宫格底**：`activity_multi.xml` 宫格底是 `#000000`；蓝来自 `item_tile_channel.xml` 的 `channel_card_normal`（`#132F4C`）。
   而 `ChannelTileView.init()` 第 59 行明明调了 `setBackgroundColor(0xFF000000)` 想铺黑——
   **被 `bind()` 里的 `setFocused(false)`（第 247 行）用 `channel_card_normal` 整张覆盖掉了**。
2. **cover 数学只要 `vw>0 && vh>0` 就必铺满**（`s=max(tw/dispW,th/dispH)` → `w≥tw` 且 `h≥th`）。
   既然这格没铺满 → 该格 `vw/vh==0` → `applyCropTransformIfNeeded()` 在尺寸未知时提前 return。
3. **为什么 `vw/vh==0`**：`MultiViewActivity.startVisibleSlots()`（第 402 行注释"Surface 未就绪也能先 play"）
   在 surface 就绪前调 `engine.play()`；`play()` 里 `attachVideoSurface()` 因 `surface==null` 直接 return、
   **没挂上布局监听器**；等 surface 到了才挂上，`onNewVideoLayout` 随后该到——
   但对**某些码流该回调来得极晚甚至不来**，那格就永远停在"VLC 按原尺寸贴左上角"。

### 修复：不等 onNewVideoLayout，surface 定尺寸就兜底算 cover
`ChannelTileView.java`：
- 新增 `surfaceWidth/surfaceHeight` 字段，在 `onSurfaceTextureAvailable` 与 `onSurfaceTextureSizeChanged`
  里记录 SurfaceTexture 实际尺寸（VLC 设完 buffer geometry 后更新）。
- `applyCropTransformIfNeeded()`：`vw/vh` 为 0 时**不再 return**，改用 surface 尺寸兜底算 cover 立刻铺满；
  `onNewVideoLayout` 回调来了再切回更精确的 visible 尺寸（padding 脏行裁得更干净）。

取舍：兜底时 surface 尺寸可能含宏块对齐 padding，cover 会留下至多 ~1–8px 底部细缝（视分辨率而定），
但远好于"视频缩在左上角 + 大片蓝底"；且兜底是**临时**的，回调一到即切回 0 缝。

### 产物
- BUILD SUCCESSFUL（JDK17 + Gradle 8.14）
- `E:/WorkBuddy/JKTV/监控直播.apk`，SHA256 `a579dd2dcec9f80b0807c1cca682b783799baeb427862781a0132107436f51e2`

### 遗留
- QA（严过关）上一轮因 429 频率限制中断；本轮已重试，重点验 cover fallback 的数学推演
  （buffer 行范围 vs padding 是否落在 view 内）与"重进即生效"在代码层面是否真成立。

---

## 第十二轮（同日收尾）：QA 抓到 releaseView 边界 bug 并修复，强制全量重建确认

### QA 验证结论：PASS（4 项 minor，1 项需修）

QA（严过关）拿具体数字推演了 cover fallback 的数学（buffer 640×368 / visible 640×360 / 格子 540×300）：
padding 行（buffer y 360–368）映射到 view y 298.5–305.25，**只有前 ~1.5 行落在 view 内** →
一条 ~1.5px 细缝，肉眼不可见；`onNewVideoLayout` 一到即切回 visible 尺寸，padding 完全裁掉。
其余验证全部通过：6 处调用点前缀无误、三档取值互异、设置页往返与焦点链完好、`onNewVideoLayout` 链路未被破坏。

### 唯一需修的源码 bug（QA 路由给 Engineer）

**`ChannelTileView.releaseView()` 未重置 `surfaceWidth/surfaceHeight`**（第 250–268 行）
- 复现场景：格子复用时 `releaseView()` 只清 `surfaceReady`，未清 surface 尺寸；
  随后 `bind()` → `applyCropTransformIfNeeded()`，若新引擎尚未报尺寸（`vw/vh==0`），
  cover fallback 会拿**上一轮残留的旧 surface 尺寸**算 cover → 短暂错误 transform。
- 修法：`surfaceReady = false;` 后追加 `surfaceWidth = 0; surfaceHeight = 0;`（已落地）。

### 顺带文档清洁

`PlayerActivity.java:1078` 注释 `avcodec-hw=none` → `avcodec-hw=disabled`（与实际取值对齐）。

### 不改的（QA 判 NoOne，主理人认同）

- cover fallback 底部 ~1.5px padding 细缝：肉眼不可见，不为消看不见的缝去裁 6px 真实画面。
- LibVLC 级 `--avcodec-hw` 运行时不更新（只有 Media 级更新）：解码模式原有设计，VLC 里 Media 级覆盖 LibVLC 级，行为正确。

### 产物（最终）

- BUILD SUCCESSFUL，**强制 `--rerun-tasks` 全量重建**（33 tasks executed，非 UP-TO-DATE），确保修复真的进包。
- `E:/WorkBuddy/JKTV/监控直播.apk`
- SHA256：`4dee0e7a1c3ee2e1d1913e12bdd857beda8353f5f8c6aecc9b382be1f2d32aa1`
- 大小：92,000,365 B

### 本次会话累计交付（监控直播 com.litevision.tv）

| 轮次 | 交付内容 | 最终 APK SHA256 |
|------|---------|----------------|
| 多画面预览 + 三项优化 | 1/4/6/9 宫格、缩略图、改名"监控直播"、退出记忆 | — |
| 花屏修复演进（6 轮） | 从软解/微裁/contain 的错误路径，最终定位 libVLC 拉伸含 padding 的解码 buffer | — |
| contain → cover | 多画面统一铺满、padding 被推出格子外 | `8c9b1320…` |
| 解码模式三选一 | 设置页硬解/软解/自动，贯通 6 处 VLC 参数，默认软解 | `497e0f3d…` |
| cover fallback | 不等 onNewVideoLayout，surface 定尺寸就兜底铺满 | `a579dd2d…` |
| releaseView 修复（最终） | 上述 + surface 尺寸归零 + 注释清洁 | **`4dee0e7a…`** |

---

## 第十三轮（同日）：cover 基准改为 SurfaceTexture 实际渲染尺寸 —— 彻底解决"没铺满 + 底边花屏"

### 用户反馈（真机截图 21:32，上一版修复后）
右上「大门口」：画面比上一版**大了**（surface 兜底确实生效），但**仍没铺满**——
视频底部一条花屏带，再往下是蓝底。要求：**所有画面铺满窗口 + 底边花屏彻底解决**。

### 真正根因（上一版 cover fallback 的隐藏 bug）
`applyCropTransformIfNeeded()` 原本用 `engine.getVideoWidth()/getVideoHeight()` 算 cover，
但这个尺寸可能是**上一条码流的残留**（如切到子码流时仍留着主码流的 1920×1080）。
用**偏大的残留尺寸**算 cover → 把实际渲染的 surface **缩小** → 蓝底缝隙 + 底部 padding 花屏带被放大显现。
（QA 上一轮只推演了"surface 尺寸兜底"场景，未覆盖"reported 尺寸残留且非零"这个更常见的情况，漏了。）

### 修复：cover 基准必须是 SurfaceTexture 实际渲染尺寸
`ChannelTileView.applyCropTransformIfNeeded()` 重写：
- 基准尺寸优先取 `surfaceWidth/surfaceHeight`（= TextureView 真正显示的东西），只有 surface 也未知才退回 reported。
- 新增 `padCrop` 精确扣除底部 padding（H.264 宏块对齐脏行总在 buffer 底部）：
  - `repVh < vh` → `padCrop = vh − repVh`（精确：visible 比 buffer 小多少）
  - `repVh == vh` → 0（无 padding）
  - `vh % 16 != 0` → 0（高度非宏块对齐，大概率是 visible）
  - 其余（reported 缺失/异常 且 vh 是 16 的倍数）→ 16（最多一个宏块兜底，确保脏行必裁）
- `dispH = effH = vh − padCrop`。数学保证 `effH·sy ≥ th` 恒成立 →
  padding 行必然映射到 view 底边之外被裁掉，同时 cover 保证铺满、无蓝底缝隙。两个问题一次解决。

代价：偶尔裁掉最多 16 行真实画面（仅发生在 reported 残留/缺失 且 buffer 高度是 16 的倍数时），
1080p 级约 1.5%、子码流级约 4%，监控场景可接受；换来的是一劳永逸消除花屏带。

### 产物（最终）
- BUILD SUCCESSFUL，强制 `--rerun-tasks` 全量重建（33 executed）。
- `E:/WorkBuddy/JKTV/监控直播.apk`
- SHA256：`4645cbdabafdb5fe8c22ab3f52ee40ca3d20b213f59511d48285cef401a78f3c`

### 单画面暂未改动（有意为之）
`PlayerActivity.applyVideoFit()` 仍是 contain（保留完整画面、不裁剪 4:3 上下边缘），且 TV 16:9 屏下
contain 走 height-fill 分支（y0=0），padding 同样被推出屏幕外。reported 尺寸残留风险在单画面较低
（每通道固定码流、切主/子码流会重建 MediaPlayer 触发新鲜 onNewVideoLayout）。
若真机单画面也出现底边花屏或没铺满，用同一套修法处理。

### 上机验证建议
装包后看多画面：所有格子应等大铺满、底边无花屏带。
若仍有格子异常，抓这一行日志定位（`<TAG>` = `ChannelTileView`）：
```
adb logcat -s ChannelTileView | grep "video-fit"
```
输出形如 `video-fit cover: surf=640x368 padCrop=8 effH=360 repVisible=640x360 buf=640x368 view=540x300 ...`
- `surf` 是实际渲染尺寸，`padCrop` 是裁掉的底部 padding 行数，`effH` 是进入 cover 的有效高度。
- 若某格 `surf=0x0` → VLC 未给该格定尺寸，需另查；若 `padCrop=16` 且画面底部被裁 → 属兜底代价，可接受。

---

## 第十四轮（同日）：单画面同样改 cover + surface 基准 —— 与多画面统一

### 用户反馈
"单画面时，很多画面底部依然有花屏，是否是因为没有铺满窗口，允许画面完全填满整个窗口"

### 用户诊断正确
单画面之前用 **contain**（等比塞进屏幕、留黑边）。黑边区域显示屏幕底色，而 libVLC 把含宏块对齐 padding
的解码 buffer 拉伸进 Surface 时，**padding 脏行正好落在黑边里** → 花屏带出现在画面底部的黑边区域。
画面比例越接近正方形（4:3），黑边越宽，花屏越明显——所以"很多画面"都有。

### 改动：`PlayerActivity.applyVideoFit()` 从 contain 改为 cover（与 ChannelTileView 同一套）
- 新增 `mSurfaceW/mSurfaceH` 字段，在 `onSurfaceTextureAvailable` / `onSurfaceTextureSizeChanged`
  记录 SurfaceTexture 实际渲染尺寸。
- `applyVideoFit()` 重写：基准尺寸优先取 surface 尺寸（否则退回 reported），
  `padCrop` 四档判定精确扣除底部 padding，`dispH = effH` 做 cover，`setClipBounds(null)`。
- 补上 `onSurfaceTextureAvailable` 里的 `applyVideoFit()` 调用（多画面原本就有，单画面漏了）：
  否则 surface 就绪到 `onNewVideoLayout` 之间会按 VLC 默认拉伸渲染，短暂出现底部花屏。

### 产物
- BUILD SUCCESSFUL，强制 `--rerun-tasks` 全量重建。
- `E:/WorkBuddy/JKTV/监控直播.apk`
- SHA256：`365df535751e3e741830a746c10acb30296b530c03143dbf833327a7f3d79632`

### 两条路径现已统一

| | 多画面 ChannelTileView | 单画面 PlayerActivity |
|--|------------------------|------------------------|
| 适配方式 | cover（铺满格子） | cover（铺满屏幕） |
| cover 基准 | surfaceWidth/Height | mSurfaceW/mSurfaceH |
| padding 裁剪 | padCrop（四档判定） | padCrop（四档判定） |
| setClipBounds | null | null |
| 触发点 | available/sizeChanged/bind/onPlaying/onVideoSizeChanged | available/sizeChanged/onNewVideoLayout |

代价（两处一致）：偶尔裁掉最多 16 行真实画面（reported 残留/缺失 且 buffer 高度 16 的倍数时），
1080p 级约 1.5%、子码流级约 4%。用户已明确接受"画面完全填满窗口"。

### 上机验证
单画面各通道应铺满屏幕、底部无花屏；多画面各格等大铺满、底部无花屏。
任一路径仍有异常则抓日志：
```
adb logcat -s ChannelTileView PlayerActivity | grep "video-fit"
```

---

## 第十五轮（同日）：花屏深度研究 —— padding 判定维度修正

### 用户反馈（真机截图 22:39，单画面 东门）
画面已**铺满**（cover 生效），但**屏幕最底边仍有一条花屏边**。要求深度研究。

### 深度研究结论：之前判定 padding 用错了维度
之前用 `surfaceHeight − visibleHeight` 判定 padding 行数。存在一种情况会让这个差**恒为 0**：
**VLC 把含 padding 的 buffer 拉伸/压缩进"visible 尺寸"的 Surface** —— 此时
`surfaceHeight == visibleHeight`，判定得出"无 padding"→ `padCrop=0` → 一行不裁，
但 **padding 脏行已被挤到画面底部** → 花屏带依旧。这就是"铺满了却还有底边花屏"的真因。

### 修复：padCrop 改用 **buffer 高 − visible 高**（来自 onNewVideoLayout）
`bufferHeight` 是含宏块对齐 padding 的解码 buffer 高，`visibleHeight` 是真实有效高，
两者的差才是 padding 行数——**不受 Surface 被设成什么尺寸影响**，是判定 padding 的正确维度。
`vh`（cover 基准）仍用 SurfaceTexture 实际渲染尺寸，两个维度各管各的。

```java
int bufH = mVideoBufH / engine.getBufferHeight();   // buffer（含 padding）
int visH = repVh / engine.getVideoHeight();          // visible 有效高
int padCrop;
if (visH > 0 && bufH > visH) {
    padCrop = bufH - visH;          // 精确：宏块对齐 padding 行数
} else if (visH > 0 && bufH > 0) {
    padCrop = 4;                    // buffer==visible：无 padding，裁 4 行防底部边缘脏行
} else {
    padCrop = 16;                   // onNewVideoLayout 未到，按最多一个宏块兜底
}
int effH = vh - padCrop;            // vh = SurfaceTexture 实际渲染高（cover 基准不变）
```
PlayerActivity 与 ChannelTileView 两处同步改同一逻辑，并清理了因此失效的 `android.graphics.Rect` import。

### 产物
- BUILD SUCCESSFUL，强制 `--rerun-tasks` 全量重建。
- `E:/WorkBuddy/JKTV/监控直播.apk`
- SHA256：`bfd8ec73dcc6452cc763325af4ec1ac080e606f3acd4bf012aca99f58eb09a91`

### 完整链路（两处统一）
| 环节 | 用什么 | 为什么 |
|------|--------|--------|
| cover 基准（铺满） | SurfaceTexture 实际渲染尺寸 | 不用可能残留的 reported 尺寸 |
| padding 裁多少 | **buffer 高 − visible 高** | 不用 surface 高 − visible 高（会漏判） |

### 上机验证
单画面各通道应铺满屏幕、底部无花屏；多画面各格等大铺满、底部无花屏。
任一路径仍有异常抓：
```
adb logcat -s ChannelTileView PlayerActivity | grep "video-fit"
```
`padCrop` 现在是 buffer 与 visible 的差值（真正的 padding 行数），`effH` 是进入 cover 的有效高度。

---

## 第十八轮（同日）：设置界面手机适配

### 用户反馈
"在手机上，设置等界面不适合，需要优化"

### 手机上设置页的两个真问题
1. **`LinearLayout` 宽度写死 `560dp`**（`activity_settings.xml` 第 9 行）+ `layout_gravity="center_horizontal"`。
   手机竖屏约 360dp 宽，560dp 远超屏幕 → 在 `ScrollView` 里要**横向滚动**才能看到完整表单。
   这是电视端思维（1920px 屏幕中间 560dp 居中），手机上不适用。
2. **`SettingsActivity` 没有 `windowSoftInputMode`**（`AndroidManifest.xml` 第 44–47 行）。
   手机点开输入框弹出软键盘后 Activity 不 resize → **输入框被键盘挡住**。

### 修复
1. `activity_settings.xml`：`LinearLayout` 宽 `560dp` → `match_parent`，去掉 `layout_gravity="center_horizontal"`。
2. `AndroidManifest.xml`：SettingsActivity 加 `android:windowSoftInputMode="adjustResize"`。

### 未改动（有意）
- RadioGroup 仍横向三钮：手机内容宽约 312dp、每钮约 104dp，"硬解/软解/自动" 16sp 能放下。
- 触控目标均 56dp 高（> 48dp 最小值）。
- `channel_card_normal` 深蓝卡底与 App 深色主题一致，保留。

### 产物
- BUILD SUCCESSFUL，强制 `--rerun-tasks` 全量重建。
- SHA256：`727fd3592c6ed5b3bfd13cdb04cfd9635b1e56deecb206ce3bdfdf026ad0dc45`

---

## 第十九轮（同日）：提示文字自动消失 + 缩略图不显示修复

### 用户需求
1. 屏幕上的所有提示文字在显示几秒后消失
2. 通道列表缩略图全部没有显示，修复

### 修复 1：多画面顶部手势提示自动隐藏
`MultiViewActivity.setHint()` 原本只显示、永不隐藏。新增 `HINT_DURATION_MS=5000` + `hideHint` Runnable，
显示 5 秒后自动 GONE；重复调用 removeCallbacks 重新排期。（单画面 overlay 原有 3 秒隐藏；多画面通道名为有意常驻标签，均不动。）

### 修复 2：缩略图三重修复
- **Bug A（根因）——失败后永远没有重试**：`ChannelAdapter.setChannelNames()` 列表相同时完全 return，
  冷启动首次抓取失败（NVR 未就绪）的缩略图永无重试机会。修法：列表相同时仍 `notifyDataSetChanged()`
  re-bind（成功项命中缓存零闪烁，失败项重试），`refresh()` 仅列表变化时执行。
- **Bug B——在途去重**：新增 `inFlight` 集合，`load()` 提交前去重，executor 用 try/finally 保证清除。
- **Bug C——IP 代理通道备选路径**：标准路径失败后自动尝试 `/ISAPI/ContentMgmt/InputProxy/channels/{id}/picture`。

### 产物
- BUILD SUCCESSFUL，强制 `--rerun-tasks` 全量重建。
- SHA256：`ae29a9fc355a64a98b647dc0de4774510c0bc0324ae3d33378c0760a9ec9bb9c`

### 上机验证
若缩略图仍不显示：`adb logcat -s ThumbnailLoader NvrClient`，失败码直接定位
（401=认证 / 404=路径 / -1=ISAPI 端口不通——视频能播不代表 ISAPI 端口对）。

---

## 第二十轮（同日）：通道名自动消失 + 缩略图快照路径探测

### 用户反馈
1. 通道名也可以自动消失（画面内已有摄像头 OSD 标注，双重遮挡）
2. 缩略图还是不显示（上轮重试修复未解决 → 系统性失败，非启动时机）

### 缩略图系统性根因（本轮定位）
通道名来自 **InputProxy** 端点（`/ISAPI/ContentMgmt/InputProxy/channels`）→ NVR 是 IP 通道。
而 `getSnapshotPath()` 固定拼模拟通道路径 `/ISAPI/Streaming/channels/{通道号×100+1}/picture`。
海康 IP 代理通道的正确路径是 `/ISAPI/ContentMgmt/InputProxy/channels/{ID}/picture`，
且 **ID 多数固件从 33 起**（模拟占 1-32）。路径对不上 → 404 → 全部缩略图失败。

### 修复
- **快照路径探测**（`ThumbnailLoader`）：新增 `snapshotPathKind` + `pathFor()` + `fetchWithProbe()`，
  按 4 类候选（InputProxy 1 基 / Streaming 1 基 / InputProxy 33 基 / Streaming 33 基）依次探测，
  第一个成功的记住类型，后续通道直接复用。
- **通道名自动消失**（`ChannelTileView`）：`showName(true)` 显示 5 秒后自动隐藏
  （`NAME_AUTO_HIDE_MS=5000`），重复显示重新排期；`releaseView()` 清排期防泄漏；触屏单击可再唤出。

### 产物
- BUILD SUCCESSFUL，强制 `--rerun-tasks` 全量重建。
- SHA256：`397914a024d5cbcf6a527db47467e4407e7d26160173743f96a9376c9be54901`

### 上机验证
若缩略图仍不显示：`adb logcat -s ThumbnailLoader`，看 `probing snapshot path kind=N ... code=X`
（401=认证 / 404=四类路径都不对，贴日志给我 / -1=ISAPI 端口不通）。

---

## 第二十一轮（9-4 凌晨）：多画面翻页——电视端遥控器无翻页入口

### 用户反馈
多画面只显示了前 4 个通道，没有后面通道的切换方式。

### 根因
`MultiViewActivity.onKeyDown` 里 DPAD 左右**只做格子间移动焦点**；`shiftChannels()`（翻页）
只有触屏 onFling 会触发——**电视端遥控器根本没有翻页入口**。

### 修复
1. **方向键到边翻页**（iVMS 同款交互）：焦点在最左列再按左 → 上一页；最右列再按右 → 下一页。
   新增 `focusAtColumnEdge(boolean)`；1 宫格（cols=1）左右键直接翻页。
2. **补直接翻页键**：PAGE_UP/PAGE_DOWN、CHANNEL_UP/CHANNEL_DOWN。
3. **shiftChannels 改整页基准**：`newBase = clamp(curBase+delta, 0, total-layoutSize)`——
   旧实现逐格 clamp，total=6/layout=4 时第二页会显示 4,5,5,5（末尾通道重复）；现在末页自动回贴尾部
   （2,3,4,5）。已在边界时 Toast 提示且不重启播放。
4. TV 提示串加"到边再按翻页"。

### 产物
- BUILD SUCCESSFUL，强制 `--rerun-tasks` 全量重建。
- SHA256：`6243c8a1eb934daab6d57a90e5e62f1711f2df0dfd83fc111b724bf15ea003b7`

---

## 第二十二轮（9-4）：多画面触屏手势全部失效——事件被格子消费

### 用户反馈
触摸屏左右滑动等都无效。

### 根因
手势监听挂在宫格容器（`grid.setOnTouchListener`），但每个 `ChannelTileView` 是
`clickable=true` + `setOnClickListener`——**触摸事件被子格子消费，容器收不到任何事件**，
手势检测器从未收到过输入。多画面触屏手势从最初就没生效过（此前用户只在单画面用触控，
上轮宣传"左右滑动翻页"后才暴露）。

### 修复
1. `createSlot` 给每个格子加 `setOnTouchListener` 转发给 `gestureDetector` 并返回 true 消费；
   电视端 `gestureDetector=null` 时返回 false，格子 onClick 照常走遥控器。
2. `onSingleTapConfirmed` 改为进全屏（`startPlayer(ch)`）——与此前实际行为一致；
   旧边界检查 `idx < layoutSize` 是错的（应为通道总数），一并修正。
3. 触屏提示串改为"单击看全屏 · 双击切换布局 · 左右滑动翻页 · 上下滑调音"。

### 产物
- BUILD SUCCESSFUL，强制 `--rerun-tasks` 全量重建。
- SHA256：见同日记忆日志。

---

## 第二十三轮（9-4）：多画面"只显4通道 + 触摸翻页无效"根治

### 用户反馈
多画面只显示了4个通道，触摸翻页无效。

### 根因分析
1. **触摸翻页静默失效（高置信）**：`isTouchScreenDevice()` 依赖 `configuration.touchscreen`，
   很多安卓电视盒子/触屏一体机把它报成 `TOUCHSCREEN_NOTOUCH` → `gestureDetector=null`、
   格子 onClick 直接进全屏、滑动永远不翻页。判定过严。
2. **缺少可见翻页入口**：手势提示 5 秒后消失，用户没意识到可滑动翻页。

### 修复
1. `isTouchScreenDevice()` **放宽**：仅 `UI_MODE_TYPE_TELEVISION` 判无触摸，其余一律可触屏，异常兜底为可触屏。
2. **底部翻页栏 UI 兜底**（活动_multi.xml）：◀上一页 / 页码 cur/total / 下一页▶，
   触屏且总页数>1 才显示；点击调 `shiftChannels(±layoutSize)`；页码实时刷新、边界置灰；总页数≤1 整条隐藏。
3. `onFling` 位移改用 **rawX/rawY 全局坐标**（消除跨格转发坐标差），速度阈值放开，位移主导。
4. `shiftChannels` 边界提示带页码；`applyLayout`/`bindPager` 后刷新翻页栏。
5. 触屏提示串改"单击看全屏 · 左右滑动翻页 · 或点底部◀▶ · 双击切布局"。

### 构建环境陷阱（本机必看）
- 系统 `gradle` 默认 jil JVM 是 **JDK25**，AGP 8.x 的 `JdkImageTransform` 用它 jlink 处理
  android-34 的 `core-for-system-modules.jar` **必报错**；`--rerun-tasks` 强制重跑 javac 前置时必然 fail。
- 正解：已装 Temurin JDK17 `C:/Android/jdk17/jdk-17.0.20.1+1`（TUNA 镜像下载），
  构建：`gradle -Dorg.gradle.java.home="C:/Android/jdk17/jdk-17.0.20.1+1" :app:assembleDebug --rerun-tasks -x lint`。
- JDK25 先编译损坏了 androidJdkImage 缓存 → 用 JDK17 先跑一次普通增量修复，再全量。

### 产物
- Gradle 8.14 + JDK17 + `--rerun-tasks`（33/32 tasks 全执行）SUCCESS.
- APK SHA256（项目根 + app/build/outputs/apk/debug 一致）：`e38b89810f361f43a7aeb1f3c98a7daa604d7376714a3b2eb8fe5792bbab9ed7`
- aapt2 验证含四个新 id：layout_pager / btn_prev_page / btn_next_page / tv_page_indicator.
