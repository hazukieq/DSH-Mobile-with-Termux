package app.dsh.mobile.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Settings
import android.util.Log
import app.dsh.mobile.DshAccessibilityService
import app.dsh.mobile.StatusOverlay
import app.dsh.mobile.engine.AgentBridge
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.service.EngineService

/**
 * **adb 调试豁免通道**（v2.9.89）。
 *
 * ## 解决什么问题
 * bridge token 只写在 App 私有目录（`<engineRoot>/.bridge-token`，0600），
 * `/data/user/0/com.termux` 是 0700 —— 无 root 的 `adb shell` 读不到，
 * 于是「在真机上用 curl 驱动 bridge」这件事每次都要绕：拉起应用内终端 →
 * 手打 `cp` 把 token 拷到 /sdcard → 再读回来（见
 * `docs/qa/vivo-experience-report.md` §1）。调试一次要四五步，还不能脚本化。
 *
 * 现在：
 * ```
 * adb shell content query --uri content://com.termux.adb/token
 * adb shell content query --uri content://com.termux.adb/state
 * ```
 * 直接拿到 token 与诊断状态。
 *
 * ## 凭什么只给 adb
 * 三重闸门：
 * 1. `android:readPermission="android.permission.DUMP"` —— 只有 shell / root /
 *    system 持有（普通第三方 App 一个都没有）；
 * 2. 代码里再查一次 `DUMP` / `READ_LOGS` / `WRITE_SECURE_SETTINGS` 任一
 *    （防御 provider 被别的包同名复用或权限声明被改）；
 * 3. `Settings.Global` 的 `dsh_adb_bridge` 开关：写成 `0` 即整条通道关闭
 *    （`adb shell settings put global dsh_adb_bridge 0`），默认开启。
 *
 * 注意：token **每次 App 启动都会重新生成**，所以查询结果只在本次进程内有效。
 */
class AdbDebugProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun allowed(): Boolean {
        val ctx = context ?: return false
        // 闸门 3：全局开关
        val switch = runCatching {
            Settings.Global.getString(ctx.contentResolver, SWITCH_KEY)
        }.getOrNull()
        if (switch == "0") {
            Log.w(TAG, "adb channel disabled by settings (dsh_adb_bridge=0)")
            return false
        }
        // 闸门 2：调用方必须持有 shell/root 才有的权限之一
        val ok = DUMP_PERMS.any {
            ctx.checkCallingOrSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!ok) Log.w(TAG, "adb channel denied: caller lacks DUMP/READ_LOGS/WRITE_SECURE_SETTINGS")
        return ok
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (!allowed()) throw SecurityException("dsh adb channel requires DUMP permission")
        val ctx = requireNotNull(context)
        val rows = ArrayList<Array<Any?>>()
        when (uri.lastPathSegment ?: "token") {
            "token" -> {
                val token = AgentBridge.currentToken()
                    ?: readTokenFile(ctx)
                    ?: ""
                rows += arrayOf("token", token)
                rows += arrayOf("endpoint", "http://127.0.0.1:${AgentBridge.PORT}")
                rows += arrayOf("hint", "curl -H \"X-DSH-Token: \$token\" http://127.0.0.1:3083/diag")
            }
            "state" -> {
                rows += arrayOf("versionName", versionName(ctx))
                rows += arrayOf("pid", android.os.Process.myPid().toString())
                rows += arrayOf("a11yConnected", (DshAccessibilityService.instance != null).toString())
                rows += arrayOf("engineRunning", runCatching { EngineService.isRunning() }.getOrDefault(false).toString())
                rows += arrayOf("overlay", runCatching { StatusOverlay.debugState() }.getOrDefault("?"))
                rows += arrayOf("token", if ((AgentBridge.currentToken() ?: "").isNotEmpty()) "ready" else "missing")
            }
            else -> throw IllegalArgumentException("unknown path: ${uri.lastPathSegment} (available: token | state)")
        }
        return MatrixCursor(COLUMNS).apply {
            for (r in rows) addRow(r)
        }
    }

    private fun readTokenFile(ctx: Context): String? = runCatching {
        val f = java.io.File(EngineConfig.engineRoot(ctx), ".bridge-token")
        if (f.isFile) f.readText().trim() else null
    }.getOrNull()

    private fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // 只读通道：写入一律拒绝
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.dsh.adb"

    private companion object {
        const val TAG = "AdbDebugProvider"
        const val SWITCH_KEY = "dsh_adb_bridge"
        val COLUMNS = arrayOf("key", "value")
        val DUMP_PERMS = arrayOf(
            android.Manifest.permission.DUMP,
            android.Manifest.permission.READ_LOGS,
            "android.permission.WRITE_SECURE_SETTINGS",
        )
    }
}
