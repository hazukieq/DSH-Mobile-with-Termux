package app.dsh.mobile.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.dsh.mobile.engine.AgentBridge
import org.json.JSONObject
import java.io.File

/**
 * APK 安装端点（`GET /install`、`POST /install`）。
 *
 * ## 权限链（这是「赋予 app 装应用的权限」在 Android 上的三段式）
 * 1. `android.permission.REQUEST_INSTALL_PACKAGES` —— manifest 里声明（**normal，装包即授予**），
 *    没有它连 PackageInstaller 会话都建不起来。
 * 2. **「安装未知应用」开关** —— 特殊权限，只能由用户/系统授予：
 *    `PackageManager.canRequestPackageInstalls()` 为 false 时 commit 会被系统拒（STATUS_FAILURE_BLOCKED）。
 *    脚本/adb 侧等价的授予命令是 `adb shell appops set <pkg> REQUEST_INSTALL_PACKAGES allow`
 *    （本 app 在 root/Shizuku 可用时会自动执行，见 [grantViaShell]）。
 * 3. 系统安装器 UI —— 普通 app 无法静默装（除非系统签名/root）。本端点用
 *    `PackageInstaller.Session` 写好 APK 后由接收者拉起系统确认页，**由用户本人确认**。
 *
 * ## 为什么不做「自动点安装」
 * Android 14（API 34）起系统对侧载安装强制「用户确认」，Android 15/16/17 进一步收紧：
 * 确认页常常连带**生物识别/解锁**（`BiometricPrompt`），确认按钮也未必是稳定文案
 * （vivo「超级守护」把放行入口做成段落里的可点 Span）。用无障碍去点等于替用户做安全决策，
 * 既不可靠也不该做。所以本端点只负责把会话准备好并拉起系统安装器，确认留给用户；
 * `GET /install` 可以事后轮询结果（`last_status`）。
 *
 * ## 语义
 * * `GET /install`：能力与状态（`can_install`、`has_permission`、`root`、`last`）。
 * * `POST /install {"path":"/sdcard/x.apk"}`：把 APK 交给系统安装器。默认 `waitMs:8000`
 *   （只等「安装器是否被拉起/是否已装完」，不等用户操作；用户确认可能要几十秒）。
 *   返回 `status`（见 [STATUS_*]）、`package`、`versionName`、`versionCode`、`requires_user`、
 *   `waitedMs`、`next`（下一步提示）。
 * * `POST /install {"openSettings":true}`：打开本 app 的「安装未知应用」页。
 * * `POST /install {"grant":true}`：root/Shizuku 可用时执行 `appops set … allow` 自行授权。
 */
internal class InstallHandler(private val bridge: AgentBridge) {

