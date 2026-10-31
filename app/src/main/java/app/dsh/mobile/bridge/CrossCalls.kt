package app.dsh.mobile.bridge

import android.content.Context
import app.dsh.mobile.engine.AgentBridge

/**
 * 跨组调用的 AgentBridge 扩展别名。
 *
 * handler 之间的相互调用（例如 Action 里 `svc ?: return a11yGone()`）仍写裸名字，
 * 由本文件把裸调用转给真正的持有者。这样被移动的函数体不用做任何改名。
 */
internal fun AgentBridge.a11yGone(): Pair<Int, String> = screen.a11yGone()

internal fun AgentBridge.annotateTapPoints(r: Pair<Int, String>): Pair<Int, String> =
    screen.annotateTapPoints(r)

internal fun AgentBridge.mergeSceneIcons(ctx: Context, r: Pair<Int, String>): Pair<Int, String> =
    screen.mergeSceneIcons(ctx, r)

internal fun AgentBridge.captureStepShot(): String? = screen.captureStepShot()

internal fun <T> AgentBridge.withScreenTimeout(what: String, block: () -> T): Result<T>? =
    screen.withScreenTimeout(what, block = block)

internal fun <T> AgentBridge.withScreenTimeout(what: String, timeoutMs: Long, block: () -> T): Result<T>? =
    screen.withScreenTimeout(what, timeoutMs, block)

internal fun AgentBridge.noteOpen(ctx: Context, target: String) = action.noteOpen(ctx, target)

internal fun AgentBridge.actionWordsFromAssets(ctx: Context): List<String> =
    appHandler.actionWordsFromAssets(ctx)
