# 音量写入路径：为什么标准 API 被丢、以及确定可行的 `viaUi` 路径

版本：2.9.100 / 671　设备：vivo V2520A（10CG1C1M1H0021Z），Android 17 / SDK 37，OriginOS 17
时间：2026-11-08

## §1 结论

| 路径 | 媒体流（STREAM_MUSIC） | 其它流（ring/notification/alarm/system） |
|---|---|---|
| `AudioManager.setStreamVolume` / `adjustStreamVolume`（`MODIFY_AUDIO_SETTINGS`，normal 权限） | **被 ROM 静默丢弃**（无异常、无 appops 记录、索引不动） | ✅ 正常 |
| `AudioManager.setVolumeIndexForAttributes` / `adjustVolumeGroupVolume`（音量组，Android 16+） | ❌ `AccessDenied`：需要 `MODIFY_AUDIO_ROUTING` \| `QUERY_AUDIO_STATE` \| `MODIFY_AUDIO_SETTINGS_PRIVILEGED` | 同左 |
| **系统「声音与振动」设置页的 SeekBar**（a11y 点击/拖动） | ✅ **实测可用**（本项目 `viaUi`） | ✅ 实测可用 |
| 物理音量键 | ✅ | ✅ |

⇒ 普通第三方 app（无 root、无系统签名）能确定改媒体音量的路径只有两条：**用户按音量键**，
或**驱动系统设置页的滑块**。本项目把后者做成了 `POST /volume {"viaUi":true, ...}` + `vol ui`。

## §2 权限矩阵（AOSP `core/res/AndroidManifest.xml`）

| 权限 | protectionLevel | 位置 |
|---|---|---|
| `android.permission.MODIFY_AUDIO_SETTINGS` | normal（装包即授予） | :2795 |
| `android.permission.MODIFY_AUDIO_ROUTING` | `signature\|privileged\|role` | :6579 |
| `android.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED` | `signature\|privileged` | :6587 |
| `android.permission.QUERY_AUDIO_STATE` | `signature\|role` | :6599 |

`role` 也需要系统角色（RoleManager）授予，普通 app 走不通。

## §3 AOSP 侧调用链与丢弃点

`services/core/java/com/android/server/audio/AudioService.java`（main 分支）：

1. `setStreamVolume(streamType,index,flags,callingPackage)`（~:4501）
2. → `setStreamVolumeWithAttribution`（~:4573）→ `setStreamVolumeWithAttributionInt`（~:4601）
   - `HardeningEnforcer.blockVolumeMethod(METHOD_AUDIO_MANAGER_SET_STREAM_VOLUME)`：AOSP 里**只有车机**会命中
   - `sVolumeLogger.enqueue(new VolumeEvent(VOL_SET_STREAM_VOL, ...))` —— 进 `dumpsys audio` 的那行日志
3. → 私有 `setStreamVolume(...)`（~:5011）
   - `if (mUseFixedVolume) return;`（设备 `isVolumeFixed=false`，排除）
   - `checkNoteAppOp(STREAM_VOLUME_OPS[streamType], uid, ...)`（~:5059）——不是 `MODE_ALLOWED` 就静默 return

真机取证：调用确实走到第 3 步之前（`dumpsys audio` 有 `setStreamVolume(stream:STREAM_MUSIC index:88
flags:0x0 oldIndex:75) from com.termux`），但 `cmd appops get com.termux android:audio_media_volume`
始终是 `No operations.`，对照写 alarm 会立刻出现 `AUDIO_ALARM_VOLUME: allow; time=+60ms ago`。
⇒ 媒体流在 `checkNoteAppOp` **之前**就被拦下；AOSP main 里该位置没有别的守卫，实际是 ROM 在
`setStreamVolume` 入口的厂商 hook（该机 `getprop` 有
`persist.sys.audio.vapc.volume.intercept_setvolume.enable=true`）。

