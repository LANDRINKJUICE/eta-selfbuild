package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 身份注入的回归测试。
 *
 * 用户 2026-11-04 反馈：「你经常不知道你是 Eta、不了解装的是当前版本」。
 * 根因是系统提示里没有任何自我状态，模型只能从历史消息里翻旧版本号。
 * 现在由 [AgentRuntimeIdentity] 在运行时注入，这两个用例锁住「版本和提交号一定在里面」。
 */
class AgentRuntimeIdentityTest {

    private fun sample(versionName: String) = AgentRuntimeIdentity.format(
        versionName = versionName,
        versionCode = 2026103101,
        updatedAtMillis = 1_762_230_000_000L,
        device = "realme RMX3700",
        sdkInt = 36,
        androidRelease = "16",
    )

    @Test
    fun `身份段写明是 Eta 并带上版本与提交号`() {
        val text = sample("3.4.0+7a71443")

        assertTrue(text.contains("你是 Eta"))
        assertTrue(text.contains("3.4.0+7a71443"))
        assertTrue(text.contains("2026103101"))
        assertTrue(text.contains("7a71443"))
        assertTrue(text.contains("realme RMX3700"))
        assertTrue(text.contains("以这一行为准"))
    }

    @Test
    fun `没有提交号时明确说明而不是留空`() {
        val text = sample("3.4.0-debug")

        assertTrue(text.contains("没有带提交号"))
        assertFalse(text.contains("源码提交号（"))
    }
}
