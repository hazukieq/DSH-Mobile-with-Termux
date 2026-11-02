package app.dsh.mobile.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLogicTest {

    @Test
    fun `sanitizeName 把斜杠换成下划线`() {
        assertEquals("a_b_c.bin", ShareLogic.sanitizeName("a/b/c.bin", "fallback"))
    }

    @Test
    fun `sanitizeName 处理反斜杠`() {
        assertEquals("dir_file.txt", ShareLogic.sanitizeName("dir\\file.txt", "fallback"))
    }

    @Test
    fun `sanitizeName 空名用兜底`() {
        assertEquals("shared-1", ShareLogic.sanitizeName(null, "shared-1"))
        assertEquals("shared-1", ShareLogic.sanitizeName("   ", "shared-1"))
    }

    @Test
    fun `单文件上限 512MB`() {
        val max = ShareLogic.MAX_SHARE_FILE_BYTES
        assertFalse(ShareLogic.fileDeclaredOverflow(max, 0))
        assertTrue(ShareLogic.fileDeclaredOverflow(max + 1, 0))
    }

    @Test
    fun `声明大小未知时不判超限`() {
        assertFalse(ShareLogic.fileDeclaredOverflow(-1, 0))
    }

    @Test
    fun `合计上限 1GB`() {
        val total = ShareLogic.MAX_SHARE_TOTAL_BYTES - 10
        assertTrue(ShareLogic.fileDeclaredOverflow(11, total))
    }

    @Test
    fun `流式写入超限要及时中断`() {
        assertTrue(ShareLogic.streamOverflow(ShareLogic.MAX_SHARE_FILE_BYTES + 1, 0))
        assertTrue(ShareLogic.streamOverflow(1, ShareLogic.MAX_SHARE_TOTAL_BYTES))
        assertFalse(ShareLogic.streamOverflow(1024, 0))
    }

    @Test
    fun `文件数上限是 20`() {
        assertEquals(20, ShareLogic.MAX_SHARE_FILES)
    }
}