排除过的因素：`app_volume_adjust_individual` 1↔0、`audio_safe_volume_state` 3→0、
zen mode、前台 Activity（MainActivity / TerminalActivity）、音频焦点与正在播放（`/media/play` 循环播放中写仍失败）、
`FLAG_SHOW_UI`、`cmd media_session volume --set/--adj`（shell uid 2000 同样无效）、其它 app 占媒体会话。

## §4 `viaUi` 实现（`app/src/main/java/app/dsh/mobile/bridge/AudioHandler.kt`）

1. `startActivity(Settings.ACTION_SOUND_SETTINGS)`；
2. 每 300ms 轮询最多 5s，`DshAccessibilityService.rootInActiveWindow` 里 BFS 找
   `className` 含 `SeekBar`、**宽度 >200px 且与屏幕相交**的节点（避免拿到离屏/其它窗口的节点）；
   用节点自身的 `text`/`contentDescription` 匹配「媒体 / 铃声 / 通知 / 闹钟」；
3. 先试 `AccessibilityAction.ACTION_SET_PROGRESS`（vivo 的 SeekBar 返回 false，AOSP 上通常可用）；
4. 失败则按目标比例在滑块条上 `dispatchGesture` 点一下（多数 SeekBar 点哪跳哪）；
5. 偏离超过 `max/24` 或完全没变时，从当前拇指位置再补一次拖动；
6. 读回 `getStreamVolume`，回执带 `before/after/changed/requested/slider_action/uiDetail`；
7. 成功后 `startActivity(MainActivity)` 把 App 切回前台，不把用户留在设置页。

回执示例：

```json
{"ok":true,"op":"ui_settings","stream":"music","before":108,"after":26,"changed":true,
 "requested":30,"slider_action":"tap",
 "uiDetail":"tap bars=1 pkg=com.android.settings now=26 tgt=30",
 "note":"driven through the system sound-settings slider (a11y tap/drag)"}
```

## §5 真机实测（150 档媒体音量）

| 目标 | before → after | 动作 | changed |
|---|---|---|---|
| 20% | 108 → 26 | tap | ✅ |
| 55% | 26 → 84 | tap | ✅ |
| 90% | 84 → 141 | tap | ✅ |
| alarm 25% | 100 → 34 | tap | ✅ |

设置页未打开时也能自动拉起（`pkg=com.android.settings` 已确认命中）；改完前台回到
`com.termux/app.dsh.mobile.MainActivity`。单次耗时约 2–4 s。

## §6 限制

- 需要无障碍服务在线（注入手势靠它）；服务被系统撤销时 `uiDetail` 返回 `no_a11y`。
- 精度受滑块像素量化限制，误差约 ±`max/24`（150 档 ±6）；需要精确值就用 `vol set`（非媒体流）。
- 设置页文案随 ROM/语言变化；匹配词是「媒体/铃声/通知/闹钟」，换语言后需要补词。
- 该兜底会短暂跳转到系统设置页（约 1–3 s），适合「用户明确要求改音量」而不是高频调用。

## §7 复现

```bash
adb -s <serial> forward tcp:3083 tcp:3083
TOK=$(scripts/probes/flicker/bridge.sh token)
curl -H "X-DSH-Token: $TOK" http://127.0.0.1:3083/volume                     # 读
curl -H "X-DSH-Token: $TOK" -d '{"stream":"music","percent":40}' \
     http://127.0.0.1:3083/volume                                            # 标准路径（本机会 blocked_by_rom）
curl -H "X-DSH-Token: $TOK" -d '{"viaUi":true,"stream":"music","percent":40}' \
     http://127.0.0.1:3083/volume                                            # UI 兜底（本机可用）
adb shell dumpsys audio | awk '/^- STREAM_MUSIC:/{f=1} f{print} f&&/Volume Group:/{exit}' | grep streamVolume
```

设备端脚本：`vol ui 40`、`vol ui 55 alarm`（`app/src/main/assets/gates/vol`）。
