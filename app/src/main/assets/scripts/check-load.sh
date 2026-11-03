#!/system/bin/sh
# =============================================================================
# check-load.sh —— 重操作前 / 后的「负载 + 温度 + 自己起的重进程」体检
#
# 用途：Gradle 构建、jadx 反编译、连续 dumpsys、全树 rg 扫描、多个子代理并行构建
#       这类重活之前先跑一次。结论三档：正常就开工，谨慎就只做轻活，别开工就先散热。
#
# 只读声明：只读 /proc、/sys/class/thermal、dumpsys。不写任何系统文件、不改设置、
#           不杀进程 —— 清理命令只是打印出来，由使用者确认后自己执行。
#
# 跑法：sh /data/local/tmp/eta/04-工具-tools/check-load.sh
#
# 本机：realme RMX3700 / 骁龙 7+ Gen 2 (SM7475) / 8 核
#       policy0 = cpu0-3 小核 1.80GHz｜policy4 = cpu4-6 大核 2.49GHz｜cpu7 单核 2.91GHz
# 阈值标定日期：2026-11-02（本机实测；下面 §1 的阈值就是这一天校准的）
#
# ---- 2026-11-02 本机实测的坑（不要照抄网上的通用路径）-----------------------
# ★ 温度来源用 /sys/class/thermal/thermal_zone*/：本机共 88 个 zone，temp 值=毫摄氏度
#   （battery zone = 41300 与 dumpsys battery temperature: 413 完全对上，确认是 /1000 ℃）。
# ★ dumpsys thermalservice 在本机**不能当读数来源**：全文只有 27 行，
#   "Current temperatures from HAL:" 是空的，只有一条缓存值 shell_skin 42.0℃ 和
#   "Thermal Status: 2"。→ 脚本以 sysfs 为主，dumpsys 只取 Thermal Status 作旁证。
# ★ thermal_zone 里不是每个 temp 都是温度：vbat=3637 是 mV、pm8350b-ibat-lvl*=312 是 mA、
#   socd=46 无量纲、usb / isense_trim / *-bcl-lvl*=0、mmw* 与 sdr-mmw*=27000 是假值、
#   -273000 与 -40000 是没接的传感器。→ 按 type 名字挑，不按数值猜。
# ★ /sys/class/hwmon 在本机是**空的**；/sys/devices/virtual/thermal/thermal_zone* 与
#   /sys/class/thermal/thermal_zone* 是同一批 zone，没有额外信息。
# ★ Android 的 ps 不支持 %CPU/pcpu 字段，只认 user,group,comm,args,pid,ppid,pgid,etime,
#   nice,rgroup,ruser,time,tty,vsz,sid,stat,rss,label（写 ps -eo pid,pcpu,comm 直接报错）。
#   busybox top 也不支持 -o；而且 top -n 2 -d 1 两屏的 loadavg 完全相同、%CPU 明显偏小。
#   → 本脚本的 CPU 占用**全部来自 /proc/<pid>/stat 的 utime+stime 差值**（1 秒窗口），
#     换算比例用同窗口的 /proc/stat 总 jiffies 自校准，不需要假设 HZ。
# ★ 内核把进程名截断到 15 字符（io.github.mangi.eta → ithub.mangi.eta；
#   KotlinCompileDaemon → KotlinCompileDa），所以匹配按前缀，身份另读 /proc/<pid>/cmdline。
# =============================================================================

# ---- §1 判定阈值（本机 8 核标定，2026-11-02；要改就改这里）-------------------
LOAD_OK=4        # 1 分钟 loadavg < 4（8 核 → 每核 0.5）算负载正常
LOAD_WARN=8      # 1 分钟 loadavg > 8（每核 1.0）直接判【别开工】
TEMP_OK=55       # CPU 温度 < 55℃ 算温度正常
TEMP_WARN=65     # CPU 温度 > 65℃ 直接判【别开工】
# 判据：正常 = load1<LOAD_OK 且 CPU<TEMP_OK
#       谨慎 = load1 落在 [LOAD_OK,LOAD_WARN] 或 CPU 落在 [TEMP_OK,TEMP_WARN]
#       别开工 = load1>LOAD_WARN 或 CPU>TEMP_WARN
# （8 核参考值来自本机实测标定；换机型请重新标定这 4 个数。）

