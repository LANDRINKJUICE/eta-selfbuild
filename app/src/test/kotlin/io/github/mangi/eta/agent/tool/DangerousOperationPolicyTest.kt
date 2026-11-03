package io.github.mangi.eta.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 危险操作判定的边界测试。
 *
 * 核心诉求（用户 2026-11-03）：**只读操作一律别拦**，复制这类可恢复动作也别拦；
 * 真正会变砖的写入必须拦住。
 */
class DangerousOperationPolicyTest {

    private fun terminal(command: String) =
        DangerousOperationPolicy.evaluate("terminal", JSONObject().put("command", command))

    // ---------------------------------------------------------------- 只读：必须放行

    @Test
    fun readOnlySystemPathsAreAllowed() {
        assertFalse(terminal("ls -la /system").dangerous)
        assertFalse(terminal("cat /vendor/build.prop").dangerous)
        assertFalse(terminal("grep -r foo /system/etc").dangerous)
        assertFalse(terminal("stat /boot").dangerous)
        assertFalse(terminal("find /odm -name '*.so'").dangerous)
        assertFalse(terminal("du -sh /product").dangerous)
    }

    @Test
    fun readOnlyWithNullRedirectIsAllowed() {
        // 丢掉输出不是写操作，不能因为出现 ">" 就判成危险
        assertFalse(terminal("ls /system > /dev/null").dangerous)
        assertFalse(terminal("cat /vendor/x 2>/dev/null").dangerous)
    }

    /** 用户明确提过：复制不该被拦。 */
    @Test
    fun copyingIsAllowed() {
        assertFalse(terminal("cp /sdcard/a.txt /sdcard/b.txt").dangerous)
        assertFalse(terminal("cp -a '/sdcard/Download/x' '/storage/emulated/0/y'").dangerous)
        assertFalse(terminal("mv /sdcard/a /sdcard/b").dangerous)
        assertFalse(terminal("rm -rf /sdcard/Download/tmp").dangerous)
    }

    @Test
    fun appDataWritesAreAllowed() {
        assertFalse(terminal("cp /sdcard/a /data/data/io.github.mangi.eta/files/a").dangerous)
        assertFalse(terminal("mkdir -p /data/local/tmp/eta/tmp/x").dangerous)
        assertFalse(terminal("chmod 644 /sdcard/a.txt").dangerous)
    }

    // ---------------------------------------------------------------- 写入敏感路径：必须拦

    @Test
    fun writingToSystemPartitionIsBlocked() {
        assertTrue(terminal("cp /sdcard/x /system/app/x").dangerous)
        assertTrue(terminal("rm -rf /system/app/Foo").dangerous)
        assertTrue(terminal("chmod 777 /vendor/bin/x").dangerous)
    }

    @Test
    fun writingToBootChainIsBlocked() {
        assertTrue(terminal("cp /sdcard/x.sh /data/adb/service.d/x.sh").dangerous)
        assertTrue(terminal("rm -rf /data/adb/modules/thermal_vrr").dangerous)
    }

    // ---------------------------------------------------------------- 破坏型动作：无条件拦

    @Test
    fun destructiveCommandsAreBlocked() {
        assertTrue(terminal("dd if=/sdcard/img of=/dev/block/by-name/boot").dangerous)
        assertTrue(terminal("mkfs.ext4 /dev/block/x").dangerous)
        assertTrue(terminal("fastboot flash boot boot.img").dangerous)
        assertTrue(terminal("setenforce 0").dangerous)
        assertTrue(terminal("rm -rf /").dangerous)
    }

    // ---------------------------------------------------------------- 文件工具

    @Test
    fun fileToolWritingToSystemIsBlocked() {
        val args = JSONObject().put("path", "/system/build.prop")
        assertTrue(DangerousOperationPolicy.evaluate("write_file", args).dangerous)
    }

    @Test
    fun fileToolWritingToSdcardIsAllowed() {
        val args = JSONObject().put("path", "/sdcard/Download/Eta/note.txt")
        assertFalse(DangerousOperationPolicy.evaluate("write_file", args).dangerous)
    }

    // ---------------------------------------------------------------- 空值

    @Test
    fun blankCommandIsSafe() {
        assertFalse(DangerousOperationPolicy.evaluate("terminal", JSONObject()).dangerous)
        assertFalse(DangerousOperationPolicy.evaluate("terminal", null).dangerous)
    }
}
