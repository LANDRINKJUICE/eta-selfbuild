#!/system/bin/sh
# ============================================================================
# sys-snapshot.sh — 本机系统档案快照生成器
#   机型：realme RMX3700 (GT Neo5 SE) / ColorOS 16 / KernelSU
#
# 【用途】
#   一次性采集「设备与构建 / 内核与 Root 栈 / LSPosed / 挂载与模块固化改动 /
#   关键 settings / 网络与本地服务 / 存储与内存 / 包计数 / 关键应用版本」，
#   输出为可直接粘贴的 Markdown。用于：
#     - 改动系统前后留一份「证据底片」，出问题可逐节对比定位漂移；
#     - 复现问题现场时证明「当时的系统长什么样」；
#     - 校验已有档案（SYSTEM-PROFILE.md / 技能正文）是否过期。
#
# 【只读声明 — 本脚本绝不写系统】
#   只执行查询类命令：getprop / uname / ls / cat / readlink / find / command -v /
#   mount / ip / ss / netstat / df / free / pm / dumpsys / settings get /
#   md5sum / wc / date。
#   不修改任何 setting、不安装或卸载、不重启服务或设备、不写任何系统目录，
#   脚本自身也不会创建临时文件（输出的重定向由调用方决定）。
#
# 【复验方法】
#   sh /data/local/tmp/eta/04-工具-tools/sys-snapshot.sh > /tmp/now.md
#   然后与上一份 05-系统分析-sysanalyze/SYSTEM-SNAPSHOT-<日期>.md 逐节 diff。
#   注：内存 / 监听端口 / 开机时长属于实时量，天然会变，对比时应忽略。
#
# 【本机已知坑（已规避）】
#   * busybox/toybox 的 grep 不支持 BRE 的 \| 交替 → 会静默返回空，一律用 grep -E
#   * dumpsys 输出接 grep 可能报 Broken pipe → 一律加 2>/dev/null
#   * 路径含中文 → 所有变量都加引号
# ============================================================================