# ★ nproc 在本机不能当核数用：Eta 终端进程受 cpuset/affinity 限制，实测
#   /proc/self/status 的 Cpus_allowed_list 会是 "0-4,6"，于是 nproc 时好时坏地返回
#   6 / 7 / 8（同一秒连测 5 次：7 6 7 6 6）。核数只认 /sys/devices/system/cpu/online。
CORES=$(awk -v RS=, '{ if ($0 ~ /-/) { split($0,a,"-"); n += a[2]-a[1]+1 } else n += 1 } \
        END { print n }' /sys/devices/system/cpu/online 2>/dev/null)
case "$CORES" in ''|*[!0-9]*) CORES=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null) ;; esac
case "$CORES" in ''|*[!0-9]*) CORES=8 ;; esac
AFF=$(awk '/Cpus_allowed_list/{print $2}' /proc/$$/status 2>/dev/null)

# ============================ 取样（静默，只读）==============================
# 1) 负载
# ★ 非 root 时 /proc/loadavg 会 Permission denied（实测 app uid：loadavg/stat/swaps/pressure
#   全被拒）。绝不能把读不到当成 0 —— 那会得出假的【正常】。所以显式区分"读不到"。
LA_RAW=$(cat /proc/loadavg 2>/dev/null)
LOAD_UNK=0
if [ -n "$LA_RAW" ]; then
  set -- $LA_RAW
  L1=${1:-0}; L5=${2:-0}; L15=${3:-0}; RUNQ=${4:-}; L1N=${1:-0}
else
  L1="读不到"; L5="读不到"; L15="读不到"; RUNQ=""; L1N=0; LOAD_UNK=1
fi
[ "$(id -u 2>/dev/null)" = "0" ] || NONROOT=1
NONROOT=${NONROOT:-0}

# 2) 全部 thermal zone（type + 毫摄氏度）
THERM=""
for z in /sys/class/thermal/thermal_zone*; do
  t=$(cat "$z/type" 2>/dev/null)
  v=$(cat "$z/temp" 2>/dev/null)
  case "$v" in ''|*[!0-9-]*) continue ;; esac
  [ -n "$t" ] && THERM="$THERM$t $v
"
done
[ -z "$THERM" ] && THERM=$(cat /sys/devices/virtual/thermal/thermal_zone*/type 2>/dev/null | head -0)

# 按名字取最相关温度（℃）
pick_max() {
  echo "$THERM" | awk -v pat="$1" '
    $1 ~ pat && $2+0 > 15000 && $2+0 < 150000 { if ($2+0 > m) m = $2+0 }
    END { if (m > 0) printf "%.1f", m/1000 }'
}
CPU_T=$(pick_max '^(cpuss-|cpu-[0-9])')
GPU_T=$(pick_max '^gpuss-')
BAT_T=$(pick_max '^battery$')
SKIN_T=$(pick_max '^(skin-msm-therm|shell_)')

# 3) dumpsys 旁证（温度读不到时的兜底 + Thermal Status）
BT_TEMP=$(dumpsys battery 2>/dev/null | grep -E 'temperature:' | head -1 | tr -d ' ' | cut -d: -f2)
BT_STATUS=$(dumpsys battery 2>/dev/null | grep -E '^ *status:' | head -1 | tr -d ' ' | cut -d: -f2)
BT_LEVEL=$(dumpsys battery 2>/dev/null | grep -E '^ *level:' | head -1 | tr -d ' ' | cut -d: -f2)
TS=$(dumpsys thermalservice 2>/dev/null | grep -E 'Thermal Status:' | head -1 | tr -d ' ' | cut -d: -f2)
[ -z "$CPU_T" ] && [ -n "$BT_TEMP" ] && CPU_T=$(awk -v v="$BT_TEMP" 'BEGIN{printf "%.1f", v/10}')

# 4) CPU 占用：1 秒窗口，两次 /proc 快照
snap() {
  awk '{ s=$0; pid=$1; sub(/^[0-9]+ \(/,"",s); comm=s; sub(/\).*/,"",comm);
         sub(/^[^)]*\)/,"",s); split(s,f," "); print pid, f[12]+f[13], comm }' \
    /proc/[0-9]*/stat 2>/dev/null || true
}
T1=$(awk '/^cpu /{print $2+$3+$4+$5+$6+$7+$8}' /proc/stat 2>/dev/null)
I1=$(awk '/^cpu /{print $5+$6}' /proc/stat 2>/dev/null)
S1=$(snap)
sleep 1
T2=$(awk '/^cpu /{print $2+$3+$4+$5+$6+$7+$8}' /proc/stat 2>/dev/null)
I2=$(awk '/^cpu /{print $5+$6}' /proc/stat 2>/dev/null)
S2=$(snap)

