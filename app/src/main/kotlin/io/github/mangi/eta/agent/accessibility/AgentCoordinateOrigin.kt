package io.github.mangi.eta.agent.accessibility

/**
 * 计算一次无障碍快照的坐标原点。
 *
 * 背景（2026-11-05 真机实测）：`AccessibilityNodeInfo.getBoundsInScreen()` 返回的是**全局屏幕坐标**。
 * 主屏与虚拟屏并存时，系统会把两个 display 拼在一块画布上布局，后创建的那个被排到右边，
 * 于是它上面所有节点的 x 都整体 +一个屏宽（实测虚拟屏 id=4 期间恒定 +1240，正好等于屏宽），
 * 而 `screen` 报的仍是单屏尺寸。结果坐标溢出屏幕范围，gui_macro 的第二步行滚动判定直接
 * `INVALID_NODE_BOUNDS`，整个宏中断。
 *
 * 为什么判据要这么保守：root 的 left/top **并不总等于** display 原点。悬浮窗、分屏、小窗的
 * root 都落在屏幕内部，若无条件减掉 root 原点，坐标会从「相对屏幕」变成「相对窗口」，点击就偏了。
 * 只有当 root 的起点已经 >= 屏幕尺寸时，它才只可能是被排到了下一个 display 上。
 */
internal object AgentCoordinateOrigin {

    /**
     * @param rootLeft root 窗口在全局坐标系里的左边界
     * @param rootTop root 窗口在全局坐标系里的上边界
     * @param displayWidth 目标 display 的宽度；拿不到时传 null（此时不做任何偏移）
     * @param displayHeight 目标 display 的高度；拿不到时传 null
     * @return 需要从节点坐标里减去的 (originX, originY)
     */
    fun resolve(
        rootLeft: Int,
        rootTop: Int,
        displayWidth: Int?,
        displayHeight: Int?,
    ): Pair<Int, Int> {
        val width = displayWidth?.takeIf { it > 0 } ?: return 0 to 0
        val height = displayHeight?.takeIf { it > 0 } ?: return 0 to 0
        val originX = if (rootLeft >= width) rootLeft else 0
        val originY = if (rootTop >= height) rootTop else 0
        return originX to originY
    }
}
