package app.dsh.mobile.bridge

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.dsh.mobile.engine.AgentBridge
import org.json.JSONObject

/**
 * 音量 / 铃声模式端点（`GET|POST /volume`）。
 *
 * ## 为什么要单独做（而不是让 agent 去点音量键）
 * 点音量键依赖「当前前台是谁 + 有没有系统音量面板挡住」：面板 ~2s 自动消失，连点时
 * 键盘焦点还会跑到面板上；而 `KEYCODE_VOLUME_*` 注入在部分 ROM 上被系统音效/焦点吞掉。
 * `AudioManager` 是官方 API，改完立刻可读回校验，跟界面是否在前台无关。
 *
 * ## 权限（这是本端点唯一的外部依赖）
 * * `MODIFY_AUDIO_SETTINGS` —— **normal 权限**，装包即授予，无运行时弹窗（AndroidManifest 已声明）。
 *   没有它 `setStreamVolume` / `adjustStreamVolume` 直接抛 `SecurityException`。
 * * `ACCESS_NOTIFICATION_POLICY`（Do Not Disturb 访问权）—— **特殊权限**，只能用户手动授予。
 *   Android N 起：**会切换 DND 的音量调整**（典型是把铃声调到 0 = 静音）没有这个权限就抛
 *   `SecurityException`。所以 `ringer=silent` 在未授权时返回 409 + `action:"open_policy_settings"`，
 *   不会伪装成功。媒体流（music）与这个权限无关。
 *
 * ## 语义选择（与系统一致，不要自己发明）
 * * `index` / `level`：绝对档位，范围 `0..getStreamMaxVolume(stream)`。
 * * `percent`：按 max 换算成档位（四舍五入），返回真实 index。
 * * `adj`：`up|down` 相对调整，默认 media 流；`steps` 可指定次数。
 * * `mute:true|false`：走 `ADJUST_TOGGLE_MUTE`，为 true 时到 0，为 false 时恢复到系统记忆的原档。
 * * `ringer`：`normal|vibrate|silent`，需要 DND 访问权（见上）。
 *
 * 所有写操作都**读回校验**并回填 `before`/`after`，避免「调用成功但被 ROM 改回去」的假成功。
 */
internal class AudioHandler(private val bridge: AgentBridge) {

    private val tag = "AudioHandler"

    /** 流名 → streamType。别名覆盖 agent 常说的叫法。 */
    private val streams: Map<String, Int> = linkedMapOf(
        "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "ringtone" to AudioManager.STREAM_RING,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "alarm" to AudioManager.STREAM_ALARM,
        "system" to AudioManager.STREAM_SYSTEM,
        "call" to AudioManager.STREAM_VOICE_CALL,
        "voice_call" to AudioManager.STREAM_VOICE_CALL,
        "dtmf" to AudioManager.STREAM_DTMF,
    )

    private fun am(ctx: Context): AudioManager? =
        runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()

    private fun streamName(type: Int): String = when (type) {
        AudioManager.STREAM_MUSIC -> "music"
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_NOTIFICATION -> "notification"
        AudioManager.STREAM_ALARM -> "alarm"
        AudioManager.STREAM_SYSTEM -> "system"
        AudioManager.STREAM_VOICE_CALL -> "voice_call"
        AudioManager.STREAM_DTMF -> "dtmf"
        else -> "stream_$type"
    }

    /**
     * 部分 ROM（实测 vivo OriginOS 17 / Android 17）对**媒体流**的「无 UI 静默改音量」直接
     * 丢弃：AudioService 日志里能看到 `setStreamVolume(stream:STREAM_MUSIC index:60 ...)`，
     * 无异常、无权限错误，但索引不动；而同一路径改 ring/alarm/notification 都成功。
     * 唯一被验证有效的触发方式是带 `FLAG_SHOW_UI`（与音量键同一条 visible 路径）。
     * 所以这里做「静默失败 → 带 UI 重试」，并把是否用了 UI 如实回报给调用方。
     */
    private fun snapshot(a: AudioManager, type: Int): JSONObject {
        val max = a.getStreamMaxVolume(type)
        val idx = runCatching { a.getStreamVolume(type) }.getOrDefault(-1)
        val pct = if (max > 0 && idx >= 0) Math.round(idx * 100f / max) else 0
        return JSONObject()
            .put("stream", streamName(type))
            .put("stream_type", type)
            .put("index", idx)
            .put("max", max)
            .put("percent", pct)
            .put("muted", idx == 0)
    }

