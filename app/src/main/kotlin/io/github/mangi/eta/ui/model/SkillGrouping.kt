package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/**
 * 技能分组：用户看到的一个可折叠分区。
 *
 * @property name 显示名，用户可以改
 * @property skillIds 组内技能，顺序就是显示顺序
 * @property collapsed 折叠状态也持久化：用户收起来的分组下次进来还是收起的
 */
@Immutable
data class SkillGroupUi(
    val id: String,
    val name: String,
    val skillIds: List<String>,
    val collapsed: Boolean = false,
    /** 自动分类生成的默认分组：用户改过名字就变 false，方便界面标一个「自动」。 */
    val auto: Boolean = true,
    /** 图标类别（dev/system/network/media/doc/self/other），模型新建的分组也带图标。 */
    val icon: String = "other",
)

/**
 * 技能自动分类。
 *
 * 为什么按关键词而不是按来源分：现在页面只有「内置 / 用户」两桶，装到二十几个技能时
 * 找不到东西 —— 用户原话「太死板了太冗长」。按**做什么**分组更符合检索习惯。
 *
 * 规则刻意写死在这里（而不是让模型生成）：分类要稳定、可预期、离线可用，
 * 而且用户改过之后不该被下一次自动分类覆盖（见 [mergeWithExisting]）。
 */
internal object SkillAutoClassifier {

    private data class Rule(val label: String, val icon: String, val keywords: List<String>)

    /**
     * 顺序就是默认的显示顺序：越靠前的组越常用。
     * 关键词匹配 id + 名称 + 描述（全部小写），命中任意一个就算。
     */
    private val RULES = listOf(
        Rule(
            "Eta 自身",
            "self",
            listOf("eta", "自改", "云端构建", "eta-cloud-build", "eta-development", "handoff", "交接"),
        ),
        Rule(
            "开发与构建",
            "dev",
            listOf(
                "kotlin", "compose", "gradle", "架构", "architecture", "viewmodel", "data-layer",
                "testing", "测试", "benchmark", "r8", "proguard", "agp", "navigation",
                "api 设计", "api-design", "coil", "glance", "android-cli",
            ),
        ),
        Rule(
            "逆向与包分析",
            "dev",
            listOf("apk", "jadx", "apktool", "smali", "反编译", "逆向", "obfuscation", "混淆", "reverse"),
        ),
        Rule(
            "系统与设备",
            "system",
            listOf(
                "root", "magisk", "kernelsu", "kernel", "模块", "module", "权限", "permissions",
                "无障碍", "accessibility", "性能", "performance", "电池", "battery", "coloros",
                "realme", "系统", "system", "调优", "tuning", "profiler", "anr", "恶意",
            ),
        ),
        Rule(
            "网络与代理",
            "network",
            listOf("proxy", "代理", "sing-box", "mihomo", "clash", "vpn", "mirror", "镜像", "web-search", "搜索"),
        ),
        Rule(
            "前端与 Web",
            "dev",
            listOf("react", "next.js", "vercel", "web", "css", "html", "设计", "design", "ui-ux", "ui", "tailwind"),
        ),
        Rule(
            "写作与文档",
            "doc",
            listOf("文档", "写作", "readme", "writing", "technical-writer", "humanizer", "guidelines", "文案"),
        ),
        Rule(
            "自我维护",
            "self",
            listOf(
                "self", "skill-creator", "skill-installer", "skill-maintenance", "find-skills",
                "learning", "evaluation", "技能", "记忆", "work-hygiene", "quick-ack", "user-rules",
                "self-improving", "adaptive",
            ),
        ),
    )

    /** 兜底组名：什么关键词都没命中的放这里，避免技能凭空消失。 */
    const val FALLBACK_LABEL = "其他"

    /** 某个组名对应的图标类别；未知组名一律 other（界面回落文件夹图标）。 */
    fun iconOf(label: String): String =
        RULES.firstOrNull { it.label == label }?.icon ?: "other"

    /** 单条技能归到哪个组。 */
    fun classify(skill: SkillItemUi): String {
        val haystack = buildString {
            append(skill.id).append(' ')
            append(skill.name).append(' ')
            append(skill.description)
        }.lowercase()
        return RULES.firstOrNull { rule -> rule.keywords.any { haystack.contains(it) } }?.label
            ?: FALLBACK_LABEL
    }

    /**
     * 把技能按规则分组。
     *
     * @return 组名 → 技能 id 列表；组顺序按 [RULES] 定义，兜底组永远在最后
     */
    fun group(skills: List<SkillItemUi>): List<Pair<String, List<String>>> {
        val buckets = linkedMapOf<String, MutableList<String>>()
        skills.forEach { skill ->
            val label = classify(skill)
            buckets.getOrPut(label) { mutableListOf() } += skill.id
        }
        val ordered = RULES.mapNotNull { rule -> buckets.remove(rule.label)?.let { rule.label to it } }
        val rest = buckets.map { (label, ids) -> label to ids }
        return ordered + rest
    }

    /**
     * 合并自动分类与用户已有的分组。
     *
     * 三条规矩：
     * 1. 用户改过名字/拖动过的分组**保留**（顺序和名字都不动）；
     * 2. 新装的技能按规则塞进对应组；已有技能从原组消失（卸载）就移出；
     * 3. 组里最后一个技能也没了就删掉这个组。
     */
    fun mergeWithExisting(
        existing: List<SkillGroupUi>,
        skills: List<SkillItemUi>,
    ): List<SkillGroupUi> {
        val installedIds = skills.map { it.id }.toSet()
        val taken = existing.flatMap { it.skillIds }.toSet()
        val result = existing.mapNotNull { group ->
            val kept = group.skillIds.filter { it in installedIds }
            if (kept.isEmpty()) null else group.copy(skillIds = kept)
        }.toMutableList()
        // 新技能（或之前没被任何组收录的）按规则就位。
        val pending = skills.filter { it.id !in taken }
        pending.forEach { skill ->
            val label = classify(skill)
            val index = result.indexOfFirst { it.id == label }
            if (index >= 0) {
                result[index] = result[index].copy(skillIds = result[index].skillIds + skill.id)
            } else {
                result += SkillGroupUi(
                    id = label,
                    name = label,
                    skillIds = listOf(skill.id),
                    icon = iconOf(label),
                )
            }
        }
        // 自动组在前（按 RULES 顺序），用户自建组按用户顺序排在后面。
        val autoOrder = RULES.map { it.label }
        return result.sortedBy { group ->
            if (group.auto) autoOrder.indexOf(group.id).takeIf { it >= 0 } ?: autoOrder.size else autoOrder.size + 1
        }
    }
}
