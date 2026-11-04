package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentSkillsUiState(
    val skills: List<SkillItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val busySkillId: String? = null,
    val replacement: SkillReplacementUi? = null,
    val notice: SkillNoticeUi? = null,
    /**
     * 技能分组（顺序即显示顺序）。用户可折叠、改名、拖动；
     * 首次进入按 [SkillAutoClassifier] 自动分好。
     */
    val groups: List<SkillGroupUi> = emptyList(),
)

@Immutable
data class SkillReplacementUi(
    val id: String,
    val name: String,
)

@Immutable
data class SkillNoticeUi(
    val id: Long,
    val title: String,
    val message: String,
    val isError: Boolean,
)

@Immutable
data class SkillItemUi(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val enabled: Boolean,
    val installed: Boolean,
    val capabilities: List<String>,
)

internal val SkillItemUi.canDeleteUserSkill: Boolean
    get() = installed && source == "user"
