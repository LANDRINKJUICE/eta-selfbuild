package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 滚动期间的实时磨砂让路。
 *
 * 实时磨砂每帧要做两件昂贵的事：把内容录进离屏层（约等于整屏再渲染一遍），
 * 再用多级降采样加升采样把结果模糊一遍。静止时这两步每帧只做一次，但列表
 * 滚动时内容每帧都在变，两步就变成每帧各一次，是落帧的主要来源。
 *
 * 滚动期间把磨砂换成主题不透明底色，手指离开、惯性停下后自动恢复。
 */
@Stable
internal class ScrollingFrostState {
    private var reporters by mutableIntStateOf(0)

    var isScrolling by mutableStateOf(false)
        private set

    /** 引用计数：多个列表同时存在时，由最后离开的列表决定是否恢复磨砂。 */
    fun attach() {
        reporters += 1
    }

    fun detach() {
        reporters -= 1
        if (reporters <= 0) {
            reporters = 0
            isScrolling = false
        }
    }

    fun update(scrolling: Boolean) {
        if (isScrolling != scrolling) isScrolling = scrolling
    }
}

internal val LocalScrollingFrost = staticCompositionLocalOf { ScrollingFrostState() }

/** 宿主创建并下发磨砂状态，子页面用 [ReportScrollingFrost] 上报滚动。 */
@Composable
internal fun ProvideScrollingFrostState(content: @Composable () -> Unit) {
    val state = remember { ScrollingFrostState() }
    CompositionLocalProvider(LocalScrollingFrost provides state, content = content)
}

/**
 * 把一个列表的滚动状态上报给磨砂层。
 *
 * 磨砂是纯视觉优化，上报失败最多退回「滚动时也磨砂」，不影响功能，
 * 所以这里不抛异常也不做重试。
 */
@Composable
internal fun ReportScrollingFrost(scrollState: LazyListState) {
    val frost = LocalScrollingFrost.current
    // 先挂引用再订阅、先退订再解引用：Collector 可能在 DisposableEffect 之前就启动，
    // 顺序反了会出现「还没 attach 就 detach」的负数计数。
    DisposableEffect(frost) {
        frost.attach()
        onDispose { frost.detach() }
    }
    LaunchedEffect(frost, scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { frost.update(it) }
    }
}

/** 滚动中返回 null，调用方据此退回不透明底色。 */
@Stable
@Composable
internal fun scrollingFrostBackdrop(backdrop: LayerBackdrop?): LayerBackdrop? {
    if (backdrop == null) return null
    return if (LocalScrollingFrost.current.isScrolling) null else backdrop
}

/**
 * 给内容挂上磨砂采样层。滚动中不挂，省掉每帧的离屏录制；
 * 状态读取收在这个包装层里，避免因为一次开关把整个页面拖进重组。
 */
@Composable
internal fun FrostCaptureBox(
    capture: Boolean,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val active = if (capture) scrollingFrostBackdrop(backdrop) else null
    Box(modifier = modifier.then(if (active != null) Modifier.layerBackdrop(active) else Modifier)) {
        content()
    }
}

/** 顶栏磨砂：滚动中退回不透明底色，颜色与磨砂开启时保持一致。 */
@Composable
internal fun ScrolledTopBarBackdrop(
    backdrop: LayerBackdrop?,
    content: @Composable (Color) -> Unit,
) {
    val active = scrollingFrostBackdrop(backdrop)
    TopBarBackdrop(active) { content(topBarContainerColor(active)) }
}
