#!/system/bin/sh
# data-guard.sh —— Eta 关键数据的「体检 + 快照 + 回滚」。
#
# 为什么需要它：用户明确说过「我怕的就是你把我数据损坏了」。
# 现有做法是「动手前我记得 cp 一份」——靠自觉，不可靠。这轮我确实犯过：
# 写记忆时把「详细事实」整段截掉、加章节时把标题顶掉、改任务 id 导致历史丢失。
#
# 设计原则：
# - **只读体检**：check 不改任何东西。
# - **快照可回滚**：backup 把关键文件复制到带时间戳的目录，restore 能对回去。
# - **不碰系统**：只管 Eta 自己的私有数据，不碰 /system、/data/adb。
# - 缺文件不报错崩掉，而是明确说「缺」。
#
# 用法：
#   sh data-guard.sh check      # 体检，有异常返回 1
#   sh data-guard.sh backup     # 快照到 03-备份-backup/data-snap-<时间>/
#   sh data-guard.sh list       # 列出已有快照
#   sh data-guard.sh restore <快照名>   # 从指定快照恢复（会先自动再备份一次）

set -u

PKG=io.github.mangi.eta
DATA=/data/data/$PKG
WORKSPACE="${ETA_WORKSPACE:-/data/local/tmp/eta}"
BACKUP_ROOT="$WORKSPACE/03-备份-backup"

# 要保护的东西：路径|说明
# 只放「丢了会心疼」的，不放缓存和临时文件。
CRITICAL_FILES="
$DATA/files/memory/MEMORY.md|记忆（最重要的一个）
$DATA/shared_prefs/eta_scheduled_tasks.xml|定时任务定义
$DATA/shared_prefs/eta_scheduled_runs.xml|定时任务执行历史
$DATA/shared_prefs/eta_agent_preferences.xml|Agent 设置
$DATA/shared_prefs/eta_roleplay.xml|角色扮演设置
$DATA/files/skills/self-improving-agent/data/ERRORS.md|失败归档
"

CRITICAL_DIRS="
$DATA/files/skills|技能库
"

say() { printf '%s\n' "$1"; }

# ---------------------------------------------------------------- check
do_check() {
  bad=0
  say "=============================================="
  say " Eta 数据体检   $(date '+%Y-%m-%d %H:%M')"
  say "=============================================="

  say ""
  say "-- 关键文件 --"
  # 用 here-doc 而不是 `printf | while`：管道会让 while 跑在子 shell 里，
  # 里面改的 bad 传不出来（实测踩过：体检报「正常」但退出码是 1）。
  while IFS='|' read -r f desc; do
    [ -z "$f" ] && continue
    if [ ! -f "$f" ]; then
      say "  [缺] $desc"
      bad=1
      continue
    fi
    size=$(wc -c < "$f" 2>/dev/null | tr -d ' ')
    if [ "${size:-0}" -lt 10 ]; then
      say "  [异常] $desc 只有 ${size} 字节，可能被清空"
      bad=1
      continue
    fi
    owner=$(ls -l "$f" 2>/dev/null | awk '{print $3}')
    if [ "$owner" != "u0_a435" ]; then
      say "  [属主异常] $desc 属主是 $owner，应为 u0_a435"
      bad=1
      continue
    fi
    say "  [正常] $desc  ${size} 字节"
  done <<EOF
$CRITICAL_FILES
EOF

  say ""
  say "-- 记忆结构 --"
  MEM="$DATA/files/memory/MEMORY.md"
  if [ -f "$MEM" ]; then
    core=$(grep -c '^# 核心记忆' "$MEM" 2>/dev/null | head -1)
    detail=$(grep -c '^# 详细事实' "$MEM" 2>/dev/null | head -1)
    [ -z "$core" ] && core=0
    [ -z "$detail" ] && detail=0
    say "  核心记忆标题: $core 个（应为 1）"
    say "  详细事实标题: $detail 个（应为 0 或 1）"
    if [ "$core" -ne 1 ]; then
      say "  [异常] 核心记忆标题数量不对，可能被写重复或删掉了"
      bad=1
    fi
    # 空标题检测：## 后面直接跟空行
    empty=$(grep -cE '^##[^#]*$' "$MEM" 2>/dev/null | head -1)
    [ -z "$empty" ] && empty=0
    say "  二级标题总数: $empty"
  else
    say "  [缺] MEMORY.md 不存在"
    bad=1
  fi

  say ""
  say "-- 定时任务 --"
  TX="$DATA/shared_prefs/eta_scheduled_tasks.xml"
  if [ -f "$TX" ]; then
    n=$(grep -o '&quot;id&quot;:&quot;[^&]*' "$TX" 2>/dev/null | wc -l | tr -d ' ')
    say "  任务数: $n"
    # 结构完整性：必须有 map 收尾
    if ! grep -q '</map>' "$TX" 2>/dev/null; then
      say "  [异常] XML 没有 </map> 结尾，文件可能被写坏"
      bad=1
    fi
    if [ "$n" -eq 0 ]; then
      say "  [警告] 任务数为 0"
    fi
  else
    say "  [缺] 任务配置不存在"
  fi

  say ""
  say "-- 技能库 --"
  SK="$DATA/files/skills"
  if [ -d "$SK" ]; then
    cnt=$(ls -1 "$SK" 2>/dev/null | wc -l | tr -d ' ')
    badowner=$(find "$SK" -maxdepth 2 -not -user u0_a435 2>/dev/null | wc -l | tr -d ' ')
    say "  技能数: $cnt"
    if [ "$badowner" -gt 0 ]; then
      say "  [异常] $badowner 项属主不是 u0_a435（会导致无法更新/卸载）"
      bad=1
    fi
  else
    say "  [缺] 技能目录不存在"
    bad=1
  fi

  say ""
  say "-- 最近快照 --"
  latest=$(ls -1d "$BACKUP_ROOT"/data-snap-* 2>/dev/null | tail -1)
  if [ -n "${latest:-}" ]; then
    say "  $latest"
  else
    say "  （还没有快照，建议先跑 backup）"
  fi

  say ""
  say "=============================================="
  if [ "$bad" -eq 0 ]; then
    say " 结论：正常"
    exit 0
  fi
  say " 结论：发现异常，详见上面标 [异常]/[缺] 的行"
  exit 1
}

