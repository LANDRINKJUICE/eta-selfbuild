package io.github.mangi.eta.ui.screens.style

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.style.SpeechStyle
import io.github.mangi.eta.agent.style.SpeechStyleStore
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.screens.accounts.LocalAvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SidePadding = 16.dp
private val CardGap = 12.dp
private val CardCorner = 20.dp
private val CardAspect = 0.78f

/** 卡片底色：沿用角色卡那种「深色渐变 + 白字」的观感，按索引轮换。 */
private val CARD_GRADIENTS = listOf(
    Color(0xFF66BB6A) to Color(0xFF1B5E20),
    Color(0xFF9575CD) to Color(0xFF311B92),
    Color(0xFFFF8A65) to Color(0xFFBF360C),
    Color(0xFF4FC3F7) to Color(0xFF01579B),
    Color(0xFFF06292) to Color(0xFF880E4F),
    Color(0xFF4DB6AC) to Color(0xFF004D40),
)

/**
 * 通知角色：语气角色库，卡片式布局（对齐「对话角色」那页的观感）。
 *
 * 交互约定：
 * - **不选任何角色 = 默认语气**，所以没有「自然」这种占位卡
 * - 点卡片 = 选中；再点一次 = 取消选择
 * - 长按卡片 = 编辑（改名 / 改描述 / 换头像），编辑弹窗里可删除
 * - 末尾「新建角色」卡片；下方推荐列表点一下即可加进来
 */