DATE_STR=$(date '+%Y-%m-%d %H:%M:%S')
DATE_YMD=$(date '+%Y-%m-%d')
FAILED=""
fail() { FAILED="${FAILED}- $1
"; }

# 取不到时：打印 (未取到) 并登记到失败清单，不中断脚本
kv() {  # kv "标签" "shell 片段"
  _v=$(sh -c "$2" 2>/dev/null)
  if [ -z "$_v" ]; then _v='(未取到)'; fail "$1"; fi
  printf '| %s | `%s` |\n' "$1" "$_v"
}

# 取不到但属于「正常为空」时：打印占位文本，不计入失败清单
kvd() { # kvd "标签" "shell 片段" "空时显示"
  _v=$(sh -c "$2" 2>/dev/null)
  [ -z "$_v" ] && _v="$3"
  printf '| %s | `%s` |\n' "$1" "$_v"
}

echo "# SYSTEM-SNAPSHOT-${DATE_YMD}"
echo
echo '> **生成方式**：在已 root 的 RMX3700 上执行 `sh /data/local/tmp/eta/04-工具-tools/sys-snapshot.sh`（只读采集，只跑查询类命令）。'
echo "> **生成时间**：${DATE_STR}"
echo '> **用途**：系统档案快照，用于跨时间对比、复现问题现场、校验已有档案文档是否过期。'
echo '> **复验方法**：重跑同一脚本，与本文逐节 diff；内存/监听端口/开机时长属实时量，差异正常。'
echo '> **只读声明**：本次采集未修改任何设置、未安装或卸载、未重启服务或设备、未写入任何系统目录。'
echo
# 环境自检：本脚本必须在 Android 宿主环境执行，跑在 Linux chroot 里会拿到假数据
if ! command -v getprop >/dev/null 2>&1 || [ ! -d /data/adb ]; then
  echo '> **⚠️ 环境警告**：未检测到 `getprop` 或 `/data/adb`，说明本脚本没有跑在 Android 宿主环境'
  echo '> （例如误在 Linux chroot / PRoot 里跑）。**下面的数据一律不可信**，请在 `environment=android` 下重新执行。'
  echo
fi

echo "---"
echo

# ---------------------------------------------------------------- 1. 设备与构建
echo "## 1. 设备与构建"
echo
echo "| 属性 | 值 |"
echo "|---|---|"
kv "ro.product.model"             "getprop ro.product.model"
kv "ro.product.brand"             "getprop ro.product.brand"
kv "ro.product.device"            "getprop ro.product.device"
kv "ro.build.display.id"          "getprop ro.build.display.id"
kv "ro.build.id"                  "getprop ro.build.id"
kv "ro.build.version.incremental" "getprop ro.build.version.incremental"
kv "ro.build.version.release"     "getprop ro.build.version.release"
kv "ro.build.version.sdk"         "getprop ro.build.version.sdk"
kv "ro.build.version.security_patch" "getprop ro.build.version.security_patch"
kv "ro.build.type"                "getprop ro.build.type"
kv "ro.build.tags"                "getprop ro.build.tags"
kv "ro.product.cpu.abi"           "getprop ro.product.cpu.abi"
kv "ro.board.platform"            "getprop ro.board.platform"
kv "ro.soc.model"                 "getprop ro.soc.model"
kv "ro.boot.slot_suffix"          "getprop ro.boot.slot_suffix"
kv "ro.boot.verifiedbootstate"    "getprop ro.boot.verifiedbootstate"
kv "ro.boot.flash.locked"         "getprop ro.boot.flash.locked"
kv "ro.boot.dynamic_partitions"   "getprop ro.boot.dynamic_partitions"
kv "ro.secure / ro.debuggable"    "echo \"\$(getprop ro.secure) / \$(getprop ro.debuggable)\""
kv "SELinux (getenforce)"         "getenforce"
echo

# ------------------------------------------------------------ 2. 内核与 Root 栈
echo "## 2. 内核与 Root 栈"
echo
echo '```'
uname -r 2>/dev/null || { echo "(未取到)"; fail "uname -r"; }
echo '```'
echo
echo "| 项 | 值 |"
echo "|---|---|"
kv  "/data/adb/ksu 存在"    "test -d /data/adb/ksu && echo yes || echo no"
kv  "/data/adb/magisk 存在" "test -d /data/adb/magisk && echo yes || echo 'no（Magisk 未安装）'"
kv  "ksud 版本 (-V)"        "/data/adb/ksud -V 2>&1 | head -1"
kvd "ksu 版本文件"          "find /data/adb/ksu -maxdepth 2 -iname '*version*' 2>/dev/null | head -5" "(无独立版本文件)"
echo
echo "### 2.1 /data/adb/modules 模块清单"
echo
echo "| 目录 | id | name | version | 备注 |"
echo "|---|---|---|---|---|"
for m in /data/adb/modules/*/; do
  [ -d "$m" ] || continue
  d=$(basename "$m")
  prop="$m/module.prop"
  note=""
  if [ -f "$prop" ]; then
    id=$(sed -n 's/^id=//p' "$prop" 2>/dev/null | head -1)
    nm=$(sed -n 's/^name=//p' "$prop" 2>/dev/null | head -1)
    vr=$(sed -n 's/^version=//p' "$prop" 2>/dev/null | head -1)
  else
    id="-"; nm="-"; vr="-"; note="**无 module.prop（非可加载模块）**"
  fi
  [ -f "$m/disable" ] && note="${note} 已 disable"
  [ -f "$m/remove" ] && note="${note} 待移除"
  printf '| `%s` | %s | %s | %s | %s |\n' "$d" "${id:-?}" "${nm:-?}" "${vr:-?}" "$note"
done
echo
echo "### 2.2 Zygisk 提供者 / 关键 root 组件"
echo
echo "| 检查项 | 结果 |"
echo "|---|---|"
kv  "zygisksu 模块目录"  "test -d /data/adb/modules/zygisksu && echo yes || echo no"
kv  "zygisksu 版本"      "sed -n 's/^version=//p' /data/adb/modules/zygisksu/module.prop 2>/dev/null | head -1"
kv  "zygisksu 自述"      "sed -n 's/^description=//p' /data/adb/modules/zygisksu/module.prop 2>/dev/null | head -1"
kv  "/data/adb/zygisksu" "test -d /data/adb/zygisksu && echo yes || echo no"
kv  "sui（目录 / 模块）" "test -d /data/adb/sui && echo -n 'dir:/data/adb/sui '; ls -d /data/adb/modules/*sui* 2>/dev/null | tr '\n' ' '"
kv  "tricky_store"       "test -d /data/adb/modules/tricky_store && echo yes || echo no"
kv  "playintegrityfix"   "test -d /data/adb/modules/playintegrityfix && echo yes || echo no"
kv  "KSU 已加载内核模块" "cat /data/adb/ksu/log/modules_info 2>/dev/null | awk '{print \$1}' | sort -u | tr '\n' ' '"
echo

# ------------------------------------------------------------------ 3. LSPosed
echo "## 3. LSPosed"
echo
echo "| 项 | 值 |"
echo "|---|---|"
kv  "LSPosed 模块版本"      "sed -n 's/^version=//p' /data/adb/modules/zygisk_lsposed/module.prop 2>/dev/null | head -1"
kv  "/data/adb/lspd 存在"    "test -d /data/adb/lspd && echo yes || echo no"
kv  "/data/adb/lsposed 存在" "test -d /data/adb/lsposed && echo yes || echo 'no（该路径不存在，实际是 /data/adb/lspd）'"
kv  "作用域数据库"          "ls -l /data/adb/lspd/config/modules_config.db 2>/dev/null | awk '{print \$5\" bytes, mtime \"\$6\" \"\$7\" \"\$8}'"
kv  "最新 modules 日志"     "ls -1t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1"
echo
echo "### 3.1 模块包清单"
echo
echo '权威来源是 `/data/adb/lspd/config/modules_config.db`（sqlite）。本机 Android shell 没有'
echo '`sqlite3` / `python3`，脚本用日志法回退：只列出【本次开机实际 Hook 过】的模块。'
echo '要看完整清单（含已安装但未启用）请把 db 拷到工作区后用 Linux 侧 sqlite3 读：'
echo
echo '```sh'
echo 'cp /data/adb/lspd/config/modules_config.db* /data/local/tmp/eta/tmp/lspd-ro/'
echo "sqlite3 /workspace/tmp/lspd-ro/modules_config.db 'select module_pkg_name from modules;'"
echo "sqlite3 /workspace/tmp/lspd-ro/modules_config.db 'select module_pkg_name,enabled from modules_state where user_id=0;'"
echo '```'
echo
echo "本机 sqlite3 可用性：$(command -v sqlite3 2>/dev/null || echo 'no（Android 侧没有 sqlite3）')"
echo
LSPD_LOG=$(ls -1t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
PM_LIST=$(pm list packages 2>/dev/null)
if [ -n "$LSPD_LOG" ]; then
  MODPKGS=$(grep -aoE '\([a-zA-Z0-9_.:]+\)\[[a-zA-Z0-9_.]+,' "$LSPD_LOG" 2>/dev/null \
            | sed -n 's/.*\[\([a-zA-Z0-9_.]*\),/\1/p' | sort -u)
fi
echo "日志法结果（模块包 → pm 里是否存在）："
echo
echo "| 模块包名 | pm 里是否存在 |"
echo "|---|---|"
if [ -n "$MODPKGS" ]; then
  for mp in $MODPKGS; do
    if printf '%s\n' "$PM_LIST" | grep -qF "package:$mp"; then r=yes; else r="**no**"; fail "LSPosed 模块包在 pm 中不存在: $mp"; fi
    printf '| `%s` | %s |\n' "$mp" "$r"
  done
else
  echo '| `(未取到)` | - |'; fail "LSPosed 日志模块包名解析"
fi
echo
echo "### 3.2 各作用域最后一次 Hook 安装结果（每个宿主只取最后一次，不 dump 全日志）"
echo
echo '```'
if [ -n "$LSPD_LOG" ]; then
  if grep -aq "Hook 安装完成" "$LSPD_LOG" 2>/dev/null; then
    # 每个宿主只取最后一次（宿主名排序，保证输出稳定）
    HK_HOSTS=$(grep -a "Hook 安装完成" "$LSPD_LOG" 2>/dev/null \
      | sed -n 's/.*] (\([a-zA-Z0-9_.:]*\)).*/\1/p' | sort -u)
    for _h in $HK_HOSTS; do
      grep -a "Hook 安装完成" "$LSPD_LOG" 2>/dev/null | grep -aF "($_h)" | tail -1
    done
  else
    echo "(未取到)"; fail "Hook 安装完成 行"
  fi
else
  echo "(未取到)"; fail "Hook 安装完成（无日志文件）"
fi
echo '```'
echo

# ----------------------------------------------------- 4. 挂载与模块固化改动
echo "## 4. 挂载与固化改动"
echo
echo "### 4.1 bind / overlay 挂载（关注系统文件是否被覆盖）"
echo
kv "bind/overlay 挂载条数" "mount 2>/dev/null | grep -cE 'bind|overlay'"
echo
echo '```'
if mount 2>/dev/null | grep -qE 'bind|overlay'; then
  mount 2>/dev/null | grep -E 'bind|overlay' | sed 's/ (rw,/ (rw,/'
else
  echo "(未取到)"; fail "mount | grep -E 'bind|overlay'"
fi
echo '```'
echo
echo '注：`/mnt/opex/*`、`oplusex`、`/product/app`、`/product/priv-app` 等 overlay 是 ColorOS 出厂机制'
echo '（Opex 动态应用），不是本机改动。重点看有没有指向 `/data/adb/modules/*` 的挂载。'
echo
kvd "指向 /data/adb 的挂载" "mount 2>/dev/null | grep -E '/data/adb' | head -5 | tr '\n' ' '" "(无：模块未做指向 /data/adb 的传统 bind mount)"
echo
echo "### 4.2 thermal_vrr 模块固化改动是否真的生效"
echo
echo '把模块内 `patched/` 的替身文件与系统可见路径做 md5 比对，相同即 overlay/bind 已生效。'
echo
echo "| 系统可见路径 | 系统侧 md5 | 模块侧 md5 (patched/) | 结论 |"
echo "|---|---|---|---|"
tv_pair() {
  _a=$(md5sum "$1" 2>/dev/null | awk '{print $1}')
  _b=$(md5sum "$2" 2>/dev/null | awk '{print $1}')
  if [ -z "$_a" ] || [ -z "$_b" ]; then
    _c="(未取到)"; fail "md5 $1"
  elif [ "$_a" = "$_b" ]; then
    _c="**一致 → 模块已生效**"
  else
    _c="不一致 → 模块未生效/已被系统改写"
  fi
  printf '| `%s` | %s | %s | %s |\n' "$1" "${_a:-?}" "${_b:-?}" "$_c"
}
tv_pair "/my_product/etc/oplus_vrr_config.json"                     "/data/adb/modules/thermal_vrr/patched/oplus_vrr_config.json"
tv_pair "/my_product/etc/refresh_rate_config.xml"                   "/data/adb/modules/thermal_vrr/patched/refresh_rate_config.xml"
tv_pair "/vendor/etc/display/thermallevel_to_fps.xml"               "/data/adb/modules/thermal_vrr/patched/thermallevel_to_fps.xml"
tv_pair "/my_stock/etc/ThermalServiceConfig/sys_thermal_config.xml" "/data/adb/modules/thermal_vrr/patched/sys_thermal_config.xml"
echo
echo "### 4.3 模块状态与开机脚本"
echo
echo "| 项 | 值 |"
echo "|---|---|"
kv  "thermal_vrr disable 文件"  "test -f /data/adb/modules/thermal_vrr/disable && echo present || echo 'absent（模块生效中）'"
kv  "thermal_vrr 守护 pid 锁"    "cat /data/adb/modules/thermal_vrr/service.lock 2>/dev/null"
kv  "/data/adb/service.d 清单"   "ls -1 /data/adb/service.d/ 2>/dev/null | tr '\n' ' '"
kvd "/data/adb/post-fs-data.d 清单" "ls -1 /data/adb/post-fs-data.d/ 2>/dev/null | tr '\n' ' '" "(空目录)"
echo
echo '`/data/adb/modules/thermal_vrr/patch.log` 末尾 10 行：'
echo
echo '```'
tail -10 /data/adb/modules/thermal_vrr/patch.log 2>/dev/null || { echo "(未取到)"; fail "thermal_vrr patch.log"; }
echo '```'
echo

# ------------------------------------------------------------ 5. 关键设置（只读）
echo "## 5. 关键设置（settings get，只读）"
echo
echo "| 命名空间 / 键 | 值 |"
echo "|---|---|"
kv  "global eta_accessibility_protection_enabled" "settings get global eta_accessibility_protection_enabled"
kv  "secure enabled_accessibility_services"       "settings get secure enabled_accessibility_services"
kv  "secure accessibility_enabled"                "settings get secure accessibility_enabled"
kv  "secure enabled_notification_listeners"       "settings get secure enabled_notification_listeners"
kv  "system peak_refresh_rate"                    "settings get system peak_refresh_rate"
kv  "system min_refresh_rate"                     "settings get system min_refresh_rate"
kv  "secure oplus_refreshrate_override"           "settings get secure oplus_refreshrate_override"
kv  "secure oplus_customize_screen_refresh_rate"  "settings get secure oplus_customize_screen_refresh_rate"
kv  "system screen_off_timeout"                   "settings get system screen_off_timeout"
kv  "secure display_density_forced"               "settings get secure display_density_forced"
kv  "system display_density_index_manual"         "settings get system display_density_index_manual"
kvd "system density"                              "settings get system density" "(空)"
kv  "global default_assistant"                    "settings get global default_assistant"
kvd "secure assistant"                            "settings get secure assistant" "(空)"
kvd "secure voice_interaction_service"            "settings get secure voice_interaction_service" "(空)"
echo
echo "### 5.1 oplus_* 键名清单（先列名，再取有限几个值）"
echo
kv "global 命名空间 oplus_* 键数" "settings list global 2>/dev/null | grep -cE '^oplus_'"
echo
echo '```'
if settings list global 2>/dev/null | grep -qE '^oplus_'; then
  settings list global 2>/dev/null | grep -E '^oplus_' | cut -d= -f1
else
  echo "(未取到)"; fail "settings list global oplus_*"
fi
echo '```'
echo
echo "与性能/温控/刷新率/密度相关的键值（只取下列有限几个，不 dump 全部）："
echo
echo "| 键 | 值 |"
echo "|---|---|"
kv "system oplus.thermal.low.voltage"          "settings get system oplus.thermal.low.voltage"
kv "system oplus.thermal.camera.low.voltage"   "settings get system oplus.thermal.camera.low.voltage"
kv "system oplus.radio.smart5g_thermal_cfg"    "settings get system oplus.radio.smart5g_thermal_cfg"
kv "system settings_thermal_high"              "settings get system settings_thermal_high"
kv "system oplus_customize_thermal_control_optimization_number" "settings get system oplus_customize_thermal_control_optimization_number"
kv "secure thermal_safety_mode"                "settings get secure thermal_safety_mode"
kv "system performance_mode_enable"            "settings get system performance_mode_enable"
kv "system high_performance_mode_on"           "settings get system high_performance_mode_on"
kv "global SpeedControlPara"                   "settings get global SpeedControlPara"
kv "global oplus_customize_ai_power"           "settings get global oplus_customize_ai_power"
echo
echo '> 说明：本表不含 Wi-Fi 密码、账号、签名哈希等敏感值；无障碍/通知监听只列包名，不列凭据。'
echo

# --------------------------------------------------------- 6. 网络与本地服务
echo "## 6. 网络与本地服务"
echo
echo "### 6.1 接口地址简表（已滤掉 lo / ifb / dummy / rmnet 噪声）"
echo
echo '```'
if ip -o addr >/dev/null 2>&1; then
  ip -o addr 2>/dev/null | awk '{print $2" "$3" "$4}' \
    | grep -vE '^(lo|dummy0|ifb[0-9]) ' | head -24
else
  echo "(未取到)"; fail "ip -o addr"
fi
echo '```'
echo
echo "### 6.2 本地监听端口"
echo
echo '```'
if command -v ss >/dev/null 2>&1; then
  ss -tln 2>/dev/null | head -30 || true
elif command -v netstat >/dev/null 2>&1; then
  netstat -tln 2>/dev/null | head -30 || true
else
  echo "(ss / netstat 都不可用)"; fail "ss/netstat"
fi
echo '```'
echo
echo "### 6.3 Eta terminal_daemon 任务目录"
echo
kv "daemon 目录存在" "test -d /data/local/tmp/eta/daemon && echo yes || echo no"
echo
echo '```'
ls -la /data/local/tmp/eta/daemon/ 2>/dev/null | head -25 || { echo "(未取到)"; fail "ls daemon"; }
echo '```'
echo
echo '任务退出码（`job_*.exit` 内容）：'
echo
echo '```'
_dfound=0
for f in /data/local/tmp/eta/daemon/job_*.exit; do
  [ -f "$f" ] || continue
  _dfound=1
  printf '%s = %s\n' "$(basename "$f")" "$(cat "$f" 2>/dev/null)"
done
[ "$_dfound" = 1 ] || echo "(没有 .exit 文件)"
echo '```'
echo

# ------------------------------------------------------- 7. 存储、内存、时长
echo "## 7. 存储与内存"
echo
echo "### 7.1 存储"
echo
echo '```'
echo '# df -h /data （注意 toybox df 对 /data 的 canonicalize 有已知怪异输出）'
df -h /data 2>/dev/null || { echo "(未取到)"; fail "df -h /data"; }
echo '# df -h /data/local/tmp （用户数据分区）'
df -h /data/local/tmp 2>/dev/null || { echo "(未取到)"; fail "df -h /data/local/tmp"; }
echo '# df -h /storage/emulated/0 （内部存储视角）'
df -h /storage/emulated/0 2>/dev/null || { echo "(未取到)"; fail "df -h /storage/emulated/0"; }
echo '```'
echo
echo "### 7.2 内存"
echo
echo '```'
free -m 2>/dev/null || { echo "(未取到)"; fail "free -m"; }
echo '```'
echo
echo "### 7.3 开机时长"
echo
echo '```'
if [ -r /proc/uptime ]; then
  UP=$(awk '{print $1}' /proc/uptime 2>/dev/null)
  echo "/proc/uptime: ${UP}"
  if [ -n "$UP" ]; then
    awk -v s="$UP" 'BEGIN{printf "约 %.2f 小时（%.2f 天）\n", s/3600, s/86400}'
  else
    echo "(折算失败)"; fail "/proc/uptime 折算"
  fi
else
  echo "(未取到)"; fail "/proc/uptime"
fi
echo '```'
echo

# -------------------------------------------------------------- 8. 包计数
echo "## 8. 包计数"
echo
echo "| 项 | 值 |"
echo "|---|---|"
kv "全部包 (pm list packages)"        "pm list packages 2>/dev/null | wc -l"
kv "第三方包 (pm list packages -3)"    "pm list packages -3 2>/dev/null | wc -l"
kv "com.oplus.* / com.coloros.* 包数" "pm list packages 2>/dev/null | grep -cE 'com\\.(oplus|coloros)\\.'"
echo
echo '> 注：`pm list packages -3` 只代表「装在 data 分区」，不代表「用户装的应用」，别用它判断系统应用。'
echo

# --------------------------------------------------------- 9. 关键应用版本
echo "## 9. 关键应用版本"
echo
echo "| 应用 | 包名 | versionName | versionCode | lastUpdateTime |"
echo "|---|---|---|---|---|"
app_row() {
  _label="$1"; _pkg="$2"
  _raw=$(dumpsys package "$_pkg" 2>/dev/null | grep -E "versionName=|versionCode=|lastUpdateTime=" | head -3)
  if [ -z "$_raw" ]; then
    printf '| %s | `%s` | **未安装** | - | - |\n' "$_label" "$_pkg"
    return
  fi
  _vn=$(printf '%s\n' "$_raw" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)
  _vc=$(printf '%s\n' "$_raw" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  _lu=$(printf '%s\n' "$_raw" | sed -n 's/.*lastUpdateTime=\(.*\)$/\1/p' | head -1)
  printf '| %s | `%s` | %s | %s | %s |\n' "$_label" "$_pkg" "${_vn:-?}" "${_vc:-?}" "${_lu:-?}"
}
app_row "Eta（自签改造版）"   "io.github.mangi.eta"
app_row "LuckyTool"          "com.luckyzyx.luckytool"
app_row "小布助手"           "com.heytap.speechassist"
app_row "ColorDirectService" "com.coloros.colordirectservice"
app_row "DeepThinker"        "com.oplus.deepthinker"
app_row "小布记忆(已下线?)"   "com.oplus.aimemory"
echo
echo "### 9.1 Eta APK 指纹"
echo
echo '```'
ETA_PATH=$(dumpsys package io.github.mangi.eta 2>/dev/null | sed -n 's/.*codePath=\(.*\)$/\1/p' | head -1)
if [ -n "$ETA_PATH" ]; then
  echo "codePath = ${ETA_PATH}"
  echo "base.apk = ${ETA_PATH}/base.apk"
  if [ -f "${ETA_PATH}/base.apk" ]; then
    ls -l "${ETA_PATH}/base.apk" 2>/dev/null
    sha256sum "${ETA_PATH}/base.apk" 2>/dev/null || { echo "(未取到)"; fail "sha256 Eta"; }
  else
    echo "(未取到)"; fail "Eta base.apk 不存在"
  fi
else
  echo "(未取到)"; fail "Eta codePath"
fi
echo '```'
echo
kv "com.oplus.aimemory 数据目录" "test -d /data/data/com.oplus.aimemory && echo present || echo 'absent（未安装）'"
echo

# ------------------------------------------------------------ 附：失败清单
echo "---"
echo
echo "## 附：本次采集失败的节"
echo
if [ -z "$FAILED" ]; then
  echo "无。全部节采集成功。"
else
  printf '%b' "$FAILED" | sed 's/^- /- /'
fi
echo
