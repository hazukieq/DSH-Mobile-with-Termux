package app.dsh.mobile.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.os.Bundle
import java.io.File
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Settings
import android.util.Log
import app.dsh.mobile.DshAccessibilityService
import app.dsh.mobile.StatusOverlay
import app.dsh.mobile.engine.AgentBridge
import app.dsh.mobile.engine.EngineEnvBuilder
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
 * adb shell content call  --uri content://com.termux.adb --method exec --arg 'ls $HOME'
 * ```
 * 直接拿到 token、诊断状态，并且**在 App 自己的 Termux 环境里执行命令**
 * （同一个 bash、同一套 PATH/PREFIX/DSH_HOME，无需在手机输入法里打字）。
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

    /**
     * `content call --uri content://com.termux.adb --method exec --arg '<command>'`
     *
     * 在 App 自己的 Termux 环境里跑命令（引擎 bash + 同一套 env），把 stdout/exit 回给 adb。
     * 这就是「给终端一个 adb 接口」：调试时不用再在手机输入法里一个字一个字敲。
     *
     * extras（都是可选）：
     *  * `cmd`        等价于 `--arg`
     *  * `timeoutMs`  默认 60000，上限 300000
     *  * `cwd`        工作目录（默认 `$HOME`）
     *  * `maxBytes`   输出上限，默认 131072
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!allowed()) throw SecurityException("dsh adb channel requires DUMP permission")
        val ctx = requireNotNull(context)
        val m = method.trim().lowercase()
        if (m != "exec" && m != "sh" && m != "run") {
            throw IllegalArgumentException("unknown method: $method (available: exec)")
        }
        val cmd = (extras?.getString("cmd") ?: arg ?: "").trim()
        if (cmd.isEmpty()) throw IllegalArgumentException("empty command: pass --arg '<command>'")

        val timeoutMs = (extras?.getLong("timeoutMs", 60_000L) ?: 60_000L).coerceIn(1_000L, 300_000L)
        val maxBytes = (extras?.getInt("maxBytes", 131_072) ?: 131_072).coerceIn(1_024, 1_048_576)
        val root = EngineConfig.engineRoot(ctx)
        val home = runCatching { app.dsh.mobile.engine.EnginePaths.dshHome(ctx) }
            .getOrElse { File(root, "home") }
        val cwd = extras?.getString("cwd")?.takeIf { it.isNotBlank() }?.let(::File) ?: home

        val bash = File(root, "bin/bash").let { if (it.canExecute()) it.absolutePath else "/system/bin/sh" }
        val env = runCatching { EngineEnvBuilder.buildEnv(ctx, AgentBridge.PORT) }
            .getOrDefault(emptyArray())
        val pb = ProcessBuilder(bash, "-lc", cmd).redirectErrorStream(true)
        if (cwd.isDirectory) pb.directory(cwd)
        pb.environment().apply {
            val keep = setOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT",
                "ANDROID_TZDATA_ROOT", "ANDROID_RUNTIME_ROOT", "BOOTCLASSPATH", "HOME", "TMPDIR")
            keys.retainAll(keep)
            for (kv in env) {
                val i = kv.indexOf('=')
                if (i > 0) put(kv.substring(0, i), kv.substring(i + 1))
            }
            putIfAbsent("HOME", home.absolutePath)
            putIfAbsent("TMPDIR", runCatching { ctx.cacheDir.absolutePath }.getOrDefault("/data/local/tmp"))
        }

        val started = android.os.SystemClock.uptimeMillis()
        val out = StringBuilder()
        var truncated = false
        var exit = -1
        var timedOut = false
        var proc: Process? = null
        try {
            proc = pb.start()
            val reader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(8 * 1024)
                        while (true) {
                            val n = br.read(buf)
                            if (n <= 0) break
                            synchronized(out) {
                                if (out.length < maxBytes) out.append(buf, 0, minOf(n, maxBytes - out.length))
                                else truncated = true
                            }
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                timedOut = true
                runCatching { proc.destroyForcibly() }
            }
            reader.join(1_000L)
            exit = runCatching { proc.exitValue() }.getOrDefault(-1)
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: $e")
            if (out.isNotEmpty()) out.append('\n')
            out.append("exec failed: ").append(e)
        } finally {
            runCatching { proc?.destroy() }
        }
        val ms = android.os.SystemClock.uptimeMillis() - started
        Log.i(TAG, "exec exit=$exit ms=$ms timeout=$timedOut cmd=${cmd.take(120)}")
        return Bundle().apply {
            putBoolean("ok", !timedOut && exit == 0)
            putInt("exit", exit)
            putLong("ms", ms)
            putBoolean("timeout", timedOut)
            putBoolean("truncated", truncated)
            putString("cmd", cmd)
            putString("cwd", cwd.absolutePath)
            synchronized(out) { putString("out", out.toString()) }
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
