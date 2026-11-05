package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁住「跨屏拼接时坐标归一化」的行为。
 *
 * 这段逻辑的边界很窄，写宽松了会误伤悬浮窗，写严格了又治不好多屏偏移，
 * 所以每个分支都配一条用例（2026-11-05 实测事故：虚拟屏 id=4 时整批坐标 +1240）。
 */
class AgentCoordinateOriginTest {

    private val screenWidth = 1240
    private val screenHeight = 2772

    @Test
    fun `单屏全屏应用不偏移`() {
        assertEquals(
            0 to 0,
            AgentCoordinateOrigin.resolve(0, 0, screenWidth, screenHeight),
        )
    }

    @Test
    fun `被排到右侧的 display 减掉屏宽`() {
        // 实测场景：root 落在 (1240,0)，节点坐标整体 +1240，需要减回去。
        assertEquals(
            1240 to 0,
            AgentCoordinateOrigin.resolve(1240, 0, screenWidth, screenHeight),
        )
    }

    @Test
    fun `悬浮窗与分屏不偏移`() {
        // root 在屏幕内部的任意位置都必须保持 0：否则坐标会变成「相对窗口」，点击就偏了。
        assertEquals(0 to 0, AgentCoordinateOrigin.resolve(100, 200, screenWidth, screenHeight))
        assertEquals(0 to 0, AgentCoordinateOrigin.resolve(screenWidth - 1, 0, screenWidth, screenHeight))
        assertEquals(0 to 0, AgentCoordinateOrigin.resolve(0, screenHeight - 1, screenWidth, screenHeight))
    }

    @Test
    fun `被排到下方的 display 减掉屏高`() {
        assertEquals(
            0 to 2772,
            AgentCoordinateOrigin.resolve(0, 2772, screenWidth, screenHeight),
        )
    }

    @Test
    fun `拿不到屏幕尺寸时不偏移`() {
        // 宁可不修，也不能凭空猜一个 origin 把坐标算歪。
        assertEquals(0 to 0, AgentCoordinateOrigin.resolve(1240, 0, null, null))
        assertEquals(0 to 0, AgentCoordinateOrigin.resolve(1240, 0, screenWidth, null))
        assertEquals(0 to 0, AgentCoordinateOrigin.resolve(1240, 0, 0, 0))
    }

    @Test
    fun `多屏串接时按实际起点归一化`() {
        // 第三块屏（x 起点 2480）也应减掉自己的起点，而不是固定减一个屏宽。
        assertEquals(
            2480 to 0,
            AgentCoordinateOrigin.resolve(2480, 0, screenWidth, screenHeight),
        )
    }
}
