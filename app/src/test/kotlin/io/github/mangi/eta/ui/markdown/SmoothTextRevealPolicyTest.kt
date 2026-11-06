package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothTextRevealPolicyTest {
    @Test
    fun coordinatorTracksBackgroundAnimationSuspension() {
        val coordinator = SmoothTextRevealCoordinator()

        assertEquals(false, coordinator.isAnimationPaused)
        coordinator.pauseAnimationsAndCatchUp()
        assertEquals(true, coordinator.isAnimationPaused)
        coordinator.resumeAnimationsAfterCatchUp()
        assertEquals(false, coordinator.isAnimationPaused)
    }

    @Test
    fun emptyAndOrdinaryTextExposeEveryGraphemeBoundary() {
        assertArrayEquals(intArrayOf(0), graphemeBoundaries(""))
        assertArrayEquals(intArrayOf(0, 1, 2, 3), graphemeBoundaries("A中B"))
    }

    @Test
    fun emojiSurrogatePairIsOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A😀B"),
        )
    }

    @Test
    fun extendedEmojiSequencesAreNeverSplit() {
        assertArrayEquals(
            intArrayOf(0, 1, 12, 13),
            graphemeBoundaries("A👨‍👩‍👧‍👦B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A👍🏽B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A🇨🇳B"),
        )
    }

    @Test
    fun combiningMarkAndCrLfStayInOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("Ae\u0301B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A\r\nB"),
        )
    }

    @Test
    fun appendedTextOnlyRebuildsTheLastPotentiallyExtendedGrapheme() {
        val familyPrefix = "A👨‍👩"
        val prefixBoundaries = graphemeBoundaries(familyPrefix)
        val family = "$familyPrefix‍👧‍👦B"

        assertArrayEquals(
            graphemeBoundaries(family),
            updateGraphemeBoundaries(familyPrefix, prefixBoundaries, family),
        )
        assertArrayEquals(
            graphemeBoundaries("A\r\nB"),
            updateGraphemeBoundaries("A\r", graphemeBoundaries("A\r"), "A\r\nB"),
        )
    }

    @Test
    fun nonAppendReplacementFallsBackToACompleteBoundaryScan() {
        val previous = "alpha 😀"
        val replacement = "beta 👨‍👩‍👧‍👦"

        assertArrayEquals(
            graphemeBoundaries(replacement),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    @Test
    fun replacementSharingPrefixInsideSurrogatePairFallsBackToCompleteScan() {
        // 增量重建只处理纯追加；替换即使共享 UTF-16 前缀也整体重扫，不会切开代理对。
        assertReplacementFallsBackToCompleteScan(previous = "😀 alpha", replacement = "😁 beta")
        assertReplacementFallsBackToCompleteScan(previous = "A😀x", replacement = "A😀y")
        assertReplacementFallsBackToCompleteScan(previous = "first", replacement = "second")
    }

    @Test
    fun changedExtendedGraphemeFallsBackToCompleteScanWithoutSplitting() {
        // “👨‍👩X”与“👨‍👧Y”共享的前缀结束在 ZWJ 序列中间，只能整体重扫而不是复用半截字素。
        val previous = "👨‍👩X"
        val replacement = "👨‍👧Y"

        assertArrayEquals(
            intArrayOf(0, 5, 6),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    @Test
    fun revealSpeedGrowsWithBacklogButStaysBounded() {
        assertEquals(36f, smoothRevealSpeed(totalBacklog = 0f), FLOAT_TOLERANCE)
        // 小积压仍按基础打字速度走，不会因为一次分片就突然加速。
        assertEquals(36f, smoothRevealSpeed(totalBacklog = 9f), FLOAT_TOLERANCE)
        assertEquals(133.333f, smoothRevealSpeed(totalBacklog = 100f), 0.01f)
        assertEquals(229.885f, smoothRevealSpeed(totalBacklog = 1_000f), 0.01f)
        // 旧实现 `backlog / 0.2s` 在同样的积压下会算出 5000 / 50000 字每秒，
        // 也就是每帧吐出几十上百个字；现在无论积压多大都不超过上限。
        val huge = smoothRevealSpeed(totalBacklog = 100_000f)
        assertEquals(249.781f, huge, 0.01f)
        assertTrue(huge <= 320f)
    }

    @Test
    fun normalFrameAdvancesFractionallyAtBaseRate() {
        assertEquals(
            0.6f,
            advanceSmoothReveal(
                current = 0f,
                target = 10f,
                elapsedSeconds = 1f / 60f,
                totalBacklog = 1f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun singleFrameAdvanceIsCappedSoLongFramesNeverDumpABlock() {
        // 一千字素的积压 + 50ms 的帧：旧实现一次推进 253 个字，等于一大段直接蹦出来。
        assertEquals(
            6f,
            advanceSmoothReveal(
                current = 3f,
                target = 1_000f,
                elapsedSeconds = 0.05f,
                totalBacklog = 1_000f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            MAX_GRAPHEMES_PER_FRAME,
            advanceReveal(current = 0f, target = 1_000f, speed = 10_000f, elapsedSeconds = 1f),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun speedChangeIsSmoothedInsteadOfJumping() {
        val firstFrame = approachRevealSpeed(current = 36f, desired = 240f, elapsedSeconds = 1f / 120f)
        val secondFrame = approachRevealSpeed(current = firstFrame, desired = 240f, elapsedSeconds = 1f / 120f)

        assertTrue(firstFrame > 36f)
        // 一帧只走一小段：目标速度一次性到 240，实际速度不会当帧就跳过去。
        assertTrue(firstFrame < 36f + (240f - 36f) * 0.15f)
        assertTrue(secondFrame > firstFrame)
        // 时间没有推进时保持原速，不会凭空加速。
        assertEquals(36f, approachRevealSpeed(current = 36f, desired = 240f, elapsedSeconds = 0f), FLOAT_TOLERANCE)
    }

    @Test
    fun sustainedStreamKeepsPerFrameAdvanceStable() {
        // 每帧到达 0.6 个字素（120Hz 下约 72 字/秒），观察稳态下单帧推进量。
        var target = 0f
        var progress = 0f
        var speed = 36f
        var minAdvance = Float.MAX_VALUE
        var maxAdvance = 0f
        repeat(600) { frame ->
            target += 0.6f
            speed = approachRevealSpeed(speed, smoothRevealSpeed(target - progress), 1f / 120f)
            val next = advanceReveal(progress, target, speed, 1f / 120f)
            val advance = next - progress
            progress = next
            if (frame > 240) {
                minAdvance = minOf(minAdvance, advance)
                maxAdvance = maxOf(maxAdvance, advance)
            }
        }
        // 跟得上：不会无限落后。
        assertTrue(target - progress < 60f)
        // 丝滑：稳态下每帧推进量几乎一致，不会忽快忽慢。
        assertTrue(maxAdvance - minAdvance < 0.05f)
    }

    @Test
    fun extremeBurstNeverOutrunsTheFrameCap() {
        // 每帧到达 20 字素（120Hz 下 2400 字/秒，远超任何真实模型），逐帧检查上限。
        var target = 0f
        var progress = 0f
        var speed = 36f
        repeat(600) {
            target += 20f
            speed = approachRevealSpeed(speed, smoothRevealSpeed(target - progress), 1f / 120f)
            val next = advanceReveal(progress, target, speed, 1f / 120f)
            assertTrue(next - progress <= MAX_GRAPHEMES_PER_FRAME + FLOAT_TOLERANCE)
            progress = next
        }
    }

    @Test
    fun frameAdvanceClampsInvalidTimeAndTargetBounds() {
        assertEquals(
            2f,
            advanceSmoothReveal(
                current = 2f,
                target = 10f,
                elapsedSeconds = -1f,
                totalBacklog = 10f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            5f,
            advanceSmoothReveal(
                current = 4.75f,
                target = 5f,
                elapsedSeconds = 1f,
                totalBacklog = 100f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            5f,
            advanceSmoothReveal(
                current = 7f,
                target = 5f,
                elapsedSeconds = 1f,
                totalBacklog = 100f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun markdownDocumentCollapsesSourceBlankLinesIntoSemanticBlocks() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "第一段\n\n\n第二段",
            isComplete = true,
        )

        val blocks = snapshot.document.blocks
        assertEquals(2, blocks.size)
        assertEquals(listOf("第一段", "第二段"), blocks.map { block -> (block as MarkdownParagraph).text.text })
    }

    @Test
    fun markdownBlockGapBuildsReadableHierarchyWithoutLeadingGap() {
        val first = MarkdownParagraph(0, AnnotatedString("第一段"))
        val second = MarkdownParagraph(4, AnnotatedString("第二段"))
        val heading = MarkdownHeading(8, level = 2, text = AnnotatedString("小节"))
        val subHeading = MarkdownHeading(12, level = 3, text = AnnotatedString("子节"))
        val list = MarkdownList(16, ordered = false, loose = false, items = emptyList())
        val table = MarkdownTable(20, alignments = emptyList(), header = emptyList(), rows = emptyList())

        assertEquals(0.sp, markdownBlockGap(null, first, MarkdownTone.Answer))
        assertEquals(12.sp, markdownBlockGap(first, second, MarkdownTone.Answer))
        assertEquals(22.sp, markdownBlockGap(first, heading, MarkdownTone.Answer))
        assertEquals(18.sp, markdownBlockGap(first, subHeading, MarkdownTone.Answer))
        assertEquals(8.sp, markdownBlockGap(heading, second, MarkdownTone.Answer))
        assertEquals(8.sp, markdownBlockGap(heading, subHeading, MarkdownTone.Answer))
        assertEquals(14.sp, markdownBlockGap(first, list, MarkdownTone.Answer))
        assertEquals(14.sp, markdownBlockGap(table, first, MarkdownTone.Answer))

        // 思考语气的间距整体收敛。
        assertEquals(0.sp, markdownBlockGap(null, first, MarkdownTone.Thinking))
        assertEquals(9.sp, markdownBlockGap(first, second, MarkdownTone.Thinking))
        assertEquals(16.5.sp, markdownBlockGap(first, heading, MarkdownTone.Thinking))
    }

    @Test
    fun listMarkerWaitsForItsOwnContentToStartRevealing() {
        val markerKey = RevealBlockKey(10)

        assertFalse(listMarkerVisible(revealActive = true, markerKey = markerKey, started = emptySet()))
        assertTrue(listMarkerVisible(revealActive = true, markerKey = markerKey, started = setOf(markerKey)))
    }

    @Test
    fun listMarkerStaysHiddenForEmptyItemsUntilRevealEnds() {
        // 图片已降级为行内链接，不再存在“项内无文字但因图片而恒可见”的特例；
        // 空项在流式期间保持隐藏，非流式渲染不再隐藏任何 marker。
        assertFalse(listMarkerVisible(revealActive = true, markerKey = null, started = emptySet()))
        assertTrue(listMarkerVisible(revealActive = false, markerKey = null, started = null))
        assertTrue(listMarkerVisible(revealActive = false, markerKey = RevealBlockKey(10), started = emptySet()))
    }

    private fun assertReplacementFallsBackToCompleteScan(previous: String, replacement: String) {
        assertArrayEquals(
            graphemeBoundaries(replacement),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
