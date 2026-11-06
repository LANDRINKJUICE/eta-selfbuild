package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 列表按消息身份保留会话，条目重新挂载时接用已有解析和显现进度。 */
internal class StreamingMarkdownState {
    private var attachedContentCount by mutableIntStateOf(0)
    val isAttachedToContent: Boolean get() = attachedContentCount > 0
    var revealedContent by mutableStateOf<String?>(null)
    private val parserSession = StreamingGfmParserSession()
    val revealCoordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
    /**
     * 思考语气专用的显现时钟。
     *
     * 思考与正文是两个独立文档，但块 key 只是文档内的源码偏移，共用同一个协调器
     * 会让两边的同偏移块互相覆盖进度。分开之后思考也能逐字丝滑显现，且各自暂停、
     * 各自收口，互不干扰。
     */
    val thinkingRevealCoordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
    val restoreState = StreamingMarkdownRestoreState()
    val parseTargets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
    var snapshot by mutableStateOf<StreamingGfmSnapshot?>(null)

    fun attachContent() {
        attachedContentCount += 1
    }

    fun detachContent() {
        check(attachedContentCount > 0)
        attachedContentCount -= 1
    }

    suspend fun parseUpdates() = consumeStreamingMarkdownTargets(
        targets = parseTargets,
        parse = { target ->
            withContext(Dispatchers.Default) {
                parserSession.parse(target.content, isComplete = !target.isStreaming, style = target.style)
            }
        },
        publish = { snapshot = it },
        // 主线程每发布一次快照就要把整篇重新组合排版一次，按 12 次/秒节流；
        // 文字是逐帧显现的，快照变稀不会让输出停顿。
        minPublishIntervalMillis = STREAMING_PUBLISH_INTERVAL_MILLIS,
    )
}

/** 一次快照发布到下一次之间的最小间隔。 */
private const val STREAMING_PUBLISH_INTERVAL_MILLIS = 80L

internal data class StreamingMarkdownTarget(
    val content: String,
    val isStreaming: Boolean,
    val style: MarkdownInlineStyle = MarkdownInlineStyle.Default,
)

internal suspend fun consumeStreamingMarkdownTargets(
    targets: ReceiveChannel<StreamingMarkdownTarget>,
    parse: suspend (StreamingMarkdownTarget) -> StreamingGfmSnapshot,
    publish: (StreamingGfmSnapshot) -> Unit,
    minPublishIntervalMillis: Long = 0L,
) {
    var target = targets.receiveCatching().getOrNull() ?: return
    val intervalNanos = (minPublishIntervalMillis.coerceAtLeast(0L)) * 1_000_000L
    var lastPublishedAt = 0L
    while (true) {
        while (true) {
            target = targets.tryReceive().getOrNull() ?: break
        }
        val parsed = parse(target)
        // 每发布一次快照，主线程就要把整篇重新组合与排版一次。模型的分片比屏幕需要的
        // 密得多，按最小间隔节流能把排版次数压下来；文字是逐帧显现的，快照变稀不会
        // 让输出停顿，只是把「每来一片重排一遍」降成「每 80ms 重排一遍」。
        if (intervalNanos > 0L && lastPublishedAt != 0L) {
            val sinceLast = System.nanoTime() - lastPublishedAt
            if (sinceLast < intervalNanos) delay((intervalNanos - sinceLast) / 1_000_000)
        }
        val newerTarget = targets.tryReceive().getOrNull()
        // 追加分片不会使已解析的前缀失效。若每次有新目标就丢弃结果，持续高速流会
        // 饿死显示端；只有上游纠正全文或重新打开终态时，才跳过不再适用的快照。
        if (newerTarget == null ||
            (newerTarget.content.startsWith(parsed.originalSource) &&
                (!parsed.isComplete || newerTarget == target))
        ) {
            publish(parsed)
            lastPublishedAt = System.nanoTime()
        }
        target = newerTarget ?: targets.receiveCatching().getOrNull() ?: return
    }
}
