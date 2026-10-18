# dsh USB bridge（手机 agent ↔ 电脑 agent）

手机端 dsh 通过 USB 把任务派给电脑端 dsh（`--profile headless`），并取回结果。
原理：电脑监听 `127.0.0.1:9100`，电脑侧执行 `adb reverse tcp:9100 tcp:9100`
把它映射到手机的 loopback；手机上的 `pc` 门脚本就是它的 HTTP 客户端。

## 电脑侧（一次性）

```sh
# 1. 启动桥（systemd-run --user 保活；也可以直接 node bridge.mjs 前台跑）
tools/usb-bridge/start.sh

# 2. 插上手机后建立 reverse 隧道（可重复执行）
tools/usb-bridge/attach.sh
```

要求：电脑端 `dsh` 在 PATH 中，headless profile 已配置可用模型
（`~/.dsh/profiles/headless/cordis.patch.yml` 里用 `provider: deepseek-account`，
不然会回退到失效的 `DEEPSEEK_API_KEY`）。

## 手机侧

```sh
pc health
pc ask "在电脑上跑一遍测试并总结失败"
pc start "..."        # 只派发 -> task id
pc show <id>          # 随时查看状态/结果
pc list
pc cancel <id>
```

结果同时落盘在电脑 `~/.dsh/usb-bridge/results/<id>.json|.md`。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活、队列、结果目录 |
| POST | `/task` | `{"task":"…","sessionId?":"…","cwd?":"…","json?":false}` → `{id,state}` |
| GET | `/tasks` | 最近任务摘要 |
| GET | `/task/<id>` | 状态 + `stdout`/`stderr`/exit/ms |
| POST | `/task/<id>/cancel` | 取消排队或运行中的任务 |

单并发队列：一个任务跑完才跑下一个（agent 任务开销大，避免并发抢模型配额）。
