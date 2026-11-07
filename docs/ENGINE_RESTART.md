# 引擎重启：受控路径

> 引擎跑在 App 的前台服务里，由 `EngineSupervisor` 统一持有。重启必须走宿主路径。

## 1. 支持的重启路径

| 路径 | 行为 | 备注 |
|---|---|---|
| 设置页「重启引擎」 | `EngineSupervisor.restart()`：停旧引擎 → 重新 spawn → 健康检查 → WebView 换新 token 重载 | 当前回合会被中断 |
| `POST /engine/restart`（AgentBridge） | 等价设置页，返回 202 异步执行；body 可带 `{"delayMs":N}` | agent 工具链自动带 token |
| `dsh-update restart`（agent 文档推荐） | 调 `POST /engine/restart`（delayMs=8000）→ 先打印结果、agent 先回复，8 秒后引擎才真正重启 | 适合 agent 主动重启 |
| 通知栏「退出」 | 停止引擎/代理/推理子进程，写 `user_exited` 标记 | 之后**不会**自动拉起，直到用户再次打开 App |

不要用 `pkill` 杀受管引擎：supervisor 会自愈重启，但属于非受控路径，容易与外来进程抢端口。

**为什么延迟 8 秒**：`dsh-update` 是引擎的子进程，若立刻停引擎，调用它的 agent 会随引擎一起被杀，
用户看到的就是「命令没响应、任务已停止」。门脚本请求 App 延迟重启：

```
restart: scheduled via app supervisor in 8s (current turn will be interrupted)
restart: the engine will stop/start by itself; do not run another restart
```

`EngineSupervisor` 8 秒后执行热重启（日志：`hot restart requested by user` → `previous engine stopped`
→ `engine healthy on :3080`）；设置页的「重启引擎」仍走默认 500ms 延迟（无感）。

## 2. 端口占用与外来监听者

第三方插件（如 dshmarket）的「立即重启」会 `SIGTERM` 宿主并 detached spawn 一个自己的 `dsh web`，
与受管引擎抢 3080，表现为 WebView `ERR_CONNECTION_REFUSED` / `ERR_INCOMPLETE_CHUNKED_ENCODING`、
引擎连启数分钟。因此：

- 插件市场的「立即重启」不会成为第二条重启路径：`EngineSupervisor` 在崩溃路径里检测
  `$PREFIX/tmp/dsh-market-restart-*`（近 5 分钟内的 `*.recovery.json` / `*.out.log`），
  一旦命中就判定「这次退出是市场发起的」→ **跳过退避** → 立即 `reclaimPort()` 清掉市场
  自己的 `dsh web` → 由受管引擎重启 → WebView 按新 token 重载。日志：
  `dshmarket self-restart detected (<file>) — taking over immediately, skipping backoff`。
  用户侧表现：市场页闪一下连接错误，随即回到 WebUI（不再出现「连启 3 分钟」）；
- 重启仍只走 §1 的受控路径（`dsh-update restart` / `POST /engine/restart` / 设置页）；
- `EngineSupervisor` 在 `spawnEngine()` 前、以及健康轮询失败后清理外来监听者：
  `portListenInodes()` 解析 `/proc/net/tcp`、`/proc/net/tcp6` 的 LISTEN 行取 socket inode →
  `pidsForSocketInodes()` 扫 `/proc/<pid>/fd` 反查 PID → `killForeignPortHolders(port)` 杀掉既不是本进程、
  也不是受管子进程的监听者，并 `waitPortFree(3080, 2000ms)`；最终只有受管引擎占用 3080，WebView 用新
  token 重载。

## 3. 重启后的 token 与 WebView

- 引擎每次启动生成新 token，supervisor 从本次启动的 stdout/log 提取 `dsh web: …?token=…`；
- `MainActivity` 检测 URL 变化后清 cookie/缓存并加载新地址；
- **返回栈同时清空**：重启后第一次 `onPageFinished` 会 `clearHistory()`，否则按 BACK 会退回上一个 token
  的引擎页或预览页（那些页面连的是已死的引擎，只有白屏）；清空后网页没有可回退的历史时，BACK 的行为见 §3.1；
- 若 WebView 显示 `ERR_CONNECTION_REFUSED`，先确认受管引擎是否健康，而不是反复点重启。

### 3.1 原生返回键（2.9.97 / 668 起）

Android 的返回键/返回手势只做两件事，**都不销毁 App**：

| 条件 | 行为 | 理由 |
|---|---|---|
| `webView.canGoBack()` | `webView.goBack()` | 类原生浏览体验：先回上一页 |
| 没有网页历史 | `moveTaskToBack(true)`：App 退到桌面 | 保住热状态 |

旧实现（≤2.9.96）第二分支走 `super.onBackPressed()` = `finish()`：Activity 被销毁，WebView 跟着回收，
再从桌面点进来要重新建 WebView、重新加载整个 SPA、重新连引擎——用户看到的是「又从头加载一遍」。

`moveTaskToBack` 只把任务退到后台：进程、WebView、滚动位置、当前会话全保留。真机实测
（vivo V2520A / Android 17，`input keyevent 4`）：焦点从 `MainActivity` 变为 `com.bbk.launcher2`，
`pidof com.termux` 前后都是 26416（进程未重启），日志 `I/MainActivity: back at web root: moveTaskToBack instead of finish`；
再从桌面启动，整屏像素差 `0.02%`（只有状态栏时钟/电量变化），即**没有重新加载**。

注意：本 App 的 SPA 是**状态驱动、不写 URL/hash**（`/json` 里打开会话后地址仍是 `http://127.0.0.1:3080/`），
所以 `canGoBack()` 在实际使用中基本恒为 false；回到「会话列表」是网页内部左上角 `←` 的职责，不是系统返回键的。
若将来需要「返回 = 回会话列表」，应让网页侧注册历史（`history.pushState`）或由 App 调 CDP 点那个箭头，
不要把状态塞进 `onBackPressed` 里猜。

## 4. Agent 应该怎么做

1. 先告诉用户「我将重启引擎，当前回合会中断」；
2. 执行 `dsh-update restart`（或 `POST /engine/restart`）；
3. 不要使用插件市场的「立即重启」，不要杀 node 进程；
4. 重启后验证：`GET /health`（或设置页状态）与 WebUI 可访问，再继续任务。

## 5. 排障

| 现象 | 检查 |
|---|---|
| 点重启没反应 | 是否已有重启在进行（`restarting` 互斥）；看 `EngineSupervisor` 日志 |
| 反复 engine start | 是否还有外来进程占 3080；看 `killing foreign listeners on port 3080` 日志 |
| 重启后白屏/401 | token 是否已更新；WebView 是否清过 cookie |
| 退出后又被拉起 | 是否写了 `user_exited`；无障碍 watchdog 是否在 `EngineService.isRunning()` 为 false 时误拉起 |
