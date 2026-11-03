package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 失败码分类的纯 JVM 测试。
 *
 * 这些用例全部来自 2026-11-04 真机 ERRORS.md 里真实出现过的失败：
 * 之前它们一律记成 `failed`，翻日志完全无法定位。
 */
class TerminalExitClassifierTest {

    @Test
    fun `成功返回 ok`() {
        assertEquals("ok", TerminalExitClassifier.classify(0, false, ""))
    }

    @Test
    fun `超时优先于退出码`() {
        assertEquals("timeout", TerminalExitClassifier.classify(0, true, ""))
        assertEquals("timeout", TerminalExitClassifier.classify(-2, false, ""))
    }

    @Test
    fun `退出码 127 是命令不存在`() {
        assertEquals("command_not_found", TerminalExitClassifier.classify(127, false, ""))
    }

    @Test
    fun `退出码 126 是权限不足`() {
        assertEquals("permission_denied", TerminalExitClassifier.classify(126, false, ""))
    }

    @Test
    fun `只读文件系统从 stderr 识别`() {
        val stderr = "mkdir: can't create directory '/workspace/': Read-only file system"
        assertEquals("read_only_fs", TerminalExitClassifier.classify(1, false, stderr))
    }

    @Test
    fun `cd 到不存在目录识别为路径不存在`() {
        val stderr = "ash: cd: line 13: can't cd to /workspace/foo: No such file or directory"
        assertEquals("no_such_path", TerminalExitClassifier.classify(1, false, stderr))
    }

    @Test
    fun `permission denied 从 stderr 识别`() {
        val stderr = "sh: /data/local/tmp/x.sh: Permission denied"
        assertEquals("permission_denied", TerminalExitClassifier.classify(1, false, stderr))
    }

    @Test
    fun `其他退出码保留原始码`() {
        assertEquals("exit_2", TerminalExitClassifier.classify(2, false, "usage error"))
    }

    @Test
    fun `stderr 大小写不敏感`() {
        assertEquals("read_only_fs", TerminalExitClassifier.classify(1, false, "READ-ONLY FILE SYSTEM"))
    }
}
