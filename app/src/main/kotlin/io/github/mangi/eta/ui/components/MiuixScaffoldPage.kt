package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Eta 二级列表页的统一骨架：手机折叠大标题、宽屏固定小标题、顶栏毛玻璃、横屏安全区、
 * 宽屏内容居中、滚动边界触感与越界回弹均由此处统一提供。
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    compactTitle: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    EtaPreferenceTheme {
        val scrollBehavior = MiuixScrollBehavior()
        val backdrop = rememberTopBarBackdrop()
        val topBarColor = topBarContainerColor(backdrop)
        ReportScrollingFrost(listState)

        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopBarBackdrop(backdrop) {
                    // 页面自带主视觉（如角色详情）时用紧凑标题，避免大标题与主视觉重复。
                    if (compactTitle) {
                        SmallTopAppBar(
                            title = title,
                            color = topBarColor,
                            navigationIcon = { MiuixBackButton(onClick = onBack) },
                            actions = actions,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        AdaptiveTopAppBar(
                            title = title,
                            color = topBarColor,
                            navigationIcon = { MiuixBackButton(onClick = onBack) },
                            actions = actions,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { innerPadding ->
            WidePageContent { sidePadding ->
                // 保留 MiuixTheme 注入的默认越界工厂，让短内容页也能回弹到顶栏采样区。
                // 滚动中不录制顶栏采样层：列表每帧都在变，离屏录制与模糊是落帧大头。
                FrostCaptureBox(
                    capture = true,
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalCutoutPadding()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            top = innerPadding.calculateTopPadding(),
                            end = sidePadding,
                        ),
                    ) {
                        content()
                        item(key = "bottom_spacer") {
                            MiuixPageBottomSpacer()
                        }
                    }
                }
            }
            overlay()
        }
    }
}

/**
 * 自定义内容二级页的低层骨架。调用方负责把顶部 padding、横向安全区与 nested scroll
 * 接入自己的内容；[sidePadding] 用于在宽屏限制实际内容宽度，滚动容器本身仍应保持全宽。
 *
 * 建议调用方在自己列表的 [LazyListState] 上调用 [ReportScrollingFrost]，滚动时顶栏磨砂
 * 自动让路。
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 紧凑顶栏：用小型标题栏代替大标题栏。
     *
     * 为什么需要（踩过）：大标题栏即使 `title` 传空串也**照样占着大标题的高度**，
     * 于是页面顶栏下面空出一大块 —— 文件管理器就踩了这个坑（两栏路径行本身已能定位，
     * 不需要大标题）。传 true 后标题区会收成一行小标题的高度。
     */
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    EtaPreferenceTheme {
        val scrollBehavior = MiuixScrollBehavior()
        val backdrop = rememberTopBarBackdrop()
        val topBarColor = topBarContainerColor(backdrop)

        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopBarBackdrop(backdrop) {
                    if (compact) {
                        // 小型标题栏：高度只占一行，标题为空时不会留下大标题的空白。
                        SmallTopAppBar(
                            title = title,
                            color = topBarColor,
                            navigationIcon = { MiuixBackButton(onClick = onBack) },
                            actions = actions,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        AdaptiveTopAppBar(
                            title = title,
                            color = topBarColor,
                            navigationIcon = { MiuixBackButton(onClick = onBack) },
                            actions = actions,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { paddingValues ->
            // 滚动中不录制顶栏采样层，与 MiuixScaffoldPage 对齐：内容每帧都在变，
            // 离屏录制与模糊是落帧大头。由内容内部的列表通过 ReportScrollingFrost 上报。
            FrostCaptureBox(
                capture = true,
                backdrop = backdrop,
                modifier = Modifier.fillMaxSize(),
            ) {
                WidePageContent { sidePadding ->
                    content(paddingValues, scrollBehavior, sidePadding)
                }
            }
        }
    }
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
