# AI_CONTEXT

## Current progress
- **悬浮窗仅作可选建议 + 系统设置页入口（2026-10-20 第六十七轮，v70）**：用户指示「让用户开悬浮窗可以建议，没授予就是 fallback」。调整：
  ① `/activity` 支持打开系统设置页：`overlay-settings`（ACTION_MANAGE_OVERLAY_PERMISSION + package uri）、`accessibility-settings`（ACTION_ACCESSIBILITY_SETTINGS）；这类请求用「本 App 是否离开前台」判定 `opened`（`requested_kind:"system-settings"`），回执仍带 handoff/verify:none；
  ② 文案改为「悬浮窗/无障碍只是**可选建议**，不是必需」：`opened:false` 时提示顺序改为「请用户切前台 → `--notify` 发通知 → 可建议授予悬浮窗/无障碍（只建议一次，不给就继续用 fallback，不纠缠）」；
  ③ `notifyIfBlocked` 的通知目标支持系统设置页（PendingIntent 指向 overlay 设置）；
  ④ 文档：AGENTS seed 67 明确「never nag or block on it」并给出 `native open overlay-settings|accessibility-settings`；`DSH-MOBILE.md` v12 同步；dotfiles VERSION 19 / doc marker v12。
  实测 vivo：seed v67、doc v12 部署、engine healthy。versionName 1.3.38 / versionCode 278。
- **BAL 后台启动检测 + 通知兜底（2026-10-20 第六十六轮，v69）**：用户报「从后台 shell 发出的启动请求可能被系统拒绝，返回成功但什么都没打开」。修复：
  ① `AppState` 记录 `lastResumeClass/lastResumeAt`（elapsedRealtime），提供 `resumedSince(t)`；
  ② `POST /activity` 不再只看 startActivity 是否抛异常：启动后最多轮询 1.2s 等「目标 Activity 真的 resume」→ 返回 `opened:true|false`；同时给出 `bal_exempt{foreground,overlay,accessibility}`、`background_blocked`（未打开且无豁免）、`notification_posted`；已在前台打开同一页时直接 `already_open:true`;
  ③ 新增 `notifyIfBlocked`：被拦时发一条可点击通知（channel dsh_open_page，PendingIntent 指向目标 Activity），返回 `notification_posted:true`，并提示让用户点通知；
  ④ `native open <page> --notify` 与 `dsh-mobile app open <page> --notify` 支持该开关；
  ⑤ 文档：AGENTS seed 66 新增「Background activity starts (BAL) - how to tell opened from silently refused」：opened:false + background_blocked 时不要重试/不要截图；按顺序：请用户切前台 → `--notify` 发通知 → 建议授予悬浮窗/无障碍（两者都是 BAL 豁免）；`/launch`、`/launch-intent` 打开别的应用无法确认，按 hand-off 处理；`DSH-MOBILE.md` v11 增 opened 字段与豁免说明；dotfiles VERSION 18 / doc marker v11。
  实测 vivo：seed v66、doc v11、15 gate 可执行、engine healthy。versionName 1.3.37 / versionCode 277。
- **即时动作回执统一（2026-10-20 第六十五轮，v68）**：用户要求「全面检查所有工具及指令，尤其模拟动作（点击/滚动等）即时性太强，也要改」。实现：
  ① `AgentBridge.decorateActionMeta(method,path,status,json)` 在响应出口统一注入语义（仅 POST + 200 + JSON 对象）：即时动作端点（/tap、/tap-id、/gesture、/key、/input、/scroll-find、/scroll-center、/scroll-edge、/vision/tap、/vision/drag、/pick、/overlay、/loop/run）加 `instant:true`、`verify:"none"`、`do_not:["screenshot_immediately","retry_immediately"]` + 提示「动作已派发，界面可能正在变化；要确认先等稳定（scr idle/cdp wait）再读一次，别立刻截图/重试」；打开页面（/launch、/launch-intent）加 `handoff:true` 同语义（/activity 自带更详细字段，不重复注入）；`POST /settings` 加 `verify:"none"`（设置已在进程内生效）。
  ② CDP helper 的 click/click-text/tap/type/key/scroll 输出统一带 `instant:true, verify:"none", do_not:[…]`。
  ③ 文档：AGENTS seed 65 新增「Instant actions (tap / scroll / type / key) - dispatched, not confirmed」：ok 即完成、不截图、要校验先 settle 再读一次、不要因界面没变化立刻重试（两次后问用户）、用户说改好了就信任；`DSH-MOBILE.md` v10 增对应小节；dotfiles VERSION 17 / doc marker v10。
  实测 vivo：seed v65、doc v10 部署、15 gate 可执行、engine healthy。versionName 1.3.36 / versionCode 276。
- **「打开页面后反复截图确认」治理：hand-off 语义（2026-10-20 第六十四轮，v67）**：用户反馈 agent 每次跳转（如打开原生设置）都要截图确认，而用户通常很快改完返回、甚至指令返回前就回来了，agent 于是反复困惑。根因：旧 `openActivity` 以「350ms 后 resumed 是否等于目标 Activity」判定 `ok`，用户快速返回时被判成 `ok:false`，看起来像失败 → agent 去截图/重试。修复：
  ① `POST /activity` 改为 hand-off 语义：`ok:true` 只表示启动请求被接受；新增 `handoff:true`、`verify:"none"`、`do_not:["screenshot","poll","retry"]`、`returned_to_previous`、`elapsed_ms`，并带 `note` 明确「用户可能在你读取结果前就改完返回，这是预期，不是失败；不要截图/轮询/重试」；观察窗口 350ms→120ms（不再等待用户操作）。
  ② 文档：AGENTS seed 64 新增「Hand-off actions: opening a page FOR THE USER needs no verification」——ok:true 即完成，不截图不轮询不重跑；只有任务本身是核对状态（「X 开了吗」「改了缩放值是多少」）才读一次状态；截图只用于用户要求看证据或诊断渲染 bug；用户说「我改完回来了」就信任并继续。`DSH-MOBILE.md` v9 增「Hand-off semantics」；dotfiles VERSION 16、doc marker v9。
  实测 vivo：AGENTS.md upgraded to v64、doc v9 部署、engine healthy。versionName 1.3.35 / versionCode 275。
- **脚本 0600 权限事故 + toybox 遗留物审计（2026-10-20 第六十三轮，v66）**：用户反馈 `$PREFIX/bin` 里 native/appshot/shot/alarm/record 变成 0600 不可执行。根因：Android 上 Java 新建文件默认 0600，门脚本部署只 `writeText` 没有任何 chmod；而旧安装升级时「安装运行时」步骤被跳过，这些新脚本从未被修过权限。修复：
  ① `applyAgentGates` 集中定义 `AGENT_GATES`（15 个），逐个：若是符号链接/非普通文件先删除（避免写穿 bootstrap 的 toybox 链接）→ 写入 → `setReadable`+`setExecutable` → 校验 `canExecute()`；完整性探针改用同一份清单并新增「不可执行（含 octal 权限位）」判据。
  ② `TermuxDotfiles.repairScriptModes`（每次启动）：对 18 个受管脚本（rm/trash-cli/dsh-mobile + 15 gate）幂等 chmod 0755；遇到 toybox/busybox 同名符号链接（指向 /system/bin、/system/xbin 或含 toybox/busybox）直接删除并记日志，随后由 deploy 重建真文件。
  ③ 新增 `writeSafe()`：$PREFIX 内所有部署写（zshrc/插件/自述文档/dsh-mobile/webview-cdp.mjs/trash-cli/rm wrapper/资源树）先删符号链接再写，并按需 chmod，杜绝写穿。
  ④ `native files|perms|doctor`：一条命令列出受管脚本是否 MISSING/SYMLINK/NOT-EXEC/ok，便于 agent 自检（AGENTS seed 63 也提示「command not found/Permission denied 时先 ls -l 并上报」）。
  ⑤ toybox 审计结论：受管脚本无一依赖 toybox 命令（bootstrap 自带 pgrep/pkill/ps/grep/sed/awk/tr/curl，roots 来自 Termux procps/coreutils/curl）；现存 toybox 字样均为**有意保留**：AGENTS 环境段说明「Termux 工具是真实构建、/system/bin 才是 toybox」、trash 规则禁止用 toybox rm 绕过、注释解释 /system/bin/sh 是 toybox 不支持 /dev/tcp、Privilege 兼容 toybox 版 su、alarm/record 有意调用 /system/bin 的 am/screenrecord、rm wrapper 非共享路径回退 /system/bin/rm。无遗留 toybox 符号链接（启动日志未发现可删项，bin/rm 早已换成 wrapper）。
  实测 vivo：启动日志 `agent gates deployed (15): …` + `agent gates: 15 scripts deployed, executable`。版本 1.3.34 / 274；提交见下。
- **App 原生控制/调试 API（2026-10-20 第六十二轮，v62）**：用户明确「开放原生能力」= 给 agent 大量 debug/控制能力（直接开设置 Activity/终端、读写设置项、看联网状态等）。实现：
  ① 新增 `AppState.kt`：各 Activity（Main/Terminal/Settings）onResume/onPause 登记，提供 `resumedActivityName`/`isForeground`；
  ② `AgentBridge` 新端点：`GET /status`（app/引擎/前台 Activity、联网 type/validated/metered/带宽/vpn、电量/充电/温度、内存、files 与 /sdcard 剩余、屏幕亮/锁屏、无障碍/所有文件/悬浮窗/通知/电池优化）、`GET /settings`（page_scale/orientation/lan_web_access/a11y_max_provider/priv_mode + 可写键说明）、`POST /settings`（白名单写入，返回 applied/restartRequired/errors；priv_mode 与局域网开关需重启引擎）、`POST /activity`（只允许本 App Activity：main/settings/terminal/Class，start 后 350ms 回读 resumed_before/after + hint，如实暴露被 BAL 拦下）；
  ③ Manifest 增 `ACCESS_NETWORK_STATE`；
  ④ 新门脚本 `native`：`native status|settings|set k=v…|open settings|terminal|main|net|json GET/POST path`；
  ⑤ `dsh-mobile app status|net|settings [set k=v…]|open …` 子命令（宿主用假 bridge 验证输出与请求体）；
  ⑥ 文档 `DSH-MOBILE.md` v8 增「Native control / debug API」表，AGENTS seed 62 把 `native` 加入门脚本清单并说明优先于 `am start`。
  版本 1.3.30 / 270；vivo 已装（日志确认 doc v8/helper/gates 重新部署；aapt 确认 ACCESS_NETWORK_STATE 进包）。
- **教 agent「怎么正确点 WebUI」+ CDP 真实输入命令（2026-10-20 第六十一轮，v61）**：用户指出上轮文档只强调「设置是浮层不是路由」不是重点，重点是让 agent 懂得怎么正确点击 WebUI。实现：① 在 vivo 上用 Playwright 真实操作摸清移动端 WebUI 的正确点法：engine 自带「打开侧边栏」按钮被 mobile-ui 顶栏挤到屏幕外（x≈-308），必须点 mobile-ui 顶栏 `button[aria-label="菜单"]` → 抽屉 → `button[aria-label="账号菜单"]`（抽屉底部）→ `[role=menuitem]` 文本「设置」→ `[role=dialog]` 设置浮层（class VOzbGW_panel，含 账号与余额/通用设置/模型/内置插件/Agent 预设/刷B站）；抽屉按钮 aria-label：新建会话/插件/插件市场/自动化任务/记忆系统/搜索会话/视图选项/添加工作区；顺带确认 `/settings` 在页面内 `fetch` 返回 200 text/html（SPA fallback 真机生效）。② helper 新增真实输入命令：`cdp click <css>`、`click-text <text> [--exact]`、`tap <x> <y>`、`type <text> [--selector css]`、`key <Enter|Tab|Escape|Backspace|Arrow*>`、`scroll <dy>`、`wait <css|text> [ms]`，全部走 CDP Input 域（`Input.dispatchMouseEvent`/`dispatchTouchEvent`/`insertText`/`dispatchKeyEvent`），不是 `element.click()`；宿主用假 CDP WS 服务器验证命令与事件序列。③ 文档：`DSH-MOBILE.md` v7 与 AGENTS seed 61 新增「Driving the WebUI correctly (CDP)」：具体入口/选择器/逐步命令、禁用合成 click、hashed class 不可依赖、每步验证状态、元素 x<0 表示面板未开、两次失败问用户；`dsh-mobile ui` 的 web 层 automation 提示同步。dotfiles VERSION 14、doc marker v7、SEED 61；vivo 已装（日志确认 doc/helper 重新部署；测试用 webview-debug flag 已移除并重启关闭 CDP）。versionName 1.3.29 / versionCode 269。
- **网页抓取双通道插件 + Gradle 国内镜像脚本（2026-10-20 第六十一轮，v61；仅构建脚本/文档，无 APK 版本变更）**：① **android-fetch-auto 插件**（`assets/android-plugins/android-fetch-auto.mjs`，322 行；overlay insert + `web.fetchProvider: android-fetch-auto`，`searchProvider` 仍 deepseek-official）解决两件事：(a) 引擎锁 npm 0.2.1-rc.2，没有上游 `allowedNonPublicRanges`，旁路由 fake-IP（198.18.0.0/15）下 web_fetch 对所有域名 `WEB_BLOCKED_URL`；插件利用 `HttpFetchProvider(limits, resolveAddresses)` 注入点自建 resolver——公网单播照旧钉死、显式放行 fake-IP 段（`DSH_ANDROID_FETCH_RANGES` 可改），loopback/RFC1918/link-local 仍拒；(b) 官方 DeepSeek Fetch API 对反爬/Captcha 站点回 404 `failed to fetch URL`（失败不计费），插件注册一个组合 provider：官方优先、任何非取消失败回落本地（`preferLocal: true` 可反转），两条都挂时把两边错误合并上报。依赖按绝对路径动态 import（同 fast-mode 踩坑：`android-plugins/` 不在模块解析链上）；不改 vendor、引擎热更新后仍可用。验证：用引擎 zip 里真实的包跑 harness（example/wikipedia/github → 200，`10.0.0.5`/`127.0.0.1` 仍被拦）；设备 logcat `plugin deployed: android-fetch-auto.mjs (13328 bytes)`、`plugin health ok=true`、`engine healthy on :3080`。② **Gradle 国内镜像脚本**（`scripts/gradle-mirror.gradle`）：本机 Gradle 直连 Maven Central 报 `Remote host terminated the handshake`（TLS 被网络/旁路由掐断，curl 同地址正常）。脚本把阿里云 public/google/gradle-plugin 镜像**插到仓库列表最前**（settings 的 pluginManagement 与 dependencyResolutionManagement + 各项目 buildscript），缺包仍回落到原有仓库；用法 `gradle -I scripts/gradle-mirror.gradle assembleDebug -Pabi=arm64-v8a`，或拷到 `~/.gradle/init.d/10-cn-mirrors.gradle` 常驻；README 中/英/德三语已补说明。实测删掉 `kotlin-gradle-plugin-idea-proto` 缓存后重建：日志显示从 `https://maven.aliyun.com/repository/public/...` 下载并 `BUILD SUCCESSFUL`。
- **「设置页」歧义修复：三层界面文档 + SPA fallback + launch 显式 component（2026-10-20 第六十轮，v60）**：设备端 agent 报「用户说打开设置页，我走错 30 步」。三项修复：
  ① **文档**：`DSH-MOBILE.md` v6 与 AGENTS seed 60 新增「Three UI layers」：native（Activity/顶栏齿轮/胶囊把手，CDP 看不到，`am start` 本应用 Activity 会被 BAL 静默拦截）/ web（3080 React，设置=账号菜单→全屏浮层，URL 不变，不是 `/settings` 路由）/ terminal（终端）；列出歧义词（设置/插件/终端）在两层分别指什么；新增工作约定「层不明确先问用户，两次探测失败就换层或问，不要在错误层继续挖」；新增 `dsh-mobile ui [--json]` 打印层图。
  ② **`/settings` SPA fallback**：新增 `EngineConfig.patchFrontendSpaFallback`，按内容锚点把 `dsh-host-frontend-static` 的 404 分支改为「扩展名缺失的未知路径返回 index.html」；宿主单元验证：`/settings`、`/settings/general` → 200 index，`/assets/nope.js` → 404，`/index.html` → 200；vivo 启动日志 `engine patch: SPA fallback enabled in frontend-static`。
  ③ **`/launch`、`/launch-intent` 支持显式 `component`**：`{"component":"pkg/cls"}`（可带 action/data/extras），不存在的 component 返回 404 `component not found`（不再假 ok），成功回执带 `backgroundLaunchMayBeBlocked` 提示 BAL 可能拦截；`/launch` 也接受 component（此前被静默忽略只开 launcher）。
  版本 1.3.28 / 268；dotfiles VERSION 13、doc marker v6、SEED 60；vivo 已装并确认部署日志。
