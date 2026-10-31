package app.dsh.mobile.bridge

import android.util.Log

/**
 * 无障碍门禁分类表（v2.5.3 事故修复，见 docs/A11Y-SCOPE.md）。
 *
 * - ACTING_PATHS：仅用于「AI 正在操作」通知计数，**不是**无障碍门禁。
 * - A11Y_DEPENDENT_REASONS：真正调用 DshAccessibilityService 的端点及原因（唯一真源）。
 * - A11Y_DEPENDENT_PATHS：由上面 map 的 keys 派生，门禁只查它。
 * - A11Y_INDEPENDENT_ACTIONS：反例集，只用于启动自检。
 */
internal val ACTING_PATHS = setOf(
    "/tap", "/tap-id", "/gesture", "/key", "/input",
    "/scroll-find", "/scroll-center", "/scroll-edge",
    "/launch", "/launch-intent", "/open", "/vision/tap", "/vision/drag",
    "/loop/run",
)

/**
 * 真正依赖无障碍服务的端点 → 503 时给用户/Agent 的「为什么需要」。
 * 分类规则与维护流程见 docs/A11Y-SCOPE.md。
 *
 * ## 不变式（违反任一条就是 bug；v2.5.2 事故正是破坏第 1 条）
 * 1. 集合里每个端点的 handler 在服务不可用时**必然**做不了主功能：它要么读节点树、
 *    要么注入手势/按键/文本、要么用无障碍截图 API、要么读无障碍事件缓存。
 *    因此路由层统一前置门禁不会误伤。
 *    **禁止**把「只派发 Intent / startActivity / 自家窗口 PixelCopy / 文件读写 /
 *    首选项读写 / 打开系统设置页」的端点加进来 —— 这些跟无障碍无关。
 * 2. 反过来，handler 无条件 `DshAccessibilityService.instance ?: 503` 的端点必须在这里；
 *    漏放会让调用方拿到一句没有原因、没有开启方式的裸 503。
 * 3. 条件依赖端点不进来（它们有不需要无障碍的成功路径，由 handler 自己判定）：
 *    /open（svc 只用于读前台包名，缺了仍 200 + hint）、/wait-app（本包走 Activity 生命周期）、
 *    /ocr（ppocr6 + Shizuku/root 可走特权 screencap；ensure/models/download 分支也不需要）、
 *    /memory/lookup|store（body 带 dump 就不读屏）、/overlay（只是更新悬浮窗）、
 *    /activity（a11y 只影响回执里的 bal_exempt 提示）。
 * 4. 新增路由：先按 docs/A11Y-SCOPE.md 归类，再写 handler；要进门禁必须同时写 reason
 *    （集合由本 map 派生，不写原因就进不来）。[A11Y_INDEPENDENT_ACTIONS] 是反例集，
 *    启动时自检会 Log.e 点名重叠项。
 */
internal val A11Y_DEPENDENT_REASONS: Map<String, String> = mapOf(
    // 读无障碍节点树
    "/screen" to "reads the on-screen node tree",
    "/screen-changed" to "subscribes to accessibility window/content-change events",
    "/scene" to "dumps the node tree and captures the screen for OCR",
    "/diff" to "diffs two accessibility node-tree snapshots",
    "/wait-text" to "polls the node tree for text",
    "/wait" to "polls the node tree until a condition holds",
    "/idle" to "waits for accessibility events to go quiet",
    "/pick" to "ranks on-screen a11y nodes and optionally taps one",
    // 注入手势 / 按键 / 文本
    "/tap" to "finds a node and injects a tap gesture",
    "/tap-id" to "finds a node by id and injects a tap gesture",
    "/gesture" to "injects a multi-point gesture",
    "/key" to "injects a global key event (back/home/recents)",
    "/input" to "focuses an editable node and injects text",
    "/scroll-find" to "scrolls while searching the node tree",
    "/scroll-center" to "scrolls a scrollable node into place",
    "/scroll-edge" to "swipes until the scrollable node hits an edge",
    // 屏幕图像（无障碍截图 API，不是 MediaProjection）
    "/screenshot" to "takes a screen capture through the accessibility service",
    "/calib" to "reads the node tree and captures the screen to pair OCR boxes with a11y bounds",
    "/frame-diff" to "captures screen frames to detect visual change",
    "/frame-wait" to "captures screen frames until they change",
    "/vision/crop" to "captures a screen region for vision analysis",
    "/vision/track" to "captures frames to track a template",
    "/vision/tap" to "captures the screen and injects a tap at the match",
    "/vision/drag" to "captures the screen and injects a drag",
    // 复合动作（内部同样读树/点/截图）
    "/batch" to "runs a11y steps (tree reads, taps, screenshots) in one request",
    "/loop/run" to "runs the local a11y executor loop (pick/act/verify)",
    "/act" to "runs transactional a11y steps (tap/input/key/tree read)",
    // 无障碍事件缓存（触摸干预计数、通知事件）
    "/interference" to "reads the accessibility touch/window-event counters",
    "/notif" to "reads the notification cache fed by accessibility notification events",
)

/** 门禁集合本体由 [A11Y_DEPENDENT_REASONS] 派生：加路径必须同时写原因，避免只加不解释。 */
internal val A11Y_DEPENDENT_PATHS: Set<String> get() = A11Y_DEPENDENT_REASONS.keys

/**
 * 反例集：设备动作但**不需要**无障碍的端点。只用于启动自检，不参与门禁判定。
 * 列的是 v2.5.2 事故端点与同类（Intent / startActivity / 自家窗口截图 / 设置页）。
 */
internal val A11Y_INDEPENDENT_ACTIONS = setOf(
    "/launch", "/launch-intent", "/open", "/open/file", "/open/folder", "/open/share",
    "/activity", "/app-screenshot", "/notify", "/say", "/share/text", "/overlay",
    "/a11y/enable",
)

private const val GATE_TAG = "A11yGate"

/** 启动自检：分类冲突只告警不崩溃（崩溃比误分类更糟）。 */
internal fun a11ySelfCheck() {
        // 分类自检：把「不需要无障碍」的端点塞进 A11Y_DEPENDENT_REASONS 会让
        // 「无障碍一关就连启动 App 都 503」的事故复发。这里只告警不崩溃 —— 崩溃比误分类更糟。
        val overlap = A11Y_DEPENDENT_REASONS.keys intersect A11Y_INDEPENDENT_ACTIONS
        if (overlap.isNotEmpty()) {
            Log.e(GATE_TAG, "a11y path classification conflict (see docs/A11Y-SCOPE.md): $overlap")
        }
}
