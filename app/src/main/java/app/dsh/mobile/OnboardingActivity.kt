package app.dsh.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.PrivMode
import app.dsh.mobile.engine.Privilege

/**
 * 首次启动引导（一次性，过后不再弹）。
 *
 * 用户在此初始化三件事：
 *  1. 界面语言（英语/德语/中文，v1.2.120 起三选一，默认英语）
 *  2. 默认显示方向（竖屏/横屏/自动，v1.2.120 起三态）—— 带简易演示图；横屏即绑定桌面布局
 *  3. 运行权限模式（普通/Shizuku/Root）—— 决定 AI 能力边界。
 *
 * Root 模式保护壳：选择时弹两次高危警告，确认后才写入；启动时（EngineSupervisor
 * 进入 Root 分支前）会先对 dsh-home 做 tar 备份可回滚。能力检测仅 stat/probe，
 * 不做任何危险动作。
 */
class OnboardingActivity : Activity() {

    /** v1.3.51：引导阶段一并申请文件管理权限（所有文件访问）。 */
    private var pendingAllFiles = false
    private var allFilesAsked = false



    private var selectedOrientation: OrientationMode = OrientationMode.PORTRAIT
    private var selectedPriv = PrivMode.NORMAL
    private var selectedLang = LocaleHelper.DEFAULT