- **CDP issue 修复：socket 三级发现 + doctor（2026-10-20 第五十九轮，v58）**：设备端 agent 报 `dsh-mobile cdp` 不可用（v1.3.22）：① 已有 `cdp` 前缀修复（v57 已做）；② 本机 SELinux 禁止 untrusted_app 读 `/proc/net/unix`（EACCES），而 helper 的 socket 发现硬依赖它 → 永远 `no devtools socket`；`/proc/<pid>/cmdline` 同样不可读 → app pid `?`。修复：helper 改为四级发现（env → **宿主 bridge `GET /process`** → `/proc/net/unix`（可读时）→ 抽象 socket 并发扫描 400×40000≈2.5s），`status` 显示 `via env|bridge|proc|scan` 与 `/proc` 不可读原因（不再自相矛盾）；新增 `dsh-mobile cdp doctor`：逐层 OK/FAIL（flag → bridge /process → socket 发现 → `/json/list` → Runtime.evaluate 实测）；`appshot`/`eval`/`serve` 等命令全部 async 走 `findSocket()`。Kotlin 侧 `AgentBridge` 新增 `GET /process`（app_pid/uid/version/webview_debug/devtools_socket/sdk/model）。宿主用假 devtools 抽象 socket 验证：proc 档与强制扫描档（`DSH_WEBVIEW_NO_PROC=1`）都能命中，doctor 输出分层结果；伪造 WS 服务器不支持升级时 doctor 正确报 Runtime.evaluate FAIL。文档 `DSH-MOBILE.md` v4、AGENTS SEED 58、dotfiles VERSION 11。versionName 1.3.26 / versionCode 266，vivo 已装（日志确认 helper/doc 重新部署）。
- **dsh-mobile cdp 子命令解析修复（2026-10-20 第五十八轮，v57）**：用户反馈 `dsh-mobile cdp on` 输出 help。原因：Node 脚本只认裸子命令（`dsh-mobile on`），而文档/AGENTS 写的是 `dsh-mobile cdp on` → `cmd='cdp'` 落入 default 打印 usage。修复：解析时若首参为 `cdp` 则取下一个作为子命令（缺省 status），两种写法都支持；同时修掉本次编辑在 help 模板字符串里误插反引号导致的语法错误（会让整个 helper 崩溃，宿主 `node --check` + 假 devtools socket 复测通过：`cdp status` 与裸 `status` 均正常）。versionName 1.3.25 / versionCode 265，vivo 已装并确认日志 `cdp helper deployed`。
- **原生 app 截图 + UI 说明 + 每次启动补齐产物（2026-10-20 第五十七轮，v56）**：用户指出① 非首次安装时新门脚本不会落地，需要每次启动检查缺什么补什么；② 内部截图不要用 WebView/CDP（不可靠），要用 Kotlin 原生；③ 文档补 App UI 说明（如顶部胶囊把手）。实现：① 新增 `engine/AppShot.kt`：`PixelCopy` 抓 `MainActivity` 窗口（API26+，失败回退 decorView 软绘），MainActivity onResume/onPause 注册/注销；`AgentBridge` 新增 `GET /app-screenshot?max=N` 返回保存路径；新门脚本 `appshot [--max N] [--out png]`；`dsh-mobile appshot [out] [max]` 走该桥；移除 `dsh-mobile cdp shot`；② 新增 `EngineConfig.ensureAgentArtifacts(ctx)`，`DshApp.onCreate` 起后台线程每次启动跑 `TermuxDotfiles.ensure` + `ensureAgentArtifacts`，补齐 dotfiles/自述文档/dsh-mobile/全部门脚本（含 shot/appshot/alarm/record），解决「升级 APK 但运行时已存在 → 安装步骤被跳过 → 新命令不存在」；③ `DSH-MOBILE.md` 升 v3：新增 “App UI map”（顶部工具栏、胶囊把手、原生设置、mobile-ui 抽屉/右栏、原生终端标签与按键条、首次引导），并把截图章节改为原生 appshot；④ AGENTS.md 升 SEED 57：门脚本清单加 shot/appshot/alarm/record，截图指引改用 appshot 并指向 UI map。实测 vivo：安装后日志确认 `agent gates deployed: … shot appshot alarm record`、`app doc deployed`、`cdp helper deployed`、zshrc v10、engine healthy。versionName 1.3.24 / versionCode 264。
- **App 自述文档 + 原生能力/CDP 开放（2026-10-20 第五十六轮，v55）**：用户要求「app 内部空间附一份 app 自身说明；尽可能多开放原生功能给 agent；WebView CDP 必须让 agent 知道用于 dsh web 调试」。实现：① 新增 `assets/agents/DSH-MOBILE.md`（App 架构、目录、门脚本、AgentBridge 61 端点分组、CDP 用法、权限模式与限制），由 `TermuxDotfiles.deployAppDoc` 部署到 `$DSH_HOME/DSH-MOBILE.md`（带 `<!-- dsh-mobile app doc v1` 标记；非我方文件或同版本不覆盖）；② 新增 `assets/termux/webview-cdp.mjs`（纯 Node 无依赖）：`status/on/off/list/serve/eval/reload/bridge/info/doc`，用抽象 Unix socket（`\0webview_devtools_remote_<pid>`）在设备内直连 WebView devtools，`serve` 桥接到 127.0.0.1:9222，`eval` 走自实现 WebSocket 做 Runtime.evaluate，`bridge` 带 token 调用全部 AgentBridge 接口；③ `assets/termux/dsh-mobile.sh` 包装为 `$PREFIX/bin/dsh-mobile`，两者由 `deployCdpHelper` 部署；④ AGENTS.md 新增两节：「This app: internals and native capabilities」（指向 DOC、门脚本清单、bridge 用法、「别手搓 intent」）与「Debugging the Web UI over CDP - mandatory」（cdp on→重开 App、serve 9222、curl/json list、eval、host adb forward + Playwright、cdp off），`SEED_VERSION` 54→55。宿主机已用假 devtools 抽象 socket 验证 `status/list/serve`（proxy curl /json/list 正常）；设备本轮 adb 掉线，v55 APK 已产出待装。versionName 1.3.22 / versionCode 262。
- **rm/trash 四个边界问题修复（2026-10-19 第五十五轮，v54）**：用户指出四个缺陷并逐项修复：① 混合目标 `rm /sdcard/a $HOME/b` 之前整条交给真 rm，导致 sdcard 目标也永久删除 → wrapper 改为**逐目标分流**：共享存储的进回收站、其余用原旗标真删；② 同名文件先后删除时回收站内互相覆盖 → 目标改存 `files/<用户路径>`（保留完整目录结构，天然唯一），索引记用户写法路径；③ `rm -f` 对不存在的共享存储目标报错并 exit 1 → 解析 `-f`，force 时静默跳过且不置错误码；④ 恢复最后一条时索引留幽灵行 → `trash-cli restore` 的索引更新从 `grep -vF && mv`（只剩一条时 grep 返回 1 导致 mv 被跳过）改为 `awk '$3 != s'` 后无条件 mv。额外：`trash-cli restore` 关键字匹配支持 `/sdcard/...` ↔ `/storage/emulated/0/...` 互相兜底。AGENTS.md 增补「一条 rm 可混合两类路径，别假设行为一致」，SEED_VERSION 54。已知：设备端中文 IME 会吞 `adb input text` 的空格/字母（自动化测试受限），用户选择自己看截图验收。versionName 1.3.21 / versionCode 261。
- **trash-cli 统一入口 + AGENTS.md 规则（2026-10-19 第五十四轮，v53）**：用户要求「AGENTS.md 补充：必须使用 trash-cli（把 trash-list/trash-restore/trash-empty 合并成一个 trash-cli）」。做法：① 新增可执行 `$PREFIX/bin/trash-cli`（`assets/termux/trash-cli.sh`，`TermuxDotfiles.deployTrashCli` 部署，带 marker 不覆盖外来文件），子命令 `list` / `restore <关键字|序号>` / `empty`，读同一 `index.tsv`（rm wrapper 写入），对 bash/zsh/Agent 都可用；② `.zshrc` v7 删除三个旧 shell 函数（改由 trash-cli 提供），回收站相关只保留 `DSH_TRASH` 环境变量；③ `assets/agents/AGENTS.md` 新增章节「Deleting files on shared storage (trash, not permanent)」：共享存储删除必须走 `rm`（wrapper 会进回收站），禁止 `/system/bin/rm`、busybox/toybox rm、find -delete、unlink 绕过；恢复用 `trash-cli list|restore|empty`，并在删除后告知用户如何恢复；④ `AgentContextSeed.SEED_VERSION` 52→53，升级时保留用户段。实测 vivo：AGENTS seed v53、规则命中 3 处；`rm /sdcard/x` → 进 dsh-trash；`trash-cli list/restore/empty` 全部正常。versionName 1.3.19 / versionCode 259。
- **rm 改成 PATH 级底层拦截（2026-10-19 第五十三轮，v52）**：用户要求「不管交互形式，尤其 Agent 调用指令时也要拦」。做法：放弃 zsh 函数，改为把 `$PREFIX/bin/rm` 换成 **POSIX sh wrapper**（`assets/termux/rm-trash.sh`，由 `TermuxDotfiles.deployRmWrapper` 部署；原来 bin/rm 是 bootstrap 指向 toybox 的符号链接，必须先 delete 再写，否则写穿到 toybox 本体；带 `DSH Mobile rm wrapper` 标记，非我们的 rm 不覆盖）。wrapper 逻辑：目标全部在 `/sdcard`/`/storage` → `mv` 到 `/sdcard/dsh-trash/<时间戳>-<pid>/files/` + 登记 `index.tsv`；否则 `exec /system/bin/rm "$@"`（保持 pkg/apt、缓存清除、脚本语义）。`.zshrc` v6 移除了 rm() 函数，只保留 trash-list/restore/empty 辅助命令。实测 vivo（用 bash 脚本、显式 PATH 模拟 Agent 环境）：`rm /sdcard/lowtest.txt` 进 dsh-trash、`rm $HOME/lowinternal.txt` 真删；`command -v rm` 首行已是 `#!/system/bin/sh`。已知不覆盖：绝对路径 `/system/bin/rm`、`busybox rm`/`toybox rm`、`find -delete`。versionName 1.3.18 / versionCode 258。
- **/sdcard 的 rm 回收站（2026-10-19 第五十二轮，v51）**：用户想「rm 关联到手机文件管理器的回收站」，澄清后接受自建恢复目录并指定名字 `dsh-trash`。结论先说明：shell `rm` **进不了**厂商文件管理器/系统 MediaStore 的回收站（那是私有库/仅媒体 API，文档类没有开放接口），所以用自建目录：`.zshrc` v5 里定义 `rm()` 函数——仅当**所有目标都在 `/sdcard`、`/storage`** 时走回收站，其它路径（$HOME、$PREFIX）保持真删；实现 `_trash_put`（`mv` 到 `/sdcard/dsh-trash/<时间戳>-<pid>/files/` + `index.tsv` 登记原路径）、`trash-list`、`trash-restore <关键字|序号>`、`trash-empty`。`TermuxDotfiles.VERSION=5`。实测 vivo：`rm /sdcard/rmshift.txt` → 文件消失、`dsh-trash/2026.../files/rmshift.txt` 出现、`trash-list` 列出、`trash-restore rmshift` 恢复到 `/storage/emulated/0/rmshift.txt`；`$HOME` 下的 rm 仍是真删（不进回收站）。versionName 1.3.17 / versionCode 257。
- **TUI 滚动减速（2026-10-19 第五十一轮，v50）**：用户反馈「滚动速度太快，完全看不到」。原因：v49 按「每行手指位移发一个滚轮事件」，而 TUI 通常 1 个滚轮事件滚 3 行 → 一次快滑飞出几十行。修复：`TerminalView` 改为**每 4 行手指位移发 1 个滚轮事件**（`WHEEL_ROWS_PER_EVENT=4f`）+ **70ms 节流**（`WHEEL_MIN_INTERVAL_MS=70L`），并给累积量封顶防甩动溢出。实测 vivo dsh-tui：400px 慢滑约滚 9 行，肉眼可控。versionName 1.3.16 / versionCode 256。
- **TUI 自然滚动：把触摸手势转成鼠标滚轮（2026-10-19 第五十轮，v49）**：用户反馈「dsh-tui 启动后无法滚动，要移动端一样的自然滚动」。根因：TUI 走 alternate screen 且没有 scrollback，我们之前又「安全忽略」鼠标上报（DECSET 1000/1002/1003/1006），所以任何手势都无处可滚。实现：① `TerminalEmulator` 记录 `mouseTrackingMode`（1000/1002/1003）与 `mouseSgrEncoding`（1006）/`mouseUtf8Encoding`（1005），提供 `mouseReporting`；② `TerminalView` 在 alt screen + 应用请求鼠标时，把纵向拖动按 cellHeight 折算成滚轮事件（SGR：`ESC[<64;col;row M` / `65`，无 1006 时回退 X10 三字节），坐标取触摸点单元格并钳制；手指下滑=wheel up（看更早），与 Android 自然滚动方向一致；普通屏幕仍走 scrollback。实测 vivo：dsh-tui 里滑动可滚上下文（能看到 todo_write/web_fetch 等工具清单滚动），方向自然。versionName 1.3.15 / versionCode 255。
- **终端按键条加 CTRL/ALT/SHIFT（2026-10-19 第四十九轮，v48）**：用户要求「终端快捷键增加 ctrl/alt/shift 在 tab 后面」。实现：① `TerminalExtraKeysView` 在 TAB 后插入 `CTRL`/`ALT`/`SHIFT` 三个粘滞修饰键，点亮时强调色底+深字，作用于下一次输入后自动熄灭；② `TerminalView` 新增 `ctrlSticky/altSticky/shiftSticky` + `applyPendingModifiers()`（Ctrl→控制字符、Alt→ESC 前缀、Shift→大写/US 上档符号映射），在 `sendText()` 这一层生效（IME commitText、硬件键 unicodeChar、按键条 `/|-_~` 都走这里），`sendControlChar` 的 ^C 等明确按键不受影响；③ 顺手修按键条一进终端就自动滚到最右（看不到 CTRL）的问题：`FOCUS_BLOCK_DESCENDANTS` + 自身不抢焦点 + target 设置时 `scrollTo(0,0)`。实测 vivo：按键条顺序 ESC TAB CTRL ALT SHIFT ^C ^D…；SHIFT 点亮后按 `-` 输出 `_`，随后自动熄灭。versionName 1.3.14 / versionCode 254。
- **退出弹窗改为自绘实心卡片（2026-10-19 第四十八轮，v47）**：用户反馈「弹框背景好奇怪，不要透明」。原因：框架 `AlertDialog` 在 vivo/ColorOS 上给按钮区叠半透明底色，且首个可点项会画焦点灰块。改为 `Dialog` + 自绘 `LinearLayout`（`bg_dialog_solid` 实心 #FF1A2029 + 1dp 描边 + 14dp 圆角），标题/正文/「新建终端」「退出 App」两个按钮全自绘（青色 #FF7DD3FC，`isFocusable=false` 去掉焦点高亮），窗口背景透明只留实心卡片、dim 0.55、固定宽 300dp。实测 vivo 弹窗完全实心、无半透明带、无灰焦点块。versionName 1.3.13 / versionCode 253。
- **终端 exit 行为（2026-10-19 第四十七轮，v46）**：用户要求「终端 exit 应退出 tab 页；如果没有 tab 则弹框问是否退出 App」。实现：`TerminalActivity.onExited` 由「只刷新 tab」改为 `closeSession(s, byExit=true)`（先 remove 再 close，避免 onExited 重入）；`closeSession(byExit)` 关闭后：有其它会话→切过去；无会话且 byExit→`promptExitAfterLastSession()` 弹「终端已结束 / 没有其他终端标签了，要退出 App 吗？」，按钮「新建终端」（同 profile 重开）与「退出 App」（`closeAllAndFinish()+finishAffinity()`，引擎前台服务按既有设计继续后台跑）。手动点「关闭会话」关最后一个 tab 仍走原来的 finish()，不弹框。三语文案 terminal_exit_confirm_*。实测 vivo：单 tab 输 exit→tab 关闭+弹框；新建终端→新 zsh 会话；再次 exit→退出 App 回到桌面。versionName 1.3.12 / versionCode 252。
- **去掉指令纠错（2026-10-19 第四十六轮，v45）**：用户「把 correct 去掉，不需要它」。`.zshrc` 升 v4，删除 `setopt correct` 段落；`TermuxDotfiles.VERSION=4` 触发已部署文件升级。保留联想（zsh-autosuggestions）与语法高亮（zsh-syntax-highlighting）。实测 `setopt` 无 correct（CORRECT-OFF）、`AUTOSUGGEST-OK`、`VER=0.8.1-dev`。versionName 1.3.11 / versionCode 251。
- **zsh 语法高亮 + 默认 shell 切 zsh（2026-10-19 第四十五轮，v44）**：用户反馈「输错指令或空格报红怎么没有」——那是 `zsh-syntax-highlighting`（无效命令红/有效命令绿/路径青），此前只装了联想与纠错。做法：① vendored `zsh-syntax-highlighting` 0.8.1-dev 到 `assets/termux/zsh-syntax-highlighting/`（主脚本 + highlighters/{main,brackets,pattern,line,cursor,root,regexp}，剔除 test-data/README，共 160KB/8 文件）；② `.zshrc` 升 v3，末尾 source 该插件（顺序必须在 compinit/alias 之后）；③ `TermuxDotfiles` 增加 `deployAssetTree()` 递归部署整棵目录，并按名显式部署 `.version`/`.revision-hash`（**aapt 不打包 assets 里以 `.` 开头的文件**，所以 assets 内存为 `version.txt`/`revision-hash.txt`，落盘时改名）；④ `ShellProfiles` 默认 shell 改为「装了 zsh 就用 zsh，否则回落 bash」，终端标题显示 `Android (zsh)`。实测：`VER=0.8.1-dev / HIGHLIGHT-OK` 无警告；交互输入 `llss` 显示红字；联想/纠错同时生效。versionName 1.3.10 / versionCode 250。
- **zshrc v2：带上指令联想与指令纠错（2026-10-19 第四十四轮，v43）**：用户要求「把部分 oh-my-zsh 也带上，比如指令联想和指令纠错」。做法（不装整个 oh-my-zsh，避免几 MB + 启动开销）：① 指令纠错用 zsh 内建 `setopt correct`（等价 oh-my-zsh 的 ENABLE_CORRECTION`）；② 指令联想内置 `zsh-autosuggestions` v0.7.1（MIT，867 行，随 APK 放在 `assets/termux/zsh-autosuggestions.zsh`），由 `TermuxDotfiles` 每次启动同步到 `$HOME/.dsh-mobile/zsh-autosuggestions.zsh`（内容比对，无标记/不覆盖用户手写 .zshrc 的策略不变）；③ `.zshrc` 升到 v2：先设 `ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE='fg=242'` 再 source 插件。实测手机1 `zsh -i -c 'setopt|grep correct; typeset -f _zsh_autosuggest_start'` → `correct` + `AUTOSUGGEST-OK`。versionName 1.3.9 / versionCode 249。
- **Termux 默认 zshrc（2026-10-19 第四十三轮，v42）**：用户要求「termux 默认带 zshrc 配置，用 PC 端的，但移出 PC 端 alias 等配置，保留 `alias cls` 和基础部分」。实现：新增 `assets/termux/zshrc`（历史/补全/`cls`、`ll`、`la`、`l`、`vi`、`..`、`...`、提示符、EDITOR；不含 nvm/pyenv/proxy/gcloud/texlive/oh-my-zsh/pnpm/WSL 等 PC 专属），新增 `engine/TermuxDotfiles.kt` 在 `BootstrapInstaller.ensure()` 里幂等部署到 `$HOME/.zshrc`（交互终端 HOME=dsh-home）与 Termux 标准 `files/home/.zshrc`；带 `# ~/.zshrc - DSH Mobile default config (v1)` 标记，无标记（用户自写）绝不覆盖，标记旧版本才升级。实测：部署后 `pkg install -y zsh`（已在手机1装好 zsh 5.9.2-2 + zsh-completions），`zsh -i -c 'alias cls; alias ll; echo ZSH-OK'` 输出 `cls=clear / ll='ls -l' / ZSH-OK`。versionName 1.3.8 / versionCode 248。默认 shell 仍是 bash（要切 zsh 需再改 ShellProfiles，未做）。
- **修复侧边栏 HTML 预览空白（2026-10-19 第四十二轮，v41）**：用户报「侧边栏无法预览 html，全空白，是不是 webview 不允许」。实测定位：不是 WebView 不支持 —— `srcdoc` iframe 正常，但 `blob:` / `data:` 的 iframe 全白。根因在 `MainActivity.shouldOverrideUrlLoading`：旧实现只认 `127.0.0.1`/`localhost`，其余 URL 一律 `startActivity(ACTION_VIEW)` 并 `return true`（取消加载）；dsh 的 documentpreview 用 `src=blob:http://127.0.0.1:3080/...` 的 iframe 渲染 HTML，blob/data URL 的 host 为空 → 被当外链拦截 → iframe 永远空白。修复：① 子框架导航（`!request.isForMainFrame`）一律 `return false` 交还 WebView；② 主框架放行 `blob:`/`data:`/`about:`/`javascript:` 伪协议；③ 只有主框架的真 http(s) 外链才跳系统浏览器。验证：CDP 里注入 srcdoc/blob/data 三种 iframe，修复后三者都渲染色块；实际预览 `preview-test.html` 正常显示 `PREVIEW-TEST / hello`。期间试过开 `allowFileAccess/allowContentAccess/allowUniversalAccessFromFileURLs`（无效，已还原 false，保持攻击面收窄）。versionName 1.3.7 / versionCode 247，手机1 已装；vivo 掉线未装。
- **恢复原生顶部工具栏（2026-10-19 第四十一轮，v40）**：用户反馈「顶部那个横条不见了」。根因是 `bcdf3a3 revert(native)` 把 `activity_main.xml` 的 `statusBarRow` 默认值改成了 `android:visibility="gone"`，而代码里没有任何地方在启动时把它设回可见（`toggleToolbar()` 也依赖它先可见/把手先可见），于是顶部工具栏+小把手都永久消失。修复：去掉 `statusBarRow` 的默认 gone（恢复 v1.3.0 基线行为：工具栏可见、`handleBar` 默认隐藏、点「隐藏」后收起成小横条胶囊）。versionName 1.3.6 / versionCode 246，vivo 实测已显示「引擎已就绪 · dsh http://127.0.0… 重启 隐藏 ⚙」横栏。手机1 本轮 adb 掉线未安装。
- **引擎层上游修复合入（2026-10-19 第四十轮，v39）**：在 v38 选择性移植基础上，按用户「引擎层修复现在合」把 v1.3.1/v1.3.3 的引擎可靠性修复逐段并入（三路 merge-file + 手工解冲突，保留我们的 mobile-ui/原生终端/热更新改造）：
  ① EngineSupervisor：端口冲突自动清场（`portInUse/looksLikePortConflict/reclaimPort/waitPortFree` + `MAX_PORT_RECLAIMS=8`，不再误把历史 EADDRINUSE 当日志证据、清场次数有上限）、插件恢复（`PluginRecovery.onHealthy/quarantineOne`，坏插件自动停用并做有界重启，`recoveredPlugin` 通知走 `State.Healthy.notice`）、健康分支补回我们的 `proc.tokenUrl` 优先 + `retryTokenUrlWhenReady()` + `RuntimeUpdater.onEngineHealthy()`；`ExtensionManager` 已移除故删掉 clearPendingRestart 调用；孤儿清场命令改为匹配 `*files/usr/bin/node*|*dsh-engine/node-bin*`（我们真实路径，上游写的是 files/engine/bin/node）。
  ② Privilege：采用上游 `runPrivileged`（Shizuku 优先）+ `hasUnreadableEntries` 自检 + restorecon，保留我们「前缀目录是 usr 不是 engine」的修正。
  ③ EngineConfig：合入 `MAX_PORT_RECLAIMS`、`bootId` 参数与 `DSH_ANDROID_BOOT_ID`、`shellPathFor()`（SHELL 注入更稳）、末尾 overlay 重复关键宿主服务 + `android-plugin-health` 探针、`plugin-health.mjs` 部署；保留我们的 `PREFIX_DIR=usr`、mobile-ui/narrow-welcome 插件注册、runtime-overlay（node-pty shim）、patchEngineForAndroid/patchInspectorWorkerForAndroid、pnpm strict 环境变量、Linux/proot 环境变量；**移除**上游 PTY 助手部署（dsh_pty_exec/PTY_HELPER_NAME，我们 AgentBridge PTY 已够用）。
  ④ 新增 PluginRecovery.kt（274 行）与 assets/android-plugins/plugin-health.mjs；EngineService 状态栏用 `status_plugin_recovered`；补 `error_runtime_arch_mismatch`/`status_plugin_recovered` 三语文案；RuntimeInstaller 保留我们的 BootstrapInstaller + 热更新保护实现（上游 ELF 架构校验走 install() 路径，对我们 bootstrap 流程不生效），只补 `EngineArchMismatchException` 类供监督器 catch。
  ⑤ 实测（手机1）：冷启动 `boot step health-poll` → `plugin health marker received (ok=true)` → `token url extracted` → `engine healthy on :3080`；`kill -9` 引擎进程后 16s 自动重启健康；`am force-stop` 后重启 App 恢复健康；引擎侧边栏终端 `echo ENGINE-PTY-OK` 正常回显。
  ⑥ 版本 versionName 1.3.5 / versionCode 245，APK `dsh-mobile-1.3.5-…-v39.apk`（sha256 59dcb9a3…）。vivo 仍 v37，待升级。
