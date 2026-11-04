package io.github.mangi.eta.agent.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub 账号相关的两个只读请求：`/user` 与 `/rate_limit`。
 *
 * 刻意只做这两件事：先用最小闭环把「配 token → 能用」跑通，仓库/issue/PR 等读写操作
 * 等这条通道稳了再谈（届时还要单独考虑权限范围和误操作防护）。
 */
internal object GitHubApiClient {
    private const val API_PREFIX = "https://api.github.com"
    private const val MAX_BODY_CHARS = 64 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 用 token 请求一次 `/user` 做真实校验；顺带取限速信息。 */
    suspend fun verify(token: String): Result<GitHubAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("Token 不能为空")
            val user = getJson("/user", trimmed)
            // 限速拿不到不影响登录：GitHub 偶尔会拒绝这个端点，界面少显示一行即可。
            val rate = runCatching { getJson("/rate_limit", trimmed) }.getOrNull()
            GitHubAccount.parse(user, rate, GitHubAccount.tokenKind(trimmed))
                ?: throw IllegalStateException("GitHub 返回的账号信息里没有 login")
        }
    }

    private fun getJson(path: String, token: String): JSONObject {
        val request = Request.Builder()
            .url(API_PREFIX + path)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Eta-Android")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(describeFailure(response.code))
            }
            return JSONObject(body.take(MAX_BODY_CHARS))
        }
    }

    /** 把 HTTP 状态码翻译成用户能看懂一句话。 */
    internal fun describeFailure(code: Int): String = when (code) {
        401 -> "Token 无效或已过期，请重新生成"
        403 -> "Token 权限不足，或已被 GitHub 限速"
        404 -> "接口不存在，GitHub 可能调整了 API"
        in 500..599 -> "GitHub 服务暂时不可用（HTTP $code）"
        else -> "GitHub 返回 HTTP $code"
    }
}