    private val tag = "InstallHandler"

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_SUCCESS = "success"
        const val STATUS_PENDING = "pending_user_action"
        const val STATUS_BLOCKED = "blocked_by_settings"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_FAILED = "failed"

    }

    /** 最近一次安装的状态（BroadcastReceiver 与 POST 共用） */
    internal object State {
        @Volatile var status: String = STATUS_IDLE
        @Volatile var packageName: String = ""
        @Volatile var detail: String = ""
        @Volatile var at: Long = 0L
        @Volatile var pendingUserAction: Boolean = false
        @Volatile var clicked: String = ""
    }

    private fun pm(ctx: Context) = ctx.packageManager

    /** 第二步的开关是否打开 */
    private fun canInstall(ctx: Context): Boolean =
        runCatching { pm(ctx).canRequestPackageInstalls() }.getOrDefault(false)

    /** 有没有 root（`su -c id` 能拿到 uid=0） */
    private fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    fun state(ctx: Context): Pair<Int, String> = 200 to JSONObject()
        .put("ok", true)
        .put("package", ctx.packageName)
        .put("has_permission", hasManifestPermission(ctx))
        .put("can_install", canInstall(ctx))
        .put("sdk", Build.VERSION.SDK_INT)
        .put("root", hasRoot())
        .put("last_status", State.status)
        .put("last_package", State.packageName)
        .put("last_detail", State.detail)
        .put("last_at", State.at)
        .put("last_clicked", State.clicked)
        .put("hint", if (canInstall(ctx)) {
            "ready: POST /install {\"path\":\"/sdcard/x.apk\"}"
        } else {
            "blocked: POST /install {\"openSettings\":true} (or {\"grant\":true} with root) to allow installing unknown apps"
        })
        .toString()

    private fun hasManifestPermission(ctx: Context): Boolean = runCatching {
        // REQUEST_INSTALL_PACKAGES 是 normal 权限：装包即授予，这里只做自检（防止有人删了 manifest 行）。
        ctx.packageManager.getPackageInfo(ctx.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions
            ?.contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
    }.getOrDefault(false)

    fun install(ctx: Context, body: String): Pair<Int, String> = with(bridge) {
        val obj = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
            ?: return 400 to JSONObject().put("ok", false).put("error", "invalid_json").toString()

        if (obj.optBoolean("openSettings", false)) return openUnknownSourcesSettings(ctx)
        if (obj.optBoolean("grant", false)) return grantViaShell(ctx)

        val path = obj.optString("path").trim()
        if (path.isBlank()) return 400 to JSONObject()
            .put("ok", false).put("error", "need_path")
            .put("hint", "POST /install {\"path\":\"/sdcard/Download/x.apk\"}").toString()

        val file = File(path)
        if (!file.isFile) return 404 to JSONObject()
            .put("ok", false).put("error", "file_not_found").put("path", path)
            .put("hint", "push the APK first (adb push x.apk /sdcard/Download/) or use {\"url\":…} via /download").toString()
        val size = file.length()
        val info = runCatching {
            pm(ctx).getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES)
        }.getOrNull()
        if (info == null || info.packageName == null) return 400 to JSONObject()
            .put("ok", false).put("error", "not_an_apk").put("path", path).put("size", size).toString()

        val verName = runCatching { info.versionName }.getOrNull() ?: ""
        val verCode = runCatching {
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(-1L)

        if (!canInstall(ctx)) {
            // 不偷偷失败：明确告诉调用方被哪一步挡住，并给出两条可走的路。
            val opened = obj.optBoolean("openSettingsOnBlock", true)
            if (opened) runCatching { openUnknownSourcesSettings(ctx) }
            return 409 to JSONObject()
                .put("ok", false).put("error", "needs_unknown_sources")
                .put("can_install", false)
                .put("package", info.packageName).put("versionName", verName).put("versionCode", verCode)
                .put("opened_settings", opened)
                .put("how_to_fix",
                    "allow 'install unknown apps' for this app: POST /install {\"openSettings\":true}; " +
                        "with root: POST /install {\"grant\":true}; adb: appops set ${ctx.packageName} REQUEST_INSTALL_PACKAGES allow")
                .toString()
        }

        State.status = STATUS_IDLE
        State.packageName = info.packageName
        State.detail = ""
        State.clicked = ""
        State.pendingUserAction = false
        val startedAt = android.os.SystemClock.uptimeMillis()

        val sessionId = runCatching { createSessionAndWrite(ctx, file, info.packageName, size) }
            .getOrElse { e ->
                Log.w(tag, "session failed: $e")
                return 500 to JSONObject().put("ok", false).put("error", "session_failed")
                    .put("reason", e.toString()).put("path", path).toString()
            }

        val waitMs = obj.optLong("waitMs", 8_000L).coerceIn(0L, 180_000L)
        // 只等「系统安装器被拉起 / 安装已结束」；用户确认（可能要解锁+生物识别）不在这里等。
        if (waitMs > 0) {
            while (android.os.SystemClock.uptimeMillis() - startedAt < waitMs) {
                val st = State.status
                if (st == STATUS_SUCCESS || st == STATUS_FAILED || st.startsWith("failure")) break
                if (State.pendingUserAction) break
                Thread.sleep(150L)
            }
        }
        val waited = android.os.SystemClock.uptimeMillis() - startedAt
        val status = if (State.status == STATUS_IDLE) STATUS_TIMEOUT else State.status
        State.clicked = ""
        val code = when (status) {
            STATUS_SUCCESS -> 200
            STATUS_TIMEOUT, STATUS_PENDING -> 202
            else -> 409
        }
        return code to JSONObject()
            .put("ok", status == STATUS_SUCCESS)
            .put("status", status)
            .put("session", sessionId)
            .put("package", info.packageName)
            .put("versionName", verName)
            .put("versionCode", verCode)
            .put("size", size)
            .put("waitedMs", waited)
            .put("requires_user", status != STATUS_SUCCESS)
            .put("next", if (status == STATUS_SUCCESS) "installed; launch it with POST /launch"
            else "user must confirm in the system installer (may require unlock/biometric); " +
                "poll GET /install for last_status")
            .put("detail", State.detail)
            .put("installer", "package_installer")
            .put("note", if (status == STATUS_SUCCESS) "installed"
            else "handed off to the system installer; the user confirms on device")
            .toString()
    }

    /** 建会话 + 把 APK 写进 base.apk + commit（PendingIntent 拉起确认页并回报结果） */
    private fun createSessionAndWrite(ctx: Context, file: File, pkg: String, size: Long): Int {
        val installer = pm(ctx).packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(pkg)
                runCatching { setInstallReason(PackageManager.INSTALL_REASON_USER) }
                if (Build.VERSION.SDK_INT >= 31) {
                    runCatching { setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED) }
                }
            }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0L, size).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val result = Intent(ctx, InstallResultReceiver::class.java)
                .setAction("app.dsh.mobile.INSTALL_RESULT")
                .putExtra("session", sessionId)
            val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(ctx, sessionId, result, flags)
            session.commit(pi.intentSender)
        }
        Log.i(tag, "commit session=$sessionId pkg=$pkg size=$size")
        return sessionId
    }


    private fun openUnknownSourcesSettings(ctx: Context): Pair<Int, String> {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
        Log.i(tag, "openUnknownSourcesSettings opened=$opened")
        return 200 to JSONObject()
            .put("ok", true).put("opened", "unknown_sources_settings").put("started", opened)
            .put("can_install", canInstall(ctx))
            .put("hint", "toggle 'Allow from this source' for DSH Mobile, then retry POST /install")
            .toString()
    }

    /** root/Shizuku 可用时自己授权（等价于 `adb shell appops set <pkg> REQUEST_INSTALL_PACKAGES allow`） */
    private fun grantViaShell(ctx: Context): Pair<Int, String> {
        if (!hasRoot()) return 409 to JSONObject()
            .put("ok", false).put("error", "no_root")
            .put("hint", "no root on this device: POST /install {\"openSettings\":true} and toggle it manually, " +
                "or run adb shell appops set ${ctx.packageName} REQUEST_INSTALL_PACKAGES allow").toString()
        val out = runCatching {
            val p = ProcessBuilder("su", "-c",
                "appops set ${ctx.packageName} REQUEST_INSTALL_PACKAGES allow").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text.trim()
        }.getOrElse { it.toString() }
        val ok = canInstall(ctx)
        return (if (ok) 200 else 409) to JSONObject()
            .put("ok", ok).put("can_install", ok).put("output", out)
            .put("note", if (ok) "granted" else "su ran but CanRequestPackageInstalls is still false")
            .toString()
    }
}

/**
 * 安装结果接收者（`PackageInstaller.commit` 的 PendingIntent 目标）。
 *
 * `STATUS_PENDING_USER_ACTION` 时必须把 `EXTRA_INTENT` 拉起来，否则系统安装器永远不出现
 * （这是 PackageInstaller 最常见的坑）。
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: InstallHandler.State.packageName
        val name = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallHandler.STATUS_SUCCESS
            PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallHandler.STATUS_PENDING
            PackageInstaller.STATUS_FAILURE_ABORTED -> "failure_aborted"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallHandler.STATUS_BLOCKED
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "failure_conflict"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "failure_incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "failure_invalid"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "failure_storage"
            PackageInstaller.STATUS_FAILURE -> "failure"
            else -> "status_$status"
        }
        InstallHandler.State.status = name
        InstallHandler.State.packageName = pkg
        InstallHandler.State.detail = msg
        InstallHandler.State.at = System.currentTimeMillis()
        InstallHandler.State.pendingUserAction = status == PackageInstaller.STATUS_PENDING_USER_ACTION
        Log.i("InstallResult", "status=$name package=$pkg msg=$msg")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            runCatching {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w("InstallResult", "cannot launch installer: $it") }
        }
    }
}