BUSY=""
case "$T1$T2" in ''|*[!0-9]*) BUSY="" ;; *)
  [ "$((T2-T1))" -gt 0 ] && BUSY=$(( (100*( (T2-T1)-(I2-I1) ))/(T2-T1) )) ; esac
# 每进程 CPU%（相对单核的百分比，等价于 top 的 %CPU 口径）
TOP=$({ printf '%s\n@@\n%s\n' "$S1" "$S2" | awk -v c="$CORES" -v tot="$((T2-T1))" '
  BEGIN{ph=1}
  $0=="@@"{ph=2; next}
  { if (ph==1) { u[$1]=$2; nm[$1]=$3 }
    else if ($1 in u) { d=$2-u[$1]; if (d < 0) d = 0; printf "%.1f %s %s\n", d*100*c/tot, $1, nm[$1] } }'
} 2>/dev/null | sort -rn)
[ -z "$TOP" ] && TOP=""

PSI10=$(awk '/^some/{sub(/avg10=/,"",$2); print $2}' /proc/pressure/cpu 2>/dev/null)

# 5) 内存 / swap
# ★ free 不是同一个程序：root(busybox) 输出 7 列带 available，非 root 落到 toybox 只有 6 列
#   （total used free shared buffers，没有 available）→ 不能按第 7 列取，改用 /proc/meminfo。
MEMTOT_K=$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null)
MEMAVA_K=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)
MEM=$(free -m 2>/dev/null | awk '/^Mem:/{print $2, $3, $4, $5}')
SWP=$(awk 'NR==2{printf "%d %d %s", $3/1024, $4/1024, $1}' /proc/swaps 2>/dev/null)
[ -z "$SWP" ] && SWP=$(free -m 2>/dev/null | awk '/^Swap:/{printf "%s %s zram", $2, $3}')

# ============================ 结论（第一行）=================================
if [ "$LOAD_UNK" = 1 ]; then
  VERDICT=$(awk -v t="${CPU_T:-0}" -v tw="$TEMP_WARN" 'BEGIN{
    if (t > tw) print "【别开工】先散热"; else print "【谨慎】只做轻活" }')
  VNOTE="非 root：/proc/loadavg 读不到（Permission denied）→ 负载无法确认，按保守处理，不给【正常】"
elif [ -z "$CPU_T" ]; then
  VERDICT="【谨慎】只做轻活"
  VNOTE="CPU 温度读不到（sysfs 与电池温度兜底都失败）→ 按谨慎处理"
else
  VERDICT=$(awk -v l="$L1N" -v t="$CPU_T" -v lo="$LOAD_OK" -v lw="$LOAD_WARN" \
                -v to="$TEMP_OK" -v tw="$TEMP_WARN" 'BEGIN{
    if (l < lo && t < to)      print "【正常】可以开工";
    else if (l > lw || t > tw) print "【别开工】先散热";
    else                       print "【谨慎】只做轻活" }')
  VNOTE=""
fi
PCORE=$(awk -v l="$L1N" -v c="$CORES" -v u="$LOAD_UNK" 'BEGIN{ if (u == 1) print "读不到"; else if (c>0) printf "%.2f", l/c; else print "-" }')

echo "=============================================================="
echo " 本机体检（只读）   $(date '+%Y-%m-%d %H:%M:%S')   RMX3700 / $CORES 核"
echo "=============================================================="
[ "$NONROOT" = 1 ] && echo " ！非 root 运行：/proc/loadavg、/proc/stat、/proc/swaps、/proc/pressure、别的进程的"
[ "$NONROOT" = 1 ] && echo "   /proc/<pid>/stat 全被 Permission denied（Android procfs 限制）→ 负载与 CPU 占用测不了，按保守处理"
echo "结论：$VERDICT"
echo "  依据：1分钟负载 $L1（每核 $PCORE）｜CPU ${CPU_T:-?}℃｜电池 ${BAT_T:-?}℃｜机身 ${SKIN_T:-?}℃"
echo "  判据：8 核标定 → 正常 load1<$LOAD_OK 且 CPU<$TEMP_OK℃；"
echo "        谨慎 load1∈[$LOAD_OK,$LOAD_WARN] 或 CPU∈[$TEMP_OK,$TEMP_WARN]℃；别开工 load1>$LOAD_WARN 或 CPU>$TEMP_WARN℃"
[ -n "$VNOTE" ] && echo "  ！$VNOTE"
[ -n "$BUSY" ] && echo "  实测 1 秒窗口整机 CPU 忙 $BUSY%（8 核均值）${PSI10:+｜PSI cpu some avg10=$PSI10}"
if [ -n "$BUSY" ] && [ "$BUSY" -lt 30 ] 2>/dev/null && \
   awk -v l="$L1N" -v c="$CORES" -v u="$LOAD_UNK" 'BEGIN{exit !(u==0 && l > c*0.75)}' 2>/dev/null; then
  echo "  备注：loadavg 明显高于实际 CPU 忙度 → 里面混着等 I/O 的进程（本机 zram swap 活跃），别只看 load 数字"
