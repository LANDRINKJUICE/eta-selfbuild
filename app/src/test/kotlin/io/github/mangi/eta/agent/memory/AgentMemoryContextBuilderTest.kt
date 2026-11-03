package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        // 1_000_000 窗口按 1/16 取 62_500；更大的窗口由 64_000 上限截断（自签改造 eta5 放宽了上限）。
        assertEquals(62_500, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(64_000, AgentMemoryContextBuilder.coreBudgetChars(4_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsOnlyCoreSectionAndBoundedHeadingIndex() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n不应自动注入"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# 核心记忆\n长期偏好\n## 关系\n家人", context.coreContent)
        assertFalse(context.coreContent.contains("不应自动注入"))
        assertEquals("# 核心记忆\n## 关系\n# 详细背景", context.headingIndex)
        assertFalse(context.coreTruncated)
        assertTrue(context.staleHints.isEmpty())
    }

    @Test
    fun oversizedCoreIsTruncatedWithoutDroppingRevision() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertTrue(context.coreContent.length <= 8_000 + 120)
        assertTrue(context.coreTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun detailsWithoutCoreHeadingAreIndexedButNotAutomaticallyInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n只应按需读取的细节"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# 项目", context.headingIndex)
    }

    @Test
    fun thirdLevelHeadingsAreIndexedButDeeperOnesAreNot() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n### 家庭\n内容\n#### 更细的片段\n不进索引"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        // 三级标题要进索引（按需定位），四级及更深的层次仍交给 memory_get 自行读取。
        assertEquals("# 核心记忆\n## 关系\n### 家庭", context.headingIndex)
    }

    /** 超预算时不能从尾部一刀切，否则写在后面的铁律会整段消失。 */
    @Test
    fun oversizedCoreKeepsRuleAndPreferenceBlocksAheadOfPlainText() {
        val content = buildString {
            appendLine("# 核心记忆")
            appendLine("## [铁律] 装包前必须先问")
            appendLine("绝不擅自安装或卸载。")
            appendLine("## [偏好] 回答要通俗")
            appendLine("先确认再动手。")
            appendLine("## 普通记录")
            appendLine("x".repeat(20_000))
        }
        val context = AgentMemoryContextBuilder.build(snapshot(content), null)

        assertTrue(context.coreTruncated)
        assertTrue(context.coreContent.contains("[铁律] 装包前必须先问"))
        assertTrue(context.coreContent.contains("[偏好] 回答要通俗"))
        assertTrue(context.coreContent.contains("未注入"))
        // 巨大的普通块必须让位，不能把铁律和偏好挤掉。
        assertFalse(context.coreContent.contains("x".repeat(100)))
    }

    /**
     * 不带标签的 `## 普通标题` 必须切断上一个标签块。
     * 否则它下面的内容会被算进 `[偏好]` 块里，整块超预算时连偏好一起丢失。
     */
    @Test
    fun plainHeadingEndsPreviousTaggedBlock() {
        val content = buildString {
            appendLine("# 核心记忆")
            appendLine("## [铁律] 甲")
            appendLine("一")
            appendLine("## 无关的大段内容")
            appendLine("z".repeat(20_000))
        }
        val context = AgentMemoryContextBuilder.build(snapshot(content), null)

        assertTrue(context.coreContent.contains("[铁律] 甲"))
        assertFalse(context.coreContent.contains("z".repeat(100)))
    }

    /** 正常预算下带标签的块必须原样保留，不能被重排或改写。 */
    @Test
    fun taggedBlocksArePreservedVerbatimWhenWithinBudget() {
        val content = "# 核心记忆\n## [铁律] 甲\n一\n## [偏好] 乙\n二\n## 丙\n三"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 256_000)

        assertEquals(content, context.coreContent)
        assertFalse(context.coreTruncated)
    }

    /** 状态类超过 7 天要被点出来，否则会拿旧版本号当事实。 */
    @Test
    fun staleStatusBlockProducesHint() {
        val old = java.time.LocalDate.now().minusDays(20).toString()
        val content = "# 核心记忆\n## [状态] 设备版本\n$old 设备装的是 3.4.0+2357078"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(1, context.staleHints.size)
        assertTrue(context.staleHints.first().contains(old))
        assertTrue(context.staleHints.first().contains("20 天"))
    }

    /** 新的状态条目不该刷提示。 */
    @Test
    fun freshStatusBlockProducesNoHint() {
        val today = java.time.LocalDate.now().toString()
        val content = "# 核心记忆\n## [状态] 设备版本\n$today 设备装的是 3.4.0+fc3ba59"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertTrue(context.staleHints.isEmpty())
    }

    /** 铁律和偏好不参与过期判定——它们是长期约定，不该被「放旧了」打扰。 */
    @Test
    fun ruleAndPreferenceBlocksNeverGoStale() {
        val old = java.time.LocalDate.now().minusDays(400).toString()
        val content = "# 核心记忆\n## [铁律] 甲\n$old 永远遵守\n## [偏好] 乙\n$old 一直如此"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertTrue(context.staleHints.isEmpty())
    }

    /** 状态块没写日期时不能崩，也不该乱报过期。 */
    @Test
    fun statusBlockWithoutDateIsIgnored() {
        val content = "# 核心记忆\n## [状态] 无日期\n设备装的是某个版本"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertTrue(context.staleHints.isEmpty())
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