    /** Shizuku 授权结果监听（requestPermission 异步回调） */
    private val shizukuPermListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ) {
                Privilege.markShizukuDenied(this, grantResult != android.content.pm.PackageManager.PERMISSION_GRANTED)
                refreshShizukuStatus()
            }
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermListener)

        // 默认选中：竖屏（手机）+ 普通 + 语言卡片高亮（默认英语）
        selectedLang = LocaleHelper.get(this)
        updateSelectionUi()

        // 语言卡片组（v1.2.120：en/de/zh 三选一）：点击保存偏好并 recreate，立即切换引导页语言
        val langCards = mapOf(
            findViewById<LinearLayout>(R.id.cardLangEn) to "en",
            findViewById<LinearLayout>(R.id.cardLangDe) to "de",
            findViewById<LinearLayout>(R.id.cardLangZh) to "zh",
        )
        for ((card, code) in langCards) {
            card.setOnClickListener {
                if (selectedLang != code) {
                    LocaleHelper.set(this, code)
                    selectedLang = code
                    recreate()
                }
            }
        }

        findViewById<LinearLayout>(R.id.cardPortrait).setOnClickListener {
            selectedOrientation = OrientationMode.PORTRAIT
            updateSelectionUi()
        }
        findViewById<LinearLayout>(R.id.cardLandscape).setOnClickListener {
            selectedOrientation = OrientationMode.LANDSCAPE
            updateSelectionUi()
        }
        findViewById<LinearLayout>(R.id.cardAuto).setOnClickListener {
            selectedOrientation = OrientationMode.AUTO
            updateSelectionUi()
        }

        val privGroup = findViewById<RadioGroup>(R.id.privGroup)
        privGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedPriv = when (checkedId) {
                R.id.privRoot -> PrivMode.ROOT
                R.id.privShizuku -> PrivMode.SHIZUKU
                else -> PrivMode.NORMAL
            }
            if (selectedPriv == PrivMode.SHIZUKU) maybeRequestShizuku()
        }
        privGroup.check(R.id.privNormal)

        // 能力检测（Root 用 su 实测；Shizuku 用官方 binder ping + 授权态）
        //
        // ⚠️ v1.2.100：**移到后台线程**。probeRoot 最坏要依次试 3 种 su 语法、
        // 每种 2s 超时（首次还会弹 Magisk 授权框），放主线程最坏阻塞 6s+ → ANR 风险。
        // 先显示「检测中」，结果回填 UI。
        findViewById<TextView>(R.id.capRoot).apply {
            text = getString(R.string.ob_cap_root_checking)
            setTextColor(0xFF8A94A3.toInt())
        }
        Thread({
            val hasRoot = Privilege.probe(this).hasRoot
            runOnUiThread {
                findViewById<TextView>(R.id.capRoot).apply {
                    text = getString(
                        if (hasRoot) R.string.ob_cap_root_present else R.string.ob_cap_root_absent
                    )
                    setTextColor(if (hasRoot) 0xFF6EE7B7.toInt() else 0xFFFFB74D.toInt())
                }
            }
        }, "probe-root").apply { isDaemon = true; start() }
        refreshShizukuStatus()

        // m1.30：未检测到 su 时，Root 选项置灰不可选 + 下方提示
        updateRootAvailability()

        findViewById<Button>(R.id.btnStart).setOnClickListener { finishOnboarding() }
    }

    /** Root 无 su 可用时：置灰 RadioButton 禁用 + 显示「未检测到 su」提示 */
    private fun updateRootAvailability() {
        val rootOk = Privilege.rootAvailableMinimal()
        val rb = findViewById<RadioButton>(R.id.privRoot)
        rb.isEnabled = rootOk
        rb.alpha = if (rootOk) 1f else 0.35f
        findViewById<TextView>(R.id.rootHint).text = getString(
            if (rootOk) R.string.ob_priv_root_desc else R.string.ob_priv_root_gray_hint
        )
        // 若当前选中了 Root 但 su 不可用，回落到普通
        if (!rootOk && selectedPriv == PrivMode.ROOT) {
            selectedPriv = PrivMode.NORMAL
            findViewById<RadioGroup>(R.id.privGroup).check(R.id.privNormal)
        }
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onDestroy()
    }

    /** 返回键：以普通模式完成引导进入主界面，绝不卡死 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        commit()
    }

    /**
     * Shizuku 状态刷新 + 必要时主动请求授权。
     * 三态：已授权 → 绿色提示；server 在跑未授权 → 主动 requestPermission 弹框；
     * server 未跑 → 黄色提示用户先启动 Shizuku（弹框无从谈起）。
     */
    private fun refreshShizukuStatus() {
        val v = findViewById<TextView>(R.id.capShizuku)
        when {
            Privilege.shizukuUsable() -> {
                v.text = getString(R.string.ob_cap_shizuku_present)
                v.setTextColor(0xFF6EE7B7.toInt())
            }
            Privilege.shizukuServerRunning() -> {
                // v1.2.158 隐私修复：不再在此自动请求（拒绝后回调会回到这里 → 死循环）。
                // 引导页的显式入口是"选中 Shizuku 单选项"→ maybeRequestShizuku()。
                v.text = getString(R.string.ob_cap_shizuku_request)
                v.setTextColor(0xFFFFB74D.toInt())
                v.isClickable = true
                v.setOnClickListener {
                    Privilege.requestShizukuPermission(SHIZUKU_REQ, this@OnboardingActivity, explicit = true)
                }
            }
            else -> {
                v.text = getString(R.string.ob_cap_shizuku_absent)
                v.setTextColor(0xFFFFB74D.toInt())
            }
        }
    }

    /** 用户切到 Shizuku 单选时再给一次授权机会（server 后来启动的情形） */
    private fun maybeRequestShizuku() {
        if (Privilege.shizukuServerRunning() && !Privilege.shizukuGranted()) {
            Privilege.requestShizukuPermission(SHIZUKU_REQ, this@OnboardingActivity, explicit = true)
        }
    }

    private companion object {
        const val SHIZUKU_REQ = 4201
        const val REQ_ALL_FILES = 2001
    }

    /**
     * 卡片选中态（v1.2.120）：
     *  - 方向三卡（竖屏/横屏/自动）：选中卡文字高亮 + 不透明，未选中变暗；
     *  - 语言三卡（en/de/zh）：同样高亮当前语言（此前无语言选中态，
     *    默认英语时用户看不出当前选的是哪个）。
     */
    private fun updateSelectionUi() {
        val highlight = 0xFF7DD3FC.toInt()
        val normal = 0xFF9CA3AF.toInt()

        // —— 方向：竖屏/横屏/自动 ——
        val orientCards = listOf(
            Triple(
                findViewById<LinearLayout>(R.id.cardPortrait),
                findViewById<TextView>(R.id.txtPortrait),
                selectedOrientation == OrientationMode.PORTRAIT,
            ),
            Triple(
                findViewById<LinearLayout>(R.id.cardLandscape),
                findViewById<TextView>(R.id.txtLandscape),
                selectedOrientation == OrientationMode.LANDSCAPE,
            ),
            Triple(
                findViewById<LinearLayout>(R.id.cardAuto),
                findViewById<TextView>(R.id.txtAuto),
                selectedOrientation == OrientationMode.AUTO,
            ),
        )
        for ((card, txt, active) in orientCards) {
            txt.setTextColor(if (active) highlight else normal)
            card.alpha = if (active) 1f else 0.6f
        }

        // —— 语言：en/de/zh ——
        for ((cardId, code) in listOf(
                R.id.cardLangEn to "en",
                R.id.cardLangDe to "de",
                R.id.cardLangZh to "zh",
            )) {
            val card = findViewById<LinearLayout>(cardId)
            val txt = card.getChildAt(0) as TextView
            val active = selectedLang == code
            txt.setTextColor(if (active) highlight else normal)
            card.alpha = if (active) 1f else 0.6f
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统「所有文件访问」页返回时，有些 ROM 不回调 onActivityResult；
        // 这里兜底：仍待授权就从 onResume 继续收尾（不阻塞，仅提示）。
        if (pendingAllFiles) {
            pendingAllFiles = false
            val granted = Build.VERSION.SDK_INT < 30 ||
                runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
            if (!granted) Toast.makeText(this, R.string.ob_all_files_not_granted, Toast.LENGTH_LONG).show()
            proceedAfterPermissions()
        }
    }

    /** 用户点「开始使用」：Root 需双警告，其余直接落库并进主界面。
     *  关键：即使 Root 双警告被取消，也照常 markOnboarded 并进主界面——
     *  避免用户陷入「取消→永远停在引导」的死循环（此前每次进应用都弹的根因）。 */
    private fun finishOnboarding() {
        // 先确保文件管理权限：Android 11+ 是「所有文件访问」，Android 10 及以下是
        // READ/WRITE_EXTERNAL_STORAGE 运行时权限。用户拒绝也不阻塞引导（可稍后在设置页开启）。
        if (Build.VERSION.SDK_INT >= 30) {
            val granted = runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
            if (!granted && !allFilesAsked) {
                allFilesAsked = true
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ob_all_files_title))
                    .setMessage(getString(R.string.ob_all_files_message))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.ob_all_files_allow)) { _, _ ->
                        pendingAllFiles = true
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                        runCatching { startActivityForResult(intent, REQ_ALL_FILES) }
                            .onFailure { pendingAllFiles = false; proceedAfterPermissions() }
                    }
                    .setNegativeButton(getString(R.string.ob_all_files_skip)) { _, _ ->
                        proceedAfterPermissions()
                    }
                    .show()
                return
            }
        } else if (!hasLegacyStorage()) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                REQ_ALL_FILES,
            )
            return
        }
        proceedAfterPermissions()
    }

    private fun hasLegacyStorage(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ALL_FILES) {
            pendingAllFiles = false
            val granted = Build.VERSION.SDK_INT < 30 ||
                runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
            if (!granted) Toast.makeText(this, R.string.ob_all_files_not_granted, Toast.LENGTH_LONG).show()
            proceedAfterPermissions()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ALL_FILES) proceedAfterPermissions()
    }

    /** 权限询问结束后继续原来的引导收尾（Root 警告 → commit）。 */
    private fun proceedAfterPermissions() {
        if (selectedPriv == PrivMode.ROOT) {
            warnRoot {
                warnRootAgain {
                    commit()
                }
            }
        } else {
            commit()
        }
    }

    private fun warnRoot(next: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn_title))
            .setMessage(getString(R.string.ob_priv_root_warn))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ ->
                // 取消 = 以普通模式继续并进入主界面（不落 Root），同样完成引导不卡死
                selectedPriv = PrivMode.NORMAL
                commit()
            }
            .show()
    }

    private fun warnRootAgain(next: () -> Unit) {
        // 使用完整对话框 + 圆角样式
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ob_priv_root_warn2_title))
            .setMessage(getString(R.string.ob_priv_root_warn2))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ob_dialog_continue)) { _, _ -> next() }
            .setNegativeButton(getString(R.string.ob_dialog_cancel)) { _, _ ->
                // 第二次取消也回落普通并完成引导
                selectedPriv = PrivMode.NORMAL
                commit()
            }
        builder.create().apply {
            setOnShowListener {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
        }.show()
    }

    private fun commit() {
        // 落库权限模式
        Privilege.setMode(this, selectedPriv)
        Privilege.markOnboarded(this)

        // 落库语言偏好（进主界面后按新语言显示）
        LocaleHelper.set(this, selectedLang)

        // 落库横竖屏偏好（v1.2.120：三态 portrait/landscape/auto）。朝向由 MainActivity
        // 在 onCreate 里 applyOrientation() 统一应用。
        // ⚠️ 这里【不能】设 requestedOrientation：本 Activity 紧接着 finish()，
        // 方向请求是异步派发给窗口的，会落在一个正在销毁的窗口上而被丢弃；
        // 而且旧实现只处理了横屏分支，选「竖屏」在这里根本不产生任何效果。
        OrientationPrefs.set(this, selectedOrientation)

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
