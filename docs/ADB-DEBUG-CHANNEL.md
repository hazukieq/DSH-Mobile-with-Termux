# adb 调试豁免通道（免 root 拿 bridge token）

> 适用：**2.9.89 / 660 及以后**。真机无 root 调试 Android 侧能力（读屏 / 点击 / 截图 / OCR）时用。

## 背景

`AgentBridge` 的 token 只留在 App 私有目录 `<engineRoot>/.bridge-token`（0600），
而 `/data/user/0/com.termux` 是 0700 —— 无 root 的 `adb shell` 读不到，包也不是 debuggable
（`run-as` 不可用）。在此之前为了一次真机调试，必须：

```text
设置 → 扩展 → 终端 → 手打 cp $PREFIX/.bridge-token /sdcard/x.txt → 宿主 cat
```

四五步、不能脚本化、每次重装 token 还会变。

## 用法

```bash
# 1) 拿 token（一次性）
adb shell content query --uri content://com.termux.adb/token
#    Row: 0 key=token, value=daa3f6920c8245c4889be17cc789e67e
#    Row: 1 key=endpoint, value=http://127.0.0.1:3083

# 2) 打通端口
adb forward tcp:3083 tcp:3083

# 3) 正常驱动 bridge
curl -H "X-DSH-Token: <token>" http://127.0.0.1:3083/diag
curl -H "X-DSH-Token: <token>" http://127.0.0.1:3083/screenshot
```

一条命令直接进 shell 变量：

```bash
TOK=$(adb shell content query --uri content://com.termux.adb/token \
      | sed -n 's/.*key=token, value=\([0-9a-f]*\).*/\1/p')
```

## 在 App 的 Termux 环境里执行命令（exec，2.9.103 / 674 起）

以前要在手机上跑命令，只能打开 App 内终端用 `adb shell input text` 打字——而中文输入法会把
`_` `.` `/` 变成全角字符（实测 `sh /sdcard/chk_install.sh` 被打成 `sh/sdcard、chk—installl。sh`），
脚本几乎没法用。现在直接走 `content call`：

```bash
adb shell "content call --uri content://com.termux.adb --method exec --arg 'ls \$HOME | head'"
# Result: Bundle[{timeout=false, ms=102, ok=true, cmd=ls $HOME | head,
#                 cwd=/data/user/0/com.termux/files/dsh-home, out=DSH-MOBILE.md ...,
#                 exit=0, truncated=false}]
```

- **环境与 App 内终端一致**：引擎自带的 `bin/bash -lc`，`PATH`/`PREFIX`/`LD_LIBRARY_PATH`/
  `DSH_HOME` 都来自 `EngineEnvBuilder.buildEnv`，工作目录默认 `$DSH_HOME`。
- 可选参数：`--extra timeoutMs:l:120000`、`--extra cmd:s:'…'`（等价 `--arg`）、
  `--extra cwd:s:/sdcard`、`--extra maxBytes:i:262144`。
- 回执字段：`ok`、`exit`、`ms`、`timeout`、`truncated`、`cmd`、`cwd`、`out`（stdout+stderr 合并）。
- 输出上限默认 128 KB；超时默认 60 s、上限 300 s，超时会 `destroyForcibly` 并置 `timeout=true`。
- 首次调用（刚装完包）可能撞上 Termux bootstrap 第二阶段，耗时 ~20 s；完成后恢复正常
  （实测第二次 102 ms）。

宿主侧封装（推荐，省掉引号地狱并透传退出码）：

```bash
./scripts/probes/adb-exec.sh 'echo hi; uname -m'
SERIAL=10CG1C1M1H0021Z ./scripts/probes/adb-exec.sh --raw 'wc -l $HOME/DSH-MOBILE.md'
./scripts/probes/adb-exec.sh --timeout 120000 'sleep 30; echo done'
```

## 诊断状态

```bash
adb shell content query --uri content://com.termux.adb/state
```

| key | 含义 |
|---|---|
| `versionName` | 装的是哪一版（对版本号最容易搞错） |
| `pid` | App 进程号 |
| `a11yConnected` | 无障碍服务是否连着（**OriginOS 会隔夜静默撤销**） |
| `engineRunning` | 引擎服务是否在跑 |
| `overlay` | `StatusOverlay.debugState()`：`root/attached/live/level/pos/size/flags/touchable/hideRefs/type` |
| `token` | `ready` / `missing` |

## 配套脚本

取证/调试脚本已经接上这条通道，不用再手贴 token：

```bash
cd scripts/probes/flicker
./bridge.sh token     # 取 token（自动 provider → /sdcard 兜底）
./bridge.sh ping      # /diag 探活
./bridge.sh state     # 版本 / pid / a11y / engine / overlay
./bridge.sh shot /tmp/x.png
./bridge.sh tap 540 1200
```

无 root 设备（vivo / OriginOS）用 `bridge.sh` 即可；`layers/frames/capture` 那些仍需要 root。

## 安全边界：凭什么只给 adb

三重闸门，缺一不可：

1. **`android:readPermission="android.permission.DUMP"`** —— 签名/特权级，只有
   shell、root、system 持有，普通第三方 App 一个都没有；
2. 代码里再查一次 `DUMP` / `READ_LOGS` / `WRITE_SECURE_SETTINGS` 任一
   （防权限声明被改 / provider 被同名复用）；
3. **全局开关**：`dsh_adb_bridge = 0` 时整条通道关闭。

```bash
# 关掉（现场设备不想留通道时）
adb shell settings put global dsh_adb_bridge 0
# 恢复
adb shell settings put global dsh_adb_bridge 1
```

关闭后访问会得到：`java.lang.SecurityException: dsh adb channel requires DUMP permission`。

其他约束：

- **只读**：`insert/update/delete` 一律抛 `UnsupportedOperationException`；
- token **每次 App 启动重新生成**，所以查询结果只在本次进程内有效（这也是为什么不能把它
  缓存进配置或提交进仓库）；
- `query`（token/state）与 `call exec` 走**同一套三重闸门**；`exec` 用 App 自己的 uid 运行，
  不额外提权（没有 root 就是应用沙箱权限，能读的只有本 app 自己的目录与已授权的共享存储）。
- 通道本身不提供新的设备能力，只把**已经能做的事**（bridge 调用、App 环境里的 shell）
  暴露给 adb，动作仍走 `127.0.0.1:3083` 的既有鉴权与权限分级。

## 相关代码

- `app/src/main/java/app/dsh/mobile/bridge/AdbDebugProvider.kt`
- `app/src/main/AndroidManifest.xml`（`<provider android:authorities="${applicationId}.adb">`）

## 和旧办法的关系

旧的「应用内终端 `cp` 到 /sdcard」在 2.9.89 之前的版本上仍然可用（也是当时唯一办法），
记录在 `docs/qa/vivo-experience-report.md` §1。
