package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 管道收尾的退出码失真提示。
 *
 * 锁的是同一轮里犯了 4 次的错：`cmd | tail` 的退出码恒为 0，把失败读成成功。
 *
 * 同时锁「别太吵」：只有命令确实在**用退出码判断成败**时才提醒，
 * 纯看输出的 `gh api ... | tail -5` 不该被打扰。
 */
class PipeExitCodeHintTest {

    /** 真实踩过的三种写法，都必须被认出来。 */
    @Test
    fun realMistakesAreFlagged() {
        // ① 重试循环里 if 判断管道结果
        assertNotNull(PipeExitCodeHint.hint("if sh push-delta.sh . repo main sha f.kt | tail -12; then break; fi"))
        // ② && 串联
        assertNotNull(PipeExitCodeHint.hint("sh build.sh && echo done | tail -3"))
        // ③ 命令替换里取 sha
        assertNotNull(PipeExitCodeHint.hint("TREE=$(gh api repos/x/git/trees --input - | tr -d ' ')"))
    }

    @Test
    fun otherSwallowingCommandsFlagged() {
        assertNotNull(PipeExitCodeHint.hint("if cmd | head -5; then echo ok; fi"))
        assertNotNull(PipeExitCodeHint.hint("if cmd | wc -l; then echo ok; fi"))
    }

    @Test
    fun pathPrefixIsHandled() {
        assertNotNull(PipeExitCodeHint.hint("if cmd | /usr/bin/tail -n 5; then :; fi"))
    }

    /** 纯看输出、不判断成败 —— 不该提醒，否则每次都念一遍就成噪音。 */
    @Test
    fun displayOnlyUsageIsNotFlagged() {
        assertNull(PipeExitCodeHint.hint("gh api repos/x/commits/main | tail -5"))
        assertNull(PipeExitCodeHint.hint("cat log.txt | head -20"))
        assertNull(PipeExitCodeHint.hint("ls -la | wc -l"))
    }

    /** `| grep -q` 本来就是在用退出码判断，不是问题。 */
    @Test
    fun grepTailIsNotFlagged() {
        assertNull(PipeExitCodeHint.hint("if cmd | grep -q pattern; then :; fi"))
    }

    @Test
    fun noPipeProducesNoHint() {
        assertNull(PipeExitCodeHint.hint("ls -la"))
        assertNull(PipeExitCodeHint.hint("out=$(cmd 2>&1); rc=$?"))
    }

    /** 只看最后一段：末尾是 grep -q 时退出码有意义。 */
    @Test
    fun onlyLastSegmentMatters() {
        assertNull(PipeExitCodeHint.hint("if cmd | tail -5 | grep -q OK; then :; fi"))
        assertNotNull(PipeExitCodeHint.hint("if cmd | grep OK | tail -5; then :; fi"))
    }
}