@Composable
internal fun SpeechStyleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var styles by remember { mutableStateOf(SpeechStyleStore.visible()) }
    var selectedId by remember { mutableStateOf(SpeechStyleStore.selectedId()) }
    var editing by remember { mutableStateOf<SpeechStyle?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftInstruction by remember { mutableStateOf("") }
    var draftAvatar by remember { mutableStateOf<String?>(null) }
    var draftTags by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val target = editing
        if (uri != null && target != null) {
            LocalAvatarStore.save(context, uri, "style_${target.id}")?.let { draftAvatar = it }
        }
    }

    fun refresh() {
        styles = SpeechStyleStore.visible()
        selectedId = SpeechStyleStore.selectedId()
    }

    fun openEditor(style: SpeechStyle?) {
        editing = style ?: SpeechStyle(
            id = SpeechStyle.newId(System.currentTimeMillis().toString()),
            name = "",
            instruction = "",
        )
        draftName = style?.name.orEmpty()
        draftInstruction = style?.instruction.orEmpty()
        draftAvatar = style?.avatarPath
        draftTags = style?.tags.orEmpty().joinToString("、")
        error = null
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.speech_style_title),
        onBack = onBack,
    ) {
        item(key = "role-hint") {
            EtaPreferenceGroupTitle(stringResource(R.string.speech_style_hint))
        }
        item(key = "role-grid") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SidePadding),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                // 两列网格：用 Row + weight 而不是 LazyVerticalGrid，
                // 因为整页已经在一个 LazyColumn 里，嵌套可滚动容器会打架。
                val cells = styles.size + 1 // 末尾留一个「新建角色」
                var index = 0
                while (index < cells) {
                    val first = styles.getOrNull(index)
                    val second = styles.getOrNull(index + 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                        if (first != null) {
                            RoleCard(
                                style = first,
                                index = index,
                                selected = first.id == selectedId,
                                onClick = {
                                    SpeechStyleStore.select(
                                        context,
                                        if (first.id == selectedId) null else first.id,
                                    )
                                    refresh()
                                },
                                onLongClick = { openEditor(first) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            AddRoleCard(
                                onClick = { openEditor(null) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (second != null) {
                            RoleCard(
                                style = second,
                                index = index + 1,
                                selected = second.id == selectedId,
                                onClick = {
                                    SpeechStyleStore.select(
                                        context,
                                        if (second.id == selectedId) null else second.id,
                                    )
                                    refresh()
                                },
                                onLongClick = { openEditor(second) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            // 占位，让最后一张卡保持半栏宽度。
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    index += 2
                }
            }
        }

        item(key = "role-templates-title") {
            EtaPreferenceGroupTitle(
                stringResource(R.string.speech_style_templates),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item(key = "role-templates") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = SidePadding)) {
                SpeechStyle.TEMPLATES.forEachIndexed { index, (name, instruction) ->
                    if (index > 0) EtaPreferenceDivider()
                    EtaPreference(
                        title = name,
                        summary = instruction,
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Palette,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        endActions = {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            SpeechStyleStore.addTemplate(context, name, instruction)
                            refresh()
                        },
                    )
                }
            }
        }

        item(key = "role-note") {
            Text(
                text = stringResource(R.string.speech_style_note),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = SidePadding, vertical = 16.dp),
            )
        }
    }

    editing?.let { style ->
        EtaWindowDialog(
            show = true,
            title = stringResource(
                if (style.name.isBlank()) R.string.speech_style_add else R.string.speech_style_edit,
            ),
            summary = stringResource(R.string.speech_style_editor_hint),
            onDismissRequest = { editing = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                        .padding(horizontal = SidePadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoleCardFace(avatarPath = draftAvatar, name = draftName, size = 44.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = stringResource(R.string.speech_style_pick_avatar),
                        style = MiuixTheme.textStyles.body1,
                        color = EtaPreferenceColors.Blue,
                    )
                    if (draftAvatar != null) {
                        Spacer(Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.speech_style_avatar_remove),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable {
                                LocalAvatarStore.clear(context, "style_${style.id}")
                                draftAvatar = null
                            },
                        )
                    }
                }
                TextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SidePadding),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = draftInstruction,
                    onValueChange = { draftInstruction = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SidePadding),
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = draftTags,
                    onValueChange = { draftTags = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SidePadding),
                )
                Text(
                    text = stringResource(R.string.speech_style_tags_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = SidePadding, vertical = 4.dp),
                )
                error?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = SidePadding, vertical = 4.dp),
                    )
                }
                MiuixDialogActions(
                    confirmText = stringResource(R.string.speech_style_save),
                    onCancel = { editing = null },
                    onConfirm = {
                        val failure = SpeechStyleStore.upsert(
                            context,
                            style.copy(
                                name = draftName,
                                instruction = draftInstruction,
                                avatarPath = draftAvatar,
                                tags = SpeechStyle.parseTags(draftTags),
                            ),
                        )
                        if (failure == null) {
                            editing = null
                            refresh()
                        } else {
                            error = failure
                        }
                    },
                )
                if (styles.any { it.id == style.id }) {
                    Text(
                        text = stringResource(R.string.speech_style_delete),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                LocalAvatarStore.clear(context, "style_${style.id}")
                                SpeechStyleStore.remove(context, style.id)
                                editing = null
                                refresh()
                            }
                            .padding(horizontal = SidePadding, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** 一张通知角色卡：渐变底 + 大字首字（或头像）+ 名字 + 选中勾。 */
@Composable
private fun RoleCard(
    style: SpeechStyle,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (start, end) = CARD_GRADIENTS[index % CARD_GRADIENTS.size]
    Box(
        modifier = modifier
            .aspectRatio(CardAspect)
            .squircleClip(CardCorner)
            .background(Brush.verticalGradient(listOf(start, end)))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.35f),
                    ),
                ),
        )
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            RoleCardFace(avatarPath = style.avatarPath, name = style.name, size = 84.dp)
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = start,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = style.name,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (style.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    style.tags.take(SpeechStyle.MAX_TAGS).forEach { tag -> RoleTag(tag) }
                }
            }
        }
    }
}

/** 末尾的「新建角色」卡：浅色描边占位，弱于真实卡片。 */
@Composable
private fun AddRoleCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .aspectRatio(CardAspect)
            .squircleClip(CardCorner)
            .background(colors.surfaceContainer)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(colors.primary.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = colors.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.speech_style_add),
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/** 卡片正面的「头像」：有图显示图，没有显示名字首字。 */
@Composable
private fun RoleCardFace(avatarPath: String?, name: String, size: androidx.compose.ui.unit.Dp) {
    var bitmap by remember(avatarPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatarPath) {
        bitmap = if (avatarPath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(avatarPath)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Text(
            text = name.trim().take(1).ifBlank { "语" },
            color = Color.White.copy(alpha = 0.92f),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.62f).sp,
        )
    }
}

/** 卡片底部的小标签：白底半透明胶囊，和对话角色卡一致。 */
@Composable
private fun RoleTag(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = Color.White.copy(alpha = 0.92f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
