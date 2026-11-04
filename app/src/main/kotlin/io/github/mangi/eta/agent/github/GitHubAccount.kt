package io.github.mangi.eta.agent.github

import org.json.JSONObject

/**
 * 已连接的 GitHub 账号快照。
 *
 * 只保留界面上真正用得着的字段：谁、头像、token 类型、限速剩余。
 * 不存 token 本身 —— token 在 [GitHubCredentialStore] 里单独存，避免这个对象被随手打印出去。
 */
internal data class GitHubAccount(
    val login: String,
    val name: String,
    val avatarUrl: String,
    val tokenType: String,
    val scopes: List<String>,
    val rateLimit: Int,
    val rateRemaining: Int,
    val rateResetAtMillis: Long,
) {
    companion object {
        /**
         * 从 `GET /user` 与 `GET /rate_limit` 的响应拼出账号信息。
         *
         * 缺字段一律用空值兜底、不抛异常：GitHub 改响应结构时界面该继续能用，
         * 只是少显示一项（登录名缺失才返回 null，因为那是唯一必需的信息）。
         */
        fun parse(user: JSONObject, rate: JSONObject?, tokenKind: String): GitHubAccount? {
            val login = user.optString("login").trim().takeIf { it.isNotEmpty() } ?: return null
            val core = rate?.optJSONObject("resources")?.optJSONObject("core")
            return GitHubAccount(
                login = login,
                name = user.optString("name").orEmpty().trim(),
                avatarUrl = user.optString("avatar_url").orEmpty().trim(),
                tokenType = tokenKind,
                scopes = user.optString("scope")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                rateLimit = core?.optInt("limit", 0) ?: 0,
                rateRemaining = core?.optInt("remaining", 0) ?: 0,
                rateResetAtMillis = (core?.optLong("reset", 0L) ?: 0L) * 1000L,
            )
        }

        /**
         * token 形态判断，**只用于界面展示**（区分 classic / fine-grained / OAuth）。
         *
         * 不做格式校验：GitHub 的 token 前缀会变，误判成非法反而挡住用户。
         * 真正的校验是拿它请求一次 `/user`。
         */
        fun tokenKind(token: String): String = when {
            token.startsWith("github_pat_") -> "fine-grained"
            token.startsWith("ghp_") -> "classic"
            token.startsWith("gho_") -> "oauth"
            token.startsWith("ghu_") -> "user-to-server"
            else -> "token"
        }
    }
}
