package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败归档的判定逻辑测试。
 *
 * 核心诉求：**只把真正的失败写进去**。
 * 误记的代价是把有价值的踩坑记录淹没，所以「什么不该记」比「什么该记」更重要。
 */
class SkillErrorJournalTest {

    @Test
    fun detectsErrorJson() {
        val parsed = SkillErrorJournal.parseError("""{"ok":false,"code":"LINUX_NOT_READY","message":"环境未安装"}""")

        assertNotNull(parsed)
        assertEquals("LINUX_NOT_READY", parsed!!.first)
        assertEquals("环境未安装", parsed.second)
    }

    @Test
    fun ignoresSuccessfulJson() {
        assertNull(SkillErrorJournal.parseError("""{"ok":true,"result":"文件已写入"}"""))
    }

    /** 终端等工具的普通文本输出不是错误，不能因为不是 JSON 就当成失败。 */
    @Test
    fun ignoresPlainTextOutput() {
        assertNull(SkillErrorJournal.parseError("total 24\ndrwxr-xr-x 2 root root 4096 Nov 3 12:00 app"))
        assertNull(SkillErrorJournal.parseError("/system/bin/sh: no such file"))
        assertNull(SkillErrorJournal.parseError(""))
    }

    /** `ok` 缺失时按成功处理，避免把正常 JSON 结果误判成失败。 */
    @Test
    fun treatsMissingOkFieldAsSuccess() {
        assertNull(SkillErrorJournal.parseError("""{"content":"hello"}"""))
    }

    @Test
    fun fallsBackToUnknownCodeWhenCodeMissing() {
        val parsed = SkillErrorJournal.parseError("""{"ok":false,"message":"炸了"}""")

        assertNotNull(parsed)
        assertEquals("UNKNOWN", parsed!!.first)
    }

    /** 流程提示类错误码不进日志，否则会把真正的坑淹掉。 */
    @Test
    fun flowHintsAreNotJournaled() {
        assertTrue("NO_OBSERVATION" in SkillErrorJournal.IGNORED_CODES)
        assertTrue("ACCESSIBILITY_UNAVAILABLE" in SkillErrorJournal.IGNORED_CODES)
        assertTrue("DANGEROUS_OPERATION_DENIED" in SkillErrorJournal.IGNORED_CODES)
        // 这类是真失败，必须记录
        assertEquals(false, "LINUX_NOT_READY" in SkillErrorJournal.IGNORED_CODES)
        assertEquals(false, "TOOL_EXECUTION_FAILED" in SkillErrorJournal.IGNORED_CODES)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(SkillErrorJournal.parseError("""{"ok":false,"code":"""))
    }

    /** 大返回必定是正常内容（读文件/长日志），跳过解析省开销，也避免被误判。 */
    @Test
    fun oversizedResultIsNotParsed() {
        val big = """{"ok":false,"code":"X","message":"""" + "a".repeat(20_000) + "\"}"
        assertNull(SkillErrorJournal.parseError(big))
    }
}
