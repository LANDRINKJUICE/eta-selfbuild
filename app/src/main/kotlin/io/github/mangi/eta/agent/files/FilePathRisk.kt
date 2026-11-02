package io.github.mangi.eta.agent.files

/**
 * 文件管理器路径的风险分级。
 *
 * 用户明确要求：**系统分区不限制写入**，但要在危险路径上给出提示。
 * 所以这里只做「分级 + 文案」，不做权限拦截 —— 拦不拦由调用方决定，
 * 判定逻辑单独抽出来是为了可测试。
 */
internal object FilePathRisk {

    /** 风险等级：从低到高。文案用于二次确认弹窗里的红字提示。 */
    internal enum class Level(val weight: Int) {
        NORMAL(0),
        CAUTION(1),
        CRITICAL(2),
    }

    internal data class Assessment(
        val level: Level,
        /** 面向用户的提示；NORMAL 时为空串。 */
        val warning: String,
    ) {
        val needsStrongWarning: Boolean get() = level.weight >= Level.CRITICAL.weight
    }

    /**
     * 判定一组路径（批量操作时可能包含多个）的最高风险。
     *
     * @param paths 用户选中的完整路径
     * @param destructive 是否是删除/覆盖这类不可逆操作；只读浏览不提示
     */
    fun assess(paths: List<String>, destructive: Boolean): Assessment {
        if (!destructive || paths.isEmpty()) return Assessment(Level.NORMAL, "")
        var worst = Level.NORMAL
        var warning = ""
        paths.forEach { path ->
            val single = assessSingle(path)
            if (single.level.weight > worst.weight) {
                worst = single.level
                warning = single.warning
            }
        }
        return Assessment(worst, warning)
    }

    /**
     * 判断 path 是否位于 dir 之下（含 dir 本身）。
     *
     * 必须按**目录边界**比，不能只比字符串前缀：
     * `/data/databases_backup` 以 `/data/data` 开头，但它根本不是 `/data/data` 的子路径。
     */
    private fun under(path: String, dir: String): Boolean =
        path == dir || path.startsWith("$dir/")

    private fun assessSingle(rawPath: String): Assessment {
        val path = rawPath.trimEnd('/')
        return when {
            // 开机链：删错会开不了机，改完重启才炸，最难排查。
            under(path, "/data/adb/modules") || under(path, "/data/adb/service.d") ||
                under(path, "/data/adb/post-fs-data.d") || path == "/data/adb" ->
                Assessment(
                    Level.CRITICAL,
                    "这是开机启动链（模块 / service.d）。删改后手机可能无法开机，且要重启才会暴露问题。",
                )

            // 系统分区：可能导致系统异常或无法启动。
            under(path, "/system") || under(path, "/vendor") || under(path, "/product") ||
                under(path, "/my_product") || under(path, "/odm") || under(path, "/boot") ->
                Assessment(
                    Level.CRITICAL,
                    "这是系统分区文件。删改后可能导致系统异常或无法开机。",
                )

            // 关键系统数据：会丢应用配置或系统设置。
            under(path, "/data/system") || under(path, "/data/misc") ->
                Assessment(
                    Level.CAUTION,
                    "这是系统数据目录，删改可能影响系统设置或应用登录状态。",
                )

            // 应用私有数据：会丢单个应用的配置/账号，但不影响开机。
            under(path, "/data/data") || under(path, "/data/user") ->
                Assessment(Level.CAUTION, "这是应用私有数据，删掉会丢失该应用的配置与登录状态。")

            else -> Assessment(Level.NORMAL, "")
        }
    }

    /** 回收站路径：删除先进这里，7 天后才真正清除。 */
    const val TRASH_DIR = "/sdcard/Download/Eta/.trash"

    /** 回收站保留天数。 */
    const val TRASH_RETENTION_DAYS = 7
}
