package app.dsh.mobile

import org.junit.Assert.*
import org.junit.Test

class OverlayAvoidanceTest {
    @Test fun `手势结束不能提前恢复仍在截图的窗口`() {
        val state = OverlayAvoidance()
        val capture = state.begin("capture")
        val gesture = state.begin("ops")
        assertTrue(state.end(gesture))
        assertTrue(state.hidden)
        assertEquals(1, state.count)
        assertTrue(state.end(capture))
        assertFalse(state.hidden)
    }

    @Test fun `重复回调不能结束另一个手势`() {
        val state = OverlayAvoidance()
        val first = state.begin("ops")
        val second = state.begin("ops")
        assertTrue(state.end(first))
        assertFalse(state.end(first))
        assertTrue(state.hidden)
        assertTrue(state.end(second))
        assertFalse(state.hidden)
    }

    @Test fun `退出后迟到的旧回调不能影响重开后的避让`() {
        val state = OverlayAvoidance()
        val previous = state.begin("ops")
        state.clear()
        val current = state.begin("capture")
        assertFalse(state.end(previous))
        assertTrue(state.hidden)
        assertTrue(state.end(current))
        assertFalse(state.hidden)
    }
}
