package io.github.mangi.eta.agent.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知风格的校验、种子与模板。
 *
 * 几条硬规则：
 * - 名字与描述都要限长（会进系统提示）
 * - 描述**允许为空**：空串表示「不加语气要求」，也就是默认语气
 * - 预置种子和推荐模板的 id / 名字不能撞
 */
class SpeechStyleTest {

    @Test
    fun `种子风格 id 与名字都不重复`() {
        val ids = SpeechStyle.SEEDS.map { it.id }
        val names = SpeechStyle.SEEDS.map { it.name }

        assertEquals(ids.size, ids.distinct().size)
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `默认风格不带任何语气指令`() {
        val natural = SpeechStyle.SEEDS.first { it.id == SpeechStyle.NATURAL_ID }

        assertEquals("", natural.instruction)
    }

    @Test
    fun `描述可以为空表示默认语气`() {
        assertNull(SpeechStyle.validate("随便", ""))
    }

    @Test
    fun `名字与描述都要限长`() {
        assertTrue(SpeechStyle.validate("", "随便说点")!!.contains("名字"))
        assertTrue(
            SpeechStyle.validate("x".repeat(SpeechStyle.MAX_NAME_CHARS + 1), "")!!.contains("名字"),
        )
        assertTrue(
            SpeechStyle.validate(
                "正常",
                "y".repeat(SpeechStyle.MAX_INSTRUCTION_CHARS + 1),
            )!!.contains("描述"),
        )
    }

    @Test
    fun `自建 id 打上 style 前缀且不撞种子`() {
        val custom = SpeechStyle.newId("邻家妹妹")

        assertTrue(custom.startsWith("style-"))
        assertTrue(SpeechStyle.SEEDS.none { it.id == custom })
    }

    @Test
    fun `推荐模板名字不重复且都非空`() {
        val names = SpeechStyle.TEMPLATES.map { it.first }

        assertEquals(names.size, names.distinct().size)
        assertTrue(SpeechStyle.TEMPLATES.all { it.second.isNotBlank() })
        assertTrue(SpeechStyle.TEMPLATES.isNotEmpty())
    }
}
