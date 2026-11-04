package io.github.mangi.eta.agent.github

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub 账号解析的回归测试。
 *
 * 重点不是「字段读全了」而是**读不全也能用**：GitHub 改响应结构时界面该继续显示，
 * 只有连登录名都拿不到才算失败。
 */
class GitHubAccountTest {

    private fun user(login: String = "octocat", name: String = "The Octocat") = JSONObject()
        .put("login", login)
        .put("name", name)
        .put("avatar_url", "https://avatars.githubusercontent.com/u/1")
        .put("scope", "read:user, public_repo")

    private fun rate(limit: Int = 5000, remaining: Int = 4980) = JSONObject()
        .put(
            "resources",
            JSONObject().put(
                "core",
                JSONObject()
                    .put("limit", limit)
                    .put("remaining", remaining)
                    .put("reset", 1762230000L),
            ),
        )

    @Test
    fun `正常响应解析出账号与限速`() {
        val account = GitHubAccount.parse(user(), rate(), "classic")

        assertEquals("octocat", account?.login)
        assertEquals("The Octocat", account?.name)
        assertEquals(5000, account?.rateLimit)
        assertEquals(4980, account?.rateRemaining)
        assertEquals(listOf("read:user", "public_repo"), account?.scopes)
        // reset 是秒级时间戳，解析时要换成毫秒。
        assertEquals(1_762_230_000_000L, account?.rateResetAtMillis)
    }

    @Test
    fun `拿不到限速也照样能登录`() {
        val account = GitHubAccount.parse(user(), null, "fine-grained")

        assertEquals("octocat", account?.login)
        assertEquals(0, account?.rateLimit)
    }

    @Test
    fun `没有 login 视为失败`() {
        val empty = JSONObject().put("login", "")

        assertNull(GitHubAccount.parse(empty, rate(), "token"))
    }

    @Test
    fun `token 前缀判断只用于展示`() {
        assertEquals("fine-grained", GitHubAccount.tokenKind("github_pat_abc"))
        assertEquals("classic", GitHubAccount.tokenKind("ghp_abc"))
        assertEquals("oauth", GitHubAccount.tokenKind("gho_abc"))
        // 认不出的形态不该当成非法，照样交给服务端校验。
        assertEquals("token", GitHubAccount.tokenKind("something-new"))
    }

    @Test
    fun `HTTP 失败文案能看懂`() {
        assertTrue(GitHubApiClient.describeFailure(401).contains("无效"))
        assertTrue(GitHubApiClient.describeFailure(403).contains("权限"))
        assertTrue(GitHubApiClient.describeFailure(503).contains("暂时不可用"))
    }
}
