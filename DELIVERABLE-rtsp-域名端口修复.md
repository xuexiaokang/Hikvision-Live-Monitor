# 「监看TV」RTSP 域名 / 端口 Bug 修复 — 交付汇报

- 日期：2026-09-03
- 项目：`E:\WorkBuddy\JKTV`（Android TV，包名 `com.litevision.tv`，minSdk 21 / targetSdk 33，libVLC 3.5.1）
- 结论：**已修复，独立验证 125 项断言全部通过（125 PASS / 0 FAIL）**

---

## 一、用户反馈症状
1. RTSP 连接**域名**时读不到数据（换 IP 正常）。
2. 设置页填**其它端口**似乎无效。

## 二、根因分析（三重根因）

| # | 根因 | 位置 | 如何导致症状 |
|---|------|------|--------------|
| 1 | **ISAPI(HTTP) 端口硬编码 80** | `NvrClient.get()`：`"http://"+host+path`，构造无端口参 | 域名走外网映射端口（8080/8888…）打 80 必失败 → 通道列表/缩略图为空。内网 IP 场景 HTTP 恰好是 80 所以正常 → 这就是"换 IP 行、换域名不行" |
| 2 | **改端口无效** | `KEY_RTSP_PORT` 只喂 `getRtspUrl()` | 与 #1 同因两表：用户改的端口对 ISAPI 链路完全无效 |
| 3 | **RTSP 凭据未 URL 编码** | `ConfigManager.getRtspUrl()` | 密码含 `@`（如 `hik@12345`）→ URL `rtsp://admin:hik@12345@host:554/...` 按第一个 `@` 切分，host 被解析成 `12345@host`、端口丢失 → 同时命中"域名不行 + 改端口也没用" |

## 三、修复内容（7 个文件）

| 文件 | 关键改动 |
|------|----------|
| `NvrClient.java` | 构造改 4 参 `(host, httpPort, user, pass)`；`formatAuthority()` 统一 host:port（IPv6 自动 `[]`，已带端口不重复追加）；`Response` 新增 `url` 诊断字段 |
| `ConfigManager.java` | 新增 `KEY_HTTP_PORT`（默认 80）+ `KEY_HTTP_PORT_FOLLOW_RTSP`（跟随开关）+ `getEffectiveHttpPort()`；DNS 预解析（`getConnectionHost()`/`awaitHostResolution()`/`startHostResolution()`）；`normalizeHost()`/`splitHostAndPort()`；`encodeUserInfo()` 最小安全编码；端口范围兜底 `clampPort()` |
| `SettingsActivity.java` | 新增 ISAPI 端口输入框 + 跟随开关；全量校验（非法拒绝保存并 Toast，不静默回落）；绑定原本失效的 `btn_cancel` |
| `ThumbnailLoader.java` | 3 处硬编码 80 改用配置端口；Digest 请求串行化（防 401 触发海康锁账号）；缓存键带端口；**缓存改 `ConcurrentHashMap`**（消除 UI 无锁读 / 后台加锁写不对称） |
| `PlayerActivity.java` | 诊断浮层/日志输出实际 RTSP 目标 host:port |
| `activity_settings.xml` | 新增控件 + 焦点链完整重排（Android TV 遥控器导航） |
| `strings.xml` | 新增文案 + 重写帮助说明 |

## 四、关键设计决策
- **端口拆两个**（用户确认）：RTSP 端口（默认 554）+ ISAPI 端口（默认 80，向后兼容老用户）。跟随开关默认 off，老用户升级后 ISAPI 仍走 80，**不会突然连不上**。
- **凭据最小安全编码**：只编码会破坏 URL 语法的 ASCII 字符（`@ / ? # % [ ] \ ` 空格 + 控制符），中文密码（≥0x80）原样保留以保 NVR 兼容性。
- **未给 VLC 加 `--ipv4`**：预解析成功已是 IPv4 无收益；域名仅 AAAA 时强制 IPv4 会打断纯 IPv6 连接，净回归。改为"解析失败回退原始域名"。
- **向后兼容**：老用户预存的 `KEY_RTSP_PORT` 语义不变，新增 `http_port` 默认 80。

## 五、验证方式（环境无 Android SDK）
- 方式：按行区间逐字抽取 6 个纯静态方法到独立测试文件，用 **JDK 25** 实跑（WDK 70 断言）覆盖：`formatAuthority` / `normalizeHost` / `splitHostAndPort` / `encodeUserInfo` / `clampPort` / `getEffectiveHttpPort` / RTSP URL 端到端 `java.net.URI` 解析。
- 结果：**Total=125 PASS=125 FAIL=0**。含边界：`@` 密码、`%` 防双重解码（`a%20` → `a%25`）、`中文密码`、IPv6/IPv6+端口、非法端口（0/99999/-1/65536）、老用户无 `http_port` 键、跟随开关 on/off。
- 修复期间 QA 因频率限制（429）中断，由 `服务方` 人工承接并修复两处测试用例 bug 后跑绿。
- 回归测试：`E:\WorkBuddy\JKTV\.workbuddy\jktv-qa\Harness.java`

## 六、遗留事项（需确认后再做）
1. **ISAPI over HTTPS（443）未支持**：现固定 `http://`。域名 + Nginx 反代 443 场景需新增 scheme 配置 + 自签名证书 TrustManager，属安全敏感改动，不在本次范围。
2. `ConfigManager.executor` 未 shutdown（既有问题，不影响功能）。

## 七、真机验证清单（按顺序，第 1 条即最短回路）
1. 填域名 + 外网映射端口 → 测试连接，诊断框格线第一行应为 `ISAPI 地址: http://域名:HTTP端口`。
2. 回归内网 IP（ISAPI 默认 80 不应受影响）。
3. 端口填 `0`/`99999` → 弹「无效」，且**不保存**。
4. 密码含 `@`（如 `hik@12345`）→ 播放应正常（修复前必败）。
5. 粘贴 `http://192.168.1.23:8080/ISAPI` → 应自动清洗 + 提取端口 8080 到 ISAPI。
6. 遥控器焦点：勾选跟随开关后 ISAPI 输入框禁用、下键可跳过。
7. 遥控器聚焦「取消」按确定 → 退出设置页（原无反应 `bug` 已修）。