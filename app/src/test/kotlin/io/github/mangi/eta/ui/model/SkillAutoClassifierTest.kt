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
}
