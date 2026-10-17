package app.dsh.mobile.linux

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.R
import app.dsh.mobile.term.TerminalActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Linux 环境管理页：安装 / 删除 proot 发行版，并从这里打开它的终端。
 *
 * 为什么按需下载而不是塞进 APK：
 *  - 发行版 rootfs 3MB（Alpine）～ 30MB（Ubuntu Base），塞进去会把 APK 再撑大一圈；
 *  - 更重要的是**许可证**：proot 是 GPL-2.0，Termux 的 .deb 也是各自许可证 ——
 *    运行时下载（用户自己取）与随包分发（我们再分发）是两种不同的合规负担。
 *    项目对 runtime.zip 也是「CI 收集、不入库」的同一逻辑。
 *  - 下载源已做国内镜像优先（阿里云），失败再退官方 CDN。
 *
 * 交互约定：
 *  - 安装是**长任务**（下载 + 解压），跑在 IO 线程，进度回灌到这张卡片；
 *  - 每张卡片独立状态机，互不阻塞（可以同时装 Alpine 与 Ubuntu）；
 *  - 安装完成后卡片换成「打开终端 / 删除」。
 */
class LinuxEnvActivity : Activity() {

    private lateinit var list: LinearLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** distro id → 卡片视图集合（刷新用） */
    private val cards = mutableMapOf<String, Card>()

