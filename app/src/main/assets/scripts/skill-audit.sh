#!/bin/sh
# skill-audit.sh —— Eta 技能文件体检（只读：不修改任何被检查文件）
#
# 用法：
#   sh skill-audit.sh                       # 报告打印到 stdout
#   sh skill-audit.sh -o 报告.md             # 同时把报告写入指定文件
#   sh skill-audit.sh -d /别的/技能根目录     # 换一个技能根目录
#
# 检查项：
#   1) frontmatter：文件是否以 --- 开头，且含 name: 与 description:
#   2) frontmatter 的 name 是否等于所在目录名（不相等是常见故障）
#   3) 正文里出现的绝对路径（/data/... /workspace/... /sdcard/...）是否真实存在
#      —— 含 < > * $ % 的行、写“不存在”的行、带 xxx/yyy/zzz 占位段或省略号 ... 的路径跳过
#   4) 正文提到的 bundled 脚本（scripts/xxx.sh 之类）是否存在、是否可执行
#   5) 每个技能 SKILL.md 的大小与最后修改时间
#
# 关于内置技能：内置技能打包在 APK 内部（应用资源里），文件系统里没有对应目录，
# 本脚本够不着，因此只扫描用户技能目录，不猜测也不编造内置技能路径。
#
# 关于“跨环境路径”：/workspace 只在 Linux 环境可见，/data/data 通常只在 Android
# 环境可见。若某路径的顶层目录（如 /workspace 本身）在本次环境里就不存在，
# 该路径判为“不可判定”单列一节，不算过时；只有顶层目录存在、目标却不存在时才报错。
#
# 兼容性：Android toybox / busybox ash 与 Debian dash 都能跑。
# 注意本机 grep 是 busybox/toybox 的，BRE 的 \| 交替会静默失效，
# 所以全文需要“交替”的地方一律用 grep -E，不用 grep '\|'。
set -u

SKILLS_ROOT=${SKILLS_ROOT:-/data/data/io.github.mangi.eta/files/skills}
OUT=""

