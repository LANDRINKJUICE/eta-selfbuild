package io.github.mangi.eta.agent.terminal

/**
 * 管道收尾导致退出码失真的提示。
 *
 * 为什么需要它（2026-11-03，同一轮里犯了 4 次）：`cmd | tail -n` 的退出码是 `tail` 的，
 * 永远是 0。于是「命令失败」被读成「成功」：
 *   ① 推送日志 `| tail` 吞输出，以为推上去了；
 *   ② 重试循环 `if sh ... | tail; then break` 第一次就 break；
 *   ③ 建树 `--input - <<EOF | tr` 把 JSON 响应压平当 sha。
 * 三次都写进了记忆，照样再犯 —— 说明靠记忆不够，得在返回值里直接点出来。
 *
 * 只对「末尾一段几乎必然成功」的命令提示（tail/head/wc/tee/sort/uniq）：
 * 这类命令放在管道末尾时，退出码必然失真，而 `| grep -q` 这类本来就是在用它的退出码，
 * 不该打扰。
 */
internal object PipeExitCodeHint {

    /** 放在管道末尾时会让退出码失真的命令。 */
    private val SWALLOWING_TAIL_COMMANDS = listOf("tail", "head", "wc", "tee", "sort", "uniq", "tr", "cut")

    /**
     * 「用退出码判断成败」的迹象。
     *
     * 只有命令里出现这些，才说明这个退出码会被当成结论用 —— 也才值得提醒。
     * 否则 `gh api ... | tail -5` 这种纯看输出的用法每次都被念一遍，就成噪音了。
     */
    private val JUDGEMENT_MARKERS = listOf("if ", "&&", "||", "; then", "while ", "$(", "set -e")

    /**
     * @param command 要执行的 shell 命令
     * @return 需要提醒时返回一行文字；否则 null
     */
    internal fun hint(command: String): String? {
        if (!command.contains('|')) return null
        if (JUDGEMENT_MARKERS.none { command.contains(it) }) return null
        // 取最后一个管道段的第一段文字（跳过前面的 env 赋值、sudo 之类）
        val last = command.split('|').lastOrNull()?.trim() ?: return null
        val head = last.split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/') ?: return null
        if (head !in SWALLOWING_TAIL_COMMANDS) return null
        return "命令以 `| $head` 收尾且用于判断，退出码是 $head 的（通常恒为 0），结论会失真。" +
            "要看真实结果：用 `out=$(cmd 2>&1); rc=$?`，或开头加 `set -o pipefail`。"
    }
}
