package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏的回归测试。
 *
 * 日志要落盘长期留着，所以密钥绝不能原样写进去。
 * 这些形态都来自真实配置（`sk-` 前缀的 key、`Authorization: Bearer`、`api_key=` 之类）。
 */
class DiagnosticLogFilesTest {

    @Test
    fun `sk 前缀的 key 被抹掉`() {
        val text = DiagnosticLogFiles.redact("config: sk-abc123DEF456ghi789 end")

        assertFalse(text.contains("abc123DEF456ghi789"))
        assertTrue(text.contains("sk-***"))
    }

    @Test
    fun `bearer token 被抹掉`() {
        val text = DiagnosticLogFiles.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload")

        assertFalse(text.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(text.contains("Bearer ***"))
    }

    @Test
    fun `api_key 与 token 赋值形态被抹掉且保留字段名`() {
        val text = DiagnosticLogFiles.redact("{\"api_key\":\"abcdef1234567890\",\"token\":\"zzzz99998888\"}")

        assertFalse(text.contains("abcdef1234567890"))
        assertFalse(text.contains("zzzz99998888"))
        assertTrue(text.contains("api_key"))
    }

    @Test
    fun `普通文本原样保留`() {
        val text = "Scheduled run start: task=fdb8aa38 duration=15139ms"

        assertEquals(text, DiagnosticLogFiles.redact(text))
    }
}
