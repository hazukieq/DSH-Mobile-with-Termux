# 音量 API 报告（2.9.99 / 670）

> 历史取证记录：结论仅适用于文中设备、版本和采样范围。当前行为与维护约束见相关使用文档；旧行号和临时文件路径不代表当前工作区。

> 需求（用户原话）：「查询互联网资料、SDK docs，看下怎么赋予 agent 调节音量的api」。
> 结论：可以用**系统公开 API** 做，不需要 root、不需要无障碍注入按键。
> 端点已实现并在 vivo 真机（Android 17 / OriginOS 17）实测；vivo 对**媒体流**的写入存在间歇性静默丢弃，
> 端点用 `changed` / `blocked_by_rom` 如实暴露，绝不假装成功。

---

## 1. 官方资料结论

| 问题 | 结论 | 来源 |
|---|---|---|
| 写音量要什么权限 | `AudioManager.setStreamVolume()` / `adjustStreamVolume()` 需 `android.permission.MODIFY_AUDIO_SETTINGS`；**normal 级、安装即授予**，无运行时弹窗 | [AudioManager](https://developer.android.com/reference/android/media/AudioManager) |
| 会不会触发 DND 限制 | Android N(7.0) 起，**会把设备切进/切出 Do Not Disturb 的音量改动**（典型：把铃声调到 0）需要 Notification Policy Access，否则抛 `SecurityException` | 同上（`setStreamVolume` 文档）/ [Do Not Disturb](https://developer.android.com/develop/ui/views/notifications/dnd) |
| 什么情况下怎么调都没用 | `AudioManager.isVolumeFixed()` 为 true（如部分 TV/车载）时所有 `set*` 无效 | 同上 |
| 有没有更省事的替代 | `adb shell cmd media_session volume --stream 3 --set 5` / `--adj raise`；`dumpsys audio` 读状态 | [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager) |
| 悬停式 UI 入口 | `Settings.Panel.ACTION_VOLUME`（API 29+）打开系统音量面板 | [Settings.Panel](https://developer.android.com/reference/android/provider/Settings.Panel) |
| ROM 定制风险 | vivo Android 12+ 第三方 `setStreamVolume/adjustStreamVolume/setVolumeIndexForAttributes` 存在全部无效的公开报告 | [SO 76305018](https://stackoverflow.com/questions/76305018) |

设计取舍：**只走公开 API**，不引入 `AudioService` 反射、不做 `su` 注入；读回校验（read-back）作为唯一成功判据。

---

## 2. 端点契约

### `GET /volume`

```json
{"ok":true,
 "streams":{"music":{"stream":"music","stream_type":3,"index":106,"max":150,"percent":71,"muted":false},
            "ring":{...},"notification":{...},"alarm":{...},"system":{...},"dtmf":{...}},
 "ringer":{"mode":"normal","mode_code":2,"policy_access":false},
 "volume_fixed":false,
 "model":"V2520A",
 "default":{"stream":"music",...}}
```

### `POST /volume`

| body | 作用 | 失败语义 |
|---|---|---|
| `{"stream":"music","index":90}` | 直接设索引（`level` 同义） | 越界自动夹取，回 `index_clamped` |
| `{"stream":"music","percent":80}` | 按百分比设 | — |
| `{"stream":"music","adj":"down","steps":2}` | 相当于按音量键 | — |
| `{"stream":"music","mute":true}` / `false` | 静音/还原 | — |
| `{"ringer":"silent\|vibrate\|normal"}` | 铃声模式 | 无 DND 权限且被拦 → `409 needs_policy_access` + `action:"open_policy_settings"` |
| `{"openPanel":true}` | 打开系统音量面板（UI 兜底） | API<29 → `409 panel_needs_api29` |
| `{"openPolicySettings":true}` | 跳到 DND 访问授权页 | — |

所有写操作回执固定包含 **`before` / `after` / `changed` / `verify:"read-back"`**；
静默失败会自动带 `FLAG_SHOW_UI` 重试一次（回执 `retried_with_ui`），仍失败则 `blocked_by_rom:true` + hint。

---

## 3. 实现位置

| 文件 | 作用 |
|---|---|
| `app/src/main/java/app/dsh/mobile/bridge/AudioHandler.kt` | 全部逻辑（GET 快照、POST 写入、读回校验、面板/DND 入口） |
| `app/src/main/java/app/dsh/mobile/bridge/Router.kt` | `GET/POST /volume` 路由（放在 `/media/*` 之前） |
| `app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt` | `internal val audio by lazy { AudioHandler(this) }` |
| `app/src/main/AndroidManifest.xml` | `MODIFY_AUDIO_SETTINGS`（normal，无需运行时授权） |
| `app/src/main/java/app/dsh/mobile/engine/AndroidOverlay.kt` | `AGENT_GATES` 追加 `"vol"` |
| `app/src/main/assets/gates/vol` | 门脚本：`vol`、`vol set 60`、`vol up 2`、`vol mute`、`vol ring silent`、`vol dnd-settings` |
| `app/src/main/assets/agents/DSH-MOBILE.md` | agent 侧用法（marker v77） |

---

## 4. 真机实测矩阵（vivo V2520A / Android 17 / OriginOS 17）

`adb forward tcp:3083 tcp:3083` 后直接 `curl -H "X-DSH-Token: …"`。

### 4.1 读取

| 流 | index / max | 说明 |
|---|---|---|
| music | 106 / 150 | vivo 媒体音量 150 档（非 AOSP 的 15 档） |
| ring | 105 / 150 | |
| notification | 7 / 150 | |
| alarm | 140 / 150 | |
| system | 4 / 15 | 系统音 15 档 |

### 4.2 写入

| 调用 | 结果 | dumpsys 复核 |
|---|---|---|
| `{"stream":"ring","index":40}` | ✅ `changed:true` | 105→40 |
| `{"stream":"notification","index":40}` | ✅ | 7→40 |
| `{"stream":"alarm","index":40}` | ✅ | 140→40 |
| `{"stream":"system","index":15}` | ✅ | 4→15 |
| `{"stream":"music","adj":"down","steps":2}` | ✅（部分时段） | 90→70 |
| `{"stream":"music","mute":true}` / `false` | ✅（部分时段） | 70→0→70 |
| `{"stream":"music","index":120}` / `45` / `percent:80` | ✅（部分时段） | 106→120→45→120→106 |
| `{"ringer":"vibrate"}` / `"normal"` | ✅ | mode 2→1→2 |

### 4.3 媒体流的间歇性静默丢弃（重点）

失败时的可观测证据（三层同时确认）：

1. 回执 `changed:false` + `blocked_by_rom:true`；
2. `dumpsys audio` 里**能看到调用本身**：`setStreamVolume(stream:STREAM_MUSIC index:60 flags:0x0 oldIndex:106) from com.termux`；
3. 但 `streamVolume:` 数值纹丝不动，且**无异常、无 logcat warning**。

已排除（全部无效，事后均已还原）：

| 尝试 | 结果 |
|---|---|
| `FLAG_SHOW_UI` 强制弹面板 | 面板不弹，索引不变 |
| vivo「应用独立音量」`app_volume_adjust_individual` 1→0（+`_remember`） | 无变化（已还原 1/1） |
| shell 侧 `cmd media_session volume --stream 3 --set 60` / `--adj raise` | 同样无效（**说明不是本 app 的调用姿势问题**） |
| 前台切成 MainActivity / 终端 Activity | 无差别 |
| 硬件音量键 `input keyevent 24` / `25` | ✅ 有效（106→116→…） ⇒ 系统通路正常，只是**第三方写入被丢** |

行为特征：**同一版本、同一进程、同一前台，会在「整段可用」与「整段被丢」之间翻转**（实测翻转两次，跨重装与开关「应用独立音量」）。
机理未完全定位（OriginOS 音量策略闭源），但端点已把该状态如实暴露，agent 不会误判成功。

`{"openPanel":true}` 在该 ROM 上打开的是「提示音和振动」底部面板（不是滑杆面板）——可用于铃声/振动相关操作，
**不适合**当媒体音量兜底；媒体音量兜底建议 `POST /media/play`（占媒体焦点）后重试，或让用户按一次音量键。

### 4.4 为什么「之前测试时可以调节」——时间线证据

同一版本（670）、同一进程（pid 不变）、同一前台（MainActivity），`dumpsys audio` 的事件日志把两种结果分得很干净：

| 时间段 | 事件日志 | 结果 |
|---|---|---|
| 01:04:12 – 01:05:36 | `setStreamVolume(... index:80 oldIndex:60) from com.termux` **紧跟** `sending VOLUME_CHANGED stream:STREAM_MUSIC index:80` | ✅ 真的生效（连续 11 次） |
| 01:06:01 起 | `setStreamVolume(... oldIndex:106) from com.termux`，**没有** `sending VOLUME_CHANGED` | ❌ 调用到达 AudioService，被丢弃 |

即：**不是我们代码的问题，也不是「随机抽风」——是 ROM 侧把媒体流写入在应用前丢掉了**，且丢的时候连日志都不打。

已逐项排除（都在现场做过对照）：

| 怀疑项 | 实测 | 结论 |
|---|---|---|
| 权限缺失 | `dumpsys package com.termux` → `MODIFY_AUDIO_SETTINGS: granted=true` | 排除 |
| DND / 铃声模式 | `zen_mode=ZEN_MODE_OFF`；`ringer:normal/vibrate` 都成功 | 排除 |
| 「应用独立音量」`app_volume_adjust_individual` | 1→0→1 切换 | 排除 |
| 前台 Activity / 应用前后台 | MainActivity、TerminalActivity 都试过 | 排除 |
| 音频焦点 / 正在播放 | 用 `/media/play` 播 3 秒音后立刻写 | 排除 |
| **别的 App 占着媒体会话** | 发现 `tv.danmaku.bili` `state=PLAYING` → `am force-stop tv.danmaku.bili` 后重试 | 排除 |
| 屏幕/锁屏 | `mWakefulness=Awake`、`mScreenOn=true` | 排除 |
| uid 不是我们 | `adb shell cmd media_session volume --stream 3 --set 60`（uid 2000）同样无效 | 排除（不是 app 身份问题） |
| 硬件音量键 | `input keyevent 24/25` 永远有效 | 说明系统通路本身正常 |

**唯一还能复现的规律：重装/升级 APK 之后会短暂恢复。**
- 第 1 次重装后：连写 11 次全部 `changed:true`（t+0s … t+72s），**t+80s 起全部失败**；
- 紧接第 2 次重装后：同样条件 16 次连写 **0/16 成功**。

⇒ 结论：**「之前能调」= 恰好落在重装后的恢复窗口里**（窗口约 1–2 分钟、且不是每次都出现）；窗口关闭后同一进程、同一前台也写不进去。ROM 内部状态机（OriginOS 闭源）无法用公开 API 控制，本仓库只能如实标记 `changed` / `blocked_by_rom`。

附带证据：该 ROM 的 `dumpsys audio` 里 `STREAM_ALARM` 显示 `Min: 0 w/o perm:29` —— 说明 vivo 在 AudioService 里按「调用方有没有某种权限」做了分档，这正是第三方写入被差别对待的痕迹。

---

## 5. agent 侧使用建议

1. **优先用 `ring` / `notification` / `alarm` / `system`**：这几个流在 vivo 上稳定可靠。
2. 调媒体音量：先 `GET /volume` 看 `index`，调完**必须看 `changed`**；为 false 时按 `hint` 处理，不要对用户说「已调好」。
3. 想做「静音一下」：`{"ringer":"silent"}`（走 DND，失败会回 `needs_policy_access` + 授权入口）比把媒体音量压到 0 更符合用户预期。
4. 门脚本一行版：`vol set 60`、`vol up 2`、`vol mute`、`vol ring silent`。

---

## 6. 复现命令

```bash
adb forward tcp:3083 tcp:3083
TOK=$(cd scripts/probes/flicker && ./bridge.sh token)
curl -s -H "X-DSH-Token: $TOK" http://127.0.0.1:3083/volume | python3 -m json.tool | head -20
curl -s -H "X-DSH-Token: $TOK" -d '{"stream":"alarm","index":40}' http://127.0.0.1:3083/volume
adb shell dumpsys audio | awk '/^- STREAM_ALARM:/{f=1} f{print} f&&/Volume Group:/{exit}' | grep streamVolume
```

## 7. 已知限制

- `isVolumeFixed` 设备上所有写入无效（端点会带 `volume_fixed:true`）。
- 无 DND 访问权时「把铃声调到 0」类操作会被系统拦截（端点返回可点的授权入口）。
- vivo 媒体流写入间歇性被丢（见 §4.3），非本 app 可修；已做读回校验与显式标记。
- 音量键/面板的 UI 自动化不在本端点范围内（a11y 注入按键在部分 ROM 会弹系统确认）。

## §9 后续：媒体流的确定可行路径（2.9.100 / 671）

标准 `setStreamVolume` 在本机对 `STREAM_MUSIC` 被 ROM 在 `checkNoteAppOp` 之前丢弃，
音量组 API 需要 `signature|privileged(|role)` 权限（普通 app 无解）。已补 **UI 兜底**：
`POST /volume {"viaUi":true,"stream":"music","percent":40}` 打开系统「声音与振动」设置页，
用无障碍在 SeekBar 上点按/拖动，实测 20%→26、55%→84、90%→141、alarm 25%→34，全部 `changed:true`。
权限矩阵、AOSP 代码定位与实测见 **[volume-write-path.md](volume-write-path.md)**。
