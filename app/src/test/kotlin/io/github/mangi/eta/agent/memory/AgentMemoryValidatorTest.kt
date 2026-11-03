package io.github.mangi.eta.agent.memory

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆写入格式校验。
 *
 * 核心诉求：让「[状态] 必须带日期」不可绕过 —— 否则过期提示机制永远不触发。
 * 但校验必须**宽松**，不然会挡住正常的写入。
 */
class AgentMemoryValidatorTest {

    @Test
    fun statusBlockWithoutDateIsRejected() {
        val violation = AgentMemoryValidator.validate(
            "## [状态] 项目概况\n云端 main 是 abc123\n",
        )

        assertNotNull(violation)
        assertTrue(violation!!.message.contains("项目概况"))
        assertTrue(violation.hint.contains("YYYY-MM-DD"))
    }

    @Test
    fun statusBlockWithDateInHeadingPasses() {
        assertNull(AgentMemoryValidator.validate("## [状态] 2026-11-03 项目概况\n云端 main 是 abc123\n"))
    }

    /** 日期写在正文里也算 —— 不要因为格式位置不同就挡人。 */
    @Test
    fun statusBlockWithDateInBodyPasses() {
        assertNull(AgentMemoryValidator.validate("## [状态] 项目概况\n2026-11-03 云端是 abc123\n"))
    }

    @Test
    fun otherTagsAreNotChecked() {
        assertNull(AgentMemoryValidator.validate("## [铁律] 安装前必须问\n不做日期检查\n"))
        assertNull(AgentMemoryValidator.validate("## [偏好] 说话通俗\n"))
        assertNull(AgentMemoryValidator.validate("## [知识] Kotlin 坑\n"))
    }

    @Test
    fun plainContentIsNotChecked() {
        assertNull(AgentMemoryValidator.validate("## 随便什么标题\n没有标签，不管\n"))
        assertNull(AgentMemoryValidator.validate("就是一段普通文本\n"))
    }

    @Test
    fun emptySegmentPasses() {
        assertNull(AgentMemoryValidator.validate(""))
        assertNull(AgentMemoryValidator.validate("   \n  "))
    }

    /** 只挑出没日期的那块，别把已合规的也一起报。 */
    @Test
    fun onlyMissingOneIsReported() {
        val violation = AgentMemoryValidator.validate(
            """
            ## [状态] 2026-11-03 合规的
            内容
            ## [状态] 没日期的
            内容
            ## [状态] 2026-11-02 也合规
            内容
            """.trimIndent(),
        )

        assertNotNull(violation)
        assertTrue(violation!!.message.contains("没日期的"))
    }

    /** 多个都缺日期时，报第一个就够，不必罗列全部。 */
    @Test
    fun reportsFirstViolationWhenMultiple() {
        val violation = AgentMemoryValidator.validate(
            "## [状态] 甲\n无日期\n## [状态] 乙\n无日期\n",
        )

        assertNotNull(violation)
        assertTrue(violation!!.message.contains("甲"))
    }

    /** 状态块下面跟着别的块，不能把下一块的内容算进状态块。 */
    @Test
    fun nextBlockStopsStatusScope() {
        assertNull(
            AgentMemoryValidator.validate(
                "## [状态] 2026-11-03 有日期\n内容\n## [知识] 别的块\n没日期但不管\n",
            ),
        )
    }

    @Test
    fun todayIsIsoDate() {
        assertTrue(AgentMemoryValidator.today().matches(Regex("20\\d{2}-\\d{2}-\\d{2}")))
    }
}
