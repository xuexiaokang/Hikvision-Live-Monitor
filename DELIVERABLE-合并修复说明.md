# 「监看TV」修复交付说明（两轮合并版）

- 项目：`E:\WorkBuddy\JKTV`（Android TV 应用，包名 `com.litevision.tv`，minSdk 21 / targetSdk 33，libVLC 3.5.1，Java 17）
- 日期：2026-09-03
- 覆盖：**第一轮** RTSP 域名/端口修复 + **第二轮** 设备自适应/音频/触控
- 交付产物：`app/build/outputs/apk/debug/监看TV.apk`
  - SHA256：`09d5c112677aa141cda26bee17ea3e29e2c26f5eb31a5a649ce665e54d44505d`
  - 构建：JDK 17 + Gradle 8.14（`--rerun-tasks` 全量重编）→ **BUILD SUCCESSFUL**

---

## 第一轮：RTSP 域名/端口问题（用户报：连域名读不到数据、改端口无效）

### 三重根因
| # | 根因 | 现象 |
|---|------|------|
| 1 | ISAPI(HTTP) 端口硬编码 80（`NvrClient` 拼接 `"http://"+host`） | 域名走外网映射端口，打 80 必失败 → 通道列表空 |
| 2 | 改端口只喂 RTSP URL，对 ISAPI 无效 | 填端口改了没反应 |
| 3 | RTSP 凭据未 URL 编码（密码含 `@` 会拆坏 host/端口） | 域名 & 端口异常，密码带 `@` 必连不上 |

### 改动
- `NvrClient` 构造改 4 参 `(host, httpPort, user, pass)`；`formatAuthority()` 统一 host:port；`Response` 增 `url` 诊断字段
- `ConfigManager` 新增 `KEY_HTTP_PORT`(默认80) + `KEY_HTTP_PORT_FOLLOW_RTSP`(跟随开关) + `getEffectiveHttpPort()`；DNS 预解析；`normalizeHost`/`splitHostAndPort`；凭据最小安全编码（只编码 `@ / ? # % [ ] \` 空格控制符，中文保留）
- `SettingsActivity` 新增 ISAPI 端口输入框 + 跟随开关 + 全量校验（非法拒绝保存）；绑定原本失效的 `btn_cancel`
- `ThumbnailLoader` 3 处硬编码 80 改走配置端口；Digest 请求串行化防 401 锁账号
- `app/build.gradle`：`compileSdk 33→34`（本机仅 platform-34）；`local.properties`：`sdk.dir=C:\Android\Sdk`

### 验证
QA 独立 125 断言全 PASS（含 `@` 密码、IPv6、中文密码、越界端口、老兼容键、跟随开关）。

---

## 第二轮：设备自适应 + 音频连接失败 + 触屏（用户反馈：视频出但音频连接失败，优化手机/平板/车机）

### 根因
播放器原为**小米电视盒子**定制：双 MediaPlayer —— 视频走 LibVLC(`:no-audio`)，音频用 **Android MediaPlayer 播同一条 RTSP**。而 Android MediaPlayer 播 RTSP 在**手机/平板/车机**原生解复用器支持差 → `OnError` → 弹「音频连接失败」且无声。

### 改动（仅 `PlayerActivity.java`，未加依赖）
| 功能 | 实现 |
|------|------|
| 设备检测 `isTouchScreenDevice()` | `UI_MODE_TYPE_TELEVISION`→false（电视/盒子）；手机/CAR 等再判触摸屏 |
| 设备自适应 | 触屏设备**默认** `useCombinedMode=true`（LibVLC 单 MP 音视频同路，绕开 MediaPlayer 播 RTSP）；电视仍双 MP |
| 音频失败自动降级（兜底） | `audioFailCount>=2 && !combined` → 自动 `toggleCombinedMode()` |
| 触屏手势（仅触屏设备） | 单击显隐 overlay · 双击播放/暂停 · 左右滑切台 · 上下滑音量 |

### 验证
QA 独立 `--rerun-tasks` 全量重编 → BUILD SUCCESSFUL；逻辑 A/B/C 全 PASS（电视不误切、combined 不启动 MediaPlayer、触控仅触屏、降级防重复）。

---

## 真机验证清单（唯一剩余项，必做）
| 设备 | 验证点 |
|------|--------|
| 手机/平板/车机 | ①默认直接出声（LibVLC 单流）②画面稳定 ③触控：滑动换台、双击暂停/播放、上下滑音量 |
| 电视/盒子 | ④仍是双 MP：画面+声音正常，**未被误切 combined** ⑤长按菜单手动切 combined 正常重启 |

已知低风险（不影响发布）：
1. 双 MP 模式下触摸双击恢复时音频可能不同步（仅手动切到双 MP 出现）
2. combined 下若流无音轨，overlay 乐观显示「有声」（无法精确查询 VLC 音频活动）

---

## 遗留 / 后续可做
- ISAPI over HTTPS(443) 未支持（当前固定 `http://`），需 scheme 配置 + 自签名 TrustManager（安全敏感，另行确认）
- 无 Android 模拟器/真机 adb 设备环境，未实机冒烟 —— 真机验证不可绕

## 构建环境备忘（可复用）
- SDK `C:\Android\Sdk`（仅 android-34）；Gradle `C:\Android\gradle\8.14`；JDK17 本地路径 `C:\Users\XXK\.workbuddy\jdks\jdk-17.0.20.1+1`
- AGP 8.1.x 必须 **JDK 17** 构建（18/25 会 jlink/class-version 失败）
- 完整流程已沉淀为 skill `android-apk-headless-build`