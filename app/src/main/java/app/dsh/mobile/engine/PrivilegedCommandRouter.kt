package app.dsh.mobile.engine

/** 后端不可跨模式回退，设备具备 Root 不代表用户授权当前任务使用它。 */
internal object PrivilegedCommandRouter {
    fun run(mode: PrivMode, shizuku: () -> String?, root: () -> String?): String? = when (mode) {
        PrivMode.NORMAL -> null
        PrivMode.SHIZUKU -> shizuku()
        PrivMode.ROOT -> root()
    }
}
