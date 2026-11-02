package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidOverlayTest {

    private val doc = AndroidOverlay.overlayDocument()

    private val requiredPlugins = listOf(
        "mobile-ui", "mobile-progress", "android-fast-mode",
        "android-priv-mode", "android-bridge-token", "android-plugin-health",
    )

    @Test
    fun `必需插件都已注册`() {
        requiredPlugins.forEach { id ->
            assertTrue("overlay 缺少插件: $id", doc.contains("- id: $id"))
        }
    }

    @Test
    fun `插件 id 不重复`() {
        val ids = Regex("""- id: ([\w-]+)""").findAll(doc).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `每个 insert 块至少注册一个插件`() {
        val blocks = doc.split("- insert:").drop(1)
        assertTrue("overlay 里没有 insert 块", blocks.isNotEmpty())
        blocks.forEach { block ->
            assertTrue("insert 块缺少 name 行: ${block.take(40)}", block.contains("name: "))
        }
    }

    @Test
    fun `关键行必须保留`() {
        assertTrue(doc.contains("port: !!js ctx.webStartup.port ?? 3080"))
        assertTrue(doc.contains("- id: web"))
        assertTrue(doc.contains("name: '@deepseek-ai/dsh-web'"))
    }
}
