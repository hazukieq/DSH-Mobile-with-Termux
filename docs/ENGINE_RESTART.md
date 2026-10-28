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

- 插件市场的「立即重启」不可用，重启只走 §1 的受控路径；
- `EngineSupervisor` 在 `spawnEngine()` 前、以及健康轮询失败后清理外来监听者：
  `portListenInodes()` 解析 `/proc/net/tcp`、`/proc/net/tcp6` 的 LISTEN 行取 socket inode →
  `pidsForSocketInodes()` 扫 `/proc/<pid>/fd` 反查 PID → `killForeignPortHolders(port)` 杀掉既不是本进程、
  也不是受管子进程的监听者，并 `waitPortFree(3080, 2000ms)`；最终只有受管引擎占用 3080，WebView 用新
  token 重载。

## 3. 重启后的 token 与 WebView

- 引擎每次启动生成新 token，supervisor 从本次启动的 stdout/log 提取 `dsh web: …?token=…`；
- `MainActivity` 检测 URL 变化后清 cookie/缓存并加载新地址；
- **返回栈同时清空**：重启后第一次 `onPageFinished` 会 `clearHistory()`，否则按 BACK 会退回上一个 token
  的引擎页或预览页（那些页面连的是已死的引擎，只有白屏）；此时按 BACK 直接退出应用；
- 若 WebView 显示 `ERR_CONNECTION_REFUSED`，先确认受管引擎是否健康，而不是反复点重启。

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
