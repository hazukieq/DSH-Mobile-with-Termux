# APK 安装能力（`GET /install` / `POST /install`）测试报告

- 版本：**2.9.102 / 673**（首个带 `/install` 的是 2.9.101 / 672）
- 真机：vivo V2520A（`10CG1C1M1H0021Z`），Android 17 / SDK 37，OriginOS 17，**无 root**
- 相关提交：本文件随「install 端点 + 不做自动化」一起提交

## 1. 结论

| 问题 | 结论 |
|---|---|
| app 能不能发起安装？ | **能**。声明 `REQUEST_INSTALL_PACKAGES` 后可以建 `PackageInstaller` 会话、写入 APK、commit，并拉起系统安装器。 |
| 能不能静默装（无用户操作）？ | **不能，也不该做**。Android 14 起侧载强制用户确认；本机 ROM 还额外要求**解锁 + 生物识别**。 |
| 是否有自动点「安装」？ | **没有**（2.9.102 已删除）。端点只准备会话 + 拉起安装器，确认交给用户。 |
| 一次调用多快？ | 会话 + 拉起安装器 **~0.2 s**（实测 `waitedMs:189`）。 |

## 2. 权限链（三段，缺一不可）

1. `android.permission.REQUEST_INSTALL_PACKAGES`：**normal**，manifest 声明即授予；没有它连
   `PackageInstaller.createSession` 都建不起来（本 app 2.9.101 起已声明）。
2. **「安装未知应用」开关**：特殊权限，只能用户/系统授予。`PackageManager.canRequestPackageInstalls()`
   为 false 时 commit 会被拒（`STATUS_FAILURE_BLOCKED`）。等价命令：
   `adb shell appops set com.termux REQUEST_INSTALL_PACKAGES allow`（root 下端点可自己执行，
   见 `POST /install {"grant":true}`）。
3. **系统安装器 UI**：普通 app 无法静默安装（除非系统签名/root）。`Session.commit()` 后
   `STATUS_PENDING_USER_ACTION` 会带回 `EXTRA_INTENT`，必须由接收者 `startActivity` 拉起，
   否则安装器永远不出现（PackageInstaller 最经典的坑，代码里已处理）。

## 3. 为什么放弃自动确认（2.9.102）

初版（2.9.101）用无障碍在安装器页上按文案点「安装/更新/继续」，本机实测失败，原因分三层：

1. **文案不稳定**：vivo 安装器把放行入口做成段落里的可点 Span「授权本次安装」，无障碍节点是
   **整段 clickable**；按文案匹配会点到整段警告文字（日志里 `autoConfirm clicked '若您是开发者…'`
   重复多次），`ACTION_CLICK` 与点段落中心都无效。
2. **需要精确命中 Span**：按蓝色像素定位（bbox `614,1218-824,1250`）点中后，系统弹出
   `Window{com.android.systemui … BiometricPrompt type=2009}`。
3. **需要用户身份**：`BiometricPrompt` = 指纹/面容/解锁确认。自动化到这里已经不是「点按钮」，
   而是替用户做安全决策，既不可靠也不应实现。

处置：删除 `autoConfirm()` / `CONFIRM_LABELS` / `DshAccessibilityService` 依赖；`waitMs` 默认从
45000 降到 **8000**（只等「安装器是否被拉起/是否已装完」，不等用户操作）；回执改为
`status:"pending_user_action"` + `requires_user:true` + `next` 提示；`GET /install` 供事后轮询
`last_status`。

## 4. 端点契约

```bash
GET  /install                       # 能力与上次结果
POST /install {"path":"/sdcard/x.apk"[,"waitMs":8000]}
POST /install {"openSettings":true} # 打开本 app 的「安装未知应用」页
POST /install {"grant":true}        # root 下 appops 授权
```

`GET /install` 回执：

```json
{"ok":true,"package":"com.termux","has_permission":true,"can_install":true,"sdk":37,"root":false,
 "last_status":"idle","last_package":"","last_detail":"","last_at":0,"last_clicked":"",
 "hint":"ready: POST /install {\"path\":\"/sdcard/x.apk\"}"}
```

`POST /install` 成功交给安装器（202）：

```json
{"ok":false,"status":"pending_user_action","session":1380807472,"package":"org.fdroid.fdroid",
 "versionName":"2.0.2","versionCode":2000052,"size":12569951,"waitedMs":189,"requires_user":true,
 "next":"user must confirm in the system installer (may require unlock/biometric); poll GET /install for last_status",
 "detail":"","installer":"package_installer",
 "note":"handed off to the system installer; the user confirms on device"}
```

状态串：`idle | success | pending_user_action | blocked_by_settings | timeout | failed |
failure_aborted | failure_blocked | failure_conflict | failure_incompatible | failure_invalid |
failure_storage`。

## 5. 真机实测记录

| # | 动作 | 结果 |
|---|---|---|
| 1 | 未授权时 `GET /install` | `can_install:false` |
| 2 | `adb shell appops set com.termux REQUEST_INSTALL_PACKAGES allow` | `can_install:true` |
| 3 | `POST /install`（F-Droid 12.57 MB / 2.0.2 / versionCode 2000052） | `session:1380807472`，`pending_user_action`，189 ms |
| 4 | 前台窗口 | `com.android.packageinstaller/.PackageInterceptActivity`（vivo 超级守护拦截页） |
| 5 | 点「授权本次安装」（蓝色 Span，中心 719,1234） | 焦点 → `BiometricPrompt type=2009` ⇒ 需生物识别 |
| 6 | 旧版 672 的 `autoConfirm` | 误点整段警告文案，60 s 后仍 `pending_user_action`；用户取消后 `failure_aborted`（`INSTALL_FAILED_ABORTED: User rejected permissions`） |

测试包来源：`curl -L https://f-droid.org/F-Droid.apk` → `/tmp/testapp.apk` →
`adb push /tmp/testapp.apk /sdcard/Download/testapp.apk`。

## 6. 复现命令

```bash
S=10CG1C1M1H0021Z
cd scripts/probes/flicker
TOK=$(./bridge.sh token); H="X-DSH-Token: $TOK"
curl -s -H "$H" http://127.0.0.1:3083/install
adb -s $S shell appops set com.termux REQUEST_INSTALL_PACKAGES allow
curl -s -H "$H" -d '{"path":"/sdcard/Download/testapp.apk"}' http://127.0.0.1:3083/install
# 用户确认后
curl -s -H "$H" http://127.0.0.1:3083/install   # last_status -> success / failure_*
```

## 7. 安全与边界

- 端点不写「静默成功」：没装完就报 `pending_user_action`，被拒就报 `failure_aborted`。
- 不做任何替用户确认的自动化（点按钮、注入指纹、改 `settings` 绕过）。原因见 §3。
- `can_install:false` 时不会偷偷失败：返回 409 `needs_unknown_sources` + `how_to_fix`，
  并可选自动打开「安装未知应用」设置页。
- 只接受**已存在**的本地 `path`（`/sdcard` 需「所有文件访问」授权）；下载由 `/download` 端点完成。
