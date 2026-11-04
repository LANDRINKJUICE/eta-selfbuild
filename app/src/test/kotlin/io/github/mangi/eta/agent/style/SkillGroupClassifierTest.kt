package io.github.mangi.eta.agent.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型分类结果的解析。
 *
 * 模型输出不可信：可能带解释、带代码块、多给几条、字段乱填。
 * 这里锁住「能救回来的救，救不回来的丢掉”，绝不把奇怪内容直接当组名用。
 */
class SkillGroupClassifierTest {

    private val candidates = listOf(
        SkillGroupClassifier.Candidate("bilibili-download", "B站下载", "下载视频"),
        SkillGroupClassifier.Candidate("weibo-helper", "微博助手", "发微博"),
    )

    @Test
    fun `正常 JSON 解析出分组与图标`() {
        val raw = """[{"id":"bilibili-download","group":"视频下载","icon":"media"}]"""

        val result = SkillGroupClassifier.parse(raw, candidates)

        assertEquals("视频下载", result["bilibili-download"]?.group)
        assertEquals("media", result["bilibili-download"]?.icon)
    }

    @Test
    fun `前后有多余文字也能抠出数组`() {
        val raw = "好的，结果如下：\n[{\"id\":\"weibo-helper\",\"group\":\"社交\",\"icon\":\"network\"}]\n希望有帮助"

        val result = SkillGroupClassifier.parse(raw, candidates)

        assertEquals("社交", result["weibo-helper"]?.group)
    }

    @Test
    fun `不在候选里的 id 被丢弃`() {
        val raw = """[{"id":"unknown-skill","group":"随便","icon":"dev"}]"""

        assertTrue(SkillGroupClassifier.parse(raw, candidates).isEmpty())
    }

    @Test
    fun `未知图标类别回落 other`() {
        val raw = """[{"id":"bilibili-download","group":"视频下载","icon":"weird"}]"""

        assertEquals("other", SkillGroupClassifier.parse(raw, candidates)["bilibili-download"]?.icon)
    }

    @Test
    fun `组名里的标点被清掉且限长`() {
        val raw = """[{"id":"bilibili-download","group":"视频·下载！！","icon":"media"}]"""

        val group = SkillGroupClassifier.parse(raw, candidates)["bilibili-download"]?.group

        assertEquals("视频下载", group)
    }

    @Test
    fun `不是 JSON 就当没拿到`() {
        assertTrue(SkillGroupClassifier.parse("抱歉我无法分类", candidates).isEmpty())
    }
}
