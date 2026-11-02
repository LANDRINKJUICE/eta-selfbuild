package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

private val assistantSuggestions = listOf(
    "总结屏幕内容",
    "解释当前页面",
    "提取关键信息",
)

private val suggestionEnterEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
internal fun EtaAssistantSuggestions(
    onSuggestionClick: (String) -> Unit,
    colors: EtaVoicePanelColors,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EnterTransition.None,
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(150, easing = FastOutSlowInEasing),
        ) + fadeOut(tween(110)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(10.dp))
            assistantSuggestions.forEachIndexed { index, suggestion ->
                val progress = remember(suggestion) { Animatable(0f) }
                val opacity = remember(suggestion) { Animatable(0f) }
                LaunchedEffect(visible) {
                    if (visible) {
                        progress.snapTo(0f)
                        opacity.snapTo(0f)
                        if (index > 0) delay(index * 40L)
                        coroutineScope {
                            launch {
                                progress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(220, easing = suggestionEnterEasing),
                                )
                            }
                            launch { opacity.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
                        }
                    } else {
                        coroutineScope {
                            launch { progress.animateTo(0f, tween(110)) }
                            launch { opacity.animateTo(0f, tween(110)) }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .graphicsLayer {
                            alpha = opacity.value
                            translationY = (1f - progress.value) * 6.dp.toPx()
                        }
                        .clip(CircleShape)
                        .background(colors.chipBackground)
                        .border(0.75.dp, colors.chipStroke, CircleShape)
                        .clickable(enabled = visible) { onSuggestionClick(suggestion) }
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = suggestion,
                        color = colors.inputSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
        }
    }
}
