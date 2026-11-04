package io.github.mangi.eta.agent.terminal

/**
 * 把自动唤醒跑完的结果压成一句直白的话，给通知的折叠态用。
 *
 * 为什么需要：唤醒 run 的输出经常是模型写的长句（「模型接口返回 HTTP 503 已重试 3 次仍未恢复，
 * 已保留此前完成的工具结果。」），直接塞进通知正文会被系统截断，还看不出重点。
 * 折叠态只要说清「哪一步失败了、大概为什么」，完整内容留在展开态和会话里。
 */
internal object BackgroundCommandSummary {
    /** 折叠态正文字数上限：手机通知栏一行大概就这么多。 */
    internal const val SHORT_CHARS = 24

    /** 常见失败原因压成固定短语；认不出就截断原文。 */
    internal fun shortReason(summary: String): String {
        val text = summary.trim().replace('\n', ' ')
        if (text.isEmpty()) return ""
        return when {
            text.contains("503") || text.contains("不可用") -> "模型接口暂时不可用"
            text.contains("429") || text.contains("限流") -> "模型接口被限流"
            text.contains("401") || text.contains("鉴权") || text.contains("无效") -> "模型接口鉴权失败"
            text.contains("超时") -> "执行超时"
            text.contains("上下文") -> "上下文超长"
            text.contains("无法启动前台服务") -> "无法启动前台服务"
            text.length <= SHORT_CHARS -> text
            else -> text.take(SHORT_CHARS) + "…"
        }
    }
}