fi
echo "--------------------------------------------------------------"

# ============================ [1] 负载 ======================================
echo "[1] 负载"
echo "    loadavg 1/5/15 分钟 : $L1  $L5  $L15"
echo "    核数 / 每核负载      : $CORES 核 / 每核 $PCORE  （参考：每核<0.5 轻松，>1.0 已饱和）"
[ -n "$RUNQ" ] && echo "    可运行/总进程        : $RUNQ"
[ "$LOAD_UNK" = 1 ] && echo "    ↑ 这里显示「读不到」是真的读不到（非 root 被 Android procfs 拒），不是负载为 0"
[ -n "$AFF" ] && echo "    本 shell CPU 亲和性  : $AFF（cpuset 限制，所以 nproc 不可信；核数取 /sys/devices/system/cpu/online）"

# ============================ [2] 温度 ======================================
echo "[2] 温度（℃；sysfs 毫摄氏度/1000，按 type 名字挑，不是按数值猜）"
echo "    CPU  max(cpuss-*/cpu-*)       : ${CPU_T:-读不到}"
echo "    GPU  max(gpuss-*)             : ${GPU_T:-读不到}"
echo "    电池 battery                  : ${BAT_T:-读不到}${BT_TEMP:+  （dumpsys battery: $(awk -v v="$BT_TEMP" 'BEGIN{printf "%.1f", v/10}')℃ 状态 $BT_STATUS 电量 $BT_LEVEL%）}"
echo "    机身 skin-msm-therm / shell_* : ${SKIN_T:-读不到}"
[ -n "$TS" ] && echo "    Thermal Status                : $TS（0=正常 1=轻 2=中 3=重；≥3 系统已在压频率）"
echo "    其余可用 zone（降序；可能是 PA / 充电 PMIC / 摄像头 / DDR / 显示等）："
REST=$(echo "$THERM" | awk '
  $1 ~ /^(cpuss-|cpu-)/ {next}  $1 ~ /^gpuss-/ {next}
  $1 == "battery" {next}        $1 ~ /^skin-msm-therm$/ {next}  $1 ~ /^shell_/ {next}
  $1 ~ /^mmw/ {next}            $1 ~ /^sdr-mmw/ {next}
  $1 ~ /-(ibat-lvl|bcl-lvl)/ {next}
  $1 == "vbat" || $1 == "socd" || $1 == "isense_trim" || $1 == "usb" {next}
  $2+0 <= 15000 || $2+0 >= 150000 {next}
  { printf "%s=%.1f\n", $1, $2/1000 }' 2>/dev/null \
  | sort -t= -k2 -rn | awk '{ printf "%s  ", $0; n++; if (n%3==0) print "" } END { if (n%3!=0) print "" }')
if [ -n "$REST" ]; then echo "$REST" | sed 's/^/    /' ; else echo "    （无）"; fi

# ============================ [3] 我自己的重进程 ============================
echo "[3] 我自己的重进程（java / gradle / kotlin / jadx / dex2oat / rg）"
HEAVY=$(echo "$TOP" | grep -iE ' (java|gradle|kotlin|jadx|dex2oat|rustc)| rg$' || true)
# 名字被截断到 15 字符，且 java 进程的 comm 可能就是 java → 再用 cmdline 补一遍
JPS=$(ps -A -o pid,args 2>/dev/null | grep -iE 'gradle|kotlin|jadx|dex2oat' | grep -vE 'grep -iE|ps -A -o' || true)
if [ -n "$HEAVY" ]; then
  echo "    CPU%    PID   进程名（CPU% 是 1 秒窗口实测；0.0 = 这个窗口没吃 CPU，但进程还在）"
  echo "$HEAVY" | awk '{printf "    %6s  %6s  %s\n", $1, $2, $3}'
else
  echo "    （未发现 java/gradle/kotlin/jadx/dex2oat/rg 类进程）"
fi
if [ -n "$JPS" ]; then
  echo "    cmdline 命中（可能是 com 名字看不出来的 JVM 进程）："
  echo "$JPS" | head -3 | awk '{printf "      pid %s\n", $1}'
fi
case "$HEAVY$JPS" in *[Gg]radle*) GSEEN=1 ;; *) GSEEN=0 ;; esac
case "$HEAVY$JPS" in *[Kk]otlin*) KSEEN=1 ;; *) KSEEN=0 ;; esac
if [ "$GSEEN" = 1 ] || [ "$KSEEN" = 1 ]; then
  echo "    → 该收尾（构建守护不会随构建退出，实测有 GradleDaemon 占 55% CPU、跑了 3h41m 不退）："
  [ "$GSEEN" = 1 ] && echo "       /opt/gradle/gradle-9.7.1/bin/gradle --stop"
  [ "$KSEEN" = 1 ] && echo "       pkill -f KotlinCompileDaemon"
  echo "       # 别写 pkill -f kotlin-daemon：会匹配到自己的命令行而自杀（实测 exit_code=143）"
