#!/usr/bin/env bash
# P4-S3: Dry-run F1 回测（不耗 token）
# Escape-Hatch：复用 P4-S2 build/_deps 下的 9 个 runtime jars + 89 classes
# 用法：bash scripts/run-f1-backtest.sh [--n-cases N] [--model M] [--out DIR] [--live]
#   默认 dry-run（不调 API，不耗 token）；加 --live 才真实调用 DeepSeek API（需要 DEEPSEEK_API_KEY env）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# ===== 环境（固定 JDK17，绕开 mise 默认 25）=====
JAVACMD="/root/.local/share/mise/installs/java/17.0.2/bin/java"
[[ -x "$JAVACMD" ]] || { echo "ERR: java 17 not found at $JAVACMD"; exit 1; }

# ===== 依赖 jars 采集（和 P4-S2 完全一致）=====
DEPS_DIR="$PROJECT_ROOT/planner-benchmarks/build/_deps"
[[ -d "$DEPS_DIR" ]] || { echo "ERR: deps dir not found: $DEPS_DIR. Run P4-S2-3A first (curl download jars)."; exit 1; }
ALL_JARS=$(find "$DEPS_DIR" -maxdepth 1 -name "*.jar" -not -name "kotlin-serialization-compiler-plugin-embeddable.jar" | sort -u | tr "\n" ":")
JAR_COUNT=$(find "$DEPS_DIR" -maxdepth 1 -name "*.jar" -not -name "kotlin-serialization-compiler-plugin-embeddable.jar" | wc -l)
[[ "$JAR_COUNT" -ge 8 ]] || { echo "ERR: jars count=$JAR_COUNT (expected 9). Re-run P4-S2-3A."; exit 1; }

CLASSES_DIR="$PROJECT_ROOT/planner-benchmarks/build/classes/kotlin/main"
[[ -d "$CLASSES_DIR" ]] || { echo "ERR: classes dir not found: $CLASSES_DIR. Re-compile (kotlinc Escape-Hatch)."; exit 1; }
CLASS_COUNT=$(find "$CLASSES_DIR" -name "*.class" | wc -l)
[[ "$CLASS_COUNT" -gt 50 ]] || { echo "WARN: classes count=$CLASS_COUNT low (expected ~89)"; }

FULL_CP="$CLASSES_DIR:$ALL_JARS"

# ===== 参数透传（直接 $@ 给 Kotlin main，避免重复解析）=====
OUT_DIR="$PROJECT_ROOT/planner-benchmarks/build/f1-backtest"
mkdir -p "$OUT_DIR"
# 追加 --out 如果用户没传
HAS_OUT=false
for a in "$@"; do [[ "$a" == "--out" ]] && HAS_OUT=true; done
ARGS=( "$@" )
if ! $HAS_OUT; then
  ARGS+=( --out "$OUT_DIR" )
fi

# ===== 执行 =====
echo "=================================================="
echo " P4-S3: LiveF1BacktestAgainstDeepSeek — Escape-Hatch Runner"
echo "=================================================="
echo "  Java:    $JAVACMD $($JAVACMD -version 2>&1 | head -1)"
echo "  JVM:     -Xmx2048m -XX:MaxMetaspaceSize=768m"
echo "  JARs:    $JAR_COUNT ($DEPS_DIR)"
echo "  .class:  $CLASS_COUNT ($CLASSES_DIR)"
echo "  Args:    ${ARGS[*]}"
echo "  （默认 dry-run，不加 --live 则不耗 DeepSeek token）"
echo "=================================================="

T0=$(date +%s)
# ===== 自动探测 HTTP/HTTPS 代理（从 env）=====
PROXY_JVM=""
if [[ -n "${https_proxy:-${HTTPS_PROXY:-}}" ]]; then
  P="${https_proxy:-$HTTPS_PROXY}"
  P_HOST="${P#http://}" ; P_HOST="${P_HOST%%/*}" ; P_PORT="${P_HOST##*:}" ; P_HOST="${P_HOST%%:*}"
  [[ -n "$P_HOST" && -n "$P_PORT" ]] && PROXY_JVM="$PROXY_JVM -Dhttps.proxyHost=$P_HOST -Dhttps.proxyPort=$P_PORT -Dhttp.proxyHost=$P_HOST -Dhttp.proxyPort=$P_PORT"
fi
echo "  Proxy JVM args: ${PROXY_JVM:-(none, 直连)}"
$JAVACMD \
  -Xmx2048m \
  -XX:MaxMetaspaceSize=768m \
  -XX:+UseSerialGC \
  -Dfile.encoding=UTF-8 \
  -Djava.net.preferIPv4Stack=true \
  $PROXY_JVM \
  -cp "$FULL_CP" \
  com.deepseek.coder.planner.bench.tools.LiveF1BacktestAgainstDeepSeekKt \
  "${ARGS[@]}"
E1=$?
T1=$(date +%s)

echo ""
echo "=================================================="
echo " 执行完成：EXIT=$E1，耗时 $((T1-T0))s"
echo " 输出目录：$OUT_DIR"
ls -lah "$OUT_DIR" 2>/dev/null | head -20
echo "=================================================="
