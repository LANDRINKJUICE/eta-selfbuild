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
        // 消息不能 fallback 到整段 JSON —— 实测那样会把几 KB envelope 抄进日志。
        assertEquals("炸了", parsed.second)
    }

    /** 有些工具用 error/status 字段放错误码，别一律归成 UNKNOWN（那样记录没有诊断价值）。 */
    @Test
    fun fallsBackToErrorOrStatusFieldForCode() {
        assertEquals("TIMEOUT", SkillErrorJournal.parseError("""{"ok":false,"error":"TIMEOUT"}""")!!.first)
        assertEquals("FAILED", SkillErrorJournal.parseError("""{"ok":false,"status":"FAILED"}""")!!.first)
    }

    /** 连 message 都没有时，至少用错误码占位，不能把原始 envelope 抄进去。 */
    @Test
    fun missingMessageUsesCodeInsteadOfRawJson() {
        val parsed = SkillErrorJournal.parseError("""{"ok":false,"code":"BOOM","payload":"${"x".repeat(500)}"}""")

        assertNotNull(parsed)
        assertEquals("BOOM", parsed!!.first)
        assertEquals("BOOM", parsed.second)
        assertTrue(parsed.second.length < 50)
    }

    @Test
    fun longMessageIsTruncated() {
        val parsed = SkillErrorJournal.parseError("""{"ok":false,"code":"X","message":"${"y".repeat(2000)}"}""")

        assertNotNull(parsed)
        assertTrue(parsed!!.second.length <= 300)
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

    /** terminal 的 ok=true 只表示命令跑起来了，成败要看 exit_code。 */
    @Test
    fun terminalNonZeroExitCodeIsFailure() {
        val parsed = SkillErrorJournal.parseError(
            """{"ok":true,"exit_code":1,"stdout":"","stderr":"can't cd to /workspace"}""",
        )

        assertNotNull(parsed)
        // 真正的线索是 stderr，不是 "failed" —— 实测旧版只记 code，11 条记录全是废话。
        assertEquals("can't cd to /workspace", parsed!!.second)
    }

    @Test
    fun terminalZeroExitCodeIsSuccess() {
        assertNull(SkillErrorJournal.parseError("""{"ok":true,"exit_code":0,"stdout":"ok","stderr":""}"""))
    }

    /** stderr 优先于 message：terminal 的 message 经常只是重复 code。 */
    @Test
    fun stderrTakesPrecedenceOverMessage() {
        val parsed = SkillErrorJournal.parseError(
            """{"ok":false,"code":"failed","message":"failed","stderr":"ls: /x: No such file or directory"}""",
        )

        assertNotNull(parsed)
        assertEquals("failed", parsed!!.first)
        assertEquals("ls: /x: No such file or directory", parsed.second)
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
