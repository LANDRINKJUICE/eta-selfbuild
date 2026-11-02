package io.github.mangi.eta.agent.runtime

import java.util.concurrent.atomic.AtomicReference

/**
 * 记录「最近一次用户运行属于哪个会话」，供**执行中通知**挂载回复入口。
 *
 * 为什么用单例就够：`AgentRuntimeService` 与 `AgentExecutionService` 都跑在主进程
 * （Manifest 里两者都没指定 `android:process`），所以进程内共享即可，不需要跨进程同步。
 *
 * 为什么需要它：执行中通知（流体云那条）是**一个通知位服务所有运行**的，
 * 它自己不知道当前跑的是哪个会话；而回复要落到对应会话，就必须有人把会话 id 记下来。
 */
internal object ActiveConversationTracker {
    private val latest = AtomicReference<String?>(null)
    private val latestTitle = AtomicReference<String?>(null)

    /**
     * 运行开始时调用；空值忽略（外部来源的运行没有 Eta 会话）。
     *
     * @param title 会话标题，用于通知标题（用户要求显示"哪个会话在跑"，而不是笼统的"Eta 在运行"）。
     */
    fun remember(conversationId: String?, title: String? = null) {
        if (!conversationId.isNullOrBlank()) {
            latest.set(conversationId)
            latestTitle.set(title?.takeIf { it.isNotBlank() })
        }
    }

    fun latest(): String? = latest.get()

    fun latestTitle(): String? = latestTitle.get()
}
