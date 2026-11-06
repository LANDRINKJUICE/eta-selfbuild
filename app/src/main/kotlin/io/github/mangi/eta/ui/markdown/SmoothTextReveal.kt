package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * 一条回答只使用一个显现时钟，按源码顺序在 Markdown 块之间分配同一帧的推进量。
 *
 * 解析和文本排版仅在目标文本变化时发生；帧间推进只更新普通字段并调用
 * [invalidateDraw]。只有显现跨入新行时才额外请求一次测量以增长消息高度，
 * 全程不写 Compose State，因此字符帧不会触发重组或重新排版。
 */
@Stable
internal class SmoothTextRevealCoordinator {
    private val records = sortedMapOf<RevealBlockKey, RevealRecord>()
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private val drainedState = MutableStateFlow(true)
    // 列表 marker 在绘制阶段读取它；用快照状态而非 Flow，块开始显现只让 marker 图层重绘，
    // 不会让整个列表随之重组。
    private val startedState = mutableStateOf<Set<RevealBlockKey>>(emptySet())
    private var animationsPaused = false
    // 速度本身也要平滑：模型分片一次到达几十个字时，积压会阶跃式变大，
    // 若速度直接跟积压走，每帧推进量就会忽大忽小，观感正是「卡卡的、一坨跳出来」。
    private var smoothedSpeed = BASE_REVEAL_GRAPHEMES_PER_SECOND

    val drained: StateFlow<Boolean> = drainedState
    /** 已经开始显现的块，用于让列表 marker 与正文保持同一生命周期。 */
    val started: State<Set<RevealBlockKey>> get() = startedState

    val isAnimationPaused: Boolean
        get() = animationsPaused

