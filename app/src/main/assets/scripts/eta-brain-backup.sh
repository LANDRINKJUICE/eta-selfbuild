#!/bin/sh
# eta-brain-backup.sh —— 把「记忆 + 技能 + subagent 模板 + 定时任务配置」备份到私有仓库
#
# 为什么要有它：代码在 GitHub 是安全的，但记忆、技能、任务配置只存在这台手机里。
# 手机丢了 / 刷机 / 清应用数据，这些就等于一夜归零。这个脚本把它们推到私有仓库。
#
# 用法：
#   sh eta-brain-backup.sh              # 备份并推送
#   sh eta-brain-backup.sh --dry-run    # 只打包，不推送（看体积与清单）
#
# ⚠️ 环境要求：本脚本要读 /data/data/io.github.mangi.eta（应用私有目录），
#    必须用 **environment=android + root** 跑。Linux chroot 里看不到这个路径
#    （chroot 的 /data 是 /data/local/tmp 的挂载，不是宿主 /data）。
#    但 git/gh 在 chroot 里更完整，所以：本机负责收集打包，chroot 负责推送。
set -eu

REPO=LANDRINKJUICE/eta-brain-backup
FILES_DIR=/data/data/io.github.mangi.eta/files
PREFS_DIR=/data/data/io.github.mangi.eta/shared_prefs
AGENTS_DIR=/data/user/0/io.github.mangi.eta/files/terminal-user/workspace/agents
# 工作区两个环境的同一个物理目录
WORK=/data/local/tmp/eta/tmp/brain-backup
[ -d /data/local/tmp/eta ] || WORK=/workspace/tmp/brain-backup
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

# 若 payload 已存在（由 Android 侧脚本收集），就跳过收集，直接推送。
# 这样能绕开"Linux chroot 看不到 /data/data"的限制。
if [ -d "$WORK/payload" ] && [ "$(find "$WORK/payload" -type f | wc -l)" -gt 5 ] && [ "${1:-}" != "--recollect" ]; then
  echo "=== 1. 复用已收集的内容（跳过收集）==="
else
echo "=== 1. 准备目录（$WORK） ==="
mkdir -p "$WORK/payload"
echo "=== 2. 收集内容 ==="
# Linux chroot 看不到 /data/data（App 私有目录），所以先在 Android 侧把最新内容
# 镜像到工作区 tmp/brain-src，这里的脚本再从这里收集。
# 不这样做的话会悄悄用上旧的 payload，备份看着"成功"其实内容是隔夜的（踩过）。
MIRROR=/workspace/tmp/brain-src
if [ -d "$MIRROR" ]; then
  SRC="$MIRROR"
  echo "  来源：工作区镜像 $MIRROR"
else
  SRC="$FILES_DIR"
  echo "  来源：$FILES_DIR（若为 Linux 环境通常读不到，请先镜像到 $MIRROR）"
fi

mkdir -p "$WORK/payload/memory"
cp "$SRC/memory/"*.md "$WORK/payload/memory/" 2>/dev/null || echo "  （无记忆文件）"

if [ -d "$SRC/skills" ]; then
  mkdir -p "$WORK/payload/skills"
  cp -a "$SRC/skills/." "$WORK/payload/skills/" 2>/dev/null || echo "  （技能目录复制有告警）"
fi

if [ -d "$SRC/agents" ]; then
  mkdir -p "$WORK/payload/agents"
  cp -a "$SRC/agents/." "$WORK/payload/agents/" 2>/dev/null || true
elif [ -d "$AGENTS_DIR" ]; then
  mkdir -p "$WORK/payload/agents"
  cp -a "$AGENTS_DIR/." "$WORK/payload/agents/" 2>/dev/null || true
fi

mkdir -p "$WORK/payload/config"
for f in eta_scheduled_tasks.xml eta_agent_preferences.xml; do
  [ -f "$PREFS_DIR/$f" ] && cp "$PREFS_DIR/$f" "$WORK/payload/config/" 2>/dev/null || true
done

echo "=== 3. 过滤敏感文件 ==="
find "$WORK/payload" -type f \( \
    -name '*.jks' -o -name '*.keystore' -o -name 'keystore-info.txt' \
    -o -name '*.pem' -o -name '*.p12' -o -name '.env' \
  \) -print -delete 2>/dev/null || true

echo "=== 4. 生成清单 ==="
MODEL=$(getprop ro.product.model 2>/dev/null || echo unknown)
ANDROID=$(getprop ro.build.version.release 2>/dev/null || echo '?')
VER=$(dumpsys package io.github.mangi.eta 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' || echo unknown)
{
  echo "Eta 大脑备份"
  echo "生成时间: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "来源设备: $MODEL / Android $ANDROID"
  echo "Eta 版本: $VER"
  echo ""
  echo "内容: memory/ 长期记忆 | skills/ 已装技能 | agents/ subagent 模板 | config/ 定时任务与偏好（不含密钥）"
  echo ""
  echo "文件清单（sha256）:"
  find "$WORK/payload" -type f ! -name manifest.txt | sort | while read -r f; do
    rel="${f#$WORK/payload/}"
    printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$rel"
  done
} > "$WORK/payload/manifest.txt"

fi

COUNT=$(find "$WORK/payload" -type f | wc -l)
SIZE=$(du -sh "$WORK/payload" | cut -f1)
echo "文件数: $COUNT  体积: $SIZE"

if [ "$DRY_RUN" = "1" ]; then
  echo "=== --dry-run：不推送 ==="
  echo "打包位置: $WORK/payload"
  echo "--- 目录概览 ---"
  ls "$WORK/payload"
  exit 0
fi

echo "=== 5. 推送（需要 git + gh，请在 Linux 环境执行本步骤） ==="
cd "$WORK/payload"
git init -q 2>/dev/null || true
git config user.email "eta-brain@localhost"
git config user.name "Eta Brain Backup"
git add -A
if git diff --cached --quiet 2>/dev/null; then
  echo "内容无变化，跳过提交"
else
  git commit -q -m "backup: $(date '+%Y-%m-%d %H:%M') · ${COUNT} 个文件"
fi

if git remote get-url origin >/dev/null 2>&1; then
  echo "复用已有远端"
else
  gh repo view "$REPO" >/dev/null 2>&1 || gh repo create "$REPO" --private \
    --description "Eta 大脑备份：记忆 / 技能 / subagent 模板 / 定时任务配置（私人）" >/dev/null
  git remote add origin "https://github.com/$REPO.git"
fi
echo "远端: $(git remote get-url origin)"

i=1
while [ $i -le 3 ]; do
  git push -u origin HEAD:main 2>&1 | tail -3 && break
  echo "推送重试 $i"
  i=$((i + 1))
  sleep 5
done
echo "=== 完成：https://github.com/$REPO ==="
