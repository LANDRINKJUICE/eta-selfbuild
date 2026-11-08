package io.github.mangi.eta.agent.terminal

import java.io.File

/** guest 的 root 只是 UID 映射，宿主进程始终保留 App UID。 */
internal object ProotCommandBuilder {
    fun available(): Boolean = TerminalRuntime.nativeExecutable("libproot_exec.so") != null &&
        TerminalRuntime.nativeExecutable("libproot_loader.so") != null

    fun payload(
        rootfsPath: String,
        command: String?,
        sharedMounts: List<SharedFolderMount> = emptyList(),
        termType: String = "dumb",
        nativeDirectory: File? = TerminalRuntime.nativeLibraryDir,
        workspace: String = TerminalRuntime.userWorkspacePath,
        tempDirectory: File = TerminalRuntime.temporaryDirectory,
        publicStorageGranted: Boolean = TerminalRuntime.publicStorageGranted,
        /**
         * 额外绑定：宿主绝对路径 → chroot 内绝对路径。
         *
         * 2026-11-08 为插件加（插件自带脚本要在 linux 环境里可执行）。
         * proot 的 `-b` 只能读写绑定，做不到只读；调用方需自行保证不去写。
         */
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): String {
        val native = nativeDirectory ?: return "echo ETA_PROOT_UNAVAILABLE >&2; exit 127"
        val proot = File(native, "libproot_exec.so").absolutePath
        val loader = File(native, "libproot_loader.so").absolutePath
        val args = mutableListOf(proot, "--root-id", "--link2symlink", "--kill-on-exit", "--sysvipc", "-r", rootfsPath, "-w", "/workspace")
        fun bind(source: String, destination: String = source) {
            require(':' !in source && ':' !in destination) { "共享路径不能包含冒号" }
            args += listOf("-b", "$source:$destination")
        }
        listOf("/dev", "/proc", "/sys").filter { File(it).isDirectory }.forEach { bind(it) }
        bind(workspace, "/workspace")
        bind(tempDirectory.absolutePath, "/dev/shm")
        if (publicStorageGranted && File("/storage/emulated/0").canRead()) bind("/storage/emulated/0")
        // proot 的 -b 只支持读写绑定，无法在绑定后 remount 成只读；
        // 需要只读保证时用 chroot 后端（Root 模式），那里会额外做 remount,bind,ro。
        sharedMounts.filter {
            SharedFolderMounts.validateName(it.name, emptyList()) == null &&
                permittedSharedSource(it.sourcePath, publicStorageGranted) &&
                File(it.sourcePath).isDirectory && File(it.sourcePath).canRead()
        }.forEach {
            bind(it.sourcePath, "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${it.name}")
        }
        // 插件目录等额外绑定。路径由调用方给出（插件目录来自 pluginsDir 子目录），
        // 这里只做「必须绝对路径 + 必须存在」的兜底校验，避免把奇怪的东西塞进 proot。
        extraBinds.forEach { (source, target) ->
            if (!source.startsWith("/") || !target.startsWith("/")) return@forEach
            if (!File(source).isDirectory) return@forEach
            bind(source, target)
        }
        args += listOf("/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/sh", "TERM=$termType",
            "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "TMPDIR=/tmp", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        if (command != null) args += "NO_COLOR=1"
        args += "/bin/sh"
        if (command != null) args += listOf("-lc", command)
        return "[ -x ${shellQuote(proot)} ] && [ -x ${shellQuote(loader)} ] || { echo ETA_PROOT_UNAVAILABLE >&2; exit 127; }; " +
            "mkdir -p ${shellQuote(workspace)} ${shellQuote(tempDirectory.absolutePath)} || exit 125; " +
            "export PROOT_LOADER=${shellQuote(loader)} PROOT_TMP_DIR=${shellQuote(tempDirectory.absolutePath)}; " +
            "unset LD_PRELOAD; " + args.joinToString(" ", transform = ::shellQuote)
    }
    internal fun permittedSharedSource(path: String, publicStorageGranted: Boolean): Boolean {
        val canonical = File(path).canonicalPath
        val publicPath = canonical == "/sdcard" || canonical.startsWith("/sdcard/") ||
            canonical == "/storage" || canonical.startsWith("/storage/") ||
            canonical == "/mnt/media_rw" || canonical.startsWith("/mnt/media_rw/") ||
            canonical.startsWith("/mnt/runtime/") || canonical.startsWith("/mnt/user/")
        return !publicPath || publicStorageGranted
    }

}
