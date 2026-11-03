package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨环境路径提示。
 *
 * 锁的是我实测踩过 6 次以上的坑：在两个环境之间切的时候，
 * stderr 只说「文件不存在」，不告诉我走错了环境。
 */
class EnvironmentPathHintTest {

    @Test
    fun androidEnvPointingAtLinuxPathSuggestsLinux() {
        val hint = EnvironmentPathHint.hint(
            environment = "android",
            stderr = "cp: can't stat '/workspace/02-Eta源码-eta-src/Eta-3.4.0-src/app': No such file or directory",
        )

        assertTrue(hint != null && hint.contains("environment=\"linux\""))
    }

    @Test
    fun linuxEnvPointingAtAndroidPathSuggestsAndroid() {
        val hint = EnvironmentPathHint.hint(
            environment = "linux",
            stderr = "cat: /data/data/io.github.mangi.eta/shared_prefs/x.xml: No such file or directory",
        )

        assertTrue(hint != null && hint.contains("environment=\"android\""))
    }

    /** 环境与路径匹配时不该乱提示 —— 那只是普通的路径写错了。 */
    @Test
    fun matchingEnvironmentProducesNoHint() {
        assertNull(
            EnvironmentPathHint.hint(
                "android",
                "cat: /data/data/io.github.mangi.eta/nope.txt: No such file or directory",
            ),
        )
        assertNull(
            EnvironmentPathHint.hint(
                "linux",
                "ls: /workspace/nope: No such file or directory",
            ),
        )
    }

    @Test
    fun unrelatedErrorProducesNoHint() {
        assertNull(EnvironmentPathHint.hint("android", "Permission denied"))
        assertNull(EnvironmentPathHint.hint("android", "command not found: foobar"))
        assertNull(EnvironmentPathHint.hint("android", ""))
    }

    @Test
    fun extractsPathFromCpCannotStat() {
        assertEquals(
            "/data/data/io.github.mangi.eta/shared_prefs/eta_scheduled_tasks.xml",
            EnvironmentPathHint.extractMissingPath(
                "cp: cannot stat '/data/data/io.github.mangi.eta/shared_prefs/eta_scheduled_tasks.xml': No such file or directory",
            ),
        )
    }

    @Test
    fun extractsPathFromCantCd() {
        assertEquals(
            "/workspace/02-Eta源码-eta-src",
            EnvironmentPathHint.extractMissingPath("sh: cd: can't cd to /workspace/02-Eta源码-eta-src"),
        )
    }

    @Test
    fun extractsPathFromNoSuchFile() {
        assertEquals(
            "/nonexistent_path_xyz",
            EnvironmentPathHint.extractMissingPath("ls: /nonexistent_path_xyz: No such file or directory"),
        )
    }

    @Test
    fun classifiesRoots() {
        assertTrue(EnvironmentPathHint.isLinuxPath("/workspace/tmp"))
        assertTrue(EnvironmentPathHint.isLinuxPath("/root"))
        assertTrue(EnvironmentPathHint.isAndroidPath("/data/data/x"))
        assertTrue(EnvironmentPathHint.isAndroidPath("/sdcard/Download"))

        // 两套根目录不重叠，这是判断依据成立的前提
        assertTrue(!EnvironmentPathHint.isAndroidPath("/workspace"))
        assertTrue(!EnvironmentPathHint.isLinuxPath("/data/data"))
    }
}