    private fun ringerJson(a: AudioManager, ctx: Context): JSONObject {
        val mode = runCatching { a.ringerMode }.getOrDefault(-1)
        val label = when (mode) {
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_SILENT -> "silent"
            else -> "unknown"
        }
        val policy = runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.isNotificationPolicyAccessGranted
        }.getOrDefault(false)
        return JSONObject().put("mode", label).put("mode_code", mode).put("policy_access", policy)
    }

    /** `GET /volume`：所有主流 + 铃声模式 + 能力位。 */
    fun volumeGet(ctx: Context): Pair<Int, String> {
        val a = am(ctx) ?: return 503 to """{"ok":false,"error":"no_audio_service"}"""
        return runCatching {
            val all = JSONObject()
            streams.entries.filter { it.key != "ringtone" && it.key != "voice_call" && it.key != "call" }
                .forEach { (name, type) -> all.put(name, snapshot(a, type)) }
            val body = JSONObject()
                .put("ok", true)
                .put("streams", all)
                .put("ringer", ringerJson(a, ctx))
                .put("volume_fixed", runCatching { a.isVolumeFixed }.getOrDefault(false))
                .put("model", Build.MODEL)
            // 默认流单列出来：agent 九成场景只关心媒体音量，省一次解析。
            body.put("default", all.optJSONObject("music"))
            200 to body.toString()
        }.getOrElse {
            Log.w(tag, "volume get failed: ${it.message}")
            500 to JSONObject().put("ok", false).put("error", "read_failed")
                .put("reason", it.message ?: it.javaClass.simpleName).toString()
        }
    }

    /** `POST /volume`：写操作，全部读回校验。 */
    fun volumeSet(ctx: Context, body: String): Pair<Int, String> {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return 400 to """{"ok":false,"error":"invalid json"}"""
        val a = am(ctx) ?: return 503 to """{"ok":false,"error":"no_audio_service"}"""

        // 0) 打开 DND 访问权设置页（给 agent 一个可执行的补救动作）
        if (obj.optBoolean("openPolicySettings", false)) {
            return runCatching {
                val i = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                200 to """{"ok":true,"opened":"notification_policy_access"}"""
            }.getOrElse {
                500 to JSONObject().put("ok", false).put("error", "open_failed")
                    .put("reason", it.message ?: "unknown").toString()
            }
        }

        // 0b) 打开系统音量面板（供 UI 兜底：面板是真实用户界面，拖滑块一定生效）
        if (obj.optBoolean("openPanel", false)) {
            if (Build.VERSION.SDK_INT < 29) {
                return 409 to """{"ok":false,"error":"panel_needs_api29"}"""
            }
            return runCatching {
                val i = Intent(android.provider.Settings.Panel.ACTION_VOLUME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                200 to """{"ok":true,"opened":"volume_panel","hint":"drag the slider with a gesture; or press volume keys"}"""
            }.getOrElse {
                500 to JSONObject().put("ok", false).put("error", "open_failed")
                    .put("reason", it.message ?: "unknown").toString()
            }
        }

        // 1) 铃声模式（可能需要 DND 访问权）
        val ringer = obj.optString("ringer").lowercase()
        if (ringer.isNotBlank()) {
            val target = when (ringer) {
                "normal", "ring" -> AudioManager.RINGER_MODE_NORMAL
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
                else -> return 400 to JSONObject().put("ok", false)
                    .put("error", "bad_ringer").put("allowed", "normal|vibrate|silent").toString()
            }
            return runCatching {
                val before = ringerJson(a, ctx)
                a.ringerMode = target
                val after = ringerJson(a, ctx)
                val ok = after.optString("mode") == ringerJsonOf(target)
                val resp = JSONObject().put("ok", true).put("op", "ringer")
                    .put("requested", ringer).put("before", before).put("after", after)
                    .put("applied", ok)
                if (!ok) {
                    resp.put("hint", "system kept ringer=${after.optString("mode")} (DND policy or ROM restriction)")
                }
                200 to resp.toString()
            }.getOrElse { it ->
                // 没有 DND 访问权时这里就是 SecurityException
                val policy = runCatching {
                    (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .isNotificationPolicyAccessGranted
                }.getOrDefault(false)
                Log.w(tag, "ringer failed: ${it.message} policy=$policy")
                409 to JSONObject().put("ok", false)
                    .put("error", "needs_policy_access")
                    .put("requested", ringer)
                    .put("policy_access", policy)
                    .put("reason", it.message ?: it.javaClass.simpleName)
                    .put("hint", "changing the ringer to silent toggles Do Not Disturb; " +
                        "grant Notification Policy Access (Settings > Notifications > Do Not Disturb access), " +
                        "or POST /volume {\"openPolicySettings\":true} to open that page")
                    .put("action", "open_policy_settings")
                    .toString()
            }
        }

        // 2) 流音量
        val streamName = obj.optString("stream", "music").lowercase()
        val type = streams[streamName]
            ?: return 400 to JSONObject().put("ok", false).put("error", "unknown_stream")
                .put("stream", streamName).put("allowed", streams.keys.toList()).toString()
        if (runCatching { a.isVolumeFixed }.getOrDefault(false)) {
            return 409 to """{"ok":false,"error":"volume_fixed","hint":"device reports a fixed-volume policy"}"""
        }

        val before = snapshot(a, type)
        val max = before.optInt("max")
        // ui=true 强制走 FLAG_SHOW_UI；默认 0（静默），静默无效时自动带 UI 重试一次。
        var flags = if (obj.optBoolean("ui", false)) AudioManager.FLAG_SHOW_UI else 0
        val resp = JSONObject().put("ok", true).put("op", "set").put("stream", streamName)
        return runCatching {
            when {
                obj.has("mute") -> {
                    val wantMute = obj.optBoolean("mute")
                    val dir = if (wantMute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                    a.adjustStreamVolume(type, dir, flags)
                    resp.put("adj", if (wantMute) "mute" else "unmute")
                }
                obj.has("index") || obj.has("level") -> {
                    val raw = if (obj.has("index")) obj.optInt("index") else obj.optInt("level")
                    val idx = raw.coerceIn(0, max)
                    a.setStreamVolume(type, idx, flags)
                    resp.put("requested", raw).put("index_clamped", idx)
                }
                obj.has("percent") -> {
                    val pct = obj.optInt("percent").coerceIn(0, 100)
                    val idx = Math.round(max * pct / 100f).coerceIn(0, max)
                    a.setStreamVolume(type, idx, flags)
                    resp.put("requested_percent", pct).put("index", idx)
                }
                obj.has("adj") || obj.has("direction") -> {
                    val dirName = if (obj.has("adj")) obj.optString("adj") else obj.optString("direction")
                    val dir = when (dirName.lowercase()) {
                        "up", "raise", "+" -> AudioManager.ADJUST_RAISE
                        "down", "lower", "-" -> AudioManager.ADJUST_LOWER
                        else -> return 400 to JSONObject().put("ok", false)
                            .put("error", "bad_adj").put("allowed", "up|down").toString()
                    }
                    val steps = obj.optInt("steps", 1).coerceIn(1, 100)
                    repeat(steps) { a.adjustStreamVolume(type, dir, flags) }
                    resp.put("adj", dirName).put("steps", steps)
                }
                else -> return 400 to JSONObject().put("ok", false)
                    .put("error", "nothing_to_do")
                    .put("accepted", "index|level|percent|adj|mute|ringer|openPanel|openPolicySettings")
                    .toString()
            }
            var after = snapshot(a, type)
            var retriedWithUi = false
            if (before.optInt("index") == after.optInt("index") && flags and AudioManager.FLAG_SHOW_UI == 0) {
                // 静默路径被 ROM 吞了：带 FLAG_SHOW_UI 再试一次（与音量键同一条 visible 路径）
                flags = AudioManager.FLAG_SHOW_UI
                retriedWithUi = true
                when {
                    obj.has("mute") ->
                        a.adjustStreamVolume(type, if (obj.optBoolean("mute")) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, flags)
                    obj.has("index") || obj.has("level") -> {
                        val raw = if (obj.has("index")) obj.optInt("index") else obj.optInt("level")
                        a.setStreamVolume(type, raw.coerceIn(0, max), flags)
                    }
                    obj.has("percent") -> {
                        val pct = obj.optInt("percent").coerceIn(0, 100)
                        a.setStreamVolume(type, Math.round(max * pct / 100f).coerceIn(0, max), flags)
                    }
                    obj.has("adj") || obj.has("direction") -> {
                        val dirName = if (obj.has("adj")) obj.optString("adj") else obj.optString("direction")
                        val dir = if (dirName.lowercase() in listOf("up", "raise", "+")) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        val steps = obj.optInt("steps", 1).coerceIn(1, 100)
                        repeat(steps) { a.adjustStreamVolume(type, dir, flags) }
                    }
                }
                after = snapshot(a, type)
            }
            resp.put("before", before).put("after", after)
                .put("changed", before.optInt("index") != after.optInt("index"))
                .put("verify", "read-back")
            if (retriedWithUi) resp.put("retried_with_ui", true)
            // 「调用成功但没变」是真实存在的，显式标出来（vivo OriginOS 实测：媒体流被丢弃）
            if (!resp.optBoolean("changed")) {
                resp.put("blocked_by_rom", true)
                resp.put("hint", "index unchanged after call. vivo OriginOS 17 / Android 17 silently drops " +
                    "third-party STREAM_MUSIC changes (the call reaches AudioService, no exception, index stays); " +
                    "ring/notification/alarm/system still work there. Workarounds: use those streams, or open " +
                    "Settings > Sound (POST /activity) and drag the slider as a UI action, or press the volume keys.")
            }
            200 to resp.toString()
        }.getOrElse { e ->
            Log.w(tag, "set volume failed: ${e.message}")
            409 to JSONObject().put("ok", false)
                .put("error", "not_permitted")
                .put("reason", e.message ?: e.javaClass.simpleName)
                .put("hint", "MODIFY_AUDIO_SETTINGS is a normal permission declared in the manifest; " +
                    "if this persists the ROM is blocking programmatic volume changes")
                .toString()
        }
    }

    private fun ringerJsonOf(code: Int): String = when (code) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> "unknown"
    }
}
