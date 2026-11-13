package app.dsh.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 页面状态检测器（v1.2.144）—— 「页面加载好了没」的判断中枢。
 *
 * ## 为什么不能只看"像素变没变"（用户实测提出的两个难题）
 * 1. **持续动态内容**：Banner 广告/轮播/计时器会让画面**永远在变** →
 *    帧差判据永远"不稳定" → 老实等满预算（旧 settle 的 1.2s 白等）。
 * 2. **慢加载**：有的 App 进页面先卡 4-5 秒加载页，再出正式页 →
 *    固定 1.2s 预算会在**加载页上就执行动作** → 必然失败。
 *
 * ## 判据：结构签名（而非像素）
 * 页面"加载完成"的本质是**结构就位**——节点数量、控件角色分布、
 * 空间布局稳定。内容变化（广告文字轮换、点赞数跳动）**不改结构**。
 * 所以签名 = `节点数 + 每节点的(角色, 4x4 网格格位)`，**刻意不含文本内容**。
 *
 * ## 三态判定
 * - [Phase.LOADING]  有 ProgressBar 或明确的加载提示 → **继续等**（到
 *   maxLoadingMs 为止，覆盖慢加载；期间不判定就绪）
 * - [Phase.READY]    结构签名连续 [stableReads] 次一致 → **立即交还控制权**
 * - [Phase.TIMEOUT]  超过总预算仍在变（罕见）→ 放行，由动作后的 verify 兜底
 *
 * 纯本地、零模型：一次签名遍历是 IPC 级读树（与 dump 同源），毫秒级。
 */
object PageStateDetector {

    private const val TAG = "PageState"

    enum class Phase { LOADING, READY, TIMEOUT }

    data class State(
        val phase: Phase,
        /** 结构签名（诊断/日志用） */
        val sig: String,
        val polls: Int,
        val loadingSeen: Boolean,
        val costMs: Long,
    )

    /** 判定"在加载"的兜底文本（多语言；命中即视为加载中） */
    private val LOADING_HINTS = listOf(
        "加载中", "正在加载", "载入中", "正在载入", "请稍候", "请稍等",
        "Loading", "loading…", "Please wait",
    )

