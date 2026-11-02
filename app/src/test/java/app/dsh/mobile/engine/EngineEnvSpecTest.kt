package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineEnvSpecTest {

    private val root = "/data/user/0/com.termux/files/usr"
    private val home = "/data/user/0/com.termux/files/dsh-home"

    @Test
    fun `PATH 顺序是引擎bin优先，系统最后`() {
        val parts = EngineEnvSpec.pathValue(root, home).split(":")
        assertEquals("$root/bin", parts.first())
        assertEquals("/system/bin", parts[parts.size - 2])
        assertEquals("/system/xbin", parts.last())
    }

    @Test
    fun `PATH 包含 dsh-home 的本地 bin 与 pnpm`() {
        val value = EngineEnvSpec.pathValue(root, home)
        assertTrue(value.contains("$home/local/bin"))
        assertTrue(value.contains("$home/.local/share/pnpm"))
    }

    @Test
    fun `LD_LIBRARY_PATH 指向引擎私有 lib`() {
        assertEquals("$root/lib", EngineEnvSpec.ldValue(root))
    }

    @Test
    fun `COREPACK_HOME 落在 dsh-home`() {
        assertEquals("$home/.cache/corepack", EngineEnvSpec.corepackHome(home))
    }
}
