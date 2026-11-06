package io.github.mangi.eta.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/**
 * 已完成内容的 Markdown：历史消息、思考全文与流式结束后的静态视图共用。
 *
 * [parsed] 是流式会话的终态快照，可直接复用，切换时不重解析、不经过占位文字。
 * 其余情况在后台解析；命中缓存时首帧即为最终排版，长会话来回滚动不会反复解析。
 */
@Composable
internal fun StaticMarkdown(
    content: String,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    parsed: MarkdownDocument? = null,
) {
    val style = rememberMarkdownStyle(tone)
    val reusable = parsed?.takeIf { it.inlineStyle == style.inline }
    val document = reusable ?: produceState(
        initialValue = staticMarkdownDocumentCache.get(content, style.inline),
        content,
        style.inline,
    ) {
        value = staticMarkdownDocumentCache.get(content, style.inline)
            ?: withContext(Dispatchers.Default) { staticMarkdownDocumentCache.parse(content, style.inline) }
    }.value
    if (document == null) {
        // 解析完成前以同字号原文占位，高度与最终排版接近，避免列表越界绘制。
        Text(text = content, style = style.body, modifier = modifier)
    } else {
        MarkdownContent(document = document, style = style, modifier = modifier)
    }
}

/**
 * 大文档的 Markdown：按块懒加载，用于整份 MEMORY.md 这类可以上百 KB 的正文。
 *
 * [StaticMarkdown] 用 Column 一次铺开全部块，短消息没问题，长文档进入页面时
 * 会把主线程按在组合 + 测量里。这里换成 [MarkdownBlockLazyColumn]，进入成本只和
 * 首屏有关；解析仍然在后台线程，命中缓存时首帧即为最终排版。
 */
@Composable
internal fun LazyStaticMarkdown(
    content: String,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val style = rememberMarkdownStyle(tone)
    val document = produceState(
        initialValue = staticMarkdownDocumentCache.get(content, style.inline),
        content,
        style.inline,
    ) {
        value = staticMarkdownDocumentCache.get(content, style.inline)
            ?: withContext(Dispatchers.Default) { staticMarkdownDocumentCache.parse(content, style.inline) }
    }.value
    if (document == null) {
        // 解析期间只铺开头一段：把整份长文档塞进单个 Text 的布局成本，
        // 本身就是这次「进入记忆页卡一下」的主要来源之一。
        Box(modifier = modifier) {
            Text(text = content.take(LazyMarkdownPlaceholderChars), style = style.body)
        }
    } else {
        MarkdownBlockLazyColumn(
            blocks = document.blocks,
            style = style,
            modifier = modifier,
            contentPadding = contentPadding,
        )
    }
}

/** 后台解析期间的占位长度；过长会把占位自身变成一次长布局。 */
private const val LazyMarkdownPlaceholderChars = 2000

/**
 * 流式内容的 Markdown。解析在后台串行进行，最新目标覆盖尚未开始的旧目标；
 * 正文语气接入逐字显现，思考语气直接显示每次解析结果。
 *
 * [onRevealCompleteChange] 在当前全文的终态快照完成排版、且显现动画排空后回调 true，
 * 调用方据此切换到可选择的静态视图。
 */
@Composable
internal fun StreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    trailingBlocks: Int? = null,
    onRevealCompleteChange: (Boolean) -> Unit = {},
) {
    val style = rememberMarkdownStyle(tone)
    // 正文与思考各用一条显现时钟，都是逐帧推进；两者块 key 都是文档内偏移，
    // 共用同一个协调器会互相覆盖进度。
    val reveal = when (tone) {
        MarkdownTone.Answer -> state.revealCoordinator
        MarkdownTone.Thinking -> state.thinkingRevealCoordinator
    }
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    val currentIsStreaming by rememberUpdatedState(isStreaming)
    val currentCallback by rememberUpdatedState(onRevealCompleteChange)
    val restoreGeneration = state.restoreState.generation

    LifecycleResumeEffect(state) {
        reveal.pauseAnimationsAndCatchUp()
        state.restoreState.begin(currentContent)
        onPauseOrDispose {
            state.restoreState.pause()
            reveal.pauseAnimationsAndCatchUp()
        }
    }
    LaunchedEffect(reveal) {
        reveal.runFrameClock()
    }
    LaunchedEffect(state) {
        state.parseUpdates()
    }
    LaunchedEffect(content, isStreaming, style.inline) {
        state.parseTargets.trySend(StreamingMarkdownTarget(content, isStreaming, style.inline))
        if (isStreaming) currentCallback(false)
    }
    // 终态收口：显现速度为了丝滑有上限，长回答结束时可能还压着成千字没走完。
    // 积压大就直接推到目标，积压小就让最后几个字照常打完。
    LaunchedEffect(isStreaming, reveal) {
        if (!isStreaming) reveal.settleBacklogIfHeavy()
    }
    LaunchedEffect(content, isStreaming, snapshot, reveal) {
        val target = snapshot
        if (!isStreamingMarkdownTargetComplete(content, isStreaming, target?.originalSource, target?.isComplete == true)) {
            currentCallback(false)
            return@LaunchedEffect
        }
        // 等终态文档完成组合与排版后，再等待尾部字符的显现收口。
        withFrameNanos { }
        if (!reveal.drained.value) reveal.drained.filter { it }.first()
        if (isStreamingMarkdownTargetComplete(
                currentContent,
                currentIsStreaming,
                target?.originalSource,
                target?.isComplete == true,
            )
        ) {
            currentCallback(true)
        }
    }

    val parsed = snapshot ?: return
    SideEffect { reveal.retainBlocks(parsed.document.revealKeys) }
    MarkdownContent(
        document = parsed.document,
        style = style,
        reveal = reveal,
        trailingBlocks = trailingBlocks,
        modifier = modifier.onGloballyPositioned {
            // 恢复基线对应的文档真正排版后才开放增量动画，解析耗时不受帧数限制。
            if (state.restoreState.completeLayout(
                    generation = restoreGeneration,
                    renderedContent = parsed.originalSource,
                    currentContent = currentContent,
                )
            ) {
                reveal.resumeAnimationsAfterCatchUp()
            }
        },
    )
}
