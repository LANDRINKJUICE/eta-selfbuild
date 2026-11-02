package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 双环境路径别名：只翻译 workspace 的两个名字，不碰共享存储与任何其它根。 */
class FileToolPathAliasTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun linuxEnvironmentTranslatesHostWorkspaceSpelling() {
        assertEquals("/workspace", FileToolPathAlias.translate("/data/local/tmp/eta", "linux"))
        // 末尾斜杠在翻译时被归一化掉（同一目录，不影响解析）。
        assertEquals("/workspace", FileToolPathAlias.translate("/data/local/tmp/eta/", "linux"))
        assertEquals("/workspace/dir/file", FileToolPathAlias.translate("/data/local/tmp/eta/dir/file", "linux"))
        assertEquals("/workspace/中文 目录/a.md", FileToolPathAlias.translate("/data/local/tmp/eta/中文 目录/a.md", "linux"))
    }

    @Test
    fun androidEnvironmentTranslatesLinuxWorkspaceSpelling() {
        assertEquals("/data/local/tmp/eta", FileToolPathAlias.translate("/workspace", "android"))
        assertEquals("/data/local/tmp/eta/a/b", FileToolPathAlias.translate("/workspace/a/b", "android"))
        assertEquals("/data/local/tmp/eta/mounts/share", FileToolPathAlias.translate("/workspace/mounts/share", "android"))
    }

    /** 单向：只把「另一种写法」翻译成本次 environment 的写法，本环境自己的写法原样保留。 */
    @Test
    fun translationIsOneWayPerEnvironment() {
        assertEquals("/workspace", FileToolPathAlias.translate("/workspace", "linux"))
        assertEquals("/data/local/tmp/eta", FileToolPathAlias.translate("/data/local/tmp/eta", "android"))
        // 两个名字互相翻译，往返一圈回到起点，不会出现第三种写法。
        assertEquals(
            "/data/local/tmp/eta/dir",
            FileToolPathAlias.translate(FileToolPathAlias.translate("/data/local/tmp/eta/dir", "linux"), "android"),
        )
    }

    /** /storage/emulated/0 与 /sdcard 在 chroot 里是同路径挂载，绝不能再加别名。 */
    @Test
    fun sharedStorageAndOtherRootsAreNeverRewritten() {
        listOf("/storage/emulated/0", "/storage/emulated/0/Download/a.txt", "/sdcard/a.txt", "/root", "/proc/1/maps").forEach { path ->
            listOf("android", "linux").forEach { environment ->
                assertEquals(path, FileToolPathAlias.translate(path, environment))
            }
        }
    }

    /**
     * 通行证不能变成绕过禁区：/data/local/tmp 的兄弟目录（共享存储挂载点、其它临时目录）与
     * SharedFolderMounts.FORBIDDEN_ROOTS 里的其它根都不翻译，摆错词法（. .. //）的写法也不翻译，
     * 因为替换后可能指向另一个目录。
     */
    @Test
    fun aliasOnlyRewritesTheWorkspacePairAndNeverWidensForbiddenRoots() {
        listOf(
            "/data/local/tmp", "/data/local/tmp/other", "/data/local/tmp/eta2", "/data/local/tmp/eta-x/y",
            "/workspace2", "/workspaces/y", "/work",
            "/data/local/tmp/eta/../other", "/data/local/tmp/eta/./x", "/data/local/tmp/eta//x", "//data/local/tmp/eta",
            "/workspace/../data/local/tmp/eta", "/workspace/./x",
        ).forEach { path ->
            listOf("android", "linux").forEach { environment ->
                assertEquals("$environment $path", path, FileToolPathAlias.translate(path, environment))
            }
        }
        // 共享文件夹的禁区规则保持原样：别名不会把宿主工作区洗成可以共享的源目录。
        assertEquals(
            SharedFolderMounts.SourceError.FORBIDDEN_ROOT,
            SharedFolderMounts.validateSource("/data/local/tmp/eta/notes", emptyList()),
        )
    }

    @Test
    fun relativePathsAndUnknownEnvironmentsAreUntouched() {
        listOf("", ".", "..", "docs/a.md", "./a.md", "~/a.md", "~").forEach { path ->
            listOf("android", "linux").forEach { environment ->
                assertEquals(path, FileToolPathAlias.translate(path, environment))
            }
        }
        assertEquals("/data/local/tmp/eta/x", FileToolPathAlias.translate("/data/local/tmp/eta/x", "proot"))
    }

    @Test
    fun fileToolResultsOnlyReportRequestedPathWhenTranslationHappened() {
        val aliased = JSONObject(AgentFileOperations(AliasStubBackend("android")).statFile("/workspace/a.txt"))
        assertEquals("/workspace/a.txt", aliased.getString("requested_path"))
        assertEquals("/data/local/tmp/eta/a.txt", aliased.getString("path"))
        assertEquals("/data/local/tmp/eta/a.txt", aliased.getJSONObject("file").getString("path"))

        val direct = JSONObject(AgentFileOperations(AliasStubBackend("android")).statFile("/data/local/tmp/eta/a.txt"))
        assertFalse(direct.toString(), direct.has("requested_path"))

        val relative = JSONObject(AgentFileOperations(AliasStubBackend("linux")).readFile("a.txt"))
        assertFalse(relative.toString(), relative.has("requested_path"))
    }

    /** 本地后端（android 的 user 身份走这条）同样在 canonicalFile 之前翻译，不是只有 shell 后端认别名。 */
    @Test
    fun localBackendResolvesAliasedPathsBeforeCanonicalizing() {
        val root = temporary.newFolder("workspace")
        val android = LocalFileToolBackend(root, environment = "android")
        assertEquals(
            "/data/local/tmp/eta/alias-missing/deep/file.txt",
            android.resolve("/workspace/alias-missing/deep/file.txt"),
        )
        // 相对路径仍以 workspace 为基准，行为不变。
        assertEquals(File(root, "sub/file.txt").canonicalPath, android.resolve("sub/file.txt"))
        assertEquals(root.canonicalPath, android.resolve(""))
        // 共享存储由 chroot 同路径挂载，不加别名。
        val storage = android.resolve("/storage/emulated/0/Download/alias-missing.txt")
        assertTrue(storage, storage.endsWith("alias-missing.txt"))
        assertFalse(storage, storage.startsWith("/data/local/tmp/eta"))

        val linux = LocalFileToolBackend(root, environment = "linux")
        assertEquals("/workspace/sub/file.txt", linux.resolve("/data/local/tmp/eta/sub/file.txt"))
    }

    /** 只记录解析结果的后端：用于验证别名是否上报，不碰真实文件系统。 */
    private class AliasStubBackend(override val environment: String) : FileToolBackend {
        override val identity = "user"
        override fun resolve(path: String) = FileToolPathAlias.translate(path, environment)
        override fun stat(path: String) = FileToolStat(path, path.trimEnd('/').substringAfterLast('/'), FileToolKind.FILE, 0, "revision")
        override fun readBytes(path: String, offset: Long, limit: Int) = "content".toByteArray()
        override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?) =
            FileToolDirectoryPage(emptyList(), 0, false, "revision")
        override fun write(
            path: String,
            bytes: ByteArray,
            append: Boolean,
            expectedRevision: String?,
            expectedSha256: String?,
        ) = FileToolWriteResult(stat(path), atomic = false)
    }
}
