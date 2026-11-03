#!/system/bin/sh
# self-status.sh —— 一次性汇总「我现在处于什么状态」，只读，不改任何东西。
#
# 为什么有这个脚本：每次开工我都要手工跑七八条命令（dumpsys package、gh api、
# 读 XML、查闹钟、看记忆大小……），而且常常漏掉某一项，导致基于不完整的认知动手。
# 这个脚本把那些检查固化下来，并且**主动指出不一致**，不需要我想到去查。
#
# 用法：
#   sh self-status.sh            # 人类可读
#   sh self-status.sh --json     # 机器可读，便于程序解析
#
# 设计原则：
# - 只读：不写、不改、不重启任何东西。
# - 每项都带「怎么算正常」，读的人不用再猜。
# - 缺依赖（比如没有 gh）不报错，标成「不可用」，不中断后面的检查。

set -u

WORKSPACE="${ETA_WORKSPACE:-/data/local/tmp/eta}"
PKG=io.github.mangi.eta
JSON=0
[ "${1:-}" = "--json" ] && JSON=1

line() { [ "$JSON" -eq 1 ] || printf '%s\n' "$1"; }

# ---------------------------------------------------------------- 1. 版本
VER=$(dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//')
[ -z "${VER:-}" ] && VER="未知"

# ---------------------------------------------------------------- 2. 进程
PROCS=$(ps -A -o pid,args 2>/dev/null | grep -c "[i]o.github.mangi.eta")
SCHED=$(ps -A -o pid,args 2>/dev/null | grep -c "[m]angi.eta:schedule")

# ---------------------------------------------------------------- 3. 定时任务与闹钟
TASKS_XML="/data/data/$PKG/shared_prefs/eta_scheduled_tasks.xml"
TASK_COUNT=$(grep -o '&quot;id&quot;:&quot;[^&]*' "$TASKS_XML" 2>/dev/null | wc -l | tr -d ' ')
ALARM_COUNT=$(dumpsys alarm 2>/dev/null | grep -cE '^[[:space:]]*tag=\*walarm\*:io.github.mangi.eta.action.RUN_SCHEDULED_TASK')

# ---------------------------------------------------------------- 4. 记忆
MEM="/data/data/$PKG/files/memory/MEMORY.md"
if [ -f "$MEM" ]; then
  MEM_BYTES=$(wc -c < "$MEM" | tr -d ' ')
  # 核心段 = 到「# 详细事实」之前
  CORE_BYTES=$(sed -n '1,/^# 详细事实/p' "$MEM" 2>/dev/null | wc -c | tr -d ' ')
  EXP_COUNT=$(grep -c '^## \[经验\]' "$MEM" 2>/dev/null | head -1)
  STALE_COUNT=$(grep -c '^## \[状态\]' "$MEM" 2>/dev/null | head -1)
  [ -z "$EXP_COUNT" ] && EXP_COUNT=0
  [ -z "$STALE_COUNT" ] && STALE_COUNT=0
  MEM_MB=0
  [ "$MEM_BYTES" -gt 1048576 ] && MEM_MB=1
else
  MEM_BYTES=0; CORE_BYTES=0; EXP_COUNT=0; STALE_COUNT=0; MEM_MB=0
fi

# ---------------------------------------------------------------- 5. 失败归档
ERR="/data/data/$PKG/files/skills/self-improving-agent/data/ERRORS.md"
if [ -f "$ERR" ]; then
  ERR_BYTES=$(wc -c < "$ERR" | tr -d ' ')
  ERR_COUNT=$(grep -c '^## 20' "$ERR" 2>/dev/null | head -1)
  [ -z "$ERR_COUNT" ] && ERR_COUNT=0
  # 只记了 code 没记原因的条目（历史遗留，值很低）
  ERR_VAGUE=$(grep -c '^- 消息：\(failed\|UNKNOWN\|[a-z_]*\)$' "$ERR" 2>/dev/null | head -1)
  [ -z "$ERR_VAGUE" ] && ERR_VAGUE=0
else
  ERR_BYTES=0; ERR_COUNT=0; ERR_VAGUE=0
fi

# ---------------------------------------------------------------- 6. 技能
SKILLS_DIR="/data/data/$PKG/files/skills"
SKILL_COUNT=$(ls -1 "$SKILLS_DIR" 2>/dev/null | wc -l | tr -d ' ')
# 非本应用属主的技能文件（会导致无法更新/卸载）
SKILL_BADOWNER=$(find "$SKILLS_DIR" -maxdepth 2 -not -user u0_a435 2>/dev/null | wc -l | tr -d ' ')

# ---------------------------------------------------------------- 7. 负载温度
if [ -f "$WORKSPACE/04-工具-tools/check-load.sh" ]; then
  LOADLINE=$(sh "$WORKSPACE/04-工具-tools/check-load.sh" 2>/dev/null | grep -m1 "结论：" | head -1)
else
  LOADLINE=""
fi
[ -z "${LOADLINE:-}" ] && LOADLINE="check-load.sh 不可用"

# ---------------------------------------------------------------- 输出
if [ "$JSON" -eq 1 ]; then
  printf '{"version":"%s","processes":%s,"schedule_process":%s,"tasks":%s,"alarms":%s,"memory_bytes":%s,"core_bytes":%s,"memory_experiences":%s,"memory_statuses":%s,"errors_count":%s,"errors_vague":%s,"skills":%s,"skills_bad_owner":%s}\n' \
    "$VER" "$PROCS" "$SCHED" "$TASK_COUNT" "$ALARM_COUNT" "$MEM_BYTES" "$CORE_BYTES" "$EXP_COUNT" "$STALE_COUNT" "$ERR_COUNT" "$ERR_VAGUE" "$SKILL_COUNT" "$SKILL_BADOWNER"
  exit 0
fi

echo "=============================================="
echo " 自改 状态速查   $(date '+%Y-%m-%d %H:%M')"
echo "=============================================="
echo "版本      : $VER"
echo "进程      : 主 $PROCS 个（含 :voice/:schedule），:schedule = $SCHED"
echo
echo "-- 定时任务 --"
echo "任务数    : $TASK_COUNT"
echo "闹钟数    : $ALARM_COUNT"
if [ "$TASK_COUNT" != "$ALARM_COUNT" ]; then
  echo "  [!] 任务数与闹钟数不一致：差 $((ALARM_COUNT - TASK_COUNT)) 个"
  echo "      可能是已删任务留下的孤儿闹钟，进一次任务页会重排（但孤儿不一定清掉）"
fi
echo
echo "-- 记忆 --"
echo "总大小    : $MEM_BYTES 字节（核心段 $CORE_BYTES）"
echo "经验条目  : $EXP_COUNT 条"
echo "状态条目  : $STALE_COUNT 条"
[ "$MEM_MB" -eq 1 ] && echo "  [!] 记忆已超过 1MB，建议整理"
echo
echo "-- 失败归档 --"
echo "记录数    : $ERR_COUNT 条（$ERR_BYTES 字节）"
if [ "$ERR_VAGUE" -gt 0 ]; then
  echo "  [i] 其中 $ERR_VAGUE 条没有具体原因（历史遗留，格式已在新版修正）"
fi
echo
echo "-- 技能 --"
echo "数量      : $SKILL_COUNT"
if [ "$SKILL_BADOWNER" -gt 0 ]; then
  echo "  [!] $SKILL_BADOWNER 个文件属主不是 u0_a435，会导致技能无法更新/卸载"
  echo "      修复：find $SKILLS_DIR -not -user u0_a435 -exec chown 10435:10435 {} +"
fi
echo
echo "-- 负载 --"
echo "$LOADLINE"
echo "=============================================="
