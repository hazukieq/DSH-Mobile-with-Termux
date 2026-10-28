# 安全与审计指引

> English TL;DR — DSH Mobile is a local-first Android shell around the DeepSeek
> Harness agent engine. Nothing is uploaded by default: the engine, sessions and
> credentials live in the app's private directory, the Web UI listens on
> `127.0.0.1` only, and the only outbound traffic is the model API you configure
> (`api.deepseek.com`), GitHub for update checks, and the npm mirror when *you*
> install a plugin. It ships no telemetry, no ad SDK, no remote-config, no
> backend of ours. This document lists every permission, every exported
> component, every network target and every binary blob, so you can verify the
> claims yourself instead of trusting them — `scripts/audit-apk.sh <apk>` prints
> the evidence in one shot.

这份文档是写给**准备安装它、但不想只凭一句「开源所以安全」的人**。原则是：所有
结论都给出可自己复现的检查方法；我们不做无法验证的承诺。

- 想快速判断要不要装：看 [第 1 节](#1-一页结论) + [第 3 节权限表](#3-权限表能做什么有什么代价)。
- 想动手验证：跑 [第 7 节](#7-自己动手审计7-步)，或直接
  `scripts/audit-apk.sh app-release.apk`。
- 想把审计交给 AI 或外部审计员：[第 8 节](#8-审计-prompt可直接粘贴) 有一段可粘贴的 prompt。

---

## 1. 一页结论

它是什么：一个 Android APK，内部跑着完整的 DSH Agent 引擎（Node.js）+ 一个内嵌
Termux 运行时 + 无障碍自动化层。**没有我们的服务器**：模型请求由你的 API Key
直接发往 `api.deepseek.com`；会话、凭证、工作区都在本机。

默认数据流（不配置任何东西时）：

```
你的输入 ──> App 内 WebView（127.0.0.1:3080，token 在 URL 里）
                │
                └─> 引擎（127.0.0.1:3080 + 127.0.0.1:3083 桥，仅回环）
                        └─> api.deepseek.com（模型 API，用你的 Key）
```

- **入站**：只有 `127.0.0.1`。局域网访问 WebUI 是一个默认关闭的开关
  （设置 → 扩展 → 局域网访问 WebUI），打开后才有 `0.0.0.0:3080`。
- **出站**：默认只有模型 API。此外：检查更新访问 `github.com`；你在插件市场
  装插件时访问 `registry.npmmirror.com`（或你配的 registry）。
- **遥测/统计/广告/远程配置**：没有。可以按 [7.4](#74-验证它没有偷偷联网) 抓包自证。

不承诺的事：不承诺「绝对安全」。它给了 AI 无障碍与 shell 权限，这两样本身就是
高危能力 —— 风险取决于你让它做什么，以及你装了什么第三方插件。

---

## 2. 威胁模型

我们在设计上防的是：

| 威胁 | 对策 | 验证方式 |
| --- | --- | --- |
| 别的 App 调我们的桥执行命令 | 桥只监听回环 + 每次启动生成随机 token，请求必须带 `X-DSH-Token` | 见 [7.5](#75-验证桥-终端没有对外开放) |
| 局域网内别人打开你的 WebUI | 默认只听 `127.0.0.1`；开 LAN 时 `/lan` 会要求 token，且页面顶部有提示 | 关掉开关后 `ss -ltnp \| grep 3080` 只有 127.0.0.1 |
| 我们从服务端下发什么 | 没有服务端；更新检查只是读 GitHub Releases 的版本号，装不装由你点 | 全文搜 `http` 只剩模型 API/GitHub/npm |
| 供应链掉包 | 引擎层来自官方 npm 发布物的 tarball 闭包，每次 release 附 `engine-manifest.json`（含逐文件 sha256） | [7.6](#76-核对内置载荷) |

我们**不**防的是（请自己决定是否接受）：

- 你让 Agent 操作银行/支付类 App —— 我们只负责拉起 App，不代填、不校验结果
  （见 `docs/PAYMENT-LINKS.md`）。
- 第三方插件的代码。插件是 npm 包，装上就等于给它与引擎相同的权限。
- Root / Shizuku 模式。开了之后 AI 拿到的是 uid 0 或 adb 身份，等于把系统交给它。
- 无障碍服务能读到屏幕内容 —— 这是它的工作原理，不是缺陷。

---

## 3. 权限表（能做什么，有什么代价）

`scripts/audit-apk.sh` 会把这张表从 APK 里重新打一遍，对不上就是有问题。

| 权限 | 用途 | 不给会怎样 | 备注 |
| --- | --- | --- | --- |
| `INTERNET` | 调模型 API；检查更新；装插件 | 引擎不可用 | 唯一必需的联网权限 |
| `ACCESS_NETWORK_STATE` | 判断是否联网后再重试 | 提示「网络不可用」可能误报 | 不读内容 |
| `FOREGROUND_SERVICE` `FOREGROUND_SERVICE_SPECIAL_USE` | 引擎以常驻服务运行，切到后台不断 | 退到后台就被系统杀 | 通知栏会显示常驻通知（可隐藏通知，但服务仍在） |
| `POST_NOTIFICATIONS` | 长任务进度/引擎状态通知 | 只能在前台看进度 | 悬浮窗模式下窗口可见时不发常驻通知 |
| `WAKE_LOCK` | 长任务期间保持 CPU | 息屏可能中断 | 仅在任务执行期持有 |
| `SYSTEM_ALERT_WINDOW` | 进度悬浮窗（可选显示方式） | 只能用通知栏 | 悬浮窗内容是自己的进度条/状态 |
| `MANAGE_EXTERNAL_STORAGE` | 让 AI 读写 `/sdcard`（如 `Download/`、相册导出、读你指定的文件） | 只能访问 App 私有目录与 SAF 选中的目录 | 需要你手动去系统设置里授权（`所有文件访问`）|
| `READ_EXTERNAL_STORAGE` `WRITE_EXTERNAL_STORAGE` | targetSdk 28 下的等价旧权限 | 同上 | 新系统上仅作兼容声明 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请「后台保活」，避免长任务被杀 | 后台任务容易被厂商 ROM 杀 | 只是申请，你可以拒绝 |
| **无障碍服务**（不是普通权限） | 读屏（节点树 + 截图 OCR）与注入点击/滑动/输入 | 只能手动点，AI 无法操作界面 | 见 [第 4 节](#4-无障碍服务能看什么不能看什么) |
| **通知使用权**（可选） | 读通知内容来回答「谁给我发了消息」 | 相关能力不可用 | 默认不开，需在系统设置里单独开 |

没有任何一条是「上传数据」用的。若审计时发现 APK 申请的权限多于本表，直接当问题报。

---

## 4. 无障碍服务：能看什么，不能看什么

- **能看到**：当前前台窗口的节点树（文本、控件类型、位置、可点击性）、屏幕截图
  （用于 OCR/图标检测）、输入法与剪贴板仅在**你要求注入文本**时才写入。
- **不能看到**：后台 App 的内容、其它 App 的私有存储、密码输入框之外的「输入
  内容历史」—— 我们只读当前屏，不建历史库；截图默认不落盘（`shot --save` 才写）。
- **能注入**：点击、滑动、输入文本、返回/主页键。**不能**提权、不能绕过系统的
  高危操作确认（装 APK、改系统设置等仍需你自己点）。
- 拒绝方式：系统设置里关掉无障碍服务即可，AI 会退回「只能说话不能动手」。

细节与边界实验见 `docs/ACCESSIBILITY.md` / `docs/A11Y-SCOPE.md`。

---

## 5. 对外组件（exported）

只有下面 5 个 `exported=true`，其余全部 `false`。三个 Activity 都是**启动入口**
（桌面图标 / 通知点进来），拿到它等于「有人帮你点了一下图标」，不是跳过鉴权：

| 组件 | 类型 | 等价于 | 有校验吗 |
| --- | --- | --- | --- |
| `app.dsh.mobile.MainActivity` | Activity | 点桌面图标 | 无（本来就该能被桌面启动） |
| `app.dsh.mobile.term.TerminalActivity` | Activity | 点「终端」图标 | 无；进去是本 App 沙箱内的 shell，不是 root |
| `app.dsh.mobile.OnboardingActivity` | Activity | 首次配置向导 | 无 |
| `app.dsh.mobile.engine.DshDocumentsProvider` | Provider | 系统文件管理器里浏览 `dsh-home` | 系统级 `MANAGE_DOCUMENTS`（普通 App 拿不到） |
| `rikka.shizuku.ShizukuProvider` | Provider | Shizuku 授权通道 | `INTERACT_ACROSS_USERS_FULL`（系统级） |

桥（`127.0.0.1:3083`）与 WebUI（`127.0.0.1:3080`）**不是 Android 组件**，是引擎里的
HTTP 服务，只绑回环；桥要求 `X-DSH-Token`，token 每次启动随机生成、存在 App 私有
目录（`$PREFIX/.bridge-token`，即 `files/usr/.bridge-token`，0600），adb（非 root）读不到。

---

## 6. 数据落在哪 / 怎么清

| 路径（App 私有） | 内容 | 清除方式 |
| --- | --- | --- |
| `files/dsh-home/` | 会话、工作区注册表、插件、`profiles/`、日志 | 设置 → 关于 → 清除数据（或卸载） |
| `files/log/` | 引擎与 App 日志（滚动，默认保留最近若干 MB） | 同上 |
| `files/usr/`（`$PREFIX`） | Termux 运行时 + 引擎闭包（约 1.2 GB） | 卸载即清 |
| `/sdcard/Documents/dshdata/` | **共享存储**里你创建的工作区（默认位置） | 需要你自己删，卸载 App 不会删 |
| 系统凭据（API Key） | 存在 App 私有目录的配置文件里，明文（沙箱内） | 删除配置或清数据 |

没有任何数据被复制到共享存储或外部（除非你让 AI 导出文件，那是显式动作）。

---

## 7. 自己动手审计（7 步）

前置：`adb`、`python3`、Android SDK `build-tools`（提供 `aapt2`/`apksigner`）。

### 7.1 一条命令拿全部证据

```bash
scripts/audit-apk.sh /path/to/app-release.apk
```

输出六节：基本信息与 SHA-256、**签名证书指纹**、权限清单、组件与 `exported`、
内置载荷哈希（引擎层 / 插件载荷）、APK 内出现的所有 URL。最后给一张结论清单。

### 7.2 核对签名

```bash
apksigner verify --print-certs app-release.apk | grep SHA-256
```

本仓库发布产物的证书指纹应为：

```
Signer #1 certificate DN: CN=DSH Mobile, O=DSH, C=CN
Signer #1 certificate SHA-256 digest: 178dc39bdcf51b0e679ae1b1d52db0b5be7c538d5a688be20325414790d06401
```

指纹变了就说明不是同一个签名密钥（可能被重打包），**不要装**。

### 7.3 对照权限

```bash
aapt2 dump permissions app-release.apk
```

逐条对照本文第 3 节。多出来的 = 问题。

### 7.4 验证它没有偷偷联网

最直接的是抓包（手机连到你电脑的热点，`mitmproxy`/`tcpdump` 看目标域名），或：

```bash
adb shell "run-as com.termux sh -c 'ss -tnp 2>/dev/null | head'"   # Debug 包可 run-as
adb shell "ss -tnp | grep -E ':(3080|3083)'"                      # 看监听面
```

预期：监听只有 `127.0.0.1:3080`（WebUI）与 `127.0.0.1:3083`（桥）；出站只有你配置
的模型 API。若你把 API 换成了自建/第三方网关，出站自然是那个地址。

### 7.5 验证桥、终端没有对外开放

```bash
adb shell "curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3083/status"   # 无 token → 401
adb shell "curl -s -o /dev/null -w '%{http_code}\n' http://<手机局域网IP>:3080/"     # 默认应连不上
```

### 7.6 核对内置载荷

```bash
unzip -l app-release.apk | grep -E 'engine-dsh|bundled-plugins'
unzip -p app-release.apk assets/engine-dsh-aarch64.zip | sha256sum
```

与 Release 附件里的 `engine-manifest.json`（逐个文件 sha256）对比；本版实测值见
[9.1](#91-26249-实测值可复现)。

### 7.7 可复现构建（可选，耗时）

```bash
git clone https://github.com/hazukieq/DSH-Mobile-with-Termux && cd DSH-Mobile-with-Termux
git checkout v2.5.49
export ANDROID_HOME=$HOME/Android/Sdk
gradle -p . assembleRelease -Pabi=arm64-v8a      # JDK 17，需预置 runtime.zip / engine zip
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

我们**不**声称字节级可复现（AGP/timestamp/签名会引入差异）；可复现的是：同一套源码 +
同一份 engine-manifest，能装出一模一样的行为。

---

## 8. 审计 Prompt（可直接粘贴）

把下面整段（含 [ ] 占位）发给任何有工具权限的 AI 或审计员：

```text
你是 Android 应用安全审计员。目标：判断这个 APK 是否值得安装。不要相信仓库里的
安全声明，用证据核对它们；每条结论都必须附命令与输出片段。

被测对象：<APK 路径>   参考文档：<仓库>/docs/SECURITY-AUDIT.md

按以下顺序做，逐项给「通过 / 不通过 / 无法验证 + 原因」：

1. 完整性：sha256sum；apksigner verify --print-certs（DN 与 SHA-256 是否等于文档公布值）。
2. 权限面：aapt2 dump permissions，与该文档第 3 节逐条比对；列出多余/缺失项，
   并对每个危险权限（MANAGE_EXTERNAL_STORAGE、SYSTEM_ALERT_WINDOW、无障碍）说明
   若被滥用能做什么、是否可撤销。
3. 组件面：解析 AndroidManifest（aapt2 dump xmltree），列出所有 exported=true 的
   activity/service/receiver/provider，逐个判断：是否带 permission？是否等价于
   用户主动操作？有没有可被第三方 App 触发的敏感动作？
4. 网络面：从 APK 中提取所有 URL 与域名（assets 与 dex，strings 均可），按「默认会连 /
   用户动作才连 / 文档里没提」分类；对「默认会连」的逐个说明用途与可否关闭。
   若有设备：adb shell ss -tnp 看监听与出站，确认只有 127.0.0.1:3080/3083 在听。
5. 载荷面：列出 >1 MB 的 assets/.so/dex，说明来源（Termux 包 / npm 闭包 / 模型权重），
   与 engine-manifest.json 的 sha256 对齐；指出任何无法解释来源的二进制。
6. 数据面：静态搜 SharedPreferences/数据库/文件路径，列出落盘位置与是否明文存储
   凭证；判断卸载能否清干净、哪些残留在共享存储。
7. 能力越权：读 manifest 与代码，确认无障碍服务只做「读当前屏 + 注入操作」，没有
   键盘记录、后台截屏、剪贴板轮询、通讯录/短信读取等行为（有就报告）。
8. 结论：给出「我会不会装」的判断与三条最需要修复的问题；引用你实际跑出的命令输出。

禁止：只复述仓库文档、无输出就下结论、把「开源」当作安全证据。
```

---

## 9. 附：实测值

### 9.1 2.5.49 实测值（可复现）

在 `2.5.49`（versionCode 482）的 release APK 上跑 `scripts/audit-apk.sh` 得到：

| 项 | 值 |
| --- | --- |
| 体积 | 299,744,221 字节 |
| SHA-256 | `17b9ca6aeb95a593cc15df602d0143ba193cdce3c1131e84fcba2757564810c9` |
| 签名 | `CN=DSH Mobile, O=DSH, C=CN`；`178dc39b…d06401` |
| targetSdk / minSdk | 28 / 26 |
| ABI | arm64-v8a |
| `assets/engine-dsh-aarch64.zip` | 188,139,482 字节，sha256 `88a914f5b026f64a283528ad0a10ca6de512ab2b1b0c25b2d9aec516437314dc` |
| `assets/bundled-plugins.zip` | 5,399,721 字节，sha256 `d269d386ff8d2a73f254f1a87f8a80a317724ccad6eb98ec4b4861b2931ce2ad` |
| 权限数 | 11（见第 3 节） |
| exported 组件 | 5（3 个入口 Activity + 2 个系统级 Provider） |

### 9.2 已知取舍（不打算改）

- `targetSdkVersion = 28`：为了让内嵌 Termux 运行时、前台服务与 `$PREFIX` 布局按老
  行为工作。代价是部分新系统限制要靠运行时适配（如通知、存储）。这是有意的取舍，
  不是遗漏；如果你要 targetSdk 35 的版本，需要先重做运行时安装路径（见
  `docs/ENGINE-BUILD.md`）。
- `MANAGE_EXTERNAL_STORAGE`：AI 要读写你指定的共享目录（导出 PDF、读相册、处理下载
  的文件）。不开就只能玩沙箱内文件。宁可给用户一个明确开关，也不假装能两全。
- 无障碍 + shell：这是产品本身。它就是一个「能在你手机上动手的 Agent」，所以把它当
  「给一个实习生 root 权限」来对待 —— 这才是正确的安装心理模型。

---

## 10. 发现问题怎么报

- 用 GitHub Issue 的 `security` 模板（或标题注明 `[security]`），附：版本号、
  `scripts/audit-apk.sh` 输出、复现步骤。
- 涉及可利用漏洞的，请先发邮件/私信而不是公开 issue；确认后我会在 release notes
  里标注感谢。
- 我们不会因为「报告了问题」而删除 issue；安全问题优先于功能开发。

## 相关文档

- `docs/A11Y-SCOPE.md` —— 无障碍能看/不能看的实验与边界
- `docs/ACCESSIBILITY.md` —— 无障碍层实现与坐标语义
- `docs/PAYMENT-LINKS.md` —— 为什么不代填支付信息
- `docs/ENGINE-BUILD.md` —— 引擎层组成与自建流程（供应链可追溯）
- `docs/NOTIFICATIONS.md` —— 常驻通知与权限
