package io.github.mangi.eta.agent.subagent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubagentResultCompactorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resultWithinLimitIsPassedThroughUnchanged() {
        val text = "结论：" + "a".repeat(SubagentResultCompactor.MAX_SUBAGENT_RESULT_CHARS - 3)

        assertEquals(text, SubagentResultCompactor.compact(text, temporaryFolder.root))
        assertEquals(emptyList<String>(), spillFiles())
    }

    @Test
    fun oversizedResultKeepsHeadAndSpillsTheFullText() {
        val head = "结论先行：" + "b".repeat(SubagentResultCompactor.MAX_SUBAGENT_RESULT_CHARS)
        val tail = "e".repeat(4_000)
        val full = head + tail

        val compacted = SubagentResultCompactor.compact(full, temporaryFolder.root)

        assertTrue(compacted.startsWith(full.take(SubagentResultCompactor.MAX_SUBAGENT_RESULT_CHARS)))
        assertTrue(compacted.length < full.length)
        val spilled = spillFiles().single()
        assertEquals(full, spilled.readText())
        assertTrue(compacted.contains("[子代理返回内容过长已截断，完整结果已落盘：${spilled.absolutePath}]"))
        assertTrue(compacted.endsWith("]"))
    }

    @Test
    fun missingSpillDirectoryFallsBackToATruncationNoticeOnly() {
        val text = "c".repeat(SubagentResultCompactor.MAX_SUBAGENT_RESULT_CHARS + 1)

        val compacted = SubagentResultCompactor.compact(text, null)

        assertTrue(compacted.contains("[子代理返回内容过长已截断，请让子代理分段汇报]"))
        assertEquals(emptyList<String>(), spillFiles())
    }

    private fun spillFiles(): List<File> =
        File(temporaryFolder.root, "subagent-results").listFiles()?.toList().orEmpty()
}
