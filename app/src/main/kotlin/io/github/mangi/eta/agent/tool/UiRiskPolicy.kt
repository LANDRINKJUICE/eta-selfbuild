package io.github.mangi.eta.agent.tool

import java.util.Locale

/**
 * 界面动作的风险闸门：**点到不可逆的东西**先确认，别指望我每次判断都对。
 *
 * 为什么单独一条：`DangerousOperationPolicy` 只看命令字符串（`fastboot flash`、`dd of=` 那种变砖级），
 * 管不到「点错一行『删除』」这类误操作。2026-11-05 的代码审查就抓到过：宏里按文字找节点，
 * 命中多个时会点到第一行 —— 屏幕上五行都叫「删除」的时候，点错就是删错数据。
 *
 * **刻意做窄**：只收「会丢用户数据 / 会动钱 / 不可恢复」的词。
 * 用户对误拦很敏感（2026-11-03 因为 `ls /system` 被拦专门提过），
 * 所以「清除筛选」「移除标签」这种可恢复动作不进表；
 * 宁可漏拦，也不要制造无意义的确认框。命中后走的是现成的确认卡片（超时按否决）。
 */
internal object UiRiskPolicy {

    /** 会丢数据、会动钱、或不可恢复的界面文案。全部小写比较。 */
    private val RISKY_LABELS = listOf(
        // 数据/账号
        "删除", "永久删除", "彻底删除", "清空", "格式化", "恢复出厂", "重置手机", "抹掉",
        "注销账号", "退出登录", "解绑", "卸载",
        // 钱
        "支付", "付款", "立即支付", "确认支付", "转账", "汇款", "立即购买", "确认购买", "订阅", "充值",
        // 英文
        "delete", "erase", "format", "factory reset", "wipe", "uninstall", "sign out", "log out",
        "pay", "transfer", "purchase", "subscribe", "checkout",
    )

    /**
     * 状态文案前缀：以这些开头的多半是「结果提示」而不是按钮（「已删除 3 项」「正在删除」），
     * 拦它只会让用户莫名其妙地看到确认框。
     */
    private val STATUS_PREFIXES = listOf(
        "已", "正在", "成功", "共", "发现", "没有",
        "deleted", "deleting", "removed", "removing", "are you", "已取消",
    )

    /** 中文按字算、西文按字符算，阈值分开：否则 `DELETE ACCOUNT` 会被长度上限挡掉。 */
    private fun lengthCap(label: String): Int = if (label.any { it.code > 127 }) 8 else 32

    /**
     * 判定一次界面动作是否需要对用户确认。
     *
     * @param labels 目标节点上能看到的文案（text、content-desc 等）。
     * @return 命中时返回给用户看的原因；安全返回 null。
     */
    fun evaluate(labels: List<String>): String? {
        val normalized = labels
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toList()
        if (normalized.isEmpty()) return null
        normalized.forEach { label ->
            if (STATUS_PREFIXES.any { label.startsWith(it) }) return@forEach
            RISKY_LABELS.forEach { risky ->
                if (label == risky) return "要点击的控件是「${risky}」，属于不可逆或涉及资金的操作"
                // 包含匹配只认「像按钮的短文案」：长说明里出现「删除」不算。
                if (label.length <= lengthCap(label) && label.contains(risky)) {
                    return "要点击的控件文案里含「${risky}」，属于不可逆或涉及资金的操作"
                }
            }
        }
        return null
    }
}
