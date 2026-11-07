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
- 通道本身不提供「执行动作」能力，只把已有的 bridge 能力**暴露给 adb**，
  动作仍走 `127.0.0.1:3083` 的既有鉴权与权限分级。

## 相关代码

- `app/src/main/java/app/dsh/mobile/bridge/AdbDebugProvider.kt`
- `app/src/main/AndroidManifest.xml`（`<provider android:authorities="${applicationId}.adb">`）

## 和旧办法的关系

旧的「应用内终端 `cp` 到 /sdcard」在 2.9.89 之前的版本上仍然可用（也是当时唯一办法），
记录在 `docs/qa/vivo-experience-report.md` §1。