    /**
     * 页面不可见时帧时钟会停，但 Runtime 仍可能继续追加文本。此时直接追平当前目标，
     * 并让后续排版结果同样立即完成，避免回到页面后补播后台积压的显现动画。
     */
    fun pauseAnimationsAndCatchUp() {
        animationsPaused = true
        records.values.forEach(::completeRecord)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun resumeAnimationsAfterCatchUp() {
        records.values.forEach(::completeRecord)
        updateDrainedState()
        animationsPaused = false
        wakeups.trySend(Unit)
    }

    /**
     * 终态收口：积压超过 [thresholdGraphemes] 时直接把进度推到目标。
     *
     * 显现速度为了丝滑是有界的，一条长回答结束时可能还压着上千字没走完；
     * 那种情况下继续匀速补播要好几秒，而且和「正文已经写完」相矛盾。
     * 积压不大时（正常回答的收尾）不动它，让最后几个字照常丝滑打完。
     */
    fun settleBacklogIfHeavy(thresholdGraphemes: Float = HEAVY_BACKLOG_GRAPHEMES) {
        val backlog = records.values.sumOf { record ->
            max(0.0, (record.targetCount - record.progress).toDouble())
        }.toFloat()
        if (backlog < thresholdGraphemes) return
        records.values.forEach(::completeRecord)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun retainBlocks(activeBlocks: Set<RevealBlockKey>) {
        val iterator = records.iterator()
        var removedPendingBlock = false
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            if (record.key !in activeBlocks) {
                removedPendingBlock = removedPendingBlock || record.progress < record.targetCount
                iterator.remove()
            }
        }
        val retainedStarted = startedState.value.intersect(activeBlocks)
        if (retainedStarted != startedState.value) {
            startedState.value = retainedStarted
        }
        if (removedPendingBlock || records.none { (_, record) -> record.progress < record.targetCount }) {
            updateDrainedState()
        }
        wakeups.trySend(Unit)
    }

    fun attach(
        key: RevealBlockKey,
        node: SmoothTextRevealNode,
        text: String?,
        layoutResult: TextLayoutResult?,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        record.node = node
        if (text != null && layoutResult != null) {
            updateRecord(record, text, layoutResult)
        }
        wakeups.trySend(Unit)
    }

    fun detach(key: RevealBlockKey, node: SmoothTextRevealNode) {
        val record = records[key]?.takeIf { it.node === node } ?: return
        record.node = null
        // 已离开组合的块不再消费帧时钟；保留完成进度，重挂载时只显现后续新增文本。
        completeRecord(record)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun updateLayout(
        key: RevealBlockKey,
        node: SmoothTextRevealNode?,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        if (node != null) record.node = node
        updateRecord(record, text, layoutResult)
        wakeups.trySend(Unit)
    }

    fun drawSnapshot(key: RevealBlockKey): RevealDrawSnapshot? {
        val record = records[key] ?: return null
        if (record.layoutResult == null) return null
        return record.drawSnapshot
    }

    suspend fun runFrameClock() {
        while (currentCoroutineContext().isActive) {
            val active = firstPendingRecord()
            if (active == null) {
                updateDrainedState()
                wakeups.receive()
                continue
            }

            drainedState.value = false
            var previousFrameNanos = withFrameNanos { it }
            while (currentCoroutineContext().isActive) {
                if (firstPendingRecord() == null) break
                val frameNanos = withFrameNanos { it }
                val elapsedSeconds = ((frameNanos - previousFrameNanos) / NANOS_PER_SECOND)
                    .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
                previousFrameNanos = frameNanos

                val totalBacklog = records.values.sumOf { candidate ->
                    max(0.0, (candidate.targetCount - candidate.progress).toDouble())
                }.toFloat()
                // 先让速度平滑地逼近目标速度，再按这一帧的时间推进：
                // 分片到达造成的积压阶跃只改变「目标速度」，不直接改变「这一帧走多远」。
                smoothedSpeed = approachRevealSpeed(
                    current = smoothedSpeed,
                    desired = smoothRevealSpeed(totalBacklog),
                    elapsedSeconds = elapsedSeconds,
                )
                var remainingAdvance = advanceReveal(
                    current = 0f,
                    target = totalBacklog,
                    speed = smoothedSpeed,
                    elapsedSeconds = elapsedSeconds,
                )
                val newlyStarted = mutableSetOf<RevealBlockKey>()
                // 块结束后剩余的推进量继续用于下一块，避免短段落把追赶速度限制为每帧一块。
                for (record in records.values) {
                    if (remainingAdvance <= 0f) break
                    if (record.node == null || record.layoutResult == null) continue
                    val pending = record.targetCount - record.progress
                    if (pending <= 0f) continue
                    val advance = remainingAdvance.coerceAtMost(pending)
                    record.progress = (record.progress + advance).coerceAtMost(record.targetCount)
                    remainingAdvance -= advance
                    if (record.key !in startedState.value) newlyStarted += record.key
                    record.node?.onRevealDataChanged()
                }
                if (newlyStarted.isNotEmpty()) startedState.value = startedState.value + newlyStarted
                updateDrainedState()
            }
        }
    }

    private fun updateRecord(
        record: RevealRecord,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        if (text != record.text) {
            // 流式文本只追加不修改，但行内语法闭合（**粗体**、`code`、链接折叠等）会让
            // 渲染文本丢掉标记字符而变短或错位。此时进度只能保持单调前进：一旦回退，
            // 已显现的文字会消失并重新打字，表现为输出反复闪烁。
            record.boundaries = updateGraphemeBoundaries(
                previousText = record.text,
                previousBoundaries = record.boundaries,
                text = text,
            )
            record.text = text
            record.targetCount = record.boundaries.lastIndex.toFloat()
            record.progress = record.progress.coerceAtMost(record.targetCount)
        }
        if (record.layoutResult !== layoutResult) {
            record.layoutResult = layoutResult
        }
        if (animationsPaused || record.node == null) completeRecord(record)
        updateDrainedState()
        record.node?.onRevealDataChanged()
    }

    private fun completeRecord(record: RevealRecord) {
        record.progress = record.targetCount
        if (record.targetCount > 0f && record.key !in startedState.value) {
            startedState.value = startedState.value + record.key
        }
        record.node?.onRevealDataChanged()
    }

    private fun firstPendingRecord(): RevealRecord? = records.values.firstOrNull { record ->
        record.progress < record.targetCount && record.node != null && record.layoutResult != null
    }

    private fun updateDrainedState() {
        drainedState.value = records.values.none { record -> record.progress < record.targetCount }
    }
}

@JvmInline
internal value class RevealBlockKey(val sourceOffset: Int) : Comparable<RevealBlockKey> {
    override fun compareTo(other: RevealBlockKey): Int = sourceOffset.compareTo(other.sourceOffset)
}

@Stable
internal class SmoothTextRevealState(
    val key: RevealBlockKey,
    private val coordinator: SmoothTextRevealCoordinator,
) {
    private var node: SmoothTextRevealNode? = null
    private var text: String? = null
    private var layoutResult: TextLayoutResult? = null

    fun onTextLayout(text: String, layoutResult: TextLayoutResult) {
        this.text = text
        this.layoutResult = layoutResult
        coordinator.updateLayout(key, node, text, layoutResult)
    }

    internal fun attach(node: SmoothTextRevealNode) {
        this.node = node
        coordinator.attach(key, node, text, layoutResult)
    }

    internal fun detach(node: SmoothTextRevealNode) {
        if (this.node === node) this.node = null
        coordinator.detach(key, node)
    }

    internal fun drawSnapshot(): RevealDrawSnapshot? = coordinator.drawSnapshot(key)
}

@Composable
internal fun rememberSmoothTextRevealState(
    key: RevealBlockKey,
    coordinator: SmoothTextRevealCoordinator,
): SmoothTextRevealState = remember(key, coordinator) {
    SmoothTextRevealState(key, coordinator)
}

internal fun Modifier.smoothTextReveal(state: SmoothTextRevealState): Modifier =
    this then SmoothTextRevealElement(state)

private data class SmoothTextRevealElement(
    val state: SmoothTextRevealState,
) : ModifierNodeElement<SmoothTextRevealNode>() {
    override fun create(): SmoothTextRevealNode = SmoothTextRevealNode(state)

    override fun update(node: SmoothTextRevealNode) {
        node.updateState(state)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "smoothTextReveal"
    }
}

internal class SmoothTextRevealNode(
    private var state: SmoothTextRevealState,
) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {
    private val alphaPaint = Paint()
    private var cachedLayoutResult: TextLayoutResult? = null
    private var cachedFullCount = -1
    private var cachedFullPath: Path? = null
    private var cachedNextPath: Path? = null
    private var cachedVisibleHeight = -1

    override fun onAttach() {
        state.attach(this)
        onRevealDataChanged()
    }

    override fun onDetach() {
        state.detach(this)
        clearPathCache()
        cachedVisibleHeight = -1
    }

    fun updateState(next: SmoothTextRevealState) {
        if (state === next) return
        if (isAttached) state.detach(this)
        state = next
        clearPathCache()
        cachedVisibleHeight = -1
        if (isAttached) state.attach(this)
    }

    fun onRevealDataChanged() {
        val visibleHeight = state.visibleHeightPx()
        if (visibleHeight != cachedVisibleHeight) {
            cachedVisibleHeight = visibleHeight
            if (isAttached) invalidateMeasurement()
        }
        if (isAttached) invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val visibleHeight = state.visibleHeightPx().coerceAtMost(placeable.height)
        cachedVisibleHeight = visibleHeight
        val measuredHeight = visibleHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(placeable.width, measuredHeight) {
            placeable.place(0, 0)
        }
    }

    override fun ContentDrawScope.draw() {
        val snapshot = state.drawSnapshot() ?: return
        val contentScope = this
        val targetCount = snapshot.boundaries.lastIndex
        if (targetCount <= 0 || snapshot.progress >= targetCount) {
            drawContent()
            return
        }

        val fullCount = floor(snapshot.progress).toInt().coerceIn(0, targetCount)
        ensurePaths(snapshot, fullCount)
        cachedFullPath?.let { path ->
            clipPath(path) { contentScope.drawContent() }
        }

        val partialAlpha = (snapshot.progress - fullCount).coerceIn(0f, 1f)
        if (partialAlpha > 0f) {
            cachedNextPath?.let { path ->
                // 图层只需要覆盖正在淡入的那一个字素。旧实现用整块文本的 size 建图层，
                // 长回答每帧都要分配一张整块大小的离屏缓冲，是打字时掉帧的直接来源。
                val layerBounds = path.getBounds()
                    .inflate(PARTIAL_LAYER_PADDING_PX)
                    .intersect(Rect(Offset.Zero, size))
                if (!layerBounds.isEmpty) {
                    clipPath(path) {
                        alphaPaint.alpha = partialAlpha
                        drawContext.canvas.saveLayer(layerBounds, alphaPaint)
                        try {
                            contentScope.drawContent()
                        } finally {
                            drawContext.canvas.restore()
                        }
                    }
                }
            }
        }
    }

    private fun ensurePaths(snapshot: RevealDrawSnapshot, fullCount: Int) {
        val sameLayout = cachedLayoutResult === snapshot.layoutResult
        if (sameLayout && cachedFullCount == fullCount) return

        if (sameLayout && fullCount == cachedFullCount + 1) {
            val completedPath = cachedNextPath
            if (completedPath != null) {
                val accumulatedPath = cachedFullPath ?: Path()
                accumulatedPath.addPath(completedPath)
                cachedFullPath = accumulatedPath
            }
            cachedFullCount = fullCount
            cachedNextPath = nextGraphemePath(snapshot, fullCount)
            return
        }

        cachedLayoutResult = snapshot.layoutResult
        cachedFullCount = fullCount
        val textLength = snapshot.layoutResult.layoutInput.text.length
        val fullEnd = snapshot.boundaries[fullCount].coerceIn(0, textLength)
        cachedFullPath = if (fullEnd > 0) {
            snapshot.layoutResult.getPathForRange(0, fullEnd)
        } else {
            null
        }
        cachedNextPath = nextGraphemePath(snapshot, fullCount)
    }

    private fun nextGraphemePath(
        snapshot: RevealDrawSnapshot,
        fullCount: Int,
    ): Path? {
        val textLength = snapshot.layoutResult.layoutInput.text.length
        val start = snapshot.boundaries.getOrNull(fullCount)?.coerceIn(0, textLength)
            ?: return null
        val end = snapshot.boundaries.getOrNull(fullCount + 1)?.coerceIn(start, textLength)
            ?: return null
        return if (end > start) {
            snapshot.layoutResult.getPathForRange(start, end)
        } else {
            null
        }
    }

    private fun clearPathCache() {
        cachedLayoutResult = null
        cachedFullCount = -1
        cachedFullPath = null
        cachedNextPath = null
    }
}

internal class RevealDrawSnapshot(
    private val record: RevealRecord,
) {
    val layoutResult: TextLayoutResult
        get() = checkNotNull(record.layoutResult)
    val boundaries: IntArray
        get() = record.boundaries
    val progress: Float
        get() = record.progress
}

internal class RevealRecord(
    val key: RevealBlockKey,
) {
    val drawSnapshot = RevealDrawSnapshot(this)
    var node: SmoothTextRevealNode? = null
    var text: String = ""
    var layoutResult: TextLayoutResult? = null
    var boundaries: IntArray = intArrayOf(0)
    var progress: Float = 0f
    var targetCount: Float = 0f
}

private fun SmoothTextRevealState.visibleHeightPx(): Int {
    val snapshot = drawSnapshot() ?: return 0
    val layoutResult = snapshot.layoutResult
    val targetCount = snapshot.boundaries.lastIndex
    if (targetCount <= 0 || snapshot.progress >= targetCount) {
        return layoutResult.size.height
    }

    val visibleCount = ceil(snapshot.progress).toInt().coerceIn(0, targetCount)
    if (visibleCount == 0) return 0
    val textLength = layoutResult.layoutInput.text.length
    val visibleEnd = snapshot.boundaries[visibleCount].coerceIn(0, textLength)
    if (visibleEnd == 0 || layoutResult.lineCount == 0) return 0
    val line = layoutResult.getLineForOffset((visibleEnd - 1).coerceAtMost(textLength - 1))
    return ceil(layoutResult.getLineBottom(line)).toInt()
        .coerceIn(0, layoutResult.size.height)
}

internal fun graphemeBoundaries(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        result += boundary
        boundary = iterator.next()
    }
    if (result.lastOrNull() != text.length) result += text.length
    return result.toIntArray()
}

/**
 * 为只追加文本增量维护字素边界。
 *
 * 新内容可能把旧文本的最后一个字素继续延长，例如组合音标、ZWJ emoji、旗帜和 CRLF。
 * 因此保留倒数第二个边界之前的结果，只重算最后一个旧字素和新增后缀，避免每个流式
 * 分片都从头扫描整条回答。
 */
internal fun updateGraphemeBoundaries(
    previousText: String,
    previousBoundaries: IntArray,
    text: String,
): IntArray {
    if (
        previousText.isEmpty() ||
        !text.startsWith(previousText) ||
        previousBoundaries.isEmpty() ||
        previousBoundaries.first() != 0 ||
        previousBoundaries.last() != previousText.length
    ) {
        return graphemeBoundaries(text)
    }
    if (text == previousText) return previousBoundaries

    val restartBoundaryIndex = (previousBoundaries.lastIndex - 1).coerceAtLeast(0)
    val restartOffset = previousBoundaries[restartBoundaryIndex]
    val suffixBoundaries = graphemeBoundaries(text.substring(restartOffset))
    return IntArray(restartBoundaryIndex + suffixBoundaries.size).also { merged ->
        for (index in 0 until restartBoundaryIndex) {
            merged[index] = previousBoundaries[index]
        }
        suffixBoundaries.forEachIndexed { index, boundary ->
            merged[restartBoundaryIndex + index] = restartOffset + boundary
        }
    }
}

internal fun smoothRevealSpeed(totalBacklog: Float): Float {
    if (totalBacklog <= 0f) return BASE_REVEAL_GRAPHEMES_PER_SECOND
    // 追平所需的时间随积压一起增长，速度因此是有界且单调的。
    // 旧实现是 `backlog / 0.2s`（固定 0.2 秒追平）：一次 1000 字素的积压会算出
    // 5000 字/秒，换算到 120Hz 就是每帧吐 40 多个字——观感就是文字成块蹦出来。
    val catchUpSeconds = MIN_CATCH_UP_SECONDS + totalBacklog * CATCH_UP_SECONDS_PER_GRAPHEME
    val desired = totalBacklog / catchUpSeconds
    return desired.coerceIn(BASE_REVEAL_GRAPHEMES_PER_SECOND, MAX_REVEAL_GRAPHEMES_PER_SECOND)
}

/**
 * 一阶低通平滑：[current] 在一个时间常数内向 [desired] 靠拢。
 *
 * 分片到达是突发的，而眼睛能察觉的是「相邻帧推进量不一致」。把速度本身的突变
 * 抹平，单帧推进量就稳定，文字看起来是匀速流动而不是忽快忽慢。
 */
internal fun approachRevealSpeed(current: Float, desired: Float, elapsedSeconds: Float): Float {
    if (elapsedSeconds <= 0f) return current
    val factor = 1f - exp(-elapsedSeconds / SPEED_SMOOTHING_SECONDS)
    return current + (desired - current) * factor
}

/**
 * 按给定速度推进一帧，单帧推进量封顶 [MAX_GRAPHEMES_PER_FRAME] 个字素。
 *
 * 这道硬上限是丝滑的最后一道保险：帧间隔变长（掉帧）时按真实时间算出的推进量会
 * 变大，封顶之后最多也只跨过三个字，不会出现「卡一下然后蹦出一大段」。
 */
internal fun advanceReveal(
    current: Float,
    target: Float,
    speed: Float,
    elapsedSeconds: Float,
): Float {
    if (current >= target) return target
    val advance = (speed * elapsedSeconds.coerceAtLeast(0f))
        .coerceIn(0f, MAX_GRAPHEMES_PER_FRAME)
    return (current + advance).coerceAtMost(target)
}

/** 用目标速度直接推进一帧，等价于先算 [smoothRevealSpeed] 再走 [advanceReveal]。 */
internal fun advanceSmoothReveal(
    current: Float,
    target: Float,
    elapsedSeconds: Float,
    totalBacklog: Float,
): Float = advanceReveal(current, target, smoothRevealSpeed(totalBacklog), elapsedSeconds)

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_FRAME_DELTA_SECONDS = 0.05f
private const val BASE_REVEAL_GRAPHEMES_PER_SECOND = 36f
private const val MIN_CATCH_UP_SECONDS = 0.35f
private const val CATCH_UP_SECONDS_PER_GRAPHEME = 0.004f
private const val MAX_REVEAL_GRAPHEMES_PER_SECOND = 320f
internal const val MAX_GRAPHEMES_PER_FRAME = 3f
private const val SPEED_SMOOTHING_SECONDS = 0.12f
private const val PARTIAL_LAYER_PADDING_PX = 2f
private const val HEAVY_BACKLOG_GRAPHEMES = 400f