# ---------------------------------------------------------------- backup
do_backup() {
  stamp=$(date +%Y%m%d-%H%M%S)
  dest="$BACKUP_ROOT/data-snap-$stamp"
  mkdir -p "$dest" || { say "无法创建 $dest"; exit 1; }
  saved=0
  while IFS='|' read -r f desc; do
    [ -z "$f" ] && continue
    [ -f "$f" ] || continue
    name=$(basename "$f")
    if cp -p "$f" "$dest/$name" 2>/dev/null; then
      saved=$((saved + 1))
    fi
  done <<EOF
$CRITICAL_FILES
EOF
  # 技能库只备份 SKILL.md，不搬整个目录（体积大且不是每次都需要）
  if [ -d "$DATA/files/skills" ]; then
    mkdir -p "$dest/skills-manifest"
    find "$DATA/files/skills" -maxdepth 2 -name "SKILL.md" -exec cp -p {} "$dest/skills-manifest/" \; 2>/dev/null
  fi
  n=$(ls -1 "$dest" 2>/dev/null | wc -l | tr -d ' ')
  say "快照已保存：$dest（$n 项）"
  say "回滚：sh $WORKSPACE/04-工具-tools/data-guard.sh restore data-snap-$stamp"
}

# ---------------------------------------------------------------- list
do_list() {
  say "已有快照："
  found=0
  for d in "$BACKUP_ROOT"/data-snap-*; do
    [ -d "$d" ] || continue
    found=1
    n=$(ls -1 "$d" 2>/dev/null | wc -l | tr -d ' ')
    say "  $(basename "$d")  ($n 项)"
  done
  [ "$found" -eq 0 ] && say "  （无）"
  return 0
}

# ---------------------------------------------------------------- restore
do_restore() {
  name="${1:-}"
  [ -z "$name" ] && { say "用法：sh data-guard.sh restore <快照名>"; exit 2; }
  src="$BACKUP_ROOT/$name"
  [ -d "$src" ] || { say "快照不存在：$src"; exit 1; }

  # 恢复前先给当前状态留一份，避免「越恢复越糟」
  say "恢复前先备份当前状态……"
  do_backup

  say ""
  say "从 $src 恢复："
  for f in "$src"/*; do
    [ -f "$f" ] || continue
    name_only=$(basename "$f")
    case "$name_only" in
      MEMORY.md) target="$DATA/files/memory/MEMORY.md" ;;
      eta_scheduled_tasks.xml|eta_scheduled_runs.xml|eta_agent_preferences.xml|eta_roleplay.xml)
        target="$DATA/shared_prefs/$name_only" ;;
      ERRORS.md) target="$DATA/files/skills/self-improving-agent/data/ERRORS.md" ;;
      *) continue ;;
    esac
    mkdir -p "$(dirname "$target")"
    if cp -p "$f" "$target" 2>/dev/null; then
      chown 10435:10435 "$target" 2>/dev/null
      chmod 600 "$target" 2>/dev/null
      say "  已恢复 $name_only → $target"
    else
      say "  [失败] $name_only"
    fi
  done
  say ""
  say "恢复完成。重启 Eta 让 SharedPreferences 重新加载。"
}

case "${1:-check}" in
  check) do_check ;;
  backup) do_backup ;;
  list) do_list ;;
  restore) shift; do_restore "${1:-}" ;;
  *) say "用法：sh data-guard.sh {check|backup|list|restore <快照名>}"; exit 2 ;;
esac
