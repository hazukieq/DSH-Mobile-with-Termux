package app.dsh.mobile.engine

import org.junit.Assert.*
import org.junit.Test

class PrivilegedCommandRouterTest {
    @Test fun `普通模式即使存在提权后端也不调用`() {
        assertNull(PrivilegedCommandRouter.run(PrivMode.NORMAL,
            shizuku = { error("must not call Shizuku") }, root = { error("must not call Root") }))
    }

    @Test fun `Shizuku 失败不能跨模式使用 Root`() {
        assertNull(PrivilegedCommandRouter.run(PrivMode.SHIZUKU,
            shizuku = { null }, root = { error("must not fall back to Root") }))
    }

    @Test fun `显式 Root 模式只调用 Root 后端`() {
        assertEquals("root result", PrivilegedCommandRouter.run(PrivMode.ROOT,
            shizuku = { error("wrong backend") }, root = { "root result" }))
    }
}
