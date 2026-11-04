package io.github.mangi.eta.agent.github

import android.content.Context

/**
 * GitHub token 与账号快照的本地存储。
 *
 * **存哪**：应用私有 SharedPreferences（和模型 API key 的做法一致）。文件权限是 app 私有，
 * root 以外读不到。等以后要管多个服务的凭据时再统一换成 Keystore 加密 ——
 * 现在为一个 token 引入加解密层不划算，出问题还多一处失败点。
 *
 * **为什么额外缓存账号信息**：设置页要显示头像和登录名，不该每次进页面都打一次网络请求。
 * 缓存不参与鉴权 —— 鉴权永远用 token 现读现用。
 */
internal object GitHubCredentialStore {
    private const val PREFS_NAME = "eta_github"
    private const val KEY_TOKEN = "token"
    private const val KEY_LOGIN = "login"
    private const val KEY_NAME = "name"
    private const val KEY_AVATAR = "avatar_url"
    private const val KEY_TOKEN_TYPE = "token_type"
    private const val KEY_RATE_LIMIT = "rate_limit"
    private const val KEY_RATE_REMAINING = "rate_remaining"
    private const val KEY_RATE_RESET = "rate_reset"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null).orEmpty().trim()

    fun isConnected(context: Context): Boolean = token(context).isNotEmpty()

    fun save(context: Context, token: String, account: GitHubAccount) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_LOGIN, account.login)
            .putString(KEY_NAME, account.name)
            .putString(KEY_AVATAR, account.avatarUrl)
            .putString(KEY_TOKEN_TYPE, account.tokenType)
            .putInt(KEY_RATE_LIMIT, account.rateLimit)
            .putInt(KEY_RATE_REMAINING, account.rateRemaining)
            .putLong(KEY_RATE_RESET, account.rateResetAtMillis)
            .apply()
    }

    /** 只刷新限速数字，不动 token（每次打开账号页顺手更新一次）。 */
    fun saveRateLimit(context: Context, account: GitHubAccount) {
        prefs(context).edit()
            .putInt(KEY_RATE_LIMIT, account.rateLimit)
            .putInt(KEY_RATE_REMAINING, account.rateRemaining)
            .putLong(KEY_RATE_RESET, account.rateResetAtMillis)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 上次连接时的账号快照；没连过返回 null。 */
    fun cached(context: Context): GitHubAccount? {
        val prefs = prefs(context)
        val login = prefs.getString(KEY_LOGIN, null)?.takeIf { it.isNotBlank() } ?: return null
        return GitHubAccount(
            login = login,
            name = prefs.getString(KEY_NAME, null).orEmpty(),
            avatarUrl = prefs.getString(KEY_AVATAR, null).orEmpty(),
            tokenType = prefs.getString(KEY_TOKEN_TYPE, null).orEmpty(),
            scopes = emptyList(),
            rateLimit = prefs.getInt(KEY_RATE_LIMIT, 0),
            rateRemaining = prefs.getInt(KEY_RATE_REMAINING, 0),
            rateResetAtMillis = prefs.getLong(KEY_RATE_RESET, 0L),
        )
    }
}
