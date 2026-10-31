#!/usr/bin/env bash
# 生成规范化 Release Notes（CI 发版与本地补写历史 release 通用）
#
# 用法一（CI 发版模式：现场对 DIST 内产物计算 SHA-256）：
#   TAG=v1.0.0-alpha.6 EPHEMERAL=false ./scripts/gen-release-notes.sh
# 用法二（补写历史 release 模式：使用现成的 SHA256SUMS 文件）：
#   TAG=v1.0.0-alpha.5 SUMS_FILE=/tmp/alpha5-SHA256SUMS.txt OUT=/tmp/alpha5.md ./scripts/gen-release-notes.sh
#
# 环境变量：
#   TAG        必填 —— 本次发布的 tag（如 v1.0.0-alpha.5）
#   EPHEMERAL  可选 —— true 时注意事项提示一次性签名密钥（默认 false）
#   DIST       可选 —— 产物目录（默认 dist；CI 模式需含 *.apk / *.tar.xz / manifest.json）
#   SUMS_FILE  可选 —— 现成 SHA256SUMS 文件路径；设置后跳过对 DIST 的现场计算（补写模式）
#   OUT        可选 —— 输出文件路径（默认 release_notes.md）
#   REPO_SLUG  可选 —— GitHub 仓库全名（默认 masgzy/DeepSeek-Harness-Mobile）
set -euo pipefail

TAG="${TAG:?需要 TAG 环境变量（本次发布的 tag，如 v1.0.0-alpha.5）}"
EPHEMERAL="${EPHEMERAL:-false}"
DIST="${DIST:-dist}"
OUT="${OUT:-release_notes.md}"
REPO_SLUG="${REPO_SLUG:-masgzy/DeepSeek-Harness-Mobile}"
BASE_URL="https://github.com/${REPO_SLUG}"

# ---------- 1. SHA-256 校验和 ----------
if [ -n "${SUMS_FILE:-}" ]; then
  [ -f "$SUMS_FILE" ] || { echo "SUMS_FILE 不存在: $SUMS_FILE" >&2; exit 1; }
  SUMS="$(cat "$SUMS_FILE")"
else
  [ -d "$DIST" ] || { echo "DIST 目录不存在: $DIST（CI 模式需先下载产物）" >&2; exit 1; }
  (
    cd "$DIST"
    # 首选显式清单（顺序稳定）；缺任一类产物时回退为目录全量（排除校验和文件自身）
    # 哈希文件名不带 ./ 前缀，下载表格与校验块展示更美观
    # shellcheck disable=SC2035
    if sha256sum *.apk *.tar.xz manifest.json > .SHA256SUMS.tmp 2>/dev/null; then
      :
    else
      find . -maxdepth 1 -type f ! -name 'SHA256SUMS.txt' ! -name '.SHA256SUMS.tmp' -printf '%f\0' |
        sort -z | xargs -0 sha256sum > .SHA256SUMS.tmp
    fi
    mv .SHA256SUMS.tmp SHA256SUMS.txt
  )
  SUMS="$(cat "$DIST/SHA256SUMS.txt")"
fi
[ -n "$SUMS" ] || { echo "SHA256SUMS 内容为空" >&2; exit 1; }

# ---------- 2. 下载表格（按实际产物逐行生成） ----------
DL_TABLE=""
while IFS= read -r line; do
  [ -n "$line" ] || continue
  f="${line#*  }"     # sha256sum 文本模式: "<hash>  <文件名>"
  f="${f##* }"        # 兼容取最后一段
  f="${f#\*}"         # 兼容二进制模式前导星号
  f="${f#./}"         # 兼容 find/./glob 产生的路径前缀
  case "$f" in
    *.apk)                desc="应用主程序（arm64-v8a，Android 8.0+）" ;;
    dsh-runtime-*.tar.xz) desc="预装运行时镜像（App 内「预装运行时」通道自动下载）" ;;
    manifest.json)        desc="预装运行时清单（版本 / SHA-256 / 字节数）" ;;
    SHA256SUMS.txt)       continue ;;
    *)                    desc="其他附件" ;;
  esac
  DL_TABLE+="| \`${f}\` | ${desc} |"$'\n'
done <<< "$SUMS"

# ---------- 3. 更新日志（git 提交区间） ----------
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# 版本序列中 TAG 的上一版本（按版本号排序、取可从 TAG 到达的 v* tag）
prev=""
while IFS= read -r t; do
  if [ "$t" = "$TAG" ]; then break; fi
  prev="$t"
done < <(git -C "$REPO_ROOT" tag --sort=v:refname --merged "$TAG" 2>/dev/null | grep -E '^v[0-9]' || true)

if [ -n "$prev" ]; then
  log_args=("${prev}..${TAG}")
else
  root="$(git -C "$REPO_ROOT" rev-list --max-parents=0 "$TAG" | tail -n 1)"
  log_args=("${TAG}" --not "${root}")
fi
commit_count="$(git -C "$REPO_ROOT" rev-list --count "${log_args[@]}")"
CHANGELOG_SECTION="$(git -C "$REPO_ROOT" log --no-decorate \
  --format='- %s ([%h]('"${BASE_URL}"'/commit/%H))' "${log_args[@]}")"

if [ -n "$prev" ]; then
  RANGE_LINE="${prev} → ${TAG}，共 ${commit_count} 个提交："
  COMPARE_LINE="**Full Changelog**: ${BASE_URL}/compare/${prev}...${TAG}"
else
  RANGE_LINE="首个预发布版本，自项目启动以来共 ${commit_count} 个提交："
  COMPARE_LINE="**Full Changelog**: ${BASE_URL}/commits/${TAG}"
fi

# ---------- 4. 组装正文（全部经 printf/quoted heredoc，杜绝变量为空与缩进问题） ----------
{
  printf '%s\n\n' '> DeepSeek Harness Mobile —— 免 root 在 Android 上运行 DeepSeek Harness 的原生容器方案。'

  printf '## 📦 下载\n\n'
  printf '| 文件 | 说明 |\n| --- | --- |\n'
  printf '%s' "$DL_TABLE"

  printf '\n## ✅ SHA-256 校验\n\n```text\n%s\n```\n' "$SUMS"

  cat <<'SECTION'

## 🚀 安装说明

1. 下载 APK 安装（需允许「安装未知应用」）；
2. 首次启动按向导安装：推荐「官方 rootfs + 在线安装」（约 30MB 下载），
   或选择「预装运行时」直接下载本页 bundle（约 200MB，无需容器内联网安装）；
3. 启动引擎后在 App 内使用 dsh 官方 Web UI，API Key 在 Web UI 设置中填写。
SECTION

  printf '\n## 📝 更新日志\n\n%s\n\n%s\n' "$RANGE_LINE" "$CHANGELOG_SECTION"
  printf '\n%s\n' "$COMPARE_LINE"

  cat <<'SECTION'

## ⚠️ 注意事项

- 本项目为社区驱动，与 DeepSeek 官方无关；dsh 处于 developer preview，接口可能变动。
SECTION
  if [ "$EPHEMERAL" = "true" ]; then
    printf -- '- **本预发布 APK 使用一次性签名密钥构建，无法覆盖安装后续版本；正式密钥配置后将保持稳定。**\n'
  else
    printf -- '- APK 使用仓库配置的正式签名密钥，可覆盖安装升级。\n'
  fi

  printf '\n## 🔗 变更明细\n\n完整条目见 [CHANGELOG.md](%s/blob/main/CHANGELOG.md)。\n' "$BASE_URL"
} > "$OUT"

echo "===== ${OUT} ====="
cat "$OUT"
