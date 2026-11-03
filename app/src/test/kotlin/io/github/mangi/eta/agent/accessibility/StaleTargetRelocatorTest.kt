package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaleTargetRelocatorTest {

    private val semanticKey = StaleTargetRelocator.SEMANTIC_PREFIX + "628|io.github.mangi.eta|android.widget.TextView||删除|"

    @Test
    fun relocatesWhenExactlyOneCandidateMatches() {
        val candidates = listOf(
            3 to (StaleTargetRelocator.SEMANTIC_PREFIX + "628|pkg|cls||添加共享文件夹|"),
            7 to semanticKey,
            9 to (StaleTargetRelocator.SEMANTIC_PREFIX + "628|pkg|cls||返回|"),
        )
        assertEquals(7, StaleTargetRelocator.relocate(semanticKey, candidates, truncated = false))
    }

    @Test
    fun refusesWhenMultipleCandidatesMatch() {
        // 列表里两个「删除」——这正是盲点会点错的地方，必须放弃。
        val candidates = listOf(7 to semanticKey, 12 to semanticKey)
        assertNull(StaleTargetRelocator.relocate(semanticKey, candidates, truncated = false))
    }

    @Test
    fun refusesWhenNothingMatches() {
        val candidates = listOf(7 to (StaleTargetRelocator.SEMANTIC_PREFIX + "628|pkg|cls||别的|"))
        assertNull(StaleTargetRelocator.relocate(semanticKey, candidates, truncated = false))
    }

    @Test
    fun refusesBlankKey() {
        assertNull(StaleTargetRelocator.relocate("", listOf(1 to ""), truncated = false))
    }

    @Test
    fun truncatedSnapshotRejectsSemanticKeysEvenWhenUnique() {
        // 树被截断时可能少了另一个同文本节点，「看起来唯一」并不安全。
        val candidates = listOf(7 to semanticKey)
        assertNull(StaleTargetRelocator.relocate(semanticKey, candidates, truncated = true))
    }

    @Test
    fun truncatedSnapshotStillAllowsUniqueIdKeys() {
        val key = StaleTargetRelocator.UNIQUE_ID_PREFIX + "abc123"
        val candidates = listOf(4 to key)
        assertEquals(4, StaleTargetRelocator.relocate(key, candidates, truncated = true))
    }

    @Test
    fun uniqueIdWinsOverSemanticDuplicates() {
        val key = StaleTargetRelocator.UNIQUE_ID_PREFIX + "abc123"
        val candidates = listOf(
            4 to key,
            8 to semanticKey,
        )
        assertEquals(4, StaleTargetRelocator.relocate(key, candidates, truncated = false))
    }
}