usage() {
  cat <<'EOF'
用法：sh skill-audit.sh [-o 报告文件] [-d 技能根目录]

  -o FILE   除打印到 stdout 外，同时把 Markdown 报告写入 FILE
  -d DIR    指定技能根目录（默认 /data/data/io.github.mangi.eta/files/skills）
  -h        显示本帮助

只读脚本：仅读取 SKILL.md 及其同目录文件，不写入、不改动技能目录内容。
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    -o) [ $# -ge 2 ] || { echo "缺少 -o 的参数" >&2; exit 2; }; OUT=$2; shift 2 ;;
    -o*) OUT=${1#-o}; shift ;;
    -d) [ $# -ge 2 ] || { echo "缺少 -d 的参数" >&2; exit 2; }; SKILLS_ROOT=$2; shift 2 ;;
    -d*) SKILLS_ROOT=${1#-d}; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

SKILLS_ROOT=${SKILLS_ROOT%/}

if [ ! -d "$SKILLS_ROOT" ]; then
  echo "技能根目录不存在：$SKILLS_ROOT" >&2
  echo "（/data/data/... 需要 root 才能读；请用 root 身份运行）" >&2
  exit 1
fi
if ! ls "$SKILLS_ROOT" >/dev/null 2>&1; then
  echo "无权限读取：$SKILLS_ROOT（请用 root 身份运行）" >&2
  exit 1
fi

# ---- 可写临时目录（Android 上可能没有 /tmp） ----
W=""
for base in "${TMPDIR:-/tmp}" /data/local/tmp /tmp "$HOME"; do
  d="$base/.skill-audit.$$"
  if mkdir -p "$d" 2>/dev/null && : > "$d/.w" 2>/dev/null; then W=$d; break; fi
done
if [ -z "$W" ]; then
  echo "无法创建临时工作目录（试过 TMPDIR、/data/local/tmp、/tmp、HOME）" >&2
  exit 1
fi
trap 'rm -rf "$W"' EXIT INT TERM HUP

TS=$(date '+%Y-%m-%d %H:%M:%S')
: > "$W/bad.md"      # 问题清单
: > "$W/stale.md"    # 疑似过时清单（提到但不存在）
: > "$W/cross.md"    # 跨环境/不可判定清单
: > "$W/table.md"    # 技能明细表

TOTAL=0      # 技能目录总数
HAVE=0       # 含 SKILL.md 的技能数
NOFILE=0     # 无 SKILL.md 的目录数
BAD=0        # 有问题的技能数
ISSUES=0     # 问题条目总数
CUR=0        # 当前技能的问题条目数
IDX=0

add_issue() { # add_issue <问题类型> <证据>   写入当前技能的临时问题文件
  printf -- '- [%s] %s\n' "$1" "$2" >> "$WD/cur.md"
  CUR=$((CUR + 1))
  ISSUES=$((ISSUES + 1))
}

prefix_exists() { # 路径不存在时的兜底：路径尾部粘着说明文字时，看父目录里有没有同前缀的条目
  pd=$(dirname "$1")
  [ -d "$pd" ] || return 1
  for pe in "$pd"/*; do
    [ -e "$pe" ] || continue
    case "$1" in "$pe"*) return 0 ;; esac
  done
  return 1
}

for dir in "$SKILLS_ROOT"/*/; do
  [ -d "$dir" ] || continue
  sname=$(basename "$dir")
  TOTAL=$((TOTAL + 1))
  IDX=$((IDX + 1))
  WD="$W/s$IDX"
  mkdir -p "$WD"
  : > "$WD/cur.md"
  : > "$WD/miss.md"
  : > "$WD/cross.md"
  CUR=0
  f="$SKILLS_ROOT/$sname/SKILL.md"

  # ---- 0) 有没有 SKILL.md ----
  if [ ! -f "$f" ]; then
    NOFILE=$((NOFILE + 1))
    BAD=$((BAD + 1))
    ISSUES=$((ISSUES + 1))
    {
      echo "### $sname"
      echo "- [缺文件] 目录内没有 SKILL.md，该技能不会被索引"
      echo
    } >> "$W/bad.md"
    printf '| %s | - | - | - | 1 |\n' "$sname" >> "$W/table.md"
    continue
  fi
  HAVE=$((HAVE + 1))

  # ---- 5) 大小与修改时间 ----
  size=$(wc -c < "$f" | tr -d ' ')
  mtime=$(date -r "$f" '+%Y-%m-%d %H:%M' 2>/dev/null)
  [ -n "${mtime:-}" ] || mtime=$(ls -l "$f" 2>/dev/null | tr -s ' ' | cut -d' ' -f6-8)

  # ---- 1) frontmatter ----
  first=$(head -n 1 "$f" 2>/dev/null | tr -d '\r')
  fmname="-"
  if [ "$first" = "---" ]; then
    dc=$(grep -c -E '^---[[:space:]]*$' "$f" 2>/dev/null)
    [ -n "${dc:-}" ] || dc=0
    [ "$dc" -ge 2 ] || add_issue "frontmatter" "首行是 --- 但找不到 frontmatter 结束的第二个 ---"
    sed -n '2,/^---[[:space:]]*$/p' "$f" 2>/dev/null | sed '$d' | tr -d '\r' > "$WD/fm"
    if ! grep -q -E '^[[:space:]]*name[[:space:]]*:' "$WD/fm" 2>/dev/null; then
      add_issue "frontmatter" "缺少 name: 字段"
    fi
    if ! grep -q -E '^[[:space:]]*description[[:space:]]*:' "$WD/fm" 2>/dev/null; then
      add_issue "frontmatter" "缺少 description: 字段"
    else
      dval=$(sed -n 's/^[[:space:]]*description[[:space:]]*:[[:space:]]*//p' "$WD/fm" | head -n 1 | tr -d '\r' | tr -d ' ')
      [ -n "$dval" ] || add_issue "frontmatter" "description: 字段存在但值为空"
    fi

    # ---- 2) name 是否等于目录名 ----
    fmname=$(sed -n 's/^[[:space:]]*name[[:space:]]*:[[:space:]]*//p' "$WD/fm" | head -n 1 | tr -d '\r')
    fmname=$(printf '%s' "$fmname" | sed "s/^[\"']//; s/[\"']$//; s/[[:space:]]*$//" | tr '|' '/')
    [ -n "$fmname" ] || fmname="-"
    if [ "$fmname" != "$sname" ]; then
      add_issue "name 不一致" "frontmatter name=$fmname，目录名=$sname"
    fi
  else
    add_issue "frontmatter" "首行不是 ---（实际首行：$first）"
  fi

  # ---- 3) 正文绝对路径是否存在 ----
  # 按行筛：含 < > * $ % 的行按占位符跳过；文本已明说“不存在”的行跳过
  # （例如文档里写 “ls /data/adb/modules/thermal_vrr/disable  # 不存在 = 生效中”）
  grep -n -E '/(data|workspace|sdcard)/' "$f" 2>/dev/null \
    | grep -v -E '[<>*$%]' 2>/dev/null \
    | grep -v -E '不存在' > "$WD/raw" 2>/dev/null
  : > "$WD/paths"
  if [ -s "$WD/raw" ]; then
    # 去行号 → 按空白切词 → 反引号/引号/括号/竖线等也当断点
    # → 只去掉路径前面不是字母数字的杂物（保留 a/b/data 这种带前导词的串，稍后被过滤掉）
    # → 去掉尾部粘着的中文标点与说明 → 只留三个前缀开头的、且不含 xxx/yyy/zzz 占位段
    # 注意：sed 的方括号里不要放多字节字符（busybox sed 会按字节拆，结果全乱）
    sed 's/^[0-9]*://' "$WD/raw" 2>/dev/null \
      | tr ' \t' '\n\n' \
      | tr "\`'\"()[]{}<>|*?\$%;:," '\n' 2>/dev/null \
      | sed 's/^[^A-Za-z0-9/]*//; s/[^A-Za-z0-9._~+/@:-]*$//' 2>/dev/null \
      | grep -E '^/(data|workspace|sdcard)/' 2>/dev/null \
      | grep -v -E '^/(data|workspace|sdcard)/?$' 2>/dev/null \
      | grep -v -E '/(xxx|yyy|zzz)/' 2>/dev/null \
      | grep -v -E '\.\.\.' 2>/dev/null \
      | sort -u > "$WD/paths" 2>/dev/null
  fi
  if [ -s "$WD/paths" ]; then
    while IFS= read -r p; do
      [ -n "$p" ] || continue
      [ -e "$p" ] && continue
      prefix_exists "$p" && continue
      top=$(printf '%s' "$p" | sed 's#^\(/[^/]*\).*#\1#' 2>/dev/null)
      if [ -n "$top" ] && [ ! -e "$top" ]; then
        # 顶层目录在本环境就不存在 → 属于另一环境的路径，无法判定
        printf '%s|%s|%s\n' "$sname" "$p" "顶层 $top 本环境不存在" >> "$WD/cross.md"
        continue
      fi
      lno=$(grep -n -F -m1 -- "$p" "$f" 2>/dev/null | head -n 1 | cut -d: -f1)
      [ -n "${lno:-}" ] || lno='?'
      add_issue "路径不存在" "SKILL.md:$lno → $p"
      printf '%s|%s|SKILL.md:%s\n' "$sname" "$p" "$lno" >> "$WD/miss.md"
    done < "$WD/paths"
  fi

  # ---- 4) bundled 脚本是否存在 / 是否可执行 ----
  grep -o -E '[A-Za-z0-9._/-]*scripts/[A-Za-z0-9._/-]+\.(sh|py|js|mjs|ts|kt|bash)' "$f" 2>/dev/null \
    | sort -u > "$WD/scripts" 2>/dev/null
  if [ -s "$WD/scripts" ]; then
    while IFS= read -r rel; do
      [ -n "$rel" ] || continue
      rel2=$(printf '%s' "$rel" | sed 's#.*\(scripts/\)#\1#')
      cand="$SKILLS_ROOT/$sname/$rel2"
      if [ -e "$cand" ]; then
        case "$rel2" in
          *.sh|*.bash)
            [ -x "$cand" ] || add_issue "脚本不可执行" "$rel2 存在但缺少可执行位（+x）"
            ;;
        esac
      else
        add_issue "缺脚本" "正文提到 $rel2，但 $cand 不存在"
        printf '%s|%s|%s\n' "$sname" "$rel2" "缺脚本" >> "$WD/miss.md"
      fi
    done < "$WD/scripts"
  fi

  # ---- 收尾：汇总该技能 ----
  if [ -s "$WD/cur.md" ]; then
    BAD=$((BAD + 1))
    {
      echo "### $sname"
      cat "$WD/cur.md"
      echo
    } >> "$W/bad.md"
  fi
  cat "$WD/miss.md" >> "$W/stale.md"
  cat "$WD/cross.md" >> "$W/cross.md"
  printf '| %s | %s 字节 | %s | %s | %s |\n' "$sname" "$size" "$mtime" "$fmname" "$CUR" >> "$W/table.md"
done

STALE_SKILLS=$(cut -d'|' -f1 "$W/stale.md" 2>/dev/null | sort -u | grep -c . 2>/dev/null)
[ -n "${STALE_SKILLS:-}" ] || STALE_SKILLS=0
CROSS_SKILLS=$(cut -d'|' -f1 "$W/cross.md" 2>/dev/null | sort -u | grep -c . 2>/dev/null)
[ -n "${CROSS_SKILLS:-}" ] || CROSS_SKILLS=0
CROSS_LINES=$(grep -c . "$W/cross.md" 2>/dev/null)
[ -n "${CROSS_LINES:-}" ] || CROSS_LINES=0

{
  echo "# 技能体检报告"
  echo
  echo "- 生成时间：$TS"
  echo "- 技能根目录：\`$SKILLS_ROOT\`"
  echo "- 扫描方式：只读（不修改任何被检查文件）"
  echo
  echo "## 汇总"
  echo
  echo "- 技能目录总数：$TOTAL"
  echo "- 含 SKILL.md：$HAVE（目录内无 SKILL.md：$NOFILE）"
  echo "- 有问题的技能：$BAD"
  echo "- 问题条目：$ISSUES"
  echo "- 疑似过时（提到但路径/脚本不存在的技能数）：$STALE_SKILLS"
  echo "- 跨环境不可判定（顶层目录本环境不存在，如 /workspace）：$CROSS_LINES 条，涉及 $CROSS_SKILLS 个技能"
  echo "- 内置技能：本脚本不扫描 —— 内置技能打包在 APK 内（应用资源里），文件系统里没有对应目录，够不着；不猜测其路径"
  echo
  echo "## 问题清单"
  echo
  if [ -s "$W/bad.md" ]; then
    cat "$W/bad.md"
  else
    echo "（无问题）"
    echo
  fi
  echo "## 疑似过时清单"
  echo
  echo "正文里提到、且在本次环境里确实不存在的绝对路径 / bundled 脚本（技能 → 缺失对象）。"
  echo
  if [ -s "$W/stale.md" ]; then
    while IFS='|' read -r s what loc; do
      [ -n "${s:-}" ] || continue
      echo "- \`$s\` → \`$what\`（$loc）"
    done < "$W/stale.md"
    echo
  else
    echo "（无）"
    echo
  fi
  echo "## 跨环境路径（不可判定，不算过时）"
  echo
  echo "这些路径的顶层目录在本次环境里就不存在，属于另一环境的名字（Android 上看不到 /workspace，Linux 上看不到 /data/data 之类）。要在另一半环境里再跑一次才能判定。"
  echo
  if [ -s "$W/cross.md" ]; then
    while IFS='|' read -r s what note; do
      [ -n "${s:-}" ] || continue
      echo "- \`$s\` → \`$what\`（$note）"
    done < "$W/cross.md"
    echo
  else
    echo "（无）"
    echo
  fi
  echo "## 技能明细"
  echo
  echo "| 技能 | SKILL.md 大小 | 最后修改 | frontmatter name | 问题数 |"
  echo "| --- | --- | --- | --- | --- |"
  cat "$W/table.md"
  echo
  echo "## 说明"
  echo
  echo "- 只读：本脚本没有写入技能目录，只是在临时目录里生成报告。"
  echo "- “问题数”不含“跨环境路径”条目。"
  echo "- 正文里含 \`<\` \`>\` \`*\` \`$\` \`%\` 的行按示例/占位符整行跳过；文本已写明“不存在”的行也跳过；路径里含 xxx/yyy/zzz 占位段、或带省略号 \`...\` 的跳过。"
  echo "- 表格里对照其他实现（比如 Magisk 的 /data/adb/magisk/busybox）的行仍会被列出，需要人工判断。"
  echo "- bundled 脚本只识别 \`scripts/xxx.sh|py|js|mjs|ts|kt|bash\` 形态。"
} > "$W/report"

cat "$W/report"

if [ -n "$OUT" ]; then
  if cp "$W/report" "$OUT" 2>/dev/null; then
    echo "报告已写入：$OUT" >&2
  else
    echo "写入报告失败：$OUT（目录不存在或不可写？）" >&2
    exit 1
  fi
fi