    private class Card(
        val root: LinearLayout,
        val status: TextView,
        val progress: ProgressBar,
        val btnPrimary: Button,
        val btnSecondary: Button,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_linux_env)
        list = findViewById(R.id.linuxList)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvLinuxSubtitle).text =
            getString(R.string.linux_subtitle, LinuxEnv.home(this).absolutePath)
        buildCards()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // 从终端返回时状态可能已变（例如 agent 通过 gate 装了新发行版）
        refreshAll()
    }

    // ── 卡片构造 ───────────────────────────────────────────────────────────

    private fun buildCards() {
        list.removeAllViews()
        cards.clear()
        LinuxEnv.distros().forEach { d -> list.addView(makeCard(d)) }
        list.addView(makeFootnote())
    }

    private fun makeCard(d: LinuxDistro): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF161C23.toInt(), 14)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.bottomMargin = dp(10)
        root.layoutParams = lp

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "${d.name} ${d.version}"
            setTextColor(0xFFE8ECF2.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badge = TextView(this).apply {
            text = getString(R.string.linux_size_mb, d.sizeMb)
            setTextColor(0xFF8B98A8.toInt())
            textSize = 11f
        }
        head.addView(title)
        head.addView(badge)
        root.addView(head)

        val desc = TextView(this).apply {
            text = getString(R.string.linux_distro_desc, d.pkgManager, d.shell)
            setTextColor(0xFF9AA5B4.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(desc)

        val status = TextView(this).apply {
            setTextColor(0xFF9AA5B4.toInt())
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(status)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(progress)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnPrimary = Button(this).apply {
            text = getString(R.string.linux_install)
            textSize = 13f
            setOnClickListener { install(d) }
        }
        val btnSecondary = Button(this).apply {
            text = getString(R.string.linux_remove)
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { confirmRemove(d) }
        }
        val btnOpen = Button(this).apply {
            text = getString(R.string.linux_open_terminal)
            textSize = 13f
            visibility = View.GONE
            setOnClickListener {
                startActivity(TerminalActivity.intent(this@LinuxEnvActivity, d.id))
            }
        }
        actions.addView(btnPrimary)
        actions.addView(btnOpen)
        actions.addView(btnSecondary)
        root.addView(actions)

        // btnSecondary 之外还需要一个「打开终端」按钮的状态位，挂到 tag 上省一个类
        root.tag = Triple(btnOpen, btnPrimary, btnSecondary)

        cards[d.id] = Card(root, status, progress, btnPrimary, btnSecondary)
        // 记住 open 按钮（Card 只有三个按钮位，用 map 额外存）
        openButtons[d.id] = btnOpen
        return root
    }

    private val openButtons = mutableMapOf<String, Button>()

    private fun makeFootnote(): View = TextView(this).apply {
        text = getString(R.string.linux_footnote)
        setTextColor(0xFF6F7B8A.toInt())
        textSize = 11f
        setPadding(dp(4), dp(8), dp(4), dp(16))
    }

    // ── 状态刷新 ───────────────────────────────────────────────────────────

    private fun refreshAll() {
        val prootReady = LinuxEnv.prootReady(this)
        LinuxEnv.distros().forEach { d ->
            val c = cards[d.id] ?: return@forEach
            val installed = LinuxEnv.isInstalled(this, d.id)
            val note = d.abiNote[LinuxDistros.deviceAbi()]
            if (installed) {
                // 已安装也保留 ABI 限制提示：x86_64 上 Alpine 装了也用不了（见 ALPINE.abiNote），
                // 只在未安装时提示等于「装完就不警告」，反而误导。
                val base = getString(R.string.linux_installed, LinuxEnv.rootfs(this, d.id).absolutePath)
                c.status.text = if (note.isNullOrEmpty()) base else base + "\n" + note
                c.status.setTextColor(
                    if (note.isNullOrEmpty()) 0xFF8FD9A8.toInt() else 0xFFFFB74D.toInt(),
                )
                c.btnPrimary.visibility = View.GONE
                c.btnSecondary.visibility = View.VISIBLE
                openButtons[d.id]?.visibility = View.VISIBLE
                c.progress.visibility = View.GONE
            } else {
                c.status.text = when {
                    !note.isNullOrEmpty() -> note
                    prootReady -> getString(R.string.linux_not_installed)
                    else -> getString(R.string.linux_proot_missing)
                }
                c.status.setTextColor(
                    if (!note.isNullOrEmpty()) 0xFFFFB74D.toInt() else 0xFF9AA5B4.toInt(),
                )
                c.btnPrimary.visibility = View.VISIBLE
                c.btnPrimary.text = getString(R.string.linux_install)
                c.btnSecondary.visibility = View.GONE
                openButtons[d.id]?.visibility = View.GONE
                c.progress.visibility = View.GONE
            }
        }
    }

    // ── 安装 / 删除 ────────────────────────────────────────────────────────

    private fun install(d: LinuxDistro) {
        val c = cards[d.id] ?: return
        c.btnPrimary.isEnabled = false
        c.progress.visibility = View.VISIBLE
        c.progress.progress = 0
        c.status.setTextColor(0xFF9AA5B4.toInt())
        c.status.text = getString(R.string.linux_preparing)
        scope.launch {
            val ok = runCatching {
                LinuxEnv.install(this@LinuxEnvActivity, d.id) { p, msg ->
                    runOnUiThread {
                        c.progress.progress = (p.coerceIn(0f, 1f) * 100).toInt()
                        c.status.text = msg
                    }
                }
            }.getOrElse { e ->
                withContext(Dispatchers.Main) {
                    c.status.setTextColor(0xFFFF8A8A.toInt())
                    c.status.text = getString(R.string.linux_install_failed, e.message ?: "error")
                    c.progress.visibility = View.GONE
                    c.btnPrimary.isEnabled = true
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                c.btnPrimary.isEnabled = true
                if (ok) {
                    refreshAll()
                    Toast.makeText(this@LinuxEnvActivity, getString(R.string.linux_install_ok, d.name), Toast.LENGTH_SHORT).show()
                } else {
                    c.status.setTextColor(0xFFFF8A8A.toInt())
                    c.status.text = getString(R.string.linux_install_failed, "")
                    c.progress.visibility = View.GONE
                }
            }
        }
    }

    private fun confirmRemove(d: LinuxDistro) {
        AlertDialog.Builder(this)
            .setTitle(R.string.linux_remove)
            .setMessage(getString(R.string.linux_remove_confirm, d.name))
            .setPositiveButton(R.string.linux_remove) { _, _ ->
                scope.launch {
                    val err = runCatching { LinuxEnv.remove(this@LinuxEnvActivity, d.id); null }
                        .getOrElse { it.message ?: "error" }
                    withContext(Dispatchers.Main) {
                        if (err != null) {
                            Toast.makeText(this@LinuxEnvActivity, err, Toast.LENGTH_LONG).show()
                        }
                        refreshAll()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }
}
