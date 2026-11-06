package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动期间磨砂让路的判断逻辑。
 *
 * 只测纯状态迁移：Compose 层的挂载验证在真机上看帧时间，不在单测里模拟。
 */
class ScrollingFrostStateTest {

    @Test
    fun startsIdle() {
        assertFalse(ScrollingFrostState().isScrolling)
    }

    @Test
    fun reportsScrollingStartAndStop() {
        val state = ScrollingFrostState()
        state.attach()
        state.update(true)
        assertTrue(state.isScrolling)
        state.update(false)
        assertFalse(state.isScrolling)
    }

    @Test
    fun repeatedSameValueKeepsStateStable() {
        val state = ScrollingFrostState()
        state.attach()
        state.update(false)
        assertFalse(state.isScrolling)
        state.update(true)
        state.update(true)
        assertTrue(state.isScrolling)
    }

    /** 页面切走时列表被回收：即使没等到 false，也必须恢复磨砂。 */
    @Test
    fun detachRestoresFrostEvenIfScrollingWasReported() {
        val state = ScrollingFrostState()
        state.attach()
        state.update(true)
        state.detach()
        assertFalse(state.isScrolling)
    }

    /** 两个列表同时存在时，先退出的那个不能把仍在滚动的状态清掉。 */
    @Test
    fun remainingReporterKeepsScrollingState() {
        val state = ScrollingFrostState()
        state.attach()
        state.attach()
        state.update(true)
        state.detach()
        assertTrue(state.isScrolling)
        state.detach()
        assertFalse(state.isScrolling)
    }

    /** 未 attach 就 detach 不能把计数搞成负数，否则之后永远恢复不了。 */
    @Test
    fun detachWithoutAttachKeepsCountingCorrectly() {
        val state = ScrollingFrostState()
        state.detach()
        state.attach()
        state.update(true)
        state.detach()
        assertFalse(state.isScrolling)
    }

    /** 页面切换过渡独立驱动让路：与列表滚动无关，过渡结束才恢复。 */
    @Test
    fun routeTransitionPausesFrostIndependently() {
        val state = ScrollingFrostState()
        state.setRouteTransition(true)
        assertTrue(state.isScrolling)
        state.setRouteTransition(false)
        assertFalse(state.isScrolling)
    }

    /** 过渡进行中旧列表被回收，不能把过渡的让路一起清掉。 */
    @Test
    fun detachKeepsRouteTransitionHold() {
        val state = ScrollingFrostState()
        state.attach()
        state.setRouteTransition(true)
        state.detach()
        assertTrue(state.isScrolling)
        state.setRouteTransition(false)
        assertFalse(state.isScrolling)
    }

    /** 滚动停下但过渡还没结束：继续让路。 */
    @Test
    fun scrollingStopKeepsHoldWhileTransitionActive() {
        val state = ScrollingFrostState()
        state.attach()
        state.setRouteTransition(true)
        state.update(true)
        state.update(false)
        assertTrue(state.isScrolling)
        state.setRouteTransition(false)
        assertFalse(state.isScrolling)
    }

    /** 流式输出独立驱动让路：与滚动无关，输出结束才恢复。 */
    @Test
    fun streamingPausesFrostIndependently() {
        val state = ScrollingFrostState()
        state.setStreaming(true)
        assertTrue(state.isScrolling)
        state.setStreaming(false)
        assertFalse(state.isScrolling)
    }

    /** 流式与滚动任一在动都让路；两者都停才恢复。 */
    @Test
    fun streamingAndScrollingCompose() {
        val state = ScrollingFrostState()
        state.attach()
        state.update(true)
        state.setStreaming(true)
        state.update(false)
        assertTrue(state.isScrolling)
        state.setStreaming(false)
        assertFalse(state.isScrolling)
    }

    /** 列表 detach 只清滚动来源，不能连带清掉仍在进行的流式让路。 */
    @Test
    fun detachKeepsStreamingHold() {
        val state = ScrollingFrostState()
        state.attach()
        state.setStreaming(true)
        state.detach()
        assertTrue(state.isScrolling)
        state.setStreaming(false)
        assertFalse(state.isScrolling)
    }
}
