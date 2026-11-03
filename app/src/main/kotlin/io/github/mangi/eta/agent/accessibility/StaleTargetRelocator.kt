package io.github.mangi.eta.agent.accessibility

/**
 * 快照过期后，用稳定指纹在新快照里找回同一个目标。
 *
 * 存在的理由：节点校验失败时模型只能重新观察再点一次，往返是秒级；而重抓一次无障碍快照
 * 只要几十毫秒。但**重抓不是重试的借口** —— 只有「唯一匹配」才返回下标，有歧义就放弃，
 * 交给模型重新观察。2026-11-04 真机上，按旧坐标盲点曾把「删除挂载」点成「添加共享文件夹」，
 * 宁可慢一次，也不要把动作落到别的控件上。
 */
internal object StaleTargetRelocator {
    /**
     * @param targetKey 旧目标的指纹键，见 `IndexedNode.relocationKey()`：
     *   有 `uniqueId` 时形如 `id:<uniqueId>`，否则是 `sem:<windowId|package|class|viewId|text|desc>`。
     * @param candidates 新快照里的 (节点下标, 指纹键) 列表。
     * @param truncated 新快照是否被截断。截断时无法确认唯一性，只接受带 `uniqueId` 的键
     *   （语义指纹在截断的树里可能少掉另一个同文本节点，看起来「唯一」其实不是）。
     * @return 唯一匹配的节点下标；没有匹配或有多个匹配都返回 null。
     */
    fun relocate(
        targetKey: String,
        candidates: List<Pair<Int, String>>,
        truncated: Boolean,
    ): Int? {
        if (targetKey.isBlank()) return null
        if (truncated && !targetKey.startsWith(UNIQUE_ID_PREFIX)) return null
        val hits = candidates.filter { it.second == targetKey }
        return if (hits.size == 1) hits.single().first else null
    }

    const val UNIQUE_ID_PREFIX = "id:"
    const val SEMANTIC_PREFIX = "sem:"
}
