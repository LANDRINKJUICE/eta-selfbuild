package io.github.mangi.eta.agent.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路径风险判定的单测。
 *
 * 为什么值得单独测：这套判定决定了「删之前会不会提醒你」，
 * 判错的方向只有两种 —— 该提醒没提醒（危险），不该提醒乱提醒（烦人）。
 * 两种都是缺陷，所以用例要覆盖边界。
 */
class FilePathRiskTest {

    @Test
    fun normalPathHasNoWarning() {
        val result = FilePathRisk.assess(listOf("/sdcard/DCIM/Camera/a.jpg"), destructive = true)
        assertEquals(FilePathRisk.Level.NORMAL, result.level)
        assertTrue(result.warning.isEmpty())
    }

    @Test
    fun readOnlyOperationsNeverWarn() {
        // 只浏览不删除时，即使用户在看 /system 也不该弹提示。
        val result = FilePathRisk.assess(listOf("/system/build.prop"), destructive = false)
        assertEquals(FilePathRisk.Level.NORMAL, result.level)
    }

    @Test
    fun bootChainIsCritical() {
        val paths = listOf(
            "/data/adb/modules/thermal_vrr",
            "/data/adb/service.d/99-newapi.sh",
            "/data/adb/post-fs-data.d/x.sh",
        )
        paths.forEach { path ->
            val result = FilePathRisk.assess(listOf(path), destructive = true)
            assertEquals("$path 应判为 CRITICAL", FilePathRisk.Level.CRITICAL, result.level)
            assertTrue(result.warning.isNotBlank())
        }
    }

    @Test
    fun systemPartitionsAreCritical() {
        listOf("/system", "/vendor/lib", "/boot", "/odm/etc", "/product/app").forEach { path ->
            assertEquals(
                "$path 应判为 CRITICAL",
                FilePathRisk.Level.CRITICAL,
                FilePathRisk.assess(listOf(path), destructive = true).level,
            )
        }
    }

    @Test
    fun appPrivateDataIsCautionNotCritical() {
        val result = FilePathRisk.assess(listOf("/data/data/com.example.app"), destructive = true)
        assertEquals(FilePathRisk.Level.CAUTION, result.level)
        assertTrue(result.warning.isNotBlank())
    }

    @Test
    fun batchTakesHighestRisk() {
        val result = FilePathRisk.assess(
            listOf("/sdcard/DCIM/a.jpg", "/sdcard/DCIM/b.jpg", "/data/adb/modules/x"),
            destructive = true,
        )
        assertEquals("批量里有一条高危，整体就该是高危", FilePathRisk.Level.CRITICAL, result.level)
    }

    @Test
    fun similarButSafePathIsNotFlagged() {
        // /data/database 不是 /data/data；前半段相同时不能误判。
        val result = FilePathRisk.assess(listOf("/data/databases_backup/x.db"), destructive = true)
        assertEquals(FilePathRisk.Level.NORMAL, result.level)
    }

    @Test
    fun trailingSlashDoesNotChangeVerdict() {
        val withSlash = FilePathRisk.assess(listOf("/system/"), destructive = true)
        val without = FilePathRisk.assess(listOf("/system"), destructive = true)
        assertEquals(without.level, withSlash.level)
    }

    @Test
    fun emptySelectionIsNormal() {
        assertEquals(FilePathRisk.Level.NORMAL, FilePathRisk.assess(emptyList(), destructive = true).level)
    }
}