- **上游 v1.3.1–v1.3.3 合并评估与首包合入（2026-10-19 第三十九轮，v38）**：用户要求「查看 dsh mobile 上游（本仓库 GitHub origin/main）代码，当前工程有问题就合并，新增功能先问，做好备份回退」。已做：① 备份 `backup/pre-upstream-20261019-031701`（分支+tag+全仓 bundle 11MB 于 ~/Projects/common/dsh-mobile-backups/）；② 上游比本地多 9 个提交（v1.3.1/v1.3.2/v1.3.3 + docs/CI）；③ 试整段 merge 冲突 11 文件（EngineConfig/EngineSupervisor/Privilege/RuntimeInstaller/AgentContextSeed/Settings 等），与我们的 mobile-ui/原生终端/UI 改造冲突大，已 abort，改走「选择性移植」；④ 已合入：CI gradle heap 4G（cherry-pick）、v1.3.2 的「自定义指令」功能（UserPromptActivity + activity_user_prompt.xml + 设置行 rowUserPrompt + 三语文案 + AndroidManifest + AgentContextSeed 分段逻辑：App 段随升级替换、用户段永不覆盖，SEED_VERSION 51→52，实测升级日志 `AGENTS.md upgraded to v52 (user section preserved, 0 chars)`，设置页写入后 AGENTS.md 用户段出现保存内容）；版本号升到 versionName 1.3.4 / versionCode 244。⑤ **暂未合入**（风险高，待逐项移植）：v1.3.1 引擎终极端修复（82ba366：自研 PTY 助手 dsh_pty_exec + patch-node-pty-android.py —— 我们已有可用的 AgentBridge PTY 方案，实测引擎侧边栏终端 `echo PTY-OK` 正常，故不重复引入）、v1.3.1 EngineSupervisor/Privilege/OrphanReaper/RuntimeInstaller 修复、v1.3.3 PluginRecovery/plugin-health/端口回收逻辑（需与我们的 BootstrapInstaller 热更新保护、token 提取、E_ROOT 清场逻辑逐段合并）。⑥ 已按用户要求丢弃：narrow-sidebar/settings/rightbar 的上游改动（这几个插件已不再部署）。
- **终端 TUI 收尾决定（2026-10-19 第三十八轮）**：用户选择 C —— `termux-change-repo` 在窄列下的两列挤压视为上游 dialog 行为，不再改字号/脚本。结论记录：① 字母线框 = 终端模拟器缺 DEC 字符集 → 已修（v36/v37，G0-G3 + SO/SI + SS2/SS3 + Canvas 矢量线框）；② 「Mirror groupRotate…」= dialog 在 `0 0 0` 自动宽 + 长提示 + 窄屏下自己压缩 tag/item 间距（用 `script` 抓真实字节流确认空格存在，终端未吞字符）；同内容 7 项 radiolist 在自动宽可滚动时对齐完美。当前 v37 已装手机1。
- **终端默认可视行数增加 + TUI 复查（2026-10-19 第三十七轮，v37）**：`TerminalActivity.DEFAULT_FONT_SP` 13f → 11.5f（终端设置里仍可调，KEY_FONT_SP 不变），窄屏下多出约 8 列，dialog 框能完整放进屏幕；配合 v36 的 DEC 矢量线框，`termux-change-repo` 的框、按钮、标题都正常成线。剩余：dialog 在窄宽度下自行压缩 tag/item 两列（像 “Mirror groupRotate…” 粘在一起），属于 ncurses/dialog 的窄屏排版；想更好可再降字号（10.5sp ≈ 65 列）或给 dialog 传固定宽度。
- **DEC 线框字符集完整实现 + 矢量绘制（2026-10-19 第三十六轮，v36）**：在 v35 基础上补全字符集支持：G0..G3 指定（`ESC ( ) * +` 后跟 `0`）、SO/SI 切 GL、SS2/SS3（`ESC N` / `ESC O`）单字符临时切 G2/G3；`printCp` 每次消费 singleShift。渲染层不再依赖字体画线框：`TerminalView` 新增 `drawBoxChar()`，把映射后的 `─│┌┐└┘├┤┬┴┼` 与 `⎺⎻⎼⎽` 用 Canvas 矢量线段绘制（strokeWidth=cellHeight/12，圆头连接），相邻格用中点到边界的线段保证无缝拼接。实测：dialog msgbox / termux-change-repo 的边框现在是连续细线，不再有断口或字母。遗留：`termux-change-repo` 的 radiolist 在 25 行高度里滚动时多条 item 会挤在同一行（疑似滚动区域/DECSTBM + IL/DL 在 origin mode 下行为不完整），需要继续补中断线语义；这是下一步。
- **原生终端 TUI 乱码修复：实现 DEC 特殊图形字符集（2026-10-19 第三十五轮，v35）**：用户反馈 `termux-change-repo` 的 dialog TUI「很诡异」——线框显示成 `lqqqk / x / mqqqj` 字母、文字重叠。排查：① 原生终端 PTY 尺寸正确（`stty -F /dev/pts/0 size` = 25x51）；② 自研 `TerminalEmulator` 之前**吞掉** `ESC ( 0` 字符集指定、并把 SO/SI 当无效（注释写着“Android shell 场景一律按 ASCII 处理”）→ ncurses/dialog 的 ACS 线框字符原样显示成字母。修法：加 `g0DecSpecial / g1DecSpecial / glFromG1` 状态，`ESC ( 0`/`ESC ) 0` 设定、SO/SI 切 GL；`printCp` 前按 DEC Special Graphics 表把 0x60..0x7E 映射到 Unicode 线框（l→┌、q→─、x→│、k→┐、j→┘、m→└、n→┼…）。⚠️ 踩坑：第一次 patch 用整段替换但没 assert 成功，实际只改了字段和 SO/SI、漏了 ESC 指定分支 → 编译安装后毫无变化；第二次改用 `if (escInter == '#'.code …)` 单行替换才真正生效。实测：`printf '\033(0lqqqk\033(B'` 现在画出一个矩形边框；dialog msgbox 边框正常。遗留：`termux-change-repo` 的两列菜单在 51 列宽下描述列紧贴条目（视觉上像粘连），以及切换到 TUI 后残留上一帧（对话框/蓝色背景）——属于清屏/滚动区域与窄列排版问题，待后续。
- **终端退出：面板必须保留且不能有收起/重开闪烁（2026-10-19 第三十四轮，v32）**：v31「只关 tab 什么都不做」后引擎会在关闭活跃 tab 时收起右栏，用户明确要求面板不能被关。修法：`closeExitedTerminalTabs()` 关闭 tab 后调用 `holdPaneOpen(900)` —— 用 MutationObserver 监听 frame 的 `data-rightbar-collapsed/fullscreen/style` + 30ms interval，一旦发现右栏被收起就**同帧** `setPaneOpen(true)` + `layout.openRightbar('guide', true)` + `rightPaneService().openTab('guide', {})`（不等待、不播动画），900ms 后停止守卫。实测采样：exit 后 1s 内 `data-dshm-pane` 全程 open、`pI_x6G_rightbarCol` 宽度全程 436（无收起帧），最终停在「开始」页。教训：只要「关 tab」会让引擎收栏，就必须在**同一渲染帧内**把栏还原，不能用延时重开（会看到收起→展开）。
- **终端退出：只关 tab，不做重开（2026-10-19 第三十三轮，v31）**：用户反馈 v30「先关闭侧边栏再打开」的闪烁很怪，要求「关闭 tab 后什么都不做」。改法：`closeExitedTerminalTabs()` 关闭退出终端 tab 后，删除 reopen/`openTab('guide')` 逻辑与动画；只做 250ms 后的**静默状态同步**（若 DOM 已无 tab 则 `setPaneOpen(false)`，不触发任何重开），面板去留完全交给引擎。实测：bash tab 里输入 `exit` → 0.4s 内 tab 消失、面板按引擎逻辑自然收起（最后一个 tab 关闭），全程无 close→open 闪烁。
- **终端退出后留在「开始」页、不收起面板（2026-10-19 第三十二轮，v30）**：v29 是「关掉退出终端 tab，没 tab 就收面板」，用户要求改成「面板留着、直接停在开始页」。改法：`closeExitedTerminalTabs()` 关闭 tab 后 350ms，若 `!rightPaneOpen()` 则 `setPaneOpen(true)` + `layout.openRightbar('guide', true)` 重新呈现，再调 `rightPaneService().openTab('guide', {})` 聚焦（已有 guide tab 时只是聚焦，不会重复创建），删掉原来的「无 tab 则 setPaneOpen(false)」。实测链路：开始页点「新建终端」卡片（DOM 里是 `.zjup-W_entry > button`，点 span 无效）→ 生成 bash tab → 输入 `exit` → 该 tab 被关闭、面板保持打开且「开始」高亮，截图确认。
- **终端退出后自动关闭该 tab（2026-10-19 第三十一轮，v29）**：用户反馈「终端 exit 后页面不刷新，旧输出和『新建终端』提示条混在一屏」（选择 A：退出后自动关掉该终端 tab）。修法：mobile-ui `src/runtime.js` 新增 `closeExitedTerminalTabs()`，在 sync（600ms + MutationObserver 触发）里检查：右栏打开时，若 `[class*="_rightbarCol"] [data-sidebar-terminal]` 的文本命中 `/进程已退出|Process exited|终端已关闭|Terminal closed/`，就取当前活跃 tab 的 `data-dockkit-tab`，调 `rightPaneService().closeTab(tabId)`（失败则点 tab 自带 `[data-dockkit-tab-close]`），并用 `state.exitedClosed` 防止重复处理；关完后 350ms 若已无 tab 则 `setPaneOpen(false)`。实测：新建终端 → 输入 `exit` → 2.5s 内该 tab 被关掉、右栏收起回到会话页，不再残留旧输出+新建终端混排。
- **右栏 tab 不再每次跳「开始」（2026-10-19 第三十轮，v28）**：用户反馈右栏已有 tab 时每次打开都跳到第二个「开始」。真因：`toggleRightPane()` 的 open 分支无条件 `right.openTab(kind)`，而 kind 候选顺序以 `guide`（=开始）优先 → 每次都把 guide 提到前台，覆盖引擎 dock 里保留的活跃 tab。修法：新增 `rightPaneHasTabs()`（查 `[class*="_rightbarCol"] [role="tablist"] [role="tab"]` 数量），open 时先 `layout.openRightbar(kind, true)`，**若已有 tab 就直接返回**、不再 openTab；只有空 pane 才首次放置。实测：切到「终端」→ 关右栏 → 再开 → 仍停在「终端」；刷新页面后 pane 恢复 open、活跃 tab 仍是「终端」（localStorage 记 paneOpen + 引擎自身保留 dock 的 tab/active 状态）。注意：截图里「此终端已不存在，请新建终端」是引擎重启后旧终端进程失效的既有行为，不是本次问题。
- **mobile-ui 两处修复：右栏记忆 + 弹层误判 modal（2026-10-19 第二十九轮，v27）**：用户反馈 ① 右侧栏不记忆上次打开（原版会）；② 点「问候」右边的子智能体图标弹 popup 时图层不对。修法都在 `tools/mobile-ui/src/`，`node build.mjs` 重生成 client.js 后同步到 assets：① `dialogSignature()` 原来把**任何** `[role=dialog]` 都当全屏 modal（`data-dshm-modal=open` → CSS 隐藏 App Bar），而引擎的「成员」popup 也是 `role=dialog`，于是打开它时顶栏被隐藏、留下一条灰色空带 + 面板从 bar-total 开始 = 图层错乱。改成只有尺寸 ≥ 视口 90% 宽且 ≥50% 高的 dialog 才算 modal（实测：成员 popup → modal=closed、App Bar 正常；设置页 436x927 → modal=open、App Bar 按设计隐藏）。② 右栏：`setPaneOpen()` 持久化 `dshm.paneOpen`/`dshm.paneKind` 到 localStorage，开/关都写；`rememberPaneKind()` 在成功放置 tab 时记下 kind；启动 `mountChrome()` 后 700ms 若记录为开且当前未开则 `toggleRightPane()` 恢复（优先用记住的 kind）。实测：打开右栏 → 重载页面 → `data-dshm-pane=open`、`pI_x6G_rightbarCol` 宽 436 恢复。⚠️ 引擎 reload 后右栏列仍然 mounted 但 `display:none`（宽 0），不能靠 DOM 内容判断引擎状态，记忆必须由插件自己存。
- **回退原生布局，改用用户自己的 dsh-mobile-ui 插件（2026-10-19 第二十八轮，v26）**：用户明确「原生太复杂，回退，在我之前的基础上改」。回退内容：`activity_main.xml`/`MainActivity.kt`/strings/EngineConfig 全部 checkout 回 v18 提交 a974281（WebView 全屏 + 40dp 悬浮横条），删除 `values/styles.xml`、删除 `narrow-rail` 插件目录；并在布局里把 `statusBarRow` 默认 `visibility=gone`（顶部交给插件 App Bar）。新增：把用户 zip 里的 `mobile-ui`（dsh-mobile-ui v0.1.17）作为内置客户端插件放进 `assets/android-plugins/mobile-ui/`（index.js/client.js/package.json），EngineConfig 的 overlay insert 与部署列表改为 `narrow-welcome` + `mobile-ui`（narrow-sidebar/settings/rightbar 不再部署，避免和 mobile-ui 抢 DOM）。源码进仓库 `tools/mobile-ui/`（src/ build.mjs dev-proxy.mjs README …），`node build.mjs` 重生成 lib/client.js 与 assets 内一致（可复现）。⚠️ 坑：mobile-ui 的宿主半边 `lib/index.js` 用 `inject: ['webServer']` 注册 3 条 rollback 路由，在本引擎的 overlay insert 方式下报 `cannot get property "webServer" without inject` 导致入口加载失败；改成 no-op 宿主插件（保留 name/apply，去掉 webServer 路由）后正常。实测手机1：`[dsh-mobile-ui] v0.1.17 applied at 436x927`，顶部 52dp App Bar（☰ 会话标题 侧栏/信息按钮）、☰ 拉出 popup 抽屉（深色遮罩、侧栏作为 330px 抽屉）、输入框全宽、页面无空白列。
- **引擎侧栏不再占位 + 原生会话列表（2026-10-19 第二十七轮，v25）**：用户要求两件事都做。① **引擎侧栏永不保持展开**：narrow-rail 插件加 `autoCollapse()`（tick 250ms：若 `[class*="_root"]` 不带 `_collapsed` 就点「收起侧边栏」），并把 `syncFrameColumns()` 改成**无条件**把内联 grid 第一轨压到 0（展开瞬间也不占内容宽度）；实测点「打开侧边栏」后 0.9s 内自动收回、`_centerCol` left≈0。② **抽屉新增「会话列表」二级页**（替代原「搜索会话」）：`drawerSessionsPanel`（300dp 原生面板，含返回、搜索框、ScrollView 列表）；点「会话列表」→ `showSessionsPanel()` + JS `requestSessions()`；插件在 `html.dsh-collecting` 期间临时隐藏侧栏，展开侧栏 → 260ms 后读 `[class*="_sessionRow"]` → 收起 → 通过 `DshAndroid.onSessions(json)` 回传；Kotlin 解析后渲染原生行（标题+相对时间，本地过滤）。点行 → `closeDrawer()` + JS `openSession(i)`（展开→点第 i 行→收起）。⚠️ 三个坑：a) `innerText` 在元素被 `visibility:hidden` 时返回空串 → 必须用 `textContent` + `span[class*="_title"]`/`span[class*="_time"]` 取值；b) Kotlin 注入的 `NATIVE_ACTIONS_JS` 原本 `window.__dshNative={...}` 直接覆盖，把插件挂的 `requestSessions/openSession` 冲掉（报 `requestSessions is not a function`）→ 改成 `Object.assign(window.__dshNative, {...})` 合并；c) 点「打开侧边栏」后 1.4s 还没收回是 tick 500ms 太慢，改 250ms。
- **左侧空白列真正原因（2026-10-19 第二十六轮，v21）**：v20 只把 `_sidebarCol` 宽度压到 0，但引擎布局是 **inline `grid-template-columns: 56px minmax(0,1fr) minmax(0,0px)`**（写在 `div[class*="_frame"]` 的 style 上），第一轨固定 56px → 空白仍在（用户看截图指出，我上一轮误报已修，教训：DOM 测 `_sidebarCol` 宽度=0 不等于布局不占位，必须量 `_centerCol` 的 left / 或直接截图核对）。修法：narrow-rail 插件加 `syncFrameColumns()` —— 收起态时把 frame 内联 grid 第一轨从 `56px` 改成 `0px`（保留其余两轨，不动右侧栏），用 MutationObserver（frame style/class + rail class）+ 700ms interval 兜底；展开时引擎自己写回 56px，插件不干预。实测：`_centerCol` left = 0、frame style = `0px minmax(0px, 1fr) minmax(0px, 0px)`，截图内容贴左无空白。
- **原生外壳第二轮（2026-10-19 第二十五轮，v20）**：用户四点反馈全部落地：① 顶栏标题**固定 App 名**（DSH Mobile），不再随 document.title 变化 —— 删除 `TitleBridge` / `NATIVE_TITLE_JS` 注入，页面标题保留在网页里；② 原生设置只在 ⋮ popup（重启/隐藏顶栏/设置）；③ 抽屉改为**纯 dsh 导航**：新会话/插件/自动化任务/记忆系统/搜索会话/**DSH 设置**（引擎自己的设置弹窗），不再放原生菜单；DSH 设置实现 = `window.__dshNative.openSettings()`：点 `button[aria-haspopup="menu"]`（账号菜单）→ 180ms 后点 `[role="menu"] button` 第一项（设置），实测直接弹出引擎设置弹窗；④ 网页左侧空白列真因：收起态外层还有 `div.pI_x6G_sidebarCol`（56px 宽）包着已被隐藏的 `_root`，narrow-rail 插件加规则 `[class*="_sidebarCol"]:has([class*="_root"][class*="_collapsed"]) { width:0; flex:0 0 0; overflow:hidden }`，实测 `sidebarCol` 宽度 = 0、内容区不再留白。⚠️ 编译坑：删常量时漏了一处 `NativeJs.TITLE` 调用 → `Unresolved reference: TITLE`；注意 build 失败后 `install` 仍会“Success”装的是上一次的旧 APK，必须看 gradle 输出确认 BUILD 成功再装。
- **原生 App 布局：固定顶栏 + 原生抽屉（2026-10-19 第二十四轮，v19）**：用户要求「安卓原生 app 布局：顶部固定 topbar，全靠点击展开」。改造：① `activity_main.xml` 从「WebView 全屏 + 悬浮工具栏」改为「竖排 [56dp 固定顶栏][WebView 内容区]」，顶栏 = [☰ 打开菜单] [标题] [⋮ 更多]，旧的 重启/隐藏/设置 三个按钮视图保留但 `visibility=gone`，功能收进 ⋮ PopupMenu（复用原 handler 的 performClick）；② 抽屉用原生 View 实现（`drawerScrim` 遮罩 + `drawerPanel` 300dp 面板，翻译位移动画，无新增依赖），条目：新会话/插件/自动化任务/记忆系统/搜索会话/设置/重启/隐藏顶栏；③ 抽屉条目通过 `webView.evaluateJavascript` click 引擎自己的按钮（`button[class*="_newSession"]`、`nav[class*="_panelList"] button[class*="_panelRow"]` 按索引、`button[class*="_searchButton"]`），不依赖中英文 aria-label；④ 新增 JS 桥 `DshAndroid.setTitle()` + `NATIVE_TITLE_JS`（document.title 去掉 em-dash 后缀，否则取顶部可见 h1/h2/`_title` 首行，最多 40 字），原生顶栏标题随会话/面板变化；⑤ 返回键：抽屉打开时先收抽屉；⑥ narrow-rail 插件改为在收起态**完全隐藏**图标栏（导航交给原生抽屉）。踩坑：手写注入 JS 有一次花括号不配对（`break}}}` 提前闭合函数）→ 整段 SyntaxError、`window.__dshT` 永远 false，标题不更新；改成先用 Playwright 在真机页面里 eval 验证 JS，再嵌回 Kotlin。实测手机1：顶栏标题「你好问候」/「插件」/「探索未至之境」随页面变化，☰ 拉出抽屉、抽屉切页正常、⋮ 菜单三项正常、隐藏顶栏后把手唤回。
- **窄屏侧边栏收纳为左上角单图标（2026-10-19 第二十三轮，v18）**：用户要求「侧边栏变成一个图标在顶部左边，点击才展开」。新增客户端插件 `android-plugins/narrow-rail`（package.json/index.js/client.js），窄屏（<=720px）且侧边栏处于收起态时：把引擎侧边栏根（`_root`+`_collapsed`，用 `:has(> _logoRow)` 精确匹配）宽度压到 0、隐藏除 logoRow 外的所有入口（新建/插件/自动化/记忆/工作区/搜索），把 logoRow 固定成左上角 40px 悬浮圆角按钮（`top: safe-area + 44px` 避开 App 的 40dp 半透明工具栏，z-index 900，半透明背景+模糊+阴影）；点击按钮本身就是引擎的「打开侧边栏」toggle，展开后 `_collapsed` 消失、插件样式自动失效。注册进 EngineConfig 的 overlay insert + 插件部署清单。⚠️ 构建时踩了个坑：`cp narrow-welcome/package.json` 后又用 `io.open(...,'w').write(io.open(同路径).read())` 把 package.json 截成 0 字节 → 引擎报 `disabling profile plugin row "android-narrow-rail": its declared dsh requirements cannot be validated: Unexpected end of JSON input`，修复后日志正常 `plugin deployed: narrow-rail/...`。实测手机1：收起态只剩左上角一个鲸鱼图标、内容区拿回 56px 宽度；点图标展开完整侧边栏（新会话/插件/自动化/记忆/工作区/会话列表），再点侧边栏顶部收起按钮复原。v18 APK。
- **开发者工具（experimental-inspector）启用失败修复（2026-10-19 第二十二轮，v16）**：用户开启「开发者工具」报 `Error: Cannot find module 'internal/modules/esm/loader'`（Require stack: addon-require-builtin/lib/index.js）。根因两层：① Android 上 `node-addon-require-builtin` 是 JS 兜底（预编译原生 addon 只有 linux-x64），用 `createRequire('internal/...')` 依赖进程带 `--expose-internals`；② 上游 `@deepseek-ai/dsh-experimental-inspector/lib/index.js` 的 `spawnWorker()` 显式写 `execArgv: []` —— 桌面端原生 addon 不需要 flag，Android 兜底就需要，于是 Worker 里 require internal 直接 MODULE_NOT_FOUND。修法：新增 `EngineConfig.patchInspectorWorkerForAndroid()`，在 `patchEngineForAndroid` 末尾把该文件里的 `execArgv: []` 幂等替换成 `execArgv: ["--expose-internals"]`（热更新后每次启动重打）。实测手机1：日志 `inspector patch: worker execArgv += --expose-internals` → 开启开发者工具后 `NodeJS 诊断`/`会话数据诊断` 均显示「运行中」、引擎日志出现 `dsh inspector: devtools://...ws=127.0.0.1:9230/...`，不再报 did not activate。
- **社区插件安装失败 ERROR_PNPM_IGNORED_BUILDS（2026-10-19 第二十一轮，v15）**：App 自带「社区插件」页一键安装报 `ERR_PNPM_IGNORED_BUILDS`。真因：pnpm 10+ 默认 `strictDepBuilds=true`，插件依赖里带 build script（`mnemon@1.3.0`、`puppeteer@24.x`）时被记成 "ignored builds" 并**直接让整次 add 失败**；手机没有交互式 `pnpm approve-builds`，所以必然卡住。修法：`EngineConfig.buildEnv` 统一注入 `npm_config_strict_dep_builds=false`（大小写两份），把 ignored builds 降级为警告继续安装 —— 引擎、App 插件中心、AI 子进程共用同一套 env，一处修复全局生效。实测：v15 装好手机1 后经 bridge `POST /plugin/install {"package":"dsh-mnemon"}` → `ok=true`（`Lockfile passes supply-chain policies`），`profiles/web/package.json` 出现 `dsh-mnemon ^0.5.31` + `mnemon ^1.3.0` 且 bundles 已登记；重启引擎 healthy。注意：脚本被忽略意味着需要编译的原生依赖不会构建，纯 JS 插件不受影响。
- **弹窗依旧不对的真凶：narrow-settings 误判账号弹窗（2026-10-19 第二十轮，v14）**：v13 修完仍不对，用 WebView CDP 逐层量 DOM 抓到真正原因 —— **narrow-settings 插件把账号插件的「Get started」弹窗误判成设置面板**：`findPanel()` 旧启发式是「`[role=dialog][aria-modal]` + 内含 ≥3 个 button」，而该弹窗有标题栏关闭按钮 + Add API Key + Sign in 共 3 个按钮 → 命中；随后把弹窗根标成 `panel`、遮罩标成 `content`、**卡片本身**标成 `nav`，于是卡片被套上 nav 的样式（`flex-direction:row`、`padding:6px 8px 8px`、`width:100%`、`overflow-x:auto`）→ 文字被裁、按钮跑到卡片外、还出现横向滚动 `scrollLeft=60`。修法：`findPanel()` 只认引擎自打的 `[data-shortcut-modal="settings"]` 属性（0.2.1 固定版本稳定存在），删掉启发式兜底；同时把 narrow-welcome 里 v13 加的 dialog 宽度/`overflow:auto`/max-height 覆盖撤掉（那会引入横向滚动），只保留「根容器 box-sizing:border-box」+「modal 内 description/content 强制换行」。实测：Get started 卡片居中、描述三行、按钮在卡内；「Add an API key to get started」输入弹窗同样正常（宽度 388、column、scrollWidth==clientWidth、未被标记）。v14 APK 已装手机1。
- **「开始使用 / Get started」卡片裁切真因与最终修复（2026-10-19 第十九轮，v13）**：按用户要求 `pm clear` 后复现（用 root 写入 `dsh_priv.xml` onboarded=true/NORMAL 跳过 App 自己的首次引导，然后点掉 Preview Notice）。**真因不是宽度而是 white-space:nowrap**：账号插件的描述段 `._76YFJG_description` 被设成 `white-space:nowrap`（桌面单行设计），文本自然宽度 690px 塞进 363px 的卡片，右侧被 dialog 的 `overflow:hidden` 裁掉；同时 primitives Modal 的 `[role=presentation]` 根缺 `box-sizing:border-box`（内容 412 + padding 48 = 460 > 视口 436），整张卡片被向右推 24px 贴边。用新增的 opt-in WebView 调试（`adb forward tcp:9222` + Playwright CDP）在真机 DOM 上量到这些数值并**先注入候选 CSS 验证**，再落到 `narrow-welcome` 插件：① 欢迎页容器正常流居中、文案自适应、隐藏 1247px 插画；② 所有非设置面板 modal：根 `box-sizing:border-box; width:100vw; padding:16px`，dialog `width:min(380px,calc(100vw - 32px)); max-height:calc(100vh-32px); overflow:auto`；③ modal 内 `[class*="_description"]`/`[class*="_content"]` 强制 `white-space:normal; overflow-wrap:anywhere; word-break:break-word`。截图对比：修复前文字「…to ge|t started」被切、卡片贴右；修复后「Sign in to your DeepSeek account or add an API Key to get started. Your projects and files are stored locally.」完整三行、左右留白。v13 APK 已装手机1（数据仍是清空后的全新状态）。
- **窄屏「开始使用」卡片溢出修复（2026-10-19 第十八轮）**：用户截图（`Screenshot_20261019-001141_DSH Mobile.png`）显示「开始使用 / 登录 DeepSeek 账号，或添加 API Key」卡片左右超出屏幕、文字被裁。取证：该卡片来自账号插件按**桌面**写的欢迎页 CSS —— `.xxx_welcome{width:100%;max-width:2200px;height:100%}`、`.xxx_copy{width:520px;position:absolute;left:clamp(64px,41.667vw-300px,200px)}`、`.xxx_welcomeIllustration{width:1247px}`，窄屏（~393 CSS px）下 520px 绝对定位文案必然溢出。修法：新增客户端插件 `android-plugins/narrow-welcome`（package.json/index.js/client.js，纯 CSS，`@media (max-width:720px)` 内把欢迎页改成正常流居中列、文案宽度自适应、隐藏大插画；并给所有非设置面板的 `[role=dialog][aria-modal]` 加 `max-width:calc(100vw-16px)` 兜底），注册进 EngineConfig 的 overlay insert 与插件部署清单。同时为后续窄屏排查加了 **opt-in WebView 远程调试**：外部目录存在 `webview-debug` 文件才启用 `setWebContentsDebuggingEnabled(true)`，用 `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` + Playwright CDP 可实时量 DOM/注入 CSS（已实测连上 Chrome 151 WebView，读到 innerWidth=436/scale=0.9、插件样式已注入）。产物 v12。
- **USB 桥回退（2026-10-19 第十七轮，seed v51）**：用户判定「手机 agent 派任务给电脑 agent」不稳定，要求回退。已删 `tools/usb-bridge/`（bridge/start/attach/watch/README）与 `assets/gates/pc`；EngineConfig 两处门脚本清单移除 "pc"，并加一行「已下线门脚本」清理（启动时 delete `bin/pc`，避免设备残留）；AGENTS.md 删掉「Calling the desktop agent over USB」整段，seed 50→51（老设备下次启动自动覆盖）。电脑侧：`systemctl --user stop dsh-usb-bridge/dsh-usb-attach`、`adb reverse --remove-all`、`~/.dsh/usb-bridge` 已 trash、`~/.dsh/profiles/headless/cordis.patch.yml` 已从备份恢复（撤销 deepseek-account 覆盖）。产物 v10（回退版）。结论记录：该方案依赖 adb reverse 隧道 + 电脑 headless profile 凭证，链路长、断线/休眠即失效，用户认为不稳，不再维护。
- **手机 agent 派任务给电脑 agent（2026-10-19 第十六轮，seed v50）**：用户要「手机 agent 调电脑端 agent 干活，结果手机能看」。实现：① 电脑侧 `tools/usb-bridge/bridge.mjs`（拷到 `~/.dsh/usb-bridge/`，systemd-run --user 启动）监听 127.0.0.1:9100，接口 `GET /health`、`POST /task {task,sessionId?,cwd?,json?}`、`GET /tasks`、`GET /task/:id`、`POST /task/:id/cancel`，单并发队列，执行方式是 `dsh --profile headless [--session-id] <task>`，结果落 `~/.dsh/usb-bridge/results/<id>.json|.md`；② 电脑侧 `attach.sh` 跑 `adb reverse tcp:9100 tcp:9100`（`watch.sh` 每 10s 重建，systemd 服务 `dsh-usb-bridge` / `dsh-usb-attach`），手机 loopback 即电脑服务；③ 手机侧新增门脚本 `pc`（assets/gates/pc，`pc health|ask|start|show|list|cancel|session`，走 Termux curl + node 解析 JSON，`pc ask` 同步等到 done 打印电脑端最终回复）；④ AGENTS.md 新增「Calling the desktop agent over USB」段（seed v50）教 AI 何时用、断链怎么提示用户。关键坑：headless profile 默认走失效的 `DEEPSEEK_API_KEY`（`AUTH ... api key ****b25f invalid`），需在 `~/.dsh/profiles/headless/cordis.patch.yml` 把 `agent-default-model` 设成 `provider: deepseek-account, model: deepseek-flash`（用桌面端同一份账号授权）——改完 `dsh --profile headless "..."` 正常。另外 gate 部署代码有两处清单（写文件 + 设可执行位），新增门脚本必须两处都加，否则 0600 不可执行。实测：手机 `pc ask "Reply with exactly: USB-BRIDGE-OK"` → 3s 后打印 `USB-BRIDGE-OK`（`exit=0`）。v9 APK sha256 2f8efcf2…ae6f（含 pc 门脚本 + seed v50，已装手机1）。
- **ROOT 模式引擎孤儿导致 401 白屏/会话打不开（2026-10-19 第十五轮）**：手机1 切 v8 后页面变成 401 纯文本（header 与 401 文案重叠），会话点不开，日志 `token url extracted: null` + 引擎每 ~12s「异常退出」。链路：v8 起 `bin/node` 是 wrapper，真实进程是 `lib/dsh-engine/node-bin`，而孤儿清理的 `pkill -f 'files/usr/bin/node'` **匹配不到新进程名** → ROOT 模式留下的 root 引擎一直占着 3080 → 新引擎 EADDRINUSE 退出 → 健康探测被孤儿回的 401 骗过 → 无限重启；同时这些失败启动根本没有 `dsh web:` 行，token 永远 null。修法三条：① 孤儿清理 pattern 同时匹配 `dsh-engine/node-bin`（普通 + runSu 两路），EngineProcess.kill 也补一条；② token 不再只读 engine.log，`EngineProcess` 直接从 PTY stdout 抓 `dsh web: …?token=` 存 `tokenUrl`（不依赖文件权限/时序），supervisor 优先用它；③ `pollHealth` 命中后 250ms 二次确认进程仍存活 + 端口仍应答，避免把前任引擎的 401 当成本进程健康。现场处理：手机1 手工 `su pkill` 清掉 5 个 root 引擎 → 把 `dsh_priv.xml` 的 priv_mode 从 ROOT 改成 NORMAL → 启动后 token 正常、页面正常（`token url extracted: yes`）。结论：ROOT 模式（引擎以 uid 0 跑）还有杀不掉孤儿、pkg 拒 root、文件属主漂移等系统性问题，日常建议 NORMAL/Shizuku，ROOT 只留给确需提权的任务。seed v49 已写入该提醒。
- **首启提速：引擎层前置 + Termux bootstrap 后台化 + 国内源（2026-10-19 第十四轮，seed v49）**：用户反馈首启卡太久。旧 `BootstrapInstaller.ensure` 顺序是 bootstrap 解压 → `pkg install nodejs-lts`（国外默认镜像下 23MB，实测卡数分钟）→ 引擎解压。重构：① **同步只解引擎层**（一次性 ~10s 解压，之后 1–2ms），随后立刻 spawn 引擎（首启总耗时 ~11s，之后 ~3s）；② **不再 `pkg install nodejs-lts`** —— 引擎层自带 node（wrapper + `lib/dsh-engine/` 私有库），装 nodejs-lts 会覆盖 `bin/node` wrapper 且多下 116MB；③ **Termux bootstrap 后台线程解压**（引擎健康后启动，`bootstrap: ready in ~3s`），不阻塞 UI；④ bootstrap 解完写 `etc/apt/sources.list` 为**清华 TUNA**（旧默认是 mirrors.in2.sahilister.net），且每次启动都纠正（幂等）；⑤ 新增 `healEngineWrappers()`：用户手动 `pkg install nodejs/ripgrep` 把 wrapper 换成真 ELF 时，下次启动自动写回 wrapper；⑥ 引擎层新增 `bash`（wrapper + bash-bin + libreadline/libiconv/libandroid-support 私有库），bootstrap 未完成前引擎也有可用 shell，之后被官方 bash 覆盖。AGENTS.md 补一条：ROOT 模式下 `pkg`/`apt` 拒绝以 root 运行（"Cannot run 'pkg' command as root"），要装包请切 Normal/Shizuku 模式（seed v49，27487 bytes）。脚本：`build-engine-layer.sh` 的 unzip 列表加 bin/bash、二进制闭包循环加 bash、修 sed 转义与 `$((sha256sum …))` 笔误。实测：手机1（ROOT 模式）卸载 debug 包 → 装 v8 → 引擎 21s 就绪（首启解压）→ 从备份恢复 dsh-home/linux/screens（su+chown 10276）→ 再次启动 3s 就绪；`dsh 0.2.1-rc.2 / engine dsh-engine-0.2.1-rc.2-20261019`；sources.list=TUNA；`bootstrap: ready in 2974ms`（后台）。v8 APK sha256 582e7a49…2d70。
- **彻底移除 ExtensionManager（2026-10-18 第十三轮）**：用户要求「检查并移出所有与 ExtensionManager 相关的」。删除 `engine/ExtensionManager.kt`（1500+ 行）与 `assets/extensions/` 两份 catalog；清掉引用：`DshApp.extensionManager`、`SettingsActivity` import、`EngineSupervisor` 的 `clearPendingRestart()`、`AgentBridge` 的 `/ext/list` `/ext/check` `/ext/install` 三条路由与 extList/extCheck/extInstall 实现及文档行、`strings.xml` 三种语言的 `notif_ext_*` 三条。`LocalModel` 原来靠 `ExtensionManager.activeRoots` 找 `llama-server`（llama-cpp 扩展提供），改为从 `$PREFIX/bin/llama-server`（`pkg install llama-cpp`）+ 引擎 bin 查找，/local/* 与 FastPicker 语义层保留。注释里对 ExtensionManager 的引用统一改成中性描述（DebInstaller/BootstrapInstaller 等价物）。文档同步：README 中/英/德删除「扩展中心 19 项扩展」整段，改成「真实 Termux 用户态 pkg/apt 直接装」（APK 体积 144/130MB 旧值改成 217MB 并说明构成）；docs/android-agent-routes.*、docs/TERMINAL_AND_LINUX*.md、docs/DEV_TERMINAL_LINUX.md、docs/translation-mapping.md、docs/FAST_PATH_PICKER.md、scripts/build-runtime-app-prefix.sh 里的 ExtensionManager/扩展中心/catalog 引用全部更新。grep 验证代码与文档均无残留（只剩历史叙述）。产物 v7 APK sha256 56877b80…ae026，vivo 上安装后引擎正常、App 内 AI 已在读新 AGENTS.md。
- **AGENTS.md 环境种子重写（2026-10-18 第十二轮，seed v48）**：用户要求把工程里的 AGENTS.md（App 预置给 dsh Agent 的 `$DSH_HOME/AGENTS.md`，由 `AgentContextSeed` 落盘）改成 Termux 时代的内容 —— 去掉 toybox/「没有 apt」/扩展中心那套旧世界观，教 Agent 充分使用真实 Termux 环境。做法：正文从 Kotlin 巨型字符串搬到 `app/src/main/assets/agents/AGENTS.md`（纯 Markdown，`{{SHIZUKU_LINE}}` 占位按权限模式替换），`AgentContextSeed.render()` 读资产 + 前置版本标记，`SEED_VERSION` 47→48（旧版会被自动升级覆盖，用户手写无标记的文件仍保护）。新内容：Platform（包名 com.termux → `$PREFIX` 真实、pkg/apt/dpkg 原生可用、toybox 只是 /system/bin 的 Android 自带工具）、Workspace、**Using Termux like Termux**（pkg search/install/update、apt 兼容、不要装 nodejs/ripgrep——app 用 wrapper 占了 `$PREFIX/bin/node|rg`、不要删 bash/termux-tools/libandroid-support、真 bash 支持 `=~`/`[[ ]]`、/sdcard 权限）、Toolchain（node/bash/rg/pnpm/curl/psx/killx/say/fast/dsh-update/notify/scr/loop）、Linux proot 段降级为「glibc 兜底」、Building Android apps 改成 `pkg install openjdk-17 aapt apksigner d8 zipalign` + `$HOME/android.jar`、LAN 段改用 `pkg install openssh`；**整段删除 Extension Center / Extension troubleshooting / `/ext/list` 引用**。实测 vivo 安装 v6 后日志 `AGENTS.md seeded (v48, 27217 bytes)`，引擎 healthy。产物 v6 APK sha256 4a498a4a…903a7。
- **终端中文输入 / 密码键盘修复（2026-10-18 第十一轮）**：vivo 上原生终端（TerminalActivity）打不了中文、还弹安全键盘。两个根因都在 `TerminalView.onCreateInputConnection`：① `inputType` 里带了 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`（给“显示明文密码”输入框的标志），vivo/小米等 ROM 据此弹**安全键盘**（无中文 IME）；② `TerminalInputConnection` 用 `BaseInputConnection(editable=false)`，`getEditable()` 为 null，中文/日文 IME 需要真正的 Editable 才进组合输入（拼音→候选）→ 只能落英文。修法：inputType 改为 `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE`（+IME_ACTION_NONE/NO_FULLSCREEN/NO_EXTRACT_UI，initialSel 0）；InputConnection 内置 `SpannableStringBuilder` 作为 IME 的 Editable，`setComposingText` 只更新缓冲、`commitText` 清缓冲后写 PTY、组合中退格只清缓冲不发 DEL。产物 v3 APK：`dsh-mobile-1.3.0-com.termux-arm64-dsh0.2.1rc2-v3.apk`。
- **第二台手机“进程异常”修复：引擎层自包含（2026-10-18 第十轮）**：vivo（Android 17/SDK 37）装 release 后 node 起不来，crash buffer 抓到 `CANNOT LINK EXECUTABLE "/data/user/0/com.termux/files/usr/bin/node": library "libcares.so" not found`。根因：引擎资产 zip 只带了 bin/node，没带它的 NEEDED 依赖库（libcares/libicu*/libssl/libcrypto/libz/libsqlite3/libc++_shared）；第一台手机因为早期 `pkg install nodejs-lts` 把库留在 prefix 里所以没暴露。修法：引擎层改成**自包含**布局 —— `bin/node`、`bin/rg` 是 wrapper（`LD_LIBRARY_PATH=<prefix>/lib/dsh-engine/lib` 后 exec `lib/dsh-engine/{node,rg}-bin`），真实二进制与 10 个 NEEDED 闭包库放 `lib/dsh-engine/`，私有目录不覆盖 bootstrap 同名库（apt/dpkg 不受影响）；`BootstrapInstaller.restoreExecBits` 增加 `lib/dsh-engine`（zip 丢权限位）。顺带修：`lib/apt/methods` 也没加 +x → 新装机器 `pkg install` 报 "Method .../lib/apt/methods/https did not start correctly"，现在一并补上。新增 `scripts/build-engine-layer.sh`（zip+tar.gz+manifest 统一构建），`build-engine-bundle.sh` 改为委托它。实测 vivo `pm clear` 后首次启动：bootstrap 解压 → 引擎解压 → `engine healthy :3080`，UI 正常（登录/会话可用）。产物 `dsh-mobile-1.3.0-com.termux-arm64-dsh0.2.1rc2-v2.apk`（sha256 647bf15b…cdd06）。
- **0.2.1 白屏修复（2026-10-18 第九轮，设备验证 0.2.1-rc.2 可用）**：用户报的两个错 `conversation.input: sessions service unavailable`、`renderSlot('root') before any 'root' registration (boot order)` 根因是**陈旧 WebView 页面**：引擎热更新/重启后 token 与客户端 bundle rev 都变，但 MainActivity 只在首次 Healthy 时 loadUrl（`urlLoaded` 布尔），旧页面继续用旧客户端连新引擎 → 这些 boot order/session 错误。修法：`loadEngineUrlIfNeeded()` 以 `webUrl()`（含 token）为判据，URL 变化就 `clearCache(true)` + **清 CookieManager cookie** + 重新 loadUrl（0.2.1 的 cookie 名里带 token 派生值，不清会直接返回纯文本 "dsh web authentication required"）。另一个坑：`extractTokenUrl()` 原先只扫 `logOffsetAtSpawn` 之后的日志，而 engine.log 是 2MB 环形会截断、且 0.2.1 的 `dsh web:` 输出晚于健康探测 → token 永远拿不到、WebView 拿裸地址 401 白屏；改为「offset 段 + 尾部 8KB 最后一条」双路，并在健康态 token 迟到时 `retryTokenUrlWhenReady()` 补发 state。顺带修：BootstrapInstaller 不再用内置 ENGINE_VERSION 严格相等判断（否则热更新被下次启动静默回退），`restoreExecBits` 只在 bootstrap 解压后扫 bin/libexec（原先每次启动 walk 整个 300MB node_modules，runtime-install 25s → 13ms）。实测：0.2.1-rc.2 界面/会话/侧边栏/模型 DeepSeek-V4.1-Flash 全部正常，`dsh-update restart` 后自动重载无需手动操作。
- **dsh-update 恢复为真热更新（2026-10-18 第八轮，设备实测）**：用户指出「原来能做到，现在怎么可能不能」——正确。旧的 App 侧 stage/apply/rollback 机制其实还在，只是我上一轮把 gate 改成了死提示。现在 gate 自己实现**引擎层热更新**（不碰 bootstrap）：`status/check/pull/stage/apply/rollback/restart`；`apply` 把旧层 mv 到 `<prefix>/.engine-prev`（node_modules + bin/node + bin/rg + stamp），换入后 pkill 引擎→守护重启→curl 3080 健康检查，失败自动回滚并还原 stamp。产物 = `engine-dsh-<arch>.tar.gz`（只含 lib/node_modules + bin/node + bin/rg + .dsh-engine-version），清单 `engine-manifest.json`（dsh_version/engine_version/assets.<arch>.url|sha256|size）。新增 `scripts/build-engine-bundle.sh`（从 runtime.zip 或现存 prefix 生成产物+清单）；`.github/workflows/dsh-runtime.yml` 增加 engine 产物步骤、`engine-manifest.json` 生成、滚动 release `dsh-engine-latest`（dsh-update 默认 feed）与不可变归档附件。`check` **只查官方 GitHub**、**不查 npm**：① 上游 dsh 本体 = `api.github.com/repos/deepseek-ai/deepseek-harness/releases` 里最新的 `dsh-v*` tag（当前 0.2.1-rc.2）；② 本仓库适配引擎包 = `dsh-engine-latest/engine-manifest.json` 直链，404 时用 Releases API 找带该资产的 release。两者分开报告，能区分「上游有新版」与「适配包是否已发布」。设备实测：stage ok → apply swapped/health ok → rollback restored previous + health ok。
- **移除扩展中心（2026-10-18 第七轮）**：Termux 原生 pkg/apt 到位后扩展中心已无意义，按用户要求移除：删 `ExtensionStoreActivity` + manifest 声明 + 设置页入口（layout `rowExt` 与 SettingsActivity 监听/刷新）；`EngineConfig.buildEnv` 里扩展带来的 PATH/LD_LIBRARY_PATH/JVM/**PERL5LIB/RUBYLIB/GIT_EXEC_PATH/GIT_TEMPLATE_DIR/GIT_SSL_CAINFO/MAGICK_CONFIGURE_PATH/PYTHONHOME/LIBRARY_PATH/CPATH/RUSTFLAGS** 注入全部删除（现在只保留 prefix 自身路径）。`ExtensionManager` 类与 AgentBridge `/ext/*` 端点仍保留（无 UI，供内部/兼容），扩展目录不再参与环境。`dsh-update` 已改成内置引擎版本查询：status/check 正常，pull 仅提示「引擎随 APK 打包，不能用 npm 覆盖」。
- **healTermuxShebangs 回归（2026-10-18 第六轮）**：包名切到 com.termux 后，引擎启动时仍会执行 `EngineConfig.healTermuxShebangs`——它把 `#!/data/data/com.termux/...` 的 shebang 一律改成 `#!/system/bin/sh`。旧 app.dsh.mobile 前缀下这是必要兜底，但现在 Termux 前缀**真实存在**，这一改把 `usr/bin/pkg`（bash 脚本，用 `=~`）改成 toybox sh → `pkg: syntax error: unexpected operator '=~'`。修法：只有当 shebang 里的解释器路径**确实不存在**时才降级。设备上靠删 `.bootstrap-version` 触发重新解压 bootstrap 修回官方脚本。
- **包名切到 com.termux（2026-10-18 第五轮，含事故复盘）**：用户要求「完整 Termux 环境」，最终把 applicationId 改成 `com.termux`、PREFIX 改 `<files>/usr`，运行时两层：① `assets/bootstrap-aarch64.zip`（官方 Termux bootstrap，apt/pkg/dpkg 原生，无需 proot/重编）解到 `files/usr` + 按 `SYMLINKS.txt` 重建 1213 个链接；② `assets/engine-dsh-aarch64.zip`（**内置适配版 dsh 引擎**，node_modules 闭包 + ripgrep）。node 用仓库 `nodejs-lts`（首启 pkg install）。**dsh 绝不能用 npm 装**——首启我让 BootstrapInstaller 跑 npm install，npm 的嵌套依赖 `node-addon-require-builtin` 覆盖适配版 → `No usable native binding found for node-addon-require-builtin-android-arm64` → 主页起不来；改成「先删 lib/node_modules，再从 APK 资产解压」。另一个坑：改包名后 dsh-home 里 `storages/workspace.json` / `session_projcache/*.json` 存的旧包名绝对路径导致会话恢复 EACCES（表现为主页坏），新增 `LegacyDataMigrator` 首启改写 + 重建 workspace 目录（幂等 marker `.path-migrated-com.termux`）。另修：bootstrap 解压后 `restoreExecBits`（ZipInputStream 丢权限位，bin/pkg 不能执行）；运行时资产打包前把符号链接解引用成真文件。当前设备：com.termux 版可用（引擎 :3080 healthy、主页正常、apt/pkg 原生），旧 app.dsh.mobile 已卸载，旧数据备份在 `dsh-migration/appdata-backup-0625.tar.gz`。**Termux 与官方 Termux 不能共存**（同包名不同签名）。
- **原生包管理器（2026-10-18 第四轮）**：用户要求「复用 Termux 的包管理器、只改包名重编」。已完成：**dpkg 1.22.6 + proot 5.1.107.96 + zstd 等按 app 前缀交叉编译**（补丁全在 `scripts/build-runtime-app-prefix.sh` 的 `[dsh-patch]`：dpkg 依赖裁到库/禁用 dpkg-perl 子包/源码换 GitHub 镜像、proot 补 string.h、attr/libacl 补 IFTODT、libandroid-shmem 补 _PATH_TMP）。安装走 **proot 假 rootfs + bind**：`proot -r <files>/pkgroot -b <engine>:/data/data/com.termux/files/usr -b <engine>:<engine> -b <dsh-home>:/data/data/com.termux/files/home <engine>/bin/dpkg ...`，于是 .deb 内的 com.termux 路径与维护脚本 shebang 都落到引擎里，dpkg 自己的 admindir 又是 app 前缀。**实测 `pkg install vim` 成功**（依赖 libsodium/ncurses/libiconv，76s，postinst + update-alternatives 全跑通，`vim --version` 正常）。三个坑：① Termux bash 不带 `bin/sh`（维护脚本 shebang 全是 .../usr/bin/sh）→ overlay 补 `bin/sh`；② `dpkg-deb` 调 GNU tar 的 `--warning=no-timestamp`，Android 只有 toybox → overlay `bin/tar` 过滤 GNU 选项再转 toybox；③ `update-alternatives` 在 guest 里建绝对链接，出 proot 就悬空 → `pkg` 装完把链接改写成相对（`pkg fixup` 可重跑），且构建脚本打包前把 zip 里 **270 个符号链接解引用成真文件**（否则 RuntimeInstaller 会把 bin/sh 写成 0 字节）。前端 `app/src/main/assets/runtime-overlay/bin/pkg.js`（node）负责索引解析/依赖解析/下载校验/fixup；`pkg` 门脚本只 exec 它。**apt 本体尚未编**（依赖 gnutls/gcrypt/gpgv/termux-keyring 十多个包），当前 pkg = Termux 仓库 + 真 dpkg + 自研 solver。
- **原生运行时 ≠ Termux / pkg / apt（2026-10-18 第三轮）**：用户问「为什么 pkg、apt 都用不了」。原因三条：① 原生 shell 是**最小闭包**（node/bash/ripgrep/...），不含 apt/dpkg；② 原 `pkg` 是 Termux 脚本、硬编码 source `/data/data/com.termux/files/usr/bin/termux-setup-package-manager`，前缀不匹配必挂；③ 即使塞进 apt/dpkg 也没用 —— Termux 的 apt/dpkg 前缀、dpkg 数据库、sources.list、公钥、仓库 .deb 内部路径全部是 com.termux。现在两条可用路：**① `engine/bin/pkg` 换成 App 门脚本**（`pkg list/search/install/state/where`，走扩展中心，Termux .deb 改写前缀装到 `<engine>/extensions/<id>`；实测 `pkg install archivers` 8.7s）；**② proot Linux 环境里用真 apk/apt**（实测 Alpine `apk-tools 2.14.4`）。顺带修了重编 bash 的一个真回归：bionic 的 `faccessat()` 不支持 `AT_EACCESS`，而 bash 的 `sh_eaccess()` 只走 faccessat → **`[ -x ]` 对所有文件恒假**（连 /system/bin/sh），导致 `linux` 门脚本误报 proot missing、所有用 `[ -x ]` 的脚本静默走错分支；构建加 `ac_cv_func_faccessat=no` 回落 `access(2)`，已写进 build-runtime-app-prefix.sh。运行时 v1.3.2-app-prefix-arm64 上验证 `[ -x ]`、`linux status → proot: ok`、`apk --version` 均正常。
- **WebUI 侧边栏终端打通（2026-10-18 第二轮后续）**：侧边栏报 `subprocess-local: terminal inspection is unsupported on platform android`（外加运行时里 node-pty 是空壳）。修法三条：① `PtyBridge.kt` + AgentBridge `/pty/{open,write,resize,kill,close,read}` 把 App 进程的 `libdshpty.so` 真 PTY 借给引擎，`assets/runtime-overlay/lib/node_modules/node-pty` 提供**真实现**（node-pty 的 `spawn()` 必须同步返回，用子 node 进程做 HTTP open + 父进程 `execFileSync` 等待）；② `EngineConfig.patchEngineForAndroid()` 按**内容锚点**把 `dsh-subprocess-local` 的 `platform === "linux"` 改成 `linux || android`（文件名带哈希，不能写死；幂等）；③ `buildEnv` 注入 `SHELL=<engine>/bin/bash`，否则默认是 toybox sh。C 层踩坑：PDEATHSIG 绑**线程**（fork 必须放常驻 pty-spawner 线程，否则 HTTP 请求线程一退子进程就 SIGTERM）、fork 后不能 opendir/malloc（改成 close(3..1023) 循环）、子进程里不能对 SIGSEGV 等调 sigaction（只复位普通信号白名单）、cwd 不存在会 `_exit(125)`（回落到 dsh-home）。会话表扩到 16 槽（App 终端仍 7）。真机实测：侧边栏出现 `~ $`，`bash --version` 打出 GNU bash 用法文本；App 内终端无回归。
- **运行时按本 App 包名重编译 + 热更新（2026-10-18 第二轮）**：
  - **重编译**：Termux 二进制把 `/data/data/com.termux/files/usr` 编译期写死（bash 的 SYS_BASHRC/SYS_PROFILE、openssl 配置目录、git-core、python stdlib）。文本可改、ELF 改不了（我们的路径更长），bash 的 rc 又没有环境变量可覆盖 → 装过 Termux 的真机上每个交互 shell 报 `Permission denied`。做法：用 termux-packages + `TERMUX_APP__PACKAGE_NAME=app.dsh.mobile` + `TERMUX__PREFIX_SUBDIR=engine` 从源码重编 bash（及其依赖 libandroid-support/libiconv/readline/ncurses），覆盖进收集版运行时。脚本：`scripts/build-runtime-app-prefix.sh`（宿主用 bwrap 造可写 /data，无需 root；对 termux-packages 的 7 处宿主补丁全部带 `[dsh-patch]` 且幂等）。
  - **坑**：① runtime.zip 里**不能有符号链接**（RuntimeInstaller 把条目当普通文件解；`zip -y` 会让 libreadline.so.8 变成 18 字节的“链接目标字符串”，启动报 CANNOT LINK）；② NDK 只认 r30（本机预下载 r30 到 ~/lib/android-ndk-r30）；③ GitHub archive 走 codeload 绕 429、codeberg 的 foot 源直接去掉；④ bash 需要 `TERMUX_PKG_API_LEVEL=26`（mblen 是 API 26+）；⑤ 宿主无 clang-18 → CC_FOR_BUILD=gcc。
  - **结果**：`strings bin/bash | grep com.termux` = **0**；x86_64 模拟器与 arm64 真机都是 bash 5.3.20，rc 正常读取，`--norc` 兜底已删。运行时版本 `v1.3.1-app-prefix-{x86_64,arm64}`（sha 见 MANIFEST）。
  - **热更新语义修正（同日）**：第一版 `dsh-update` 只接受「你给的 zip」、状态里只有运行时标签，用户指出「要的是拉取 **dsh runtime 最新版本**，不是 app 最新版本」。现改为以 **dsh 版本（@deepseek-ai/dsh）** 为准：新增 `RuntimeFeed`（feed 清单 `runtime-manifest.json`，键为 dsh 版本，按 ABI 给包；默认 release tag `dsh-runtime-latest`，可用 `DSH_RUNTIME_MANIFEST` 或 `--manifest` 覆盖）、桥路由 `/runtime/feed`（只读检查：本机 dsh / npm 最新 / feed 可装）与 `/runtime/pull`（下载→SHA256→复用 stage 校验解压→engine.new，同版本不重复拉）、`dsh-update check|pull [--apply]`、设置页「检查 dsh 更新 / 拉取并应用」。为什么不在设备上直接 npm 装：Android 补丁（openPath 内联、WebView polyfill、koffi/node-pty 打桩、node-addon-system flock 兜底）是**构建期**打进去的，现场装上游包会得到起不来的引擎 → 改为拉「按该 dsh 版本预先构建并打好补丁的运行时包」，由 `.github/workflows/dsh-runtime.yml` 每日查 npm 并发布。真机实测：check 正常、pull 19.6s、pull --apply 26s、同版本保护生效；`status` 现在同时给 `version`（运行时标签）与 `dsh`（引擎真实版本）。
  - **热更新骨架**：`RuntimeUpdater` + 桥路由 `/runtime/stage|apply|rollback|status` + `/engine/restart` + `dsh-update` 门脚本 + 设置页一行。stage 只写 `engine.new`（sha256/zip 结构/ELF ABI/空间校验），apply = write-ahead → 停引擎 → 原子换树 → 启动 → 90s 健康检查，失败自动换回；`.runtime-source=external` 让 `RuntimeInstaller` 不再用 APK 内置 runtime 覆盖热更新结果。模拟器实测 stage 14s / apply 4.3s / rollback 6.3s / 坏包自动回滚 / 换树中途被杀可恢复。
  - 构建：`assembleDebug -Pabi=x86_64`、`-Pabi=arm64-v8a` 均通过；`check-i18n.py` 全绿（438 strings ×3）。

- **终端 + Linux 环境（2026-10-18，本轮新增，已模拟器实测）**：给 App 加了两件原本没有的东西。
  - **原生终端**（`app/src/main/java/app/dsh/mobile/term/`）：`dsh_pty.c` 从「单引擎子进程」重构为 8 槽会话表（槽 0 保留给引擎 legacy API，JNI 符号与行为不变），上层 VT100/xterm 模拟器（主/备屏、5000 行回滚、CJK 宽字符、16/256/真彩、DECSTBM、OSC 标题）+ Canvas 自绘 `TerminalView`（IME、硬件键、触摸回滚、View 自管 PTY 尺寸）+ 多会话标签 + 额外按键条（Esc/Ctrl/Tab/方向键/KBD 收键盘）。注册了**第二个 LAUNCHER 入口**（桌面 `DSH Terminal`）。
  - **可选 Linux 用户空间**（`app/src/main/java/app/dsh/mobile/linux/`）：Alpine 3.20.3 / Ubuntu Base 24.04.3 rootfs 按需从阿里云镜像下载，proot 5.1.107.96 + libtalloc + libandroid-shmem 三个 Termux .deb 走 packages.termux.dev；`PROOT_LOADER` 显式覆盖（Termux 的 loader 路径是编译期写死的）。安装 = 下载→SHA256→原子 rename；后配置写 resolv.conf/hosts/镜像源/profile.d。**rootfs 与 proot 都不进 APK**（GPL 再分发问题）。
  - **与 dsh 打通**：`EngineConfig.buildEnv` 注入 `DSH_LINUX_HOME/PROOT_LOADER/PROOT_TMP_DIR/PROOT_NO_SECCOMP/DSH_LINUX_DEFAULT` 并把 proot bin/lib 并入 PATH/LD_LIBRARY_PATH；`assets/gates/linux` 部署到 engine/bin（`list/status/run/shell/exec/path`），seed 新增「Linux environment」一节教 AI 何时用、安装属用户动作。
  - **实测（Android 14 x86_64 AVD）**：终端跑引擎 bash（`node -v`=v24.18.0）；颜色/CJK/备用屏/多会话/IME/KBD 均通过；Alpine 与 Ubuntu 均 <10s 装完；`linux exec uname -a` 返回 guest 内核信息；Ubuntu 会话 `apt 2.8.3 (amd64)` 正常。
  - **实测（真机 Redmi K40 / Android 16 / LineageOS，arm64）**：完整包（含 arm64 runtime.zip）安装后引擎 healthy；终端引擎 bash 提示符正常；`which linux` 命中 `engine/bin/linux`；`linux list` → `alpine`；`linux exec uname -a` → `Linux localhost 4.19.325-cip131-… aarch64 Linux`；Alpine 内 `apk update` 走 aliyun 镜像返回 24059 个包。**arm64 真机无 x86_64 的 fork 限制**，与推断一致。
  - **真机顺带修掉**：Termux 版 bash 把系统 bashrc 路径编译期写死成 `/data/data/com.termux/...`，装了 Termux 的设备上每个交互 shell 多打一行 `Permission denied`；改用 `bash --norc -i`（PS1 已由环境注入，不依赖 rc）。
  - **⚠️ x86_64 模拟器限制（已定位，已在 UI 提示并让 Ubuntu 排前）**：x86_64 上 musl 用 `fork(2)` 实现 fork，Android app seccomp 以 SIGSYS 拦截（proot 日志 `seccomp SIGSYS: fork(...) = 0x39`）→ Alpine 里任何外部命令 `can't fork`。arm64 无 fork 系统调用（走 clone）不受影响；Ubuntu(glibc) 走 clone 正常。
  - **修到的两个真 bug**：① 交互式会话把 guestEnv/hostEnv 直接拼给 proot，`PATH` 重复键导致 guest 拿到宿主 PATH（`ls: command not found`）→ 改 guest 侧 `env -i` 重建；② `adb input text`/硬件键盘走 KeyEvent 而非 IME commitText，字符被丢弃 → 补 `unicodeChar` + `onKeyMultiple`。
  - 构建：`assembleDebug -Pabi=x86_64` 与 `-Pabi=arm64-v8a` 均通过；`check-i18n.py` 全绿（新增 41 个 key ×3 语言）。

- **开源仓库 Issues/PR 处理（2026-10-14，已完成）**：
  - **PR #11 已合并**（`1f317ea`，squash；贡献者 @jahdaganj02ki-sketch）：界面三语（en/de/zh，默认英语，移除「跟随系统」）+ 新增 values-de（402 键）+ 删除过时的 values-en（169 键旧部分翻译）+ 屏幕方向三态 OrientationPrefs（竖屏/横屏/自动，旧布尔键自动迁移）+ 冷启动桌面布局修复 + CI 全量触发/build-monitor/Gradle 管道退出码修复 + check-i18n 重复键检查。**合并前维护者修复了阻塞项**（`fa8d85e`）：自动发布 job 原条件在上游同样成立（会每次 push main 自动发 debug 签名包、破坏人工发布流程）→ 加 `github.repository == 'jahdaganj02ki-sketch/...'` 门禁，**实测上游运行该 job 显示 skipped ✓、Releases 无新增污染 ✓**。
  - **Issue #10**（版本检测）已回复：功能自 v1.2.77 已存在（设置 → 关于 → 检查更新，查 Releases tag 对比 App versionName，手动触发）。
  - **Issue #2**（语音唤醒）已回复：现状说明（悬浮球语音输入 / TTS / 离线 ASR 插件已有；唤醒词常驻监听仍待评估），并请对方补充使用场景。
  - **⚠️ 遗留观察点**：① `LocaleHelper` 默认值 `"system"` → `"en"`，未设置过语言的用户界面会从跟随系统变为英文（已在 PR 评论中提示贡献者，待宝宝判断是否要改）；② CI 触发扩大为 `push: ["**"]` + 每日定时构建，Actions 用量会明显上升（上游待观察后决定是否收敛）；③ 合并后上游 versionName 已是 1.2.120（与已发布 release 同号），下次发版需递增。
- **开源仓库 README 更新至 v1.2.120（2026-10-12 晚，已完成）**：三语 README（zh/en/de）版本号对齐公开 release（1.2.106/98/97 → **v1.2.120**）；内容：扩展中心补「支持卸载」（1.2.120 实际新增）、APK 体积修正约 130MB（实测 129.6MiB）、边界与 FAQ 补 Android 13+ 无障碍弹窗（需点 Allow）、简介补「不是 proot / Ubuntu 容器方案」、修德文版语言链接 bug（README.zh-CN.md 不存在 → README.md；English 误指 → README_EN.md）。**远程 main = `a83d2e4`**（共 4 个 README-only 提交，全部 fast-forward，**无任何代码提交**）；本地 main 已逐次 merge 对齐（`66af304`）。
- **用户新规则（2026-10-14）：无障碍任务一律走 daemon，对话流只做简单任务**（已改进 seed）：
  · `Tool choice` 段落开头改为 "**Decide by task size FIRST**"：① **简单 UI 任务（1-2 个动作、
    目标就在当前屏）→ 自己用 `loop run`**；② **其它（多步、探索未知页、"去做完告诉我"）→
    `parallel run "<goal>" --success "<可验证终态>"` 交给 daemon，然后别管它**——明确写了
    "不要逐步盯着（do NOT babysit）"、"不要用 scr 自己重演它的步骤"、每几轮或用户问时
    `parallel status` 轮询、卡住看 `parallel log`、`parallel stop` 中止；成功条件必须是
    **可验证状态**而非动作描述。
  · `parallel` 那条 bullet 从"长任务才用、2-3 步留给自己"改为"**所有非简单 UI 任务都交给
    daemon**，只有 1-2 动作的任务留给自己"。
  · 并写入了**理由**（让它不只是偏好）：对话流驱动多步 UI 任务 = 每步一次完整推理往返；
    daemon 把这个成本放在对话轮之外，且上下文更小。
  · 状态：seed 已改 + x86_64 debug 构建通过，**尚未装到设备**（seed 在 app 启动时由 Kotlin
    常量写入 `$DSH_HOME/AGENTS.md`，必须装新包 + 重启才生效）。
  · ⚠️ **新规则的前置条件**：`parallel run` 需要 `$DSH_HOME/.credentials.yaml` 的 `refs:`
    段里有 API key（daemon 每轮现读）。我测完已把该段还原为空 → **不先补 key，任务一律失败**。
    补法：用户在 app 里开 max+（由 app 自己写），或测试时重新注入 PC 端 key（用完再撤）。
  · ⚠️ **daemon 的头号待修**：**目标锚点缺失**（实测它中途离开目标页后跑去分析别的页面
    并自认成功）。新规则把绝大多数任务都压到 daemon 上之后，这条从"次要"升为**最优先**。
- **v1.2.157（2026-10-13 晚，未出包）两条 seed 规则 + 本地模型部署状态**：
  · **seed 加两条规则**（用户点名要求）：① **出错/点错先返回**——按 BACK/`back_to`/重开
    目标页回到"认得的状态"，重读屏幕后再用更准的锚点重试；同一失败步骤**不连续重复
    超过两次**，要换打法（先滚、先关弹窗、换别名）。② **真找不到就如实说**——报告
    "页面上现在有什么"，并且**禁止用通用页标记冒充成功**（"详情页打开了"证明不了
    **哪一个**商品打开了；必须验证被要求的**具体身份**：名称/价格/状态）。
    后者正是本项目实测过的"进错商品却自报成功"（`anyText:["商品详情"]` 任何商品页
    都通过）的根治。改的是 `AgentContextSeed.kt`，x86_64 debug 构建通过。
  · **本地模型：模型文件已部署** —— `engine/models/bge-small-zh-v1.5-q4_k_m.gguf`
    **15,448,256 字节（与 PC 下载逐字节一致）**。⚠️ 踩坑：`adb shell ... cat > file`
    传二进制会**截断**（实测只到 447KB），必须 `base64 file | adb shell "run-as ...
    sh -c 'base64 -d > file'"`。
  · **llama.cpp 扩展：装不上（缺 `llama-server` 二进制）** —— 设备上虽有 Termux 的
    `pkg`，但它是**硬编码 `/data/data/com.termux/files/usr/...`** 的脚本，DSH 运行时
    用的是自己的 prefix → 无法执行。**唯一正确的安装器是 app 自己的 ExtensionManager**
    （Packages.gz 索引 → 依赖闭包 → .deb + SHA256 校验 → 改写 prefix），而它是 UI 驱动
    （扩展中心）。→ 待办：**让 AI 去扩展中心装 llama.cpp 扩展**（正好是一次真实 UI 实战），
    装完 `LocalModel.ensureReady` 会自动拉起 llama-server（模型已就位）。
  · **daemon 对照实测（同一抽象目标：噪声页找全页最低价）**：主对话流 **✓ 通过 2/2 轮**
    （115s/128s、13/14 步、thinking 13/14）；**daemon ✗ 失败**——日志显示它中途离开
    目标页（#15"浏览器停在主页…需重新打开"）→ #16-17 跑去分析 **index.html** 的商品
    并自认"最低价 ¥699"（答非所问）。**daemon 单步快 2-4 倍**（planner 调用 969-1758ms、
    上下文 ~3.1k vs 对话流 13.8k），但**没有目标锚点**：规划提示词只喂"当前屏幕+目标"，
    一旦被带走就对着新页面重新规划。→ 待办：daemon 加目标锚（起始页身份 + 硬规则
    "已离开目标页先回退/重开，禁止在别的页面找目标" + done 前校验锚点）。
  · 凭证处理：为跑 daemon，把 **PC 端** `DEEPSEEK_API_KEY` 注入过设备
    `.credentials.yaml` 的 `refs:` 段（daemon 每轮现读该段，此前为空），**测完已还原**
    （168 字节、无 refs），未触碰用户自己的任何密钥。
- **scroll_find：三次提速尝试全部回滚（v1.2.156，2026-10-13 血的教训）**：
  ⚠️ **先说结论**：本轮对 `scroll_find` 的三次优化**全部引入回归**，已按用户认可的原版
  完整回滚（`stuck>=2` + 每轮 `settle`）。教训：
  ① **我第一次改错了地方**：改的是 `svc.scrollToFind`，而 DSL `scroll_find` 有**自己的
     循环**（在 LocalLoopExecutor）——所以"优化无效"（8.6→10.2s）根本不是优化不够好，
     而是**没改到被调用的那段代码**。改之前必须先确认调用链（`grep 调用者`），别照名字猜。
  ② **`dispatchSwipeHuman` 是异步派发**：把每轮的 `settle()` 换成固定 150ms 睡眠后，
     读屏发生在手势进行中，读到与滑动前**完全相同**的树 → 被到底判定误判成"已到底"。
     实测表现：scroll_find 滑 1-3 次就退出（目标需要 8 次）。**任何"滑完就立刻读"的
     改动都必须轮询到内容真的变化，或保留 settle。**
  ③ "首次不变即停"和"廉价复读确认即停"两种到底判据**都会**踩②的坑，一并回滚。
  **用户实测反馈（保留原版的依据）**："平均一秒不到滚一次…整体感觉完全能接受…
  比较流畅"，唯一缺点是**到底后会多划 2-3 次**（`stuck>=2` 要连续两次不变才停）。
  下一步若再动：需要**滚动位置读数**这类不依赖"读屏是否变化"的判据，
  或先确认手势落地（轮询到变化）再谈减少白滑。
  · 附带说明：v1.2.155/1.2.156 里 `svc.scrollToFind` 的那处改动（真手势大距离+自适应
    等待）**仍在包里**，它服务于 `scr find` 等路径（不是 DSL scroll_find），
    但**该路径未单独实测**——如需保守可一并回滚。
- **A（本地闭环）速度基准 + OCR 提速（v1.2.155/1.2.156，2026-10-13）**：
  · **用户决策**：A（能力层）为主体；C（关思考插件）**撤回**（v1.2.155 已从 overlay/
    assets/部署列表三处移除，包内确认无该插件）；长无障碍任务直接交给 A。
  · **A 的单动作实测**（`scripts/probes/bench_a.py`，各 3 次中位）：
    set_switch **419ms** / input **682ms** / tap 切标签 **1207ms** / scroll 单滑 **1288ms** /
    **scroll_find 10796ms（唯一异常项）**。
  · **8 步长任务一次调用跑完：17.4-19.5s（平均 2.2-2.4s/步）**；分段 act 61-67% /
    settle 21-24% / verify 12-15%。其中**滚动查找一步占 57%**（9.9-10.6s）——
    note 实测 `found after 8 swipes`，即 8 次滑动 × 约 1.25s。
    ⚠️ 本轮把 scrollToFind 从「ACTION_SCROLL_FORWARD + 固定 sleep」改成
    「真手势 75%+ 自适应等待」**未见效**（8.6→10.2s）：滑动次数没减少，目标在长页
    深处 8 次是实打实距离。下一步应做：每轮覆盖提到 ~80% 屏高、并查 dispatchSwipeHuman
    是否阻塞到手势结束（1.25s 里手势只占 0.24s）。
  · **OCR 链提速约 4 倍（本节主要成果，canvas 纯图形页实测）**：
    ① **可靠性**：`screenshotArgb` 一直被系统节流拒（errorCode=3 INTERVAL_TIME_SHORT，
       **间隔 5s 仍被拒**）→ OCR 长期显示 `ocr=unavailable`（无文本界面等于读瞎）。
       修：失败短退避重试 ×3（450ms）。
    ② **减像素**：ML Kit **infer 占大头**（791-2595ms，scale 只 8-68ms）→ 只识别内容区
       （跳过顶部 12%：状态栏+地址栏，实测这两条只产出 `o0 5:06 3G` 噪声行）；图像
       540x1140→540x1003，**infer 791-2595ms → 302-559ms**，OCR 行 8→4（干净）。
    ③ **共用一帧**：一次 dump 里 OCR 与图标识别各截一帧互相抢配额（两边都撞节流各付
       450ms 退避）→ 加 700ms"近期帧复用"，整次读屏只消耗一次配额，`shot=0ms`。
    **合计：canvas 页读屏 1370-1588ms → 326-393ms（约 4 倍）**，infer 241-296ms。
  · 产物：`dist/v1.2.156/dsh-mobile-v1.2.156-arm64-v8a.apk`（已签名 + Downloads 副本）。
- **v1.2.154「第一步思考、之后关闭」（2026-10-13，已出包）**：用户要求"把 AI 思考关了，
  除了第 1 次允许思考，以后步骤的思考都关掉"。实现方式**不碰提示词、不碰模型配置**——
  写了一个引擎插件挂 `agent/request` 瀑布钩子（agent 循环**每一步**都会带 {turn, step}
  询问该步请求配置，返回值即该步实际配置）：
  step===1 原样返回（思考开，规划值这个钱）；step>1 返回 `reasoningEffort:'off'`
  （适配器据此发 `thinking:{type:'disabled'}`；`off` 是 schema 允许值之一）。
  · 落点按项目既有惯例：`app/src/main/assets/android-plugins/no-think-after-first.mjs`
    + `EngineConfig.ensureAndroidOverlay()` 的 overlay 插入行（`name: ./android-plugins/…`）
    + 部署列表加文件名（与 priv-mode/bridge-token 同一套幂等拷贝）。
  · **实测（同一目标：搜索耳机→进详情→开开关，只给目标不给路径）**：
    思考全程开 = 78~129s、thinking 24 个、output 14537；
    **接插件后 = 50~78s、thinking 1 个、output 2748**，且三项子任务**客观全对**
    （URL `detail.html?sku=静音降噪耳机`、页面商品名=静音降噪耳机、开关节点 `cv [switch]`）。
  · 想恢复全程思考：把插件行 `disabled: true` 或 config.enabled 设 false。
  · 产物：`dist/v1.2.154/dsh-mobile-v1.2.154-arm64-v8a.apk`（150.7MB，签名，已复制 Downloads）。
- **速度归因实测（2026-10-13，用户反馈"平均接近四五秒一步"）**：把运行流水按
  「本地闭环执行」与「云端往返」拆开计时（loop run 报告里有 totalMs）：
  · 基线（只准用界面，243s / 24 个 LLM 步）：本地执行合计 **56.9s = 23.4%**；
    云端 ≈ **186s = 76.6%** → **7753ms/步**。每次 loop run 的计划**平均只有 1.7 步**，
    24 步里步步都在 thinking（事件流 24 个 thinking 事件）。
  · 单次往返地板价（只说"好的"的极小任务）：**10s**，上下文 13.8k tokens
    （32KB seed 占大头；cacheRead 5120 / input 8670）。
  · **杠杆=减少轮次**：同一目标加一句"每次 loop run 给 4-8 步，用 scroll_find/when/
    retry 兜住不确定性" → **66s**（LLM 步数 24→9，计划步数 4/3），**3.7 倍**。
    （该次页面处于"过滤未生效"的坏状态，可比性打折；页面修好后同指令 = **129s /
    12 步 / 计划 5-1-3-3-2**，本地占比 18%。）
  · 结论：**瓶颈是"轮次×单次延迟"，不是本地无障碍**（本地均摊 2.4s/步，
    其中 settle ~0.4s、verify compact 15-750ms、pick 30-60ms）。
  · 待办（最高价值）：把"批量计划 + 用 scroll_find/when 吸收不确定性"写进 seed——
    这是唯一 2-3.7 倍级别的提速杠杆，且不损失正确性（DSL 本就有这些原语）。
- **靶场自身两个 bug（都会伪装成"AI 出错"，已修）**：
  ① **`.item{display:flex}` 压过 UA 的 `[hidden]{display:none}`** → JS 里
     `el.hidden=true` 只改属性、元素照样显示（搜索过滤后计数变"共 1 项"但 8 行商品
     仍在无障碍树里）→ 修：app.css 加 `[hidden]{display:none !important}`。
  ② **`am start -d <同一 URL>` 在 Chrome 已开该页时只切回旧标签页、不重新加载** →
     整个测试跑在旧版 HTML/CSS 上（改了 CSS 却"不生效"，并误判成 AI 点错商品）→
     修：`full_suite.goto()` 与手测命令都加 cache-buster（`?_t=<ms>`），
     七页静态资源引用也带 `?v=3`。
  · 修好后再验：过滤后无障碍树里只剩 1 个「查看」、唯一商品=静音降噪耳机 ✓。
  · **仍未解决（引导缺口，非执行器缺陷）**：修好页面后 AI 依旧落到
    `detail.html?sku=星耀 Pro 手机`——它自己的计划 #4 明写 `target:["星耀 Pro 手机"]`
    （即它当时的 dump 里第一行就是它），根因是**验证用通用页标记、不断言商品身份**，
    加上过滤状态在多次往返中丢失。待 seed 补"验证要带身份标识"。
- **v1.2.153 靶场 + 真实任务实测（2026-10-13）**：新建复杂测试网站 `scripts/probes/testsite/app/`
  （9 文件：app.css / app.js / index / shop / detail / wizard / long / vision / slow），
  把无障碍难点做进页面：无文字图标按钮、1.6s 轮转 banner（页面永不"稳定"）、
  3.5s 自动弹窗打断、空输入框 placeholder、8 个同名「查看」按钮、实时搜索过滤、
  无限滚动、懒渲染（滚到才出现）、标签页、折叠面板、20 个开关（目标在靠底部）、
  range 滑块、两层确认（自定义模态 + 原生 confirm）、三步向导 + 校验、分段长文、
  Canvas 画出的按钮（节点树里不存在）、纯图标工具栏、4.2s 加载页（进度条强信号）。
  PC 起服务：`python -m http.server 8899 --directory scripts/probes`，
  模拟器 URL：`http://10.0.2.2:8899/testsite/app/<page>.html`。
- **只给目标的真实任务实测（未知路径，两次）**——用户点出"真实控制时 AI 不可能知道下一步会遇到什么"，
  故改用 `headless_task.py`（真 agent，只给一句目标，不给任何路径/锚点）：
  · **第 1 次（141s，46 步，exit=0）**：目标含 URL → AI 用 `curl` 把靶场 HTML/CSS/JS 拉下来
    grep 选择器（**约 1/3 命令花在读源码上**），3 件事**全部完成**（已客观核验开关为 `cv`）；
    工具调用 loop run×8 / scr dump×15 / /screen×10 / loop pick×3 / scr tap×2；缓存命中 **98%**。
  · **第 2 次（243s，24 步，exit=0）**：目标改为「只准用界面、不许读源码/curl」→ 不再 curl，
    但耗时 **+100s**（源码抄近路确实有效，禁掉就要靠试）；loop run×16 / scr dump×4；
    另花 ~4 条命令 `sed/grep` 读 `engine/bin/loop` 自己摸索 DSL 用法。
    **⚠️ 关键发现：第 2 次最终汇报自称"进入其详情页"，但客观状态是
    `detail.html?sku=星耀 Pro 手机` —— 进错了商品（搜索结果本该是「静音降噪耳机」）；
    开关确实开了。即 AI 自述成功 ≠ 实际成功。** 根因是验证不够具体
    （用 `anyText:["商品详情"]` 这类**通用页标记**做验证，任何商品页都通过），
    属**引导缺口**（seed 未要求"验证带身份的具体标识"），非执行器缺陷。
  · 另观察：AI 会用 shell `for` 循环做重复滑动（不走 DSL 的 repeat）、
    会用坐标 target（从 dump 抄 x,y）绕过歧义——都是可继续引导的点。
- **本轮（v1.2.153）修的能力缺陷（全部来自靶场实测，非提示词）**：
  ① **空输入框 placeholder 进 dump**（`p:` 前缀，API 26+ 读 `hintText`）——此前
     `<input placeholder="搜索商品">` 在 dump 里只剩 `- @x,y ce [edit]`，AI 既认不出
     也点不准；真实世界的登录/搜索/验证码框全中招。compact + JSON + 匹配链路（含语义）全打通。
  ② **pick 同档质量优先可交互节点**——反例：目标「搜索商品」同时命中不可点的
     `<label>`（精确 1.0）与可点可编辑的输入框（0.95），旧逻辑在精确档直接返回 label →
     点击落在死标签上静默无反应。现在同档内优先 clickable/editable。
  ③ **`input` 动作回退到步骤级锚点**——此前只认 `action.target`（字符串），
     AI 自然写成步骤级 `target.text` 就静默失败。
- **v1.2.152 已出包（2026-10-12 晚，未上架）**：5 份子智能体代码/架构审查的发现**已全部处理**——修复 5 个 P0 + 9 个 P1（其中 1 个 P0 是审查漏掉、本轮实测抓到的 daemon 语法错误，详见 Version timeline 的「v1.2.152 审查修复轮」）。产物 `dist/v1.2.152/dsh-mobile-v1.2.152-arm64-v8a.apk`（150.7MB，签名摘要 `d28ca4f5…`）已复制到 `C:/Users/Qt/Downloads/`；模拟器 x86_64 debug 端到端验证通过。
- **⚠️ 重要更正（宝宝指正 + 本地核实，2026-10-12）**：**「本地闭环执行器」「场景记忆」不属于 v1.2.120 的公开能力，公开文档不得宣传**。核实细节：dist/v1.2.120 APK 的 dex 虽含 LocalLoopExecutor/SceneMemory 类，但 AI 侧无入口（seed 加 loop 说明的提交 `d79db55` 晚于 v1.2.120 发布构建，`ad9df8b` 只为插件卸载升版本号），实际不可用。README 已移除相关小节与全部配套描述（性能表闭环行 / Termux 段 / 边界视觉定位补充），只保留 1.2.106 基线 + 插件卸载 + APK 体积 + Android 13+ 无障碍说明。**本文件及工作区根大 AI_CONTEXT 里旧的「v1.2.120 已发布」功能清单（含闭环/记忆/视觉定位/checkedText）不可信，勿照抄进公开材料。**
- **⚠️ 事件记录（教训）**：本次初次操作时误将本地 60 个未推送提交一并推送（`a08189a`），已立即 `push --force-with-lease` 回滚至 `0b88f8c`。**远程代码已恢复原状，未上架任何新版本，release 仍为 v1.2.120**。遗留：GitHub Actions 留有一次 a08189a 的 i18n-check 失败记录与 android-build run（未产生 release）；`a08189a` 在 GitHub 上不可达（不在任何分支/PR 上，正常浏览不可见）。
- **边界（现状）**：1.2.121–1.2.151 全部功能（离线 OCR、本地嵌入模型、分支计划、max+、角色标记、幂等开关、parallel daemon、intent 直达、图标识别、拟人化手势、技能沉淀、乐观执行……）**仍在本地改进中，全部未公开**；README 只描述到 1.2.120 为止的能力。
- **发布状态**：对外公开版本保持 **v1.2.120**（已补安装包）；**v1.2.121 不上架**（草稿）。
- **v1.2.128 已出包**（`dist/v1.2.128/` 与 `C:\Users\Qt\Downloads\`，arm64 签名，未上架）：离线 OCR + 本地模型自动就绪 + settle 提速 + verify 语义兜底 + 用量统计 + 分支计划 + max+ 开关（知情同意）+ 模型来源选择 + 坐标越界修复 + **引导 AI 用本地闭环（seed v34 Tool choice + batch 动态提示）**。
- pending 记忆机制完整闭环验证通过；记忆诊断可见性已改进（1.2.122）。

## Verified facts
### i18n-check CI 失败清单（2026-10-12 推送 a08189a 后实测，`python scripts/check-i18n.py` → FAILED(14)）
- **Kotlin CJK 字面量 13 处**：`PageStateDetector.kt:48`（加载中/正在加载/载入中/正在载入/请稍候/请稍等）、`ScreenOcr.kt:212`（`＠` 全角替换）、`CredentialProbe.kt:101/105/106`（DeepSeek 官方/通义千问/智谱 GLM）、`SceneMemory.kt:251`（记忆诊断）、`DshAccessibilityService.kt:1127`、`AgentBridge.kt:1175`。
  **修复建议**：PageStateDetector 的加载文本与 ScreenOcr 的 `＠` 是必要的界面匹配/文本处理（与 seed 保留中文串同原则）→ 加 `check-i18n.py` allow-list；CredentialProbe 友好名、SceneMemory 诊断消息属界面文本 → 挪 strings.xml 或 allow-list（待宝宝拍板）。
- **catalog 不一致**：`catalog.zh-rCN.json`（20 项）缺 `asr-service`，`catalog.json`（21 项）有 —— `90ca9fd` 加 asr 时只改了英文目录 → 中文目录补条目即可（README 现写 19 项，为 1.2.120 时代公开数量，二者不冲突；后续发布新版本时如需宣传 20+/21 项再考虑修 catalog）。

### 快捷命令 + 截图自动化 + 提示词规范（1.2.138）
- **截图**：`/screenshot?save=1` 保存到文件只回路径（base64 每帧吃 ~100-200KB 上下文），旧图自动清理（保留 12 张）；`scr shot --save` 推荐用法；seed 教 AI 用 `read_image` 按需读。
- **应用级直达（+3）**：`scr macro app-info|app-notify|app-perms <pkg>`——应用详情/通知/权限一步直达（实测 1 步 vs 此前 4-5 步）。
- **提示词规范（seed v40 + daemon）**：计划模板（可照抄的 JSON 形状 + action.type/verify 键白名单，明示"未知字段被忽略，不要发明"）；「绝不重复」规则（规划前重读历史与屏幕；已成功不重做；屏幕已是目标态即完成；解释重复点开关会翻回去）。
- **⚠️ 模拟器测试流程**：每次重装 APK 后无障碍服务会被系统停用——**测试前先跑 `scripts/probes/enable_a11y.py`**（真机不受影响，用户已确认）。

### 边规划边行动（1.2.136-1.2.137）：常驻守护进程 + 执行环境扩展
- **架构**：主 AI 委托（`parallel run "<goal>" --success "<终态>"`）→ 常驻 `planner-daemon`（独立于对话轮）循环「读屏 → DeepSeek 规划 → 本地闭环执行 → 观察 → 再规划」→ `parallel status/log/stop` 查询。任务队列用文件（`$DSH_HOME/tasks/`），API key 走 refs 段。
- **执行环境扩展（1.2.137）**：`intent` 动作（12 个系统设置页直达：DISPLAY/WIFI/SOUND/BLUETOOTH/BATTERY/APPLICATION/LOCATION/SECURITY/DEVICE_INFO/INTERNAL_STORAGE/DATE/ACCESSIBILITY_SETTINGS）+ `/launch-intent` 端点 + `scr macro` 14 个快捷命令。
- **实测**：intent 单步直达 3.7s/1 步（此前 3-5 步）；daemon 任务「打开深色主题」**21s 完成（4 步，用了 intent）**，对比扩展前 45.8s —— **提速 2.2 倍**。
- **实测暴露并修复**：① daemon 读屏失败无限重试 → 连续 6 次快速失败；② app 重启后**孤儿 daemon 持旧 token** 全部 403 空转 → 加启动自检（403 即退出让 gate 重启）；③ 测试库 `pidof node` 多进程取错 token → 改按 cmdline 找引擎。
- **环境痛点（待产品级修复）**：无障碍服务反复被系统停用（Android 13+ 侧载限制清理 `settings put`）→ UI 自动化全部失效。建议 app 侧自愈（检测未启用 → 引导/自动恢复）。

### 四轮 AI 反馈迭代闭环（1.2.131→1.2.134，2026-10-12）
**方法论**：每轮「真实任务 → 访谈 AI（哪里不方便）→ 按带数据的反馈修复 → 重测」。四轮成果：

| 轮次 | 修复内容 | 效果 |
|---|---|---|
| 1 | 角色标记 `[switch]/[row]`、`set_switch` 幂等、`verify.toggle`、修 `scr macro open-settings` 字段名 bug | 深色主题任务 118s→35s |
| 2 | 失败帧 `failScreen`（escalate 时附屏幕快照）、重试角色守卫（防重选到页面标题） | 无回归 |
| 3 | `scroll` + `scroll_find`（命中即停/别名/tap 一步到位）、verify 默认超时 5000 | 滚动任务调用 20→8 次 |
| 4 | **role 优先级坑**、loop run 超时 10s→120s、scroll 后 settle、scroll_find 幅度 35%+到底检测 | 综合任务 **7 次调用 / 64.7s** |

- **⚠️ Kotlin 优先级坑（第 4 轮抓到，值得全仓警惕）**：`a + if (x) "p" else "" + if (y) "q" else ""` 被解析为 `a + if (x) "p" else ("" + if (y) "q" else "")` —— **else 分支吞掉后续拼接**，导致所有带 flags 的节点丢失 `[role]`。修复=显式加括号。AI 的"dump 里没有 [switch] 标记"报告是发现它的线索。
- **最终验证（AI 新对话跑 4 阶段综合任务）**：7 次工具调用（初版 43 次）、64.7s、任务完成；AI **主动使用 failScreen**（"失败帧证明点击已生效，无需重复点击"）并自我诊断锚点选择问题。
- AI 报告的非工具问题：偶发 dump 超时（启动瞬间）、set_switch 校验延迟（主题切换 3.7s，预算内）。

### AI 体验反馈驱动的无障碍优化（1.2.131，2026-10-12）
- **方法论**：直接向 AI 发真实任务 + 访谈（"哪里让你感到不方便"）→ 它给出带实测数据的痛点清单 + 12 条改进建议。**一轮访谈 = 最准的需求来源**。
- **AI 报告的核心痛点与已修**：
  1. **dump 无控件角色** → 把导航行（`d:深色主题 co`）误判成开关 → 4.4s escalation。已加 `[role]` 后缀（switch/row/button/edit/slider/list/scroll；text/image 静默）。
  2. **缺幂等开关原语** → toggle 重试会二次翻转。已加 `set_switch`（读态→仅必要时点击→回传 note）；实测重试时 `already on` 命中，**幂等语义防止了二次翻转**。
  3. **无状态位断言** → 已加 `verify.toggle {label|text, to: on|off}`（**能验 off**；checkedText 只验"标签旁有已勾选节点"，off 永远失败）；修 `needsDump` 遗漏（只有 toggle 时不读屏→必然超时）。
  4. **`scr macro open-settings` 100% 失败**（真 bug）：gates 传 `{"pkg"}`、端点只认 `"package"` → 已修两端。
- **实测**（同一深色主题任务，三次迭代）：118s → 42s → **35s**；AI 最终双重确认状态（勾选位 o→v + 副标题变化）并正确理解幂等语义。
- **AI 反馈中尚未实现**（第二轮候选）：失败帧自动附带（escalate 时给失败屏幕文本，省一次 dump）、重试角色守卫（实测重试曾重选到页面标题 score 0.95，"比不重试更危险"）、OCR 行与无障碍节点去重（坐标差 176px）、`scr changed --tree`（只回变化节点）、`loop run --dry-run`、`scr launch --wait`、usage 补全 macro/launch/changed。

### 端到端 AI 测试与 243s→48s 的真相（1.2.130，2026-10-12）
- **方法**：`scripts/probes/headless_task.py` 用 `dsh --profile headless --json` 直发任务；**必须带两个 --patch**：
  ① `agent-instructions` 的 id 覆盖（`config: {maxBytes: 65536}`，base 有此行但 headless 需显式补 config）；
  ② **`engine/android-overlay.yml`** —— 关键！`dsh-subprocess.scrubbedParentEnv()` 会清除所有 `DSH_*` 变量，`DSH_BRIDGE_TOKEN` 必须经 shell-env 注册才能到 AI 的 shell（否则 scr/loop 全部 403，AI 被锁在设备能力之外）。cwd 用 app 私有目录（外部存储 workspaces 无权限）。
- **同一极难任务三次对比**（Chrome 打开 canvas 页并列出中文）：
  | 环境 | 耗时 | 工具调用 | 结果 |
  |---|---|---|---|
  | v34 无 token 注册 | 243s | 43（22 次找 token） | ✓ 完成 |
  | v35 无 token 注册 | 80s | 9 | ✗ scr 全 403，快速失败 |
  | **v35 + token 注册** | **48s** | **13，零探索** | ✓ 完成（9 行文字全对）|
- **结论**：243s 主要是**测试环境缺 token 注册**导致 AI 花 ~110s 摸索环境，不是产品性能问题；真机 web profile 由 app 注入 android-overlay.yml（token 已注册）→ 体验应接近 48s。
- **AI 高效行为**（48s 测试）：`scr dump` 勘察 → `scr launch com.android.chrome` 一步启动 → `scr idle` 等待 → `scr shot` 截图 + 读图/OCR → 输出；最终回答正确说明「canvas 文字不在无障碍树，用截图 + 离线 OCR 读取核对」。
- **seed v35 新增**「Environment (read once — do NOT re-verify)」：命令已在 PATH、bridge 认证内部处理、禁止找 token/curl 3083/读包装脚本/翻 `$PREFIX/bin`。

### 端到端 AI 测试：极难任务自主规划（1.2.129，2026-10-12）
- **方法**：`scripts/probes/headless_task.py` —— 用 `dsh --profile headless --json`（无 Host/HTTP 层的一次性 Agent）直发任务给模型，环境变量从运行中引擎进程 `/proc/<pid>/environ` 复制（保证一致）；`--json` 输出机器可读事件流，统计工具调用特征。API key 走 `.credentials.yaml` 的 **refs 段**（PC 环境变量注入模拟器，仅测试用）。
- **任务**（极难操作）：在 Chrome 打开 `http://10.0.2.2:8899/_ocr_canvas.html`（canvas 绘制、节点树无文字、比屏幕宽 2.6 倍）并列出页面中文文字。
- **结果（243s，成功）**：`loop run` **4 次**（v34 引导生效 ✓）、`scr dump` 21 次（勘察）、`tap-text` 1 次；AI 自主完成 launch → 输入 URL → 横向滚动逐段读取 → 输出全部 9 行中文，且**明确说明「canvas 文字 DOM 无文本 → 用截图 + 离线 OCR 逐段读取，并与页面源码 fillText 交叉核对」**——OCR 兜底在真实 AI 工作流中得到验证。
- **观察**：dump 21 次偏多（每次一次推理往返）；横向滚动读取场景可理解，后续可引导用 `loop` 的 `collect` 动作收内容。
- **附带修正**：查明 API key 真实存储在 `refs:` 段（`renderRef()` 写 `refs.<REF>`；records 段是 grant/api-key 结构化记录）→ `CredentialProbe` 改为两段都扫，否则真机 WebUI 配的 key 会被漏掉。

### 引导 AI 用本地闭环而非自己操作无障碍（1.2.128，用户反馈驱动）
- **现象**（真机反馈）：「ai 好像还是在自己操作无障碍功能」——AI 用 `scr tap`/`tap-text`/`scr batch` 逐步操作，没走 `loop run` 本地闭环。
- **根因**：seed 里 `scr batch` 描述过于详细（"run a whole flow in ONE turn" + 完整字段表），而 `loop` 只是 "prefer" 级措辞 → AI 倾向选前者（它没有本地判别/验证/重试）。
- **修复（三层）**：
  1. seed v34 新增 **「Tool choice (non-negotiable)」** 规则段：多步 UI 任务必须用 `loop run`；逐个 `scr tap` = AI 自己当 picker（每步一次推理往返）；`scr batch` 不是替代品（无判别/验证/重试，一步错全错）；`scr` 保留给勘察/单步特殊手势/宏/纯手势序列；含自检句（"If you catch yourself writing a sequence of `scr tap`/`tap-text` calls, stop and express it as a `loop` plan instead"）。
  2. `loop` 行强化为 "THE tool for multi-step UI automation"；`scr batch` 行加 ⚠️ 警告与适用边界。
  3. **`/batch` 响应动态提示**（仅 ≥3 步）：告知本次由 AI 自己执行、无判别/验证/重试，下次改用 `loop run`——静态提示词易被忽略，响应内反馈更直接。
- 实测：`AGENTS.md` 注入 v34✓ 新规则可见✓ 3 步 batch 响应含 `tip`✓。
- 注意：seed 属提示词层软约束；`tip` 是每次多步 batch 都会出现的强反馈。

### max+ 模型来源选择（1.2.127，模拟器全流程实测）
- 需求（真机反馈）：「有多个模型多个 api key 怎么处理？检测并给个选项，默认 DeepSeek 官方（响应最快）」。
- **CredentialProbe**：解析 `$DSH_HOME/.credentials.yaml` 的**记录键名与 kind**，只列 `kind: api-key` 记录（`grant` 类会话凭证自动排除）；**不读取不输出 key 值**（行级解析只保留键名）。友好名：DEEPSEEK→DeepSeek 官方、OPENAI→OpenAI、MOONSHOT→Kimi 等。
- 设置页「模型来源」行（**仅 max+ 开启时显示**）：显示当前选择，点击弹单选列表；默认选含 DEEPSEEK 的凭证。
- 存储：`dsh_ui` SharedPreferences 的 `a11y_max_provider`（凭证记录键名，空=按默认兜底）。
- 实测（构造 3 key + 1 grant 的凭证文件）：关闭→行隐藏✓ 开启→行出现且默认 DeepSeek 官方✓ 弹窗列 3 项✓ 选 OpenAI→显示更新+存储 `llm-openai/openai-api-key`✓。

### ⚠️ 凭证文件格式（踩坑记录，改动前必读）
`.credentials.yaml` 有**严格校验，写错会让引擎启动失败**（`StartupError: 1 required plugin did not activate` → credentials）：
- **键名**必须 `<scope>/<id>`，两段都匹配 `^[a-z][a-z0-9-]*$`（小写连字符；`env/DEEPSEEK_API_KEY` 这种大写会被拒）；
- **权限**必须 600（否则 `readable beyond its owner` 拒绝）；
- **字段白名单**：`kind: api-key` 只允许 `key`/`env`；`kind: grant` 只允许 `payload`；
- 引用名（如 `DEEPSEEK_API_KEY`，`/^[A-Za-z_][A-Za-z0-9_]*$/`）与存储键名是**两套命名**。
- 诊断入口：`$DSH_HOME/logs/startup-*.log`（含失败插件与堆栈）。

### 无障碍功能 max+（1.2.126，模拟器全流程实测）
- 位置：设置页「扩展」分组末尾（钥匙图标 + Switch），默认关闭。
- 行为：**从关到开**必须先过 AlertDialog（明确告知①读取本机 API key 驱动云端持续预测 ②自动下载 15MB 本地嵌入模型并启动本地推理服务）；CANCEL 回弹不落盘；**从开到关**无弹窗直接落盘。
- 存储：`dsh_ui` SharedPreferences 的 `a11y_max_plus`（DshApp.KEY_A11Y_MAX_PLUS / a11yMaxPlusOn()）。
- 作用：`startLocalModelAutoReady` 受其控制（日志实测 off 时 `local-model auto-ready skipped (Accessibility max+ off)`）；**OCR 预热不受限**（模型随 APK 分发、不联网、不读凭证）。
- 验证矩阵（全部通过）：行渲染✓ 弹窗内容✓ CONTINUE 落盘 true+选中态✓ 关闭无弹窗+false✓ CANCEL 回弹+不落盘✓ 日志控制✓。

### 坐标越界点击修复（1.2.126，实测发现）
- **现象**：引导页「GET STARTED」节点中心 @540,2211 超出屏高 2209 → `dispatchGesture` 返回 true 但手势落在屏幕外，**点击静默失败**（真机「底部按钮点不到」的典型原因）。
- **修复**：`dumpScreenCompact`/`dumpScreenJson` 输出**可见区域中心**（节点与屏幕求交后的中心）；`dispatchTap` 在 jitter=0 时也钳制坐标。
- **实测**：修复后 @540,2178 点击成功进入主界面；DSH 手势注入总体正常（English 选项点击成功切换界面）。

### 对话流「持续预测」缺口（2026-10-12 用户指出，架构分析）
- **现象**：对话流里 AI 只能同步 `loop run` 后「确认当前内容再继续」。
- **原因**：对话流是请求-响应模型（一轮推理→工具→结果→下一轮），推理期间无法执行工具；「持续预测」需要**独立于对话轮的进程**。现状：并行架构（predict_parallel.py 的 epoch/进度同步/去重）只在 PC 测试脚本里；gates 无 parallel 命令；seed 第 87/92 行虽写了并行协作语义但无对应工具。
- **已做补强（纯本地、零额外 API）**：**分支计划** —— loop 步骤支持 `"when":{"anyText":[...],"noneText":[...]}`，云端一次推理生成多分支（弹窗/版本差异），本地按屏幕状态选路；条件不满足 → `status=skipped`（不算失败）。seed v33 已教云端此格式。
  - 实测：条件满足→ok、不满足→skipped、noneText→ok，总 ok=True 2.1s（3 步 1 跳过）。
- **待决策方案**：设备内并行协调器（`loop parallel "<goal>"` → 后台进程循环「读屏→调 API 预测→喂本地执行器」）。**关键约束**：需要 API 访问——引擎环境变量里无 key（只有 DSH_BRIDGE_TOKEN/DSH_HOME 等），key 在 `$DSH_HOME/.credentials.yaml`；用户此前明确「不希望系统直接读取用户 API」，故该方案需用户拍板（或改用引擎自身接口代理）。

### 本地判决 AI 的适用边界（用户提问，2026-10-12）
- **近义/不同表述**（「深色模式」vs「深色主题」）：语义匹配强项（实测 0.742 / 0.926）。
- **完全无文字**（纯图标/图形）：语义匹配用不了（无文本）；兜底链 = 无障碍文本 → OCR（图里的文字）→ 语义匹配 → 视觉模板匹配（VisionTracker，需预存模板，非自动）→ 云端看图。

### 本地判决 AI「很少用」的架构分析与修复（2026-10-12，用户反馈驱动）
- **根因**：pick 的锚点来自云端抄的屏幕原文 → exact 层必中，语义层天然少触发；verify 此前是纯 `contains` 精确子串 → 本地模型完全没参与验证。
- **修复**：`LocalLoopExecutor.semanticTextMatch` —— verify 精确 miss 后用本地嵌入语义匹配，阈值 **0.78**（比 pick 的 0.62 严：验证误判=错误完成，代价大于点错）。真实收益场景：OCR 识别噪声、近义表述、云端概括词。
- **统计**：`FastPicker.stats`（pickExact/pickSemantic/pickNone/verifySemanticHits），`/local/status` 输出。
- **端到端实测（模拟器，本地模型全链路跑通）**：
  - catalog 修复后 `/ext/list` 21 条可加载 → `POST /ext/install {"id":"llama-cpp"}` → green 0.6.0 → `POST /local/ensure` → `modelReady=true, serverRunning=true`（10s 内）。
  - 语义 pick：`深色模式`→0.742、`显示与触控设置`→0.926（对屏幕「显示和触控」）。
  - verify 语义兜底：anyText `显示与触控设置`（精确 miss）→ `verify=True`，`verifySemanticHits 0→1`。
- **能力边界（诚实记录）**：嵌入模型（bge-small-zh，24M）擅长文本相似度（近义/错字/跨语言），**不适合意图理解**（goal→动作规划需要 LLM）。本地判决 AI 的正确定位 = 快速匹配 + 快速验证；规划仍归云端。

### v1.2.123 真机反馈修复（2026-10-12 模拟器实测）
用户真机反馈：① 基本没用到本地判决 AI ② OPPO 浏览器无障碍取不到文字 ③ 不快、经常 sleep。根因与修复：
1. **本地判决 AI 用不到的根因 = 无触发路径**：`/local/ensure` 在 UI/seed/gates 里**都没有调用点**（模型永不下载、llama-server 永不启动）；且 `catalog.json` 语法错误使扩展中心解析失败、llama.cpp 装不上。修复：无障碍服务连接后后台 `ensureReady`（失败静默）+ seed v32 告知云端 `/local/status` 与 `/local/ensure`。
2. **OCR 兜底（ML Kit bundled 16.0.1）**：`libmlkit_google_ocr_pipeline.so` + 25 个模型文件随 APK 分发（**无 GMS 依赖**，+~15MB）。`ScreenOcr` 截图→文本行（中英混合）；`dumpScreenCompact`/`dumpScreenJson` 在**内容区**（屏幕 25% 高度以下）字符 < 40 时自动补 OCR（头行 `ocr=Nlines/<ms>`，行前缀 `o`）。`/ocr?force=1&scale=` 端点。缓存 3s + 截图节流冷却 3s + 服务连接预热。
   - **判定必须用内容区而非全屏**：Chrome 类界面顶部 UI 壳 97 字符但页面内容区 0 节点（实测全屏判定漏触发）。
   - **verify 必须也能看到 OCR**：verify 用 `dumpScreenJson`，此前只有 compact 版带 OCR → 「pick 能用 OCR 文本、verify 永远失败」（实测点击成功验证失败）。
3. **settle 提速**：`waitForIdle` 对空节点树连续两次空读即返回（此前每次动作前白等 900ms 超时）；settle 预算 1800→1200ms、帧差间隔 300→200ms。

**实测数据（模拟器 x86_64，canvas 页面 = 无节点树场景）**：
- dump 首次触发 OCR：`1080x2209 18nodes ocr=10lines/1419ms`；3s 内重复 dump：20ms（cached）。
- 识别正确：`设置中心 @598,750`、`显示和触控`、`深色主题`、`亮度调节 自动`（均为 canvas 绘制的文字）。
- 闭环：`loop/run` 单步 → `ok=True via=exact score=1 picked=设置中心 actMs=1132 verify=True verifyMs=69`，总 1.45s。
- OCR 推理耗时与分辨率几乎无关（540p/270p 均 ~1.7s，模拟器 CPU 固定成本主导）→ 保持 scale=0.5 保质量；真机 CPU 快数倍，待真机复测。
- 截图本身仅 36-72ms（`/frame-diff` 实测），不是瓶颈。

### 发布（2026-10-11 晚）
- GitHub repo `Soodok/Deepseek-Harness-Local-Android`：
  - **v1.2.120**（release id `409571993`）：`draft=false`（公开），资产已补齐（`dsh-mobile-v1.2.120-arm64-v8a.apk` id `631149424`、`-debug.apk` id `631150479`，各 129.6MB）。
  - **v1.2.121**（release id `409712207`）：`draft=true`（撤下），APK 资产保留在草稿内。
  - 公开页：`https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.2.120`
- **v1.2.123 arm64 签名包**（本地，未上架）：`dist/v1.2.123/dsh-mobile-v1.2.123-arm64-v8a.apk`（150.5MB，证书 SHA-256 `d28ca4f5…92c8`，与 120 同证书可覆盖安装）；zip 完整性 OK、含 `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so` + 25 OCR 模型 + `assets/runtime.zip`(126.5MB)。
- **签名命令**（release 无 signingConfig，构建后手动签）：`gradle assembleRelease -Pabi=arm64-v8a` → `E:/Android/Sdk/build-tools/36.0.0/apksigner.bat sign --ks keystore/dsh-release.keystore --ks-pass pass:dshmobile2026 --ks-key-alias dsh --key-pass pass:dshmobile2026 --out <out.apk> app-release-unsigned.apk`。

### pending 记忆完整闭环（2026-10-12 模拟器 emulator-5554，APK 1.2.121→1.2.122）
1. **产生 pending**：带 `useMemory:true` 的 2 步计划执行成功 → `/loop/run` 返回 `memory.result="stored-pending"`。
2. **任务级转正**：并行流程（`predict_parallel.py 困难-深色主题开关`）done 时自动 `/memory/confirm` → 日志 `[记忆] 任务级确认：1 条 pending 转正`；墙钟 11.8s、目标达成。
3. **转正后命中复用**：同 goal 再跑 → `memory.hit=true, similarity=1, result="hit-confirmed"`，hits 0→1，执行 7998ms→1190ms。
4. **诊断字段对照**：转正条目 `pending=False`、新条目 `pending=True`；stats `记忆条目 2 条（待确认 1 条），累计命中复用 1 次`。

### 并行架构与记忆的设计关系（本轮查明）
- `predict_parallel.py` 的 `plan_entry()` **有意强制 `useMemory=False`**（源码注释：记忆会整份替换 steps，并行协调必须知道真正执行了哪些预测步骤）→ 并行流程本身不产生 pending；其 done 时的 confirm 用于转正**其他路径**（本地闭环/云端决策执行）产生的同 goal pending。

### 发布（2026-10-11 晚）
- GitHub repo `Soodok/Deepseek-Harness-Local-Android`：
  - **v1.2.120**（release id `409571993`）：`draft=false`（公开），资产已补齐：
    - `dsh-mobile-v1.2.120-arm64-v8a.apk`（asset id `631149424`，129.6MB）
    - `dsh-mobile-v1.2.120-arm64-v8a-debug.apk`（asset id `631150479`，129.6MB）
  - **v1.2.121**（release id `409712207`）：`draft=true`（撤下，不可见），其两个 APK 资产保留在草稿内。
  - 公开页：`https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.2.120`
- 本地 `dist/v1.2.120/` 两个 APK 上传前已验证：zip 完整性 OK、含 `AndroidManifest.xml` + 4 个 dex + `assets/runtime.zip`（126.5MB 压缩、16980 条目、含 node_modules/libreadline.so/ripgrep）+ 正式包有 v1 签名与 `.idsig`。

### 提示词英文化（本轮前完成）
- `AgentContextSeed.kt` `SEED_VERSION = 31`；本地闭环执行器与 UI 自动化工作流描述全英文（facts vs promises、2–3 步短预测、验证语义、yielded、takeover、anti-patterns）。
- 协议名与选项保留：`anyText`、`checkedText`、`frameChanged`、`foregroundPkg`、`waitSliceMs`、`useMemory`、`useScreenEvents`。
- `planner.py` 的 `ROLE_RULES`/`SYSTEM_OBSERVE`/请求模板/场景规则/诊断全英文；动作类型、字段名、JSON 结构、验证语义不变。
- `planner.py` 中 `已下载完毕`、`正在下载` 两个中文串是屏幕实际标签，故意保留（用于匹配本地化文本）。

## Verification evidence
- v1.2.120 两个资产下载链接 HEAD（不带 token，模拟普通用户）均返回 `HTTP 200`、`129.6MB`。
- v1.2.121 API 查询确认 `draft=True`。
- `python -c "import ast; ast.parse(open('scripts/probes/planner.py',encoding='utf-8').read())"` → `planner AST: OK`。
- 2026-10-11 23:35:55 +0800 `gradle -p . assembleDebug -Pabi=x86_64` → `BUILD SUCCESSFUL in 5s`。
- 并行架构最终验收（英文化后）：困难-APK 下载场景墙钟 **70.0s**、完成 True、停止原因「目标达成」，云端 41 次调用 / 13 份预测计划。
- 2026-10-12 `gradle -p . assembleDebug -Pabi=x86_64`（1.2.122）→ `BUILD SUCCESSFUL in 37s`；`adb install -r` → `Success`，模拟器 `versionCode=171 versionName=1.2.122`。
- 模拟器重装后引擎启动约需 30-60s（runtime 重新解压），`loop_common.init()` 首轮可能超时，重试即通。

## Current blocker
- None.

## Key paths
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/ScreenOcr.kt`（离线 OCR：降采样/缓存/节流冷却/预热）
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt`（dump 兜底 + waitForIdle 快速返回 + 本地模型自动就绪）
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt`（seed v32：OCR/本地快速层说明）
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/engine/SceneMemory.kt`（pending 机制 + 诊断字段）
- `E:/Deepeek harness/dsh-android/app/src/main/java/app/dsh/mobile/engine/LocalModel.kt`（本地嵌入模型：下载 + llama-server 启动）
- `E:/Deepeek harness/dsh-android/scripts/probes/loop_common.py`（测试库；`/memory/clear` 等写端点须传 body 才是 POST）
- `E:/Deepeek harness/dsh-android/scripts/probes/_ocr_canvas.html`（canvas 测试页；PC 起 `python -m http.server 8899 --directory scripts/probes`，模拟器 Chrome 访问 `http://10.0.2.2:8899/_ocr_canvas.html`）
- `E:/Deepeek harness/dsh-android/dist/v1.2.120/`（已上架产物）
- `E:/Deepeek harness/dsh-android/dist/v1.2.121/`、`dist/v1.2.123/`（未上架）

## Version timeline
- 2026-10-11: handoff created.
- 2026-10-11: seed v30→v31 英文化 + planner.py 英文重写；AST 与 x86_64 debug 构建通过。
- 2026-10-11 深夜: v1.2.120 release 补上两个 APK（公开可用）；v1.2.121 撤下保持草稿。
- 2026-10-12 上午: pending 记忆完整闭环验证通过（产生→转正→命中）；v1.2.122 诊断可见性；**v1.2.123 真机反馈三修复**（离线 OCR + 本地模型自动就绪 + settle 提速）+ catalog.json 语法修复；**v1.2.124 verify 语义兜底 + 用量统计**；**v1.2.125 分支计划（when 条件）**。模拟器端到端全通（扩展安装→模型就绪→语义 pick/verify→分支选路）；arm64 签名包在 dist 与 Downloads。提交 `a213cf0`/`15b154c`/`6c86b88`/`57196a4`/`ba06359`/`5f6ff5d`。
- 2026-10-12 中午: **v1.2.126 无障碍功能 max+ 开关**（扩展分组，知情同意弹窗：读取 API key + 自动下载本地模型）+ **坐标越界点击修复**（部分可见节点中心超出屏幕 → 点击静默落空，真机「底部按钮点不到」根因）。提交 `2298d39`/`f17ff88`。
- 2026-10-12 下午: **v1.2.127 max+ 模型来源选择**（多 API key 检测 + 用户自选，默认 DeepSeek 官方）；查明凭证文件严格格式（键名/权限/字段白名单，写错致引擎启动失败）。arm64 包在 dist 与 Downloads。
- 2026-10-12 傍晚: **v1.2.128 引导 AI 用本地闭环**（用户反馈「AI 还在自己操作无障碍」）：seed v34「Tool choice (non-negotiable)」+ loop/scr batch 措辞强化 + `/batch` 响应动态 tip（≥3 步）。提交 `47d0add`。
- 2026-10-12 晚: **v1.2.152 审查修复轮**——5 份子智能体审查（2 份代码 + 3 份架构/性能）共出 4 个 P0、若干 P1，全部处理：
  - **P0-1 `_fastVerify` 污染记忆**：乐观标记被写进 plan 的 step → 随场景记忆持久化 → 此后**非乐观**请求命中记忆时中间步骤被静默乐观化（验证语义被悄悄放宽）。修复=store 前深拷贝并剥离 `_fastVerify`。
  - **P0-2 daemon DSL 白名单只有 11/21 原语**（缺 scroll_collect/repeat/repeat_until/back_to/long_press/pinch/drag/double_tap/collect/sleep，且从不提 `when` 分支）→ 新能力在 daemon 路径不可见。已补齐 + 补 when / optimistic / human 说明。
  - **P0-3 daemon 丢升级证据**（failScreen/candidates/error 未进规划 AI 上下文）→ 已透传进 history。
  - **P0-4 `scr pick --all` 语义反了且从未进请求体**（`--all) P_ALL=false`）→ 改为 `P_CLICKABLE` 并真正进入 body；顺带把闸门注释改成与实现一致（preferClickable 只影响**排序**，不是过滤——文本节点才是点击目标）。
  - **P0-5（审查漏掉，本轮实测抓到）`planner-daemon.mjs` 语法错误**：v1.2.146 加的「VERIFY THE MINIMUM」一行用反引号包了 foregroundPkg 示例，在模板字符串内部**提前闭合了模板字面量** → 整个文件 JS 解析失败、**daemon 自 v1.2.146 起根本起不来**。`node --check` 复现并修复（转义），现已纳入发布包内容校验。
  - **P1 修复**：IconMatcher/Framer 有符号 Byte 回绕（灰度 128+ 变负 → NCC 与帧差判定失真）、dispatchSwipe/dispatchLongPress 漏打自身标记（自己的手势被误判"用户接管"）、verify 未知 key 静默通过改明确报错、toggle 空标签立即失败、loop 闸门超时 120s→195s（必须 > 服务端 180s，否则拿不到服务端结构化超时报告 → AI 猜着重跑，是唯一会放大真实副作用的路径）、daemon bridge() 补 _http（两处 403 自检此前是死代码，孤儿守护进程永远不自愈）、screenExec 单线程被 /loop/run 独占（并发读屏全 504）→ 2 线程、图标识别加时间预算守卫（截图撑爆 2s 读屏上限 → 级联 504）、PageStateDetector.maxLoadingMs 因 AND 条件永不生效 + 换单调时钟。
  - **本轮实测新发现并修复（审查之外）**：
    ① **冷读 504**：同一静止页面，屏幕刚变化后第一次读树要 2.1-2.5s、之后 57-77ms（连续 5 次取证）——2s 上限把"节点树正在重建"误报成"无障碍卡死"，AI 收到 504 只会重试一次、正撞上重建完成，白花一轮。修复=超时后一次放宽重试（5s）；/loop/run 这类长预算路径不重试。
    ② **加载信号分强弱**：isLoading 原先 ProgressBar ∥ 加载文案 ∥ total<5 同等对待，会把"稀疏但已就绪"的页面也判成加载中；改为只有**强信号**（进度条/加载文案）才延长等待（maxLoadingMs 6s→4s），空树只走常规预算。settle 实测 355-1566ms（不再拖长）。
    ③ **verify 一律用 JSON dump → 改 compact 快路径**（仅 anyText 时；checkedText/toggle 仍走 JSON 结构化字段）。compact 体积约 1/7，且**含 OCR 行/图标识别名/空间推断标签——正是 AI 在 dump 里看到的内容**；实测 how=anyText(compact)、verifyMs=5ms。
    ④ **foregroundPackage() 返回系统壳层**：Chrome 弹 ANR（"没有响应/关闭应用/等待"）时窗口列表顶部是 package=android 的对话框窗口 → foregroundPkg 验证 8s 全预算失败，而页面就在对话框底下活着；权限弹窗同理。修复=跳过系统壳层包（android / systemui / permissioncontroller）取下面真正的前台应用。实测回桌面后正确返回 com.google.android.apps.nexuslauncher。
    ⑤ 删掉 scroll_find 的重复不可达分支（Kotlin when 取首个匹配）、seed 里 date +%s%3N（toybox 不支持，旧指令产出垃圾字符串）。
  - **新增测试基建**：`scripts/probes/sim_setup.py`（一键就绪，固化 6 条踩坑：侧载停用无障碍、**必须先起 app 再启用无障碍**否则得"连着但读不到节点"的僵尸绑定、force-stop 后要 delete+put 才重绑、引擎健康检查 ~25s 桥在其后才起、bridge_token() 会 sys.exit 杀掉调用方、残留 node 占旧 token）。
  - **验证证据**：node --check 全库 JS 通过、sh -n 全库 gate 通过、x86_64 debug 端到端（launch / compact verify / scroll_collect / 升级证据）通过、发布包解包校验 5 项修复均在包内且包内 daemon 语法 OK。
  - **遗留**：模拟器上 Chrome 反复 ANR（环境负载：本 app + 引擎 + OCR + llama-server 同跑），部分场景未能端到端跑完；v1.2.141-1.2.152 的多数改动仍待**真机复测**。
- 待办：真机复测 1.2.128（重点看 AI 是否改用 `loop run`；OCR 速度、浏览器场景、语义验证触发率——`/local/status` 的 stats；本地模型需先在扩展中心装 llama.cpp 扩展并开启 max+）；**后续可选：设备内并行协调器**（现在有 max+ 开关 + 模型来源选择作为 API 授权基础）；libz.so.1 反馈已查明为环境问题（三个 runtime 包均含 libz.so.1.3.2 + 软链接）。

## 2026-10-17 竖屏 UI 修复（客户端插件形式，v1.2.175 → v1.2.179）

### 用户需求
「手机端 UI 在竖屏的时候会非常奇怪」→「旁边列表展开时，旁边对话框直接被莫名其妙压缩，
整个就很畸形，可以直接让侧边栏全展开，然后隐藏对话流，切换到横屏的时候就不用这么做
（可以做个屏幕判定）」→ **「UI 布局改变不要直接改源代码，要直接做成一个插件形式，
假如用户安了其他 UI 插件，我们可能会冲突」** → 「你要不要重构一下这个设置？这个设置还是有点畸形」

### 根因（真机取证，非推测）
1. **竖屏对话区被挤压**：引擎 `@deepseek-ai/dsh-client-ui-layout` 的 `AppFrame` 用
   CSS Grid（`grid-template-columns: <sidebar>px minmax(400px,1fr) minmax(0,<r>)px`）。
   视口 < `SIDEBAR_AUTO_COLLAPSE`(1024) 时进入 narrow 模式；用户手动展开侧栏后
   `computeColumns()` 仍按桌面规则分配（侧栏 264~420px + 假设对话区至少 400px）→
   1216x2640 屏（视口 386px）下对话区被压到 **336px**，文字逐字换行（用户截图取证）。
2. **设置面板畸形**：`dsh-client-ui-settings-general` 的面板 `.VOzbGW_panel` 固定
   `width:800px; max-width:calc(100vw - 48px)`，左导航 `.VOzbGW_nav` 固定
   `width:188px; flex:none` → 手机上面板 338px、导航吃 188px、内容区仅剩 ~150px，
   标签被压到 **47px**（uiautomator 实测 `[743,437][790,563]`）→ 逐字竖排。
   该页 CSS **只有 `prefers-reduced-motion` 一条媒体查询，无任何宽度断点**。

### DSH 客户端插件机制（本次摸清，关键知识）
- 客户端 UI 插件 = **标准 npm 包**：package.json 声明 `dsh.client`（含 `platform`/`inject`/
  `external`/`immediately`）+ `exports["./client"]`。
- 引擎 `@deepseek-ai/dsh-client-modules` 扫描宿主 Loader entries（cordis.yml 的行）→
  `locatePkgJson(loaderName, baseUrl)` 定位 package.json → `nearestPackage()` 从**插件文件
  路径向上找到最近的 package.json**（要求 `name` 与 loader 期望名一致）。
- 浏览器端 bundle 由引擎以**经典 script**（非 ES module）加载，必须调用
  `window.__ModuleLoader__.load({ id, factory })`，且 **id 必须严格等于 package.json 的
  `name`**（引擎按包名建索引，见 `reconcilePackage`）。漏注册 → "loaded without registering"。
- Loader entry 指向**具体文件**（`./android-plugins/<pkg>/index.js`）—— Node ESM
  **不支持目录导入**（实测 `Directory import ... is not supported`）。
- `nearestPackage` 会向上一直找，故 `android-plugins/` 上层不能有 package.json（已确认无）。
- 验证方法：`curl -sL -b ck.txt "http://127.0.0.1:3080/?token=<TK>"` 的返回页里能 grep 到
  `<pkgname>/client.js`（与官方插件并列在 preload 清单里）。

### 交付物（全部为插件，零引擎源码改动）
1. `app/src/main/assets/android-plugins/narrow-sidebar/`（package.json + index.js + client.js）
   - 定位：按无障碍标签 `收起侧边栏`/`Collapse sidebar` 找到折叠按钮 → 向上找
     `display:grid` 祖先 → 该列即侧栏列，`nextElementSibling` 即中心列。
   - 行为：`innerWidth <= 900 && innerHeight > innerWidth`（竖屏）时，给侧栏列打
     `data-android-narrow-sidebar-col`（CSS `grid-column: 1 / -1`）、从中心列起给
     **列**打 `data-android-narrow-yield-col`（CSS `display:none`）。
   - 判列用 `isColumn()`：跳过 `position:absolute/fixed`（overlay 浮层）与
     `data-shell-overlay` —— 否则会误伤浮层（jsdom 实测抓到该 bug）。
2. `app/src/main/assets/android-plugins/narrow-settings/`（同结构）
   - 定位（两道**硬门槛**，缺一即放弃）：① `isInOverlay()`（自身或祖先 fixed/absolute）
     ② `hasCloseButton()`（aria-label/title/文本/lucide-x 四种匹配）；
     再要求 ③ 直接子元素中有含 ≥3 按钮的（导航列）+ 另一个较大的兄弟（内容列）
     ④ 自身宽 > 55% 视口 或 高 > 50% 视口。
   - 行为：`innerWidth <= 720` 时给面板打标记 → 面板竖向堆叠、导航改顶部横滑图标条、
     内容列拿满整宽。
3. 部署：`EngineConfig.kt` 的 `ensureAndroidOverlay()` 里 `deployOne()` 逐文件部署
   （含 v1.2.173/174 的「读不了就删掉重建」+「`!dst.isFile ||` 判空」两处修复），
   overlay 追加两条 insert entry。

### P0 事故与修复（我自己引入，真机复现）
- **v1.2.178 黑屏**：`narrow-settings` 初始判据只有「按钮多 + 有大兄弟 + 规模可观」，
  主界面 AppFrame（侧栏含新会话/插件/设置/会话项多个按钮 + 大对话区）**完全命中** →
  被当设置面板改成竖向堆叠 → **界面黑屏空白**（用户报告「界面头黑的都空白的」）。
  修复=加 `isInOverlay` + `hasCloseButton` 两道硬门槛；已加场景⑨（主界面结构必须不被
  标记）与场景⑩（带关闭按钮的浮层面板仍正常接管）两个回归测试。
- **rAF 竞态**：`schedule()` 写成 `raf = window.requestAnimationFrame(refresh)` ——
  当 rAF 同步执行时（测试桩），refresh 内清掉的标志被返回值写回 → 此后所有 schedule
  被 "已在排队" 挡掉，**转屏后不再重判**（桩测试抓到）。修复=用独立 `pending` 布尔，
  在调用 rAF **之前**置位。

### 测试
- `scripts/probes/_test_narrow_sidebar.mjs`（自写 DOM 桩，30/30 通过）
- `scripts/probes/_test_narrow_jsdom.mjs`（jsdom 真实 DOM + 引擎真实结构，26/26 通过）
- `scripts/probes/_test_narrow_settings.mjs`（jsdom，29/29 通过，含黑屏事故回归⑨⑩）
- 依赖：`scripts/probes/` 下 `npm install jsdom --no-save`

### 真机验证证据
- 日志：`[dsh-android] narrow-sidebar: 已接管（侧栏全宽，隐藏 2 个对话列；视口 386x777）`
  （引擎日志 `/data/user/0/app.dsh.mobile/files/engine/engine.log`，由 MainActivity 的
  `onConsoleMessage` 转发 —— 只转发 WARNING+ 或含 `[dsh-android]` 前缀的消息）。
- 设备文件哈希 = 本地哈希（`f3cc06de…`，含 `isInOverlay`/`hasCloseButton`）。
- 截图确认：主界面正常（v1.2.179）、竖屏展开侧栏 → 侧栏全宽 + 对话流隐藏。

### 待办
- 设置页修复效果**尚未真机视觉确认**（操作期间用户在用酷安占前台，`am start` 无法切回；
  已用设备文件哈希 + 引擎 bundle 清单间接确认插件已上线）。
- `fast-mode.mjs` 仍报 `failed to import`（**仅历史日志**；直接 import 测试通过，
  最近几次启动无该报错，但值得复查是否与 overlay entry 顺序有关）。

### 设备操作要点（本次新增踩坑）
- Git Bash 下 adb 设备路径会被 MSYS 转换 → 必须 `export MSYS_NO_PATHCONV=1`。
- 无线 adb（192.168.101.21:9000）会周期性掉线，需 `adb connect` 重连。
- `adb push` 到 `/data/local/tmp` 用 **Windows 真实路径**（`C:/Users/Qt/AppData/Local/Temp/...`），
  `/tmp/...` 是 Git Bash 虚拟路径，adb 看不到。
- 引擎 node 直接跑脚本验证导入：`su -c 'B=/data/user/0/app.dsh.mobile/files; export
  LD_LIBRARY_PATH=$B/engine/lib:$B/engine/bin; export DSH_HOME=$B/dsh-home; cd $B;
  $B/engine/bin/node /data/local/tmp/x.mjs'`（引号地狱时改用推送 .sh 文件执行）。
- 大包部署：`split -b 10M` 分片 → 设备端 `for i in $(seq 1 N); do cat p_$i >> x.apk; done`
  → `md5sum` 比对 → `pm install -r`（InstallerX 会拦截 `adb install`）。

### 2026-10-17 续：v1.2.179 / v1.2.180 两个真机事故与修复

**事故 A — v1.2.178 主界面黑屏（P0，我引入）**
- 现象：用户报「怎么都改坏了？界面头黑的都空白的」。
- 根因：`narrow-settings` 的 `findPanel()` 只看「按钮多 + 有大兄弟 + 规模可观」，
  **主界面 AppFrame 完全命中**（侧栏含新会话/插件/设置/会话项多个按钮 + 大对话区）
  → 被当设置面板改成竖向堆叠 → 黑屏。
- 修复：加 `isInOverlay()`（自身或祖先 fixed/absolute）+ `hasCloseButton()` 两道硬门槛。
- 回归测试：场景⑨（主界面结构不得被标记）、场景⑩（带关闭按钮的浮层面板仍生效）。
- 已部署 v1.2.179，真机确认主界面恢复正常。

**事故 B — v1.2.179 设置页导航消失（用户报「看不到选项栏」）**
- 现象：内容可读但需要横滑、左侧/顶部导航栏不见。
- 根因：`findPanel()` 按文档顺序遍历 `div/section/aside`，**先命中外层 `overlay`**
  （它自身 fixed、含全部按钮、有 ≥2 个子元素）→ 标记打到 overlay；
  `structureOf` 把 `panel` 当成"导航列" → 真导航被 `[nav] > :first-child{display:none}`
  隐藏、`content` 被设成 row → 内容横滑。
- 修复（引擎源码取证后）：
  1. 定位优先用 `[role="dialog"][aria-modal="true"]`（引擎自标的对话框语义，
     `.VOzbGW_panel` 上实测有这个属性）+ 退路才用浮层+关闭按钮；
  2. `structureOf()` 优先用 **`<nav>` 语义标签**识别导航列（引擎 DOM 实测是 `<nav>`，
     不是 div —— 这也是之前误判的另一半原因）；
  3. **删掉** `[nav] > :first-child { display: none }` 规则（它把整条导航隐掉了）；
     导航标题改为紧凑小标签保留。
- 回归测试：场景⑪（标记必须在 panel 而非 overlay；nav 必须被标记；mask 不得被当内容列；
  CSS 不得隐藏首个子元素）。
- 已部署 v1.2.180，真机截图确认：顶部导航横条（设置｜通用设置｜模型｜内置插件｜Agent 预设）
  完整可见、可横滑；内容纵向正常滚动；卡片不再被压扁。

**累计测试**：`_test_narrow_sidebar.mjs` 30/30、`_test_narrow_jsdom.mjs` 26/26、
`_test_narrow_settings.mjs` 36/36（含全部事故回归）。

**m-dash 教训（写进下一次的检查清单）**
1. **客户端插件改 UI 前，必须先从引擎 bundle 里取证真实 DOM 结构**
   （`role` / 语义标签 / 真实层级），不能凭 GUI 截图猜。本次两起事故都源于猜测。
2. **任何"给元素打标记"的逻辑，必须在测试里断言"标记打在了哪个元素上"**，
   而不是只断言"有没有标记"。
3. **CSS 里禁止 `:first-child` / `:nth-child` 这种位置选择器**作用于导航/标题等
   结构性元素 —— 必须用语义属性或 JS 精确定位后再打标记。
4. 打包前必须跑：`node --check` 全部插件 + 三套测试全绿 + 包内资产校验。

## 2026-10-17 GitHub Issues 处理（v1.2.181）

仓库：`Soodok/Deepseek-Harness-Local-Android`（token 走 API 直连，`gh` 未登录）。
处理前开放 5 条，全部已回复 + 打标签。

| Issue | 结论 | 处理 |
|---|---|---|
| #16 更改无法查看 | **真 bug，已修** | 新增 `narrow-rightbar` 插件（v1.2.181） |
| #12 拒绝 Shizuku 后仍弹窗 | **v1.2.158 已修** | 回复说明修复内容 + 请用户升级确认 |
| #15 手动添加 MCP | 能力已有，无 UI | 回复完整配置示例（stdio / streamable-http） |
| #10 App 内检测版本升级 | 已有功能 | 回复指出 设置→关于→检查更新，并问是否需要自动检查 |
| #2 语音唤醒 | 路线图 | 回复现状（语音输入/TTS/离线 ASR 已有，唤醒词未做） |

### #16 根因（引擎源码取证 + 数值验算）
`dsh-client-ui-layout` 的 `computeColumns()`：
```js
const available = viewport - sidebar - 400;   // 固定给中心区留 400
const rightbar = rightbar === 0 || available < 300 ? 0 : Math.min(available, …);
```
竖屏视口 386px、侧栏折叠 56px → `available = 386-56-400 = -70 < 300` → **右栏宽度 = 0**（不是挤压，是没这列）。
叠加：窄屏 `autoFullscreen = viewportWidth < 768` 生效时，面板挂在 0 宽列里，
`--dsh-sidebar-width: 100vw` + `transform: translateX(...)` 把 dock 推出屏幕外。

### 新增插件 `narrow-rightbar`（assets/android-plugins/narrow-rightbar/）
- 定位：`[data-sidebar-right-panel]` **且** `[data-sidebar-right-open]`（引擎语义属性，
  仅在打开时存在）—— **关闭态绝不接管**，不可能凭空冒出面板。
- 行为：`innerWidth <= 900` 且面板打开 → 打 `data-android-narrow-rightbar`，
  CSS 把面板提为 `position:fixed; inset:0; width:100vw; z-index:60`，
  `--dsh-sidebar-width:0` + dock 强制 `visibility:visible; transform:none`。
- 宽屏（>900）完全不动。
- 测试：`scripts/probes/_test_narrow_rightbar.mjs` **28/28 通过**
  （含「关闭态不接管」「dock 保持引擎原样」「面板重建→标记迁移」三个关键回归）。

### 备注
- Issue 回复用 GitHub REST API（token 在对话里出现过，**建议撤销后换新**，
  见文首安全提示）。`/tmp` 是 Git Bash 虚拟路径，Windows 版 curl 读不到 —— 用
  `C:/Users/Qt/AppData/Local/Temp/`。
- 本地仓库仍有 **131 个未提交改动**（v1.2.159 → v1.2.181 全部工作都还没 commit），
  HEAD 停在 `31b4a26`（v1.2.158）。发布到 GitHub Releases 前必须先提交推送。

## 2026-10-17 v1.2.182 → v1.2.184（用户要求：删开关 / 修语音闪烁 / 操作通知）

### 1) 删除「试验性功能」与「无障碍功能 max+」两个开关（v1.2.182）
- `activity_settings.xml`：删掉 `rowExperimental`（48 行）、`rowA11yMaxModel`（63 行，
  含前置分隔线）、`rowA11yMaxPlus`（57 行，含后置分隔线）。
- `SettingsActivity.kt`：删掉两个 Switch 的全部逻辑、`applyExperimentalVisibility()`、
  `applyA11yMaxVisibility()`、`setRowVisibleWithDivider()`、`KEY_EXPERIMENTAL`。
- `DshApp.kt`：删 `KEY_EXPERIMENTAL` / `experimentalOn()` / `KEY_A11Y_MAX_PLUS` /
  `a11yMaxPlusOn()`；保留 `a11yMaxProvider`（仍有引用）。
- `DshAccessibilityService.kt`：
  · 悬浮窗**常驻**（`StatusOverlay.show()` 不再受开关控制）；
  · **移除 max+ 控制的本地模型自动下载/启动** —— 那段行为需要用户知情同意
    （读 API key + 下载 15MB 模型），开关取消后不能再静默执行。
    引擎需要时仍可显式调 `POST /local/ensure`（AgentBridge）。
  · 原实验性功能涉及的四行（无障碍/麦克风/语音服务/本地识别）现在**始终可见**。
- 清理 10 个孤儿字符串（中英同步删除），i18n 键集保持一致。

### 2) 语音输入闪烁（v1.2.182 + v1.2.183 + v1.2.184）
真机反馈两个阶段：
- **识别结束时闪**：`onFinal`/`onEnd` 立刻 `setListening(false)` → `setIdle()` →
  悬浮窗 `expandLevel=0` **自动收起**，视觉上就是文字出现那瞬缩一下。
  修复：识别通道的 `onFinal` 不再立刻回空闲；只有**面板为空**（误触/没听清）时
  才 hide + setListening(false)。有文本则保持聆听态直到发送/关闭（`panel.onHide`
  统一收口）。
- **点击发送时框闪一下**：旧顺序是「先发文本 → 再播 180ms 淡出」；提交会让对话页
  立刻滚动，半透明面板正压在滚动的页面上 = 闪一下。
  修复：发送路径改为 **先 `hide(animate=false)` 再 `onSend`**；`hide` 立即路径
  （v1.2.184）先 `alpha=0`、**延后一帧 `post{ removeView }`** —— 避免在窗口自己的
  ACTION_UP 派发过程中同步销毁窗口（会出现一帧表面残影）。同时移除前先
  `input.clearFocus()`，防止输入法骤然收起导致宿主重排。

### 3) AI 长串无障碍操作的系统通知（v1.2.182）
- `AgentBridge.route()`：POST 命中动作类端点（`/tap /tap-id /gesture /key /input
  /scroll-find /scroll-center /scroll-edge /launch /launch-intent /vision/tap
  /vision/drag /loop/run`）时调用 `noteA11yOperation()`；读屏类不计入。
- 规则：**连续第 3 步起**提示，之后**原地更新**同一条通知（固定 id `0x0A11`、
  `setOnlyAlertOnce` 不重复响铃），最后一步后 10s 自动撤掉；30s 无动作视为新序列。
- 无 `POST_NOTIFICATIONS` 权限时静默跳过（不影响操作）；点通知回到 App。
- 新增字符串：`notif_a11y_active_title/_text/_open_app` + 8 个动作标签
  （`notif_a11y_op_*`，中英各一套）。

### 4) 项目门禁
- `python scripts/check-i18n.py`：我的改动**不再新增错误**；EngineConfig 的
  overlay 注释已全部英文化（该文件约定 CJK-free）。仓库仍有 **20 条历史遗留错误**
  （CredentialProbe 中文标签、SceneMemory 中文摘要、AgentBridge 视觉错误文案等），
  属既有问题，未在本次范围内处理。

### 版本
- v1.2.182（versionCode 230）：删开关 + 通知 + 识别结束闪烁
- v1.2.183（231）：发送路径瞬间移除面板 + 清焦点
- v1.2.184（232）：alpha=0 + 延后一帧移除（修发送时"框闪一下"）

### 设备注意事项（本次新增）
- **ColorOS/OnePlus 上 `pm install -r` 会把无障碍服务关掉**（`enabled_accessibility_services`
  变 null）。重装后需重新启用：
  `adb shell settings put secure enabled_accessibility_services
  app.dsh.mobile/app.dsh.mobile.DshAccessibilityService`
  `adb shell settings put secure accessibility_enabled 1`

### 2026-10-17 续：语音发送后「悬浮框收起闪一两下」（v1.2.185）
- 真机反馈：发送之后，语音悬浮框收起时会闪一两下。
- 根因：悬浮窗从「展开卡片」变回「圆形球」是**窗口尺寸变化**
  （文案/动作行隐藏、padding、minWidth/Height、背景同时变），View 属性动画管不到
  窗口尺寸 —— 直接改会在 1~2 帧里整体跳变；且它和语音面板窗口移除几乎同帧，
  两个 overlay 窗口同时重排更明显。
- 修复（`StatusOverlay.setIdle()`）：
  1. 把**所有**会改尺寸的改动（文案、动作行、档位）挪到 `alpha=0` 期间；
  2. 顺序改为「延迟 60ms → 80ms 淡出 → 改布局 → 140ms 淡入」，跳变不可见；
  3. 新增 `collapsePending` + `cancelCollapse()`，任何新状态（`setStatus` /
     `setListening(true)` / `flash*`，它们都走 `cancelIdleReset`）都会取消待执行的收起，
     避免新任务开始时把悬浮窗缩掉。

### 2026-10-17 续：语音面板发送时「闪一两帧」—— 最终结论（v1.2.186）
- 用户反馈历经三轮：`v1.2.183` 立即移除、`v1.2.184` alpha=0+延帧移除、
  `v1.2.185` 归因悬浮球收起并加淡出淡入、`v1.2.186` **停靠而不销毁窗口**
  （alpha=0 + NOT_TOUCHABLE/NOT_FOCUSABLE，`wm.updateViewLayout` 不 removeView，
  下次 `show()` 复用）。**仍然能观察到 1~2 帧闪烁。**
- 用户判断：截图/录屏抓不到（就少一两帧），不要继续追。
- **结论（未验证，标注为推测）**：这类 1~2 帧残影很可能来自 **TYPE_ACCESSIBILITY_OVERLAY
  窗口在合成器层的重绘/可见性切换**，与应用层 alpha/移除时机无关 ——
  继续在应用层调时序收益很低。
- 现状保留：v1.2.186 的**停靠**方案（窗口不销毁，避免 add/remove 抖动）是其中
  最稳的一种，且功能正常（`isVisible` 在停靠态返回 false，麦克风 toggle 语义不变；
  服务销毁时走 `hide()` 正常移除）。
- 若后续仍要解决，方向建议（未尝试）：
  1. 放弃 overlay 窗口，改为在 MainActivity 的 WebView 里内嵌语音输入条（无独立窗口）；
  2. 面板常驻（不透明切换，用位移移出屏幕外而不是改 alpha）；
  3. 用 `SurfaceView` 自绘（成本高，不推荐）。

### 版本
- v1.2.185（233）：悬浮球收起改为淡出→改布局→淡入
- v1.2.186（234）：语音面板发送改为「停靠」不销毁窗口

## 2026-10-17 发版前独立审查（v1.2.189）

派两组 `checker` 独立审查本轮改动，结论：**P0 1 / P1 1 / P2 8**。已修：

### P0（阻断发版）— 退出后重开 App 秒退
- 原实现用 `explicitExit` 标记位：退出置 true，只有 `EngineSupervisor.start()` 清零；
  而自动启动入口 `EngineService.start()` 被 `isUserStopped()` 拦住 → 标记永不清零 →
  用户下次打开 App，`render()` 读到 true 立刻 `finishAndRemoveTask()`。
- **改为回调式**（无持久状态）：
  · `EngineSupervisor`：`@Volatile var onExplicitExit: (() -> Unit)?` +
    `dispatchExplicitExit()`（post 主线程），在 `State.Stopped` emit **之后**调用；
    删除 explicitExit/isExplicitExit/consumeExplicitExit。
  · `MainActivity`：onCreate 注册 `onExplicitExit = { finishAndRemoveTask() }`，
    onDestroy 置 null；render 里的判断删除。
  · Activity 不在时回调为 null → 什么也不做、无残留。

### P1 — 普通模式下局域网代理杀不掉（关不掉但通知已消失）
- 根因：`LanWebProxy.stop()` 依赖 `child` 引用（**从未赋值**）+ Shizuku/su；
  普通模式（无 Shizuku/root）两条通道全失效 → node 仍监听 3090，而通知已撤掉。
- 修复：`killStaleProxy()` 增加**同 uid 直杀**：
  `ProcessBuilder("/system/bin/sh","-c","pkill -f lan-web-proxy").start().waitFor(3s)`
  （app 杀自己 fork 的进程无需提权）。

### P2（已修）
1. 启动线程末尾无条件 `running.set(false)` → 改为 `running.set(ok)`（成功后 isRunning 正确）。
2. `showLanNotice` 前复查 `DshApp.lanWebEnabled(ctx)`（spawn 有 ~2s 窗口，期间用户可能已关闭）。
3. `VoicePanel.unpark()`：`parked=false` 只在 `updateViewLayout` 成功后置位（避免"可见但摸不到"僵尸态）。
4. `VoicePanel` 重说分支补 `consumed = false`（历史 bug：重说后 Send/Close 全被守卫吞掉）。
5. 新增 `VoicePanel.dispose()`（同步 removeWindow，不播 180ms 动画）；服务 onDestroy 改用它。
6. `StatusOverlay.setDaemonAction()` 补 `cancelIdleReset()`（避免 60ms 收起窗口内被覆盖成空闲）。
7. `AgentBridge`：`/loop/run` 计 3 步（daemon 内部步骤不走 HTTP，否则长任务永远凑不够阈值 3）。
8. `EngineSupervisor.stop()` 补 `cleanupOrphanEngine()`（覆盖 spawn 阻塞段内取消 loop 的孤儿进程窗口）。

### 未修（记录）
- 截图避让的并发/嵌套竞态（P1，审查 1）：属既有功能，改动面大且与本次需求无关，留待专项。
- `shizukuExec` 无超时、`screenshotBase64` 未接避让、`setOverlayEnabled`/`a11yMaxProvider` 死代码：既有问题，低风险。
- 系统 ASR 回调缺 voiceGeneration 守卫：AsrManager.stop() 的 cancel+destroy 已大幅压制，留观。

### 验证
- `compileDebugKotlin` 通过；`assembleRelease` 通过（v1.2.189 / versionCode 237）。
- 插件语法 `node --check` 全通过；四套插件测试全绿。
- `check-i18n.py` 错误数 20 = 基线（本轮零新增）。

## 2026-10-17 v1.3.0 正式发布

- **源码**：`main` 已推送至 `ed55935`（合并了远程 PR #11 三语/方向三态/CI）。
- **Release**：https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/tag/v1.3.0
  · `dsh-mobile-v1.3.0-arm64-v8a.apk`（143.8 MB，versionCode 238）
  · `dsh-mobile-v1.3.0-arm64-v8a-debug.apk`（144.9 MB）
  · 说明：`RELEASE_NOTES_v1.3.0.md`（1783 字符，含无障碍/UI/WebUI/语音/稳定/已知问题）
  · draft=false, prerelease=false
- **README 三语**已更新：版本徽章/下载版本、LAN 段落改写（内置代理+验证码+常驻通知）、
  移除实验性开关说明、新增 v1.3.0 更新摘要；德语资源补齐 25 个键。

### ⚠️ 安全事件（已处理，需人工跟进）
- 推送被 GitHub Push Protection 拦截：本地历史中的
  `scripts/probes/_cred_new.yaml` 与 `scripts/probes/_headless_cmd.sh` **含真实 DeepSeek API Key**。
- 处理：删除这三个探针凭证文件（含 `_cred_backup.yaml`）；重写从未推送的本地历史
  （压缩为单个 v1.3.0 提交后合并到远程 main）；全工作区扫描确认无 `sk-` 残留。
- **待人工**：到 DeepSeek 控制台**吊销并重发该 key**（它曾以明文存在于本地与旧提交对象中）。

### 发版流程备忘（下次照做）
1. 改 `app/build.gradle.kts` 的 versionCode/versionName；
2. `gradle assembleRelease -Pabi=arm64-v8a` → `apksigner sign --ks keystore/dsh-release.keystore …`；
3. `gradle assembleDebug -Pabi=arm64-v8a`（Release 按惯例带 debug 包）；
4. 分片 push（10MB）+ `pm install -r` 真机验证；
5. `git add app/ README*.md RELEASE_NOTES*.md AI_CONTEXT.md`（**不要 `git add -A scripts/`**，
   probes 里有凭证/大文件）；`git push`（需 token，网络不稳时重试）；
6. GitHub API 建 release + `uploads.github.com` 传两个 APK（144MB 约 30s/个）。

## 2026-10-17 i18n 门禁修复（v1.3.0 已发布后补丁）

### 背景
CI `i18n-check` 失败（23 条）。项目门禁（`scripts/check-i18n.py`）禁 CJK 进入 Kotlin 字面量、
要求 values / values-zh-rCN / values-de 三向键集与占位符一致、catalog 与 zh catalog 条目顺序一致。

### 修复（23 → 0）
1. **用户可见文案改资源**：`CodeToast` 4 条（`code_toast_fallback`/`code_copied_hint`/`code_copy`/
   `code_copied_toast`）、悬浮窗 `overlay_exit_daemon`；三语（en/zh/de）同步补齐。
2. **日志/错误去中文**：`DshAccessibilityService` 截图错误码说明、`AgentBridge` 裁剪过平提示。
3. **德语清理**：删除已废弃的 `setting_experimental(_sub)`；`lan_dialog_copied_code` 补回 `%1$s`。
4. **catalog.zh-rCN.json** 补 `asr-service`（id 顺序与默认 catalog 对齐，共 21 条）。
5. **白名单**（有意保留的中文）：PageStateDetector 6 个加载关键词（匹配屏上文本）、
   ScreenOcr 全角 `＠` 替换、SceneMemory 面向 AI 的诊断摘要、CredentialProbe 3 个品牌名。

### 已发布资产的更新
- 因修复包含**中文扩展目录新增 asr-service**（用户可见），已重建 v1.3.0 双包并**替换 Release 资产**
  （同版本号/同 tag，先删除旧资产再上传；release 143.8MB / debug 145.2MB）。
- 校验：包内 `assets/extensions/catalog.zh-rCN.json` 含 asr-service（21 条）；
  `aapt2 dump resources` 确认 `code_copied_hint` 有 `(de) "Code kopiert"`。
- CI：`i18n-check` for d4ad230 = success。

### 教训
- 改 strings 后必须跑 `python scripts/check-i18n.py`（本地 0 错误再推）；
- 该门禁会强制三语一致（值/占位符/键集），新增字符串必须同时补 en/zh/de；
- 用户可见文案不要内联在 Kotlin 里；匹配屏上文本的关键词才走白名单。

## 2026-10-17 v1.3.0 补丁：启动动画（同版本覆盖发布）

### 需求
用户反馈「刚启动时会有一段时间黑屏」+「提示要1分钟太吓人，改成请等待几秒」+「进入页面不要硬切」。

### 实现
- `activity_main.xml` 末尾新增全屏启动层 `@id/splash`（**elevation 30dp**，否则被工具栏的
  4dp elevation 盖住）：logo + 应用名 + 状态文案 + 进度条 + 提示语；clickable/focusable 吞误触。
- `MainActivity`：
  · `setupSplash()`：logo 淡入(420ms)+缩放 0.82→1，之后 1↔1.05 呼吸循环；
  · `renderProgress()` 同步真实解压百分比到遮罩进度条；
  · `render(state)` 更新阶段文案（准备运行环境 → 启动引擎 → 加载界面），
    Failed/Backoff/Stopped 时兜底淡出；
  · `onPageFinished` → `dismissSplash()`：最短展示 700ms，再等 180ms 让页面画出首帧，
    然后「淡出 460ms + 放大到 1.04」渐变进入（解决"硬切生硬"）；
  · WebView 底色 #101418，淡出时不会闪白。
- 文案三语：`splash_preparing/starting/loading/hint`，hint 改为「请稍等几秒…」。

### 发布方式（用户要求）
- **不新增版本**：versionName 仍 1.3.0；因设备上测试过 239，为避免降级拒绝，
  versionCode 提到 **240**（对外仍显示 1.3.0）。
- 已重建 release+debug 并**替换 GitHub Release 资产**（release 150,762,553 / debug 151,947,526 字节）。
- 真机验证：启动层正常显示（工具栏不再盖住）、文案正确、页面加载后渐变进入。

## 2026-10-17 v1.3.0 补丁：横屏启动卡死（issue #17）

### 现象
用户反馈 + 截图：选择横屏（桌面布局）进入后停在启动遮罩「正在加载界面… / 请稍等几秒…」，
界面无响应（Xiaomi Pad 7 / Android 16 / WebView 154）。

### 根因（已由代码与截图确认）
`MainActivity.onPageFinished` 里，`desktopMode` 分支在调用 `dismissSplash()` **之前**就
`return` 了 → 全屏、`clickable=true`、elevation 30dp 的遮罩永不淡出，盖在 WebView 上吞掉
所有触摸。竖屏路径不经过该分支，故不受影响。

### 修复（commit 75f796d）
- `dismissSplash()` 移到模式分支**之前**，横竖屏都会执行；
- 新增 45s 兜底看门狗（`splashWatchdog`）：任何加载路径异常都不能让遮罩永久遮挡；
- `onDestroy` 移除看门狗回调。

### 验证（模拟器 x86_64 dsh36，debug 241）
- `dsh_ui.xml` 写入 `orientation=landscape` → 启动 10s 内遮罩消失，uiautomator 可见完整
  桌面布局（DeepSeek Harness / 新建会话 / 收起侧边栏…），截图
  `C:\Users\Qt\AppData\Local\Temp\emu_landscape_ok.png`；
- 改回 `orientation=portrait` 重启 → 页面加载完成后遮罩同样正常消失。
- 真机（192.168.101.21:9000）已用 `adb push + pm install -r` 装上 241 正式包
  （versionCode=241 / versionName=1.3.0）；安装时设备锁屏，**未做真机手动复测**。

### 发布（同版本覆盖）
- versionCode 240 → **241**，versionName 仍 **1.3.0**；
- 已删除旧 Release 资产并上传新包：
  release id `650756213`（150,762,553B）、debug id `650763724`（151,947,526B）；
- issue #17 已回复（comment `6235397136`）并标记 closed/completed；
- 本地提交 `75f796d`；`git push` 仍被重置（API/资产上传正常，git 直连 github.com
  `Recv failure: Connection was reset`），**待网络恢复后补推**。

### 顺带处理
- x86_64 调试构建会把 `assets/runtime.zip` 换成 x86_64 版；本轮已恢复
  `runtime.zip`=arm64 并核对 MANIFEST sha256 一致（`14dca972…`），随后才重打 arm64 包。