    /**
     * 结构签名：节点数 + **角色直方图**（如 `23:C8T10S2E1`）。
     *
     * ⚠️ v1.2.145 实测修正：初版用「角色+4x4 格位」序列，但 WebView（Chrome）
     * 渲染时节点**位置与遍历顺序会持续抖动** → 签名永不收敛 → 实测在有轮播的
     * 页面上 settle 白等 4.4 秒。改为角色计数（不含位置/顺序/文本）后：
     *  · 内容轮换（广告文字、计数跳动）不改计数 → 稳定 ✓
     *  · 渲染抖动（位置/顺序）不改计数 → 稳定 ✓
     *  · 页面加载完成 = 节点规模定型 → 计数稳定 ✓
     * 代价：不同页面可能有同签名——但本签名只用于判「当前页稳没稳」，
     * 不用于识别页面，故无影响。
     */
    fun structureSignature(root: AccessibilityNodeInfo?, screenH: Int): String {
        if (root == null) return "empty"
        var n = 0
        val counts = HashMap<Char, Int>(8)
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || n >= 200) return
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 0 && r.height() > 0) {
                val role = when {
                    node.isCheckable -> 'S'
                    node.isEditable -> 'E'
                    node.isClickable -> 'C'
                    node.isScrollable -> 'L'
                    else -> 'T'
                }
                counts[role] = (counts[role] ?: 0) + 1
                n++
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return "$n:" + counts.entries.sortedBy { it.key }.joinToString("") { "${it.key}${it.value}" }
    }

    /**
     * 是否"没有可操作的内容"（加载中/空页面）。
     * @param screenH 屏幕高度（>0 时启用内容区判据——Chrome 等浏览器打开时
     *   顶部 UI 壳有 8+ 个可交互节点但不代表内容就位：用户实测浏览器
     *   1-2 秒就能输 URL，内容要 6-7 秒）
     */
    /**
     * 加载信号强度（v1.2.152 审查修复引入）。
     *
     * 分档的原因：**只有强信号才值得延长等待**。`total < 5`（近乎空树）是个弱
     * 判据——空状态页/纯 Canvas 页/稀疏对话框本来节点就少，把它当成"正在加载"
     * 会白等到 maxLoadingMs；而"进度条在转 / 写着加载中"是应用**自述**正在加载，
     * 那才是值得等的（用户实测：有些应用进页面会卡四五秒加载页）。
     */
    enum class LoadSignal { NONE, WEAK, STRONG }

    fun loadSignal(root: AccessibilityNodeInfo?, screenH: Int = 0): LoadSignal {
        if (root == null) return LoadSignal.WEAK
        var total = 0
        var progress = false
        var hint = false
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || progress && hint) return
            val cls = node.className?.toString() ?: ""
            if (cls.contains("ProgressBar")) progress = true
            val t = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (t.isNotEmpty() && LOADING_HINTS.any { h -> t.contains(h, true) }) hint = true
            total++
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        // **只认明确信号**。上一版"内容区节点 < 3"判据实测误判风险高
        // （对话框/空状态页内容本来就少 → 被判加载中 → settle 白等），已移除。
        return when {
            progress || hint -> LoadSignal.STRONG
            total < 5 -> LoadSignal.WEAK
            else -> LoadSignal.NONE
        }
    }

    fun isLoading(root: AccessibilityNodeInfo?, screenH: Int = 0): Boolean =
        loadSignal(root, screenH) != LoadSignal.NONE

    /**
     * 等页面就绪（阻塞；请在 IO 线程调用）。
     * @param budgetMs 总预算（含加载等待）
     * @param maxLoadingMs 连续判定"加载中"时的最长等待（覆盖慢加载；到时放行）
     * @param stableReads 结构签名连续一致次数（2 = 约 300ms 窗口）
     */
    fun waitReady(
        svc: AccessibilityService,
        budgetMs: Long = 6000L,
        maxLoadingMs: Long = 8000L,
        stableReads: Int = 2,
    ): State {
        // v1.2.152 审查修复：① 时钟换单调源（System.currentTimeMillis 会被 NTP/
        // 用户改时间拉走，等待窗口随之漂移）；② 原先 `now < deadline && now <
        // loadingDeadline` 的 AND 让 maxLoadingMs **永远不生效**——默认
        // maxLoadingMs(8s) > budgetMs，预算一到就退，慢加载页根本等不到 8s。
        // 现在：常规受 budgetMs 约束；**只有确实观察到加载态**才把窗口延到
        // maxLoadingMs（这正是"慢加载页卡四五秒"要覆盖的场景）。
        fun nowMs() = System.nanoTime() / 1_000_000
        val t0 = nowMs()
        val screenH = svc.resources.displayMetrics.heightPixels
        var last = ""
        var same = 0
        var polls = 0
        var loadingSeen = false
        val loadingDeadline = t0 + maxLoadingMs
        val deadline = t0 + budgetMs
        // ⚠️ 延长等待的条件必须是**强信号**（进度条/加载文案）——弱信号（空树）
        // 只能走常规预算，否则稀疏但已就绪的页面会把每次 settle 都拖到 maxLoadingMs。
        var strongLoadingSeen = false
        while (nowMs() < if (strongLoadingSeen) maxOf(deadline, loadingDeadline) else deadline) {
            polls++
            val root = svc.rootInActiveWindow
            if (root == null) { Thread.sleep(150); continue }
            val sig2 = loadSignal(root, screenH)
            if (sig2 != LoadSignal.NONE) {
                loadingSeen = true
                if (sig2 == LoadSignal.STRONG) strongLoadingSeen = true
                same = 0
                Thread.sleep(200)
                continue
            }
            val sig = structureSignature(root, screenH)
            if (sig.contains(":") && sig != "0:") {
                if (sig == last) {
                    same++
                    if (same >= stableReads) {
                        return State(Phase.READY, sig, polls, loadingSeen, nowMs() - t0)
                    }
                } else {
                    same = 0
                    last = sig
                }
            }
            Thread.sleep(150)
        }
        val cost = nowMs() - t0
        return State(Phase.TIMEOUT, last, polls, loadingSeen, cost)
    }
}
