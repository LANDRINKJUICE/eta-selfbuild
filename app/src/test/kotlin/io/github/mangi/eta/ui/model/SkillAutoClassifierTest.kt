package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能自动分类的回归测试。
 *
 * 规则是写死的关键词表：分类必须稳定、可预期，用户改过之后不能被下一次刷新覆盖。
 */
class SkillAutoClassifierTest {

    private fun skill(id: String, name: String = id, description: String = "") = SkillItemUi(
        id = id,
        name = name,
        description = description,
        source = "builtin",
        enabled = true,
        installed = true,
        capabilities = emptyList(),
    )

    @Test
    fun `开发类技能归到开发与构建`() {
        assertEquals("开发与构建", SkillAutoClassifier.classify(skill("compose-performance")))
        assertEquals("开发与构建", SkillAutoClassifier.classify(skill("kotlin-api-design")))
    }

    @Test
    fun `系统与设备类归到一起`() {
        assertEquals("系统与设备", SkillAutoClassifier.classify(skill("android-root-hiding")))
        assertEquals("系统与设备", SkillAutoClassifier.classify(skill("android-system-profile")))
    }

    @Test
    fun `技能维护类归到自我维护`() {
        assertEquals("自我维护", SkillAutoClassifier.classify(skill("skill-creator")))
        assertEquals("自我维护", SkillAutoClassifier.classify(skill("self-improving-agent")))
    }

    @Test
    fun `没有关键词的技能落到兜底组`() {
        assertEquals(SkillAutoClassifier.FALLBACK_LABEL, SkillAutoClassifier.classify(skill("zzz-unknown-thing")))
    }

    @Test
    fun `分组结果按规则顺序排列且不丢技能`() {
        val skills = listOf(
            skill("zzz-unknown-thing"),
            skill("compose-performance"),
            skill("android-root-hiding"),
        )

        val grouped = SkillAutoClassifier.group(skills)
        val names = grouped.map { it.first }

        // 开发与构建 在 系统与设备 之前，兜底组最后。
        assertTrue(names.indexOf("开发与构建") < names.indexOf("系统与设备"))
        assertEquals(SkillAutoClassifier.FALLBACK_LABEL, names.last())
        assertEquals(3, grouped.sumOf { it.second.size })
    }

    @Test
    fun `合并时保留用户改过的组名与顺序`() {
        val existing = listOf(
            SkillGroupUi(id = "系统与设备", name = "我的折腾", skillIds = listOf("a"), auto = false),
            SkillGroupUi(id = "开发与构建", name = "开发", skillIds = listOf("b"), auto = false),
        )
        val skills = listOf(skill("a"), skill("b"))

        val merged = SkillAutoClassifier.mergeWithExisting(existing, skills)

        assertEquals(listOf("我的折腾", "开发"), merged.map { it.name })
    }

    @Test
    fun `新技能按规则进入已有组`() {
        val existing = listOf(
            SkillGroupUi(id = "开发与构建", name = "开发与构建", skillIds = listOf("a")),
        )
        val skills = listOf(skill("a"), skill("kotlin-control-flow"))

        val merged = SkillAutoClassifier.mergeWithExisting(existing, skills)

        assertEquals(listOf("a", "kotlin-control-flow"), merged.single().skillIds)
    }

    @Test
    fun `卸载后空组被删掉`() {
        val existing = listOf(
            SkillGroupUi(id = "开发与构建", name = "开发与构建", skillIds = listOf("gone")),
            SkillGroupUi(id = "系统与设备", name = "系统与设备", skillIds = listOf("alive")),
        )
        val skills = listOf(skill("alive"))

        val merged = SkillAutoClassifier.mergeWithExisting(existing, skills)

        assertEquals(listOf("系统与设备"), merged.map { it.id })
    }

    @Test
    fun `早期落盘的 other 图标在合并时自动修正`() {
        // 真机数据就长这样：组名是规则名、auto=false，但 icon 全是 other（字段是后加的）。
        // 不自愈的话界面就是一排黑文件夹（2026-11-04 用户实测反馈）。
        val existing = listOf(
            SkillGroupUi(id = "开发与构建", name = "开发与构建", skillIds = listOf("a"), auto = false, icon = "other"),
            SkillGroupUi(id = "系统与设备", name = "系统与设备", skillIds = listOf("b"), auto = false, icon = "other"),
            SkillGroupUi(id = "自我维护", name = "自我维护", skillIds = listOf("c"), auto = false, icon = "other"),
        )

        val merged = SkillAutoClassifier.mergeWithExisting(
            existing,
            listOf(skill("a"), skill("b"), skill("c")),
        )

        assertEquals(listOf("dev", "system", "self"), merged.map { it.icon })
    }

    @Test
    fun `用户改过组名时仍然按 id 修好图标`() {
        val existing = listOf(
            SkillGroupUi(id = "网络与代理", name = "我的工具箱", skillIds = listOf("a"), auto = false, icon = "other"),
        )

        val merged = SkillAutoClassifier.mergeWithExisting(existing, listOf(skill("a")))

        assertEquals("network", merged.single().icon)
    }

    @Test
    fun `模型新建分组没给合法类别时按组名关键词补图标`() {
        // 模型漏给 / 给了不认识的类别，就按组名里的关键词再兜一次。
        assertEquals("network", SkillAutoClassifier.normalizeIcon("other", "代理工具", "代理工具"))
        assertEquals("doc", SkillAutoClassifier.normalizeIcon("", "写作助手", "写作助手"))
        // 什么都判断不出来的仍然落 other（界面用文件夹图标），但绝不能是空串。
        assertEquals("other", SkillAutoClassifier.normalizeIcon("", "随手一放", "随手一放"))
    }

    @Test
    fun `已经是合法类别的不被覆盖`() {
        assertEquals("doc", SkillAutoClassifier.normalizeIcon("doc", "开发与构建", "开发与构建"))
        assertEquals("media", SkillAutoClassifier.normalizeIcon("media", "随便什么", "随便什么"))
    }
}