else
  echo "    → 没有构建类残留，不需要收尾。"
fi
if [ -n "$HEAVY" ] && [ "$GSEEN" = 0 ] && [ "$KSEEN" = 0 ]; then
  echo "    → 上面的 java/dex2oat/jadx 是你自己起的就等它跑完；不确定是谁起的不要乱杀。"
fi

# ============================ [4] CPU 占用前 6 ==============================
echo "[4] CPU 占用前 6（1 秒窗口实测；已滤掉脚本自身的 awk/sed/sort 等取样进程）"
if [ -n "$TOP" ]; then
  echo "$TOP" | grep -vE ' (awk|sed|grep|sort|head|cat|sleep|tr|cut|wc|sh|busybox|top|dumpsys|ps|tee|sh\.orig)$' \
    | head -6 | awk '{printf "    %6s%%  pid %-6s %s\n", $1, $2, $3}'
else
  echo "    （/proc 取样失败，回退 top -n 1 -b，数值粗略）"
  top -n 1 -b 2>/dev/null | sed -n '4,9p' || echo "    （top 也失败）"
fi

# ============================ [5] 内存 / swap ===============================
echo "[5] 内存 / swap"
if [ -n "$MEMTOT_K" ] && [ -n "$MEMAVA_K" ]; then
  echo "    Mem：总 $((MEMTOT_K/1024))M  可用(MemAvailable) $((MEMAVA_K/1024))M  已用 $(( (MEMTOT_K-MEMAVA_K)/1024 ))M"
elif [ -n "$MEM" ]; then
  echo "    Mem（free -m 兜底，列口径随身份会变）：$(echo "$MEM" | tr ' ' '/')M"
else
  echo "    （内存读取失败）"
fi
if [ -n "$SWP" ]; then
  s_tot=$(echo "$SWP" | cut -d' ' -f1); s_used=$(echo "$SWP" | cut -d' ' -f2); s_dev=$(echo "$SWP" | cut -d' ' -f3)
  echo "    Swap $s_dev：总 ${s_tot}M  已用 ${s_used}M（本机 swap 是 zram 压缩内存，占用高说明内存压力大、会额外吃 CPU）"
else
  echo "    （swap 读不到；非 root 时 /proc/swaps 被 Permission denied）"
fi

# ============================ [6] 提示 ======================================
echo "--------------------------------------------------------------"
echo "[6] 重操作前 / 后该做什么"
echo "    1) 重操作前先跑本脚本：【谨慎】只做轻活，【别开工】先散热，绝不在发烫时叠加新任务。"
echo "    2) 重操作后（成功失败都要收尾）：/opt/gradle/gradle-9.7.1/bin/gradle --stop + pkill -f KotlinCompileDaemon，"
echo "       再用 ps -A -o pid,comm,args | grep -iE 'java|gradle' 确认没有残留。"
echo "    3) 不并发重活：同一时间只跑一个构建；子代理里含构建的同一批只派一个；构建中不要叠加 jadx / 全量 dumpsys。"
echo "    4) 长会话本身就是热点：超长任务拆到子代理或开新会话（Eta 跑超长对话实测 CPU 到过 87%）。"
echo "=============================================================="
exit 0
