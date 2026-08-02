#!/usr/bin/env bash
# P4-S2: 生成 LoRA 小样本（2,000 条）+ Q1~Q10 质检
# Escape-Hatch P4-S2-3A：Gradle caches 丢失 + Google Maven 被墙 → 直接用 build/_deps 下的 9 个 runtime jars
# 用法：bash scripts/run-gen-dataset.sh [--n N] [--web-ratio F] [--seed S] [--out DIR]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# ===== 参数 =====
N=2000
WEB_RATIO=0.4
SEED=42
OUT_DIR="$PROJECT_ROOT/planner-benchmarks/build/lora"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --n) N=$2; shift 2;;
    --web-ratio) WEB_RATIO=$2; shift 2;;
    --seed) SEED=$2; shift 2;;
    --out) OUT_DIR=$2; shift 2;;
    *) echo "Unknown: $1"; exit 2;;
  esac
done

# ===== 环境（固定 JDK17，不读全局 JAVA_HOME）=====
JAVACMD="/root/.local/share/mise/installs/java/17.0.2/bin/java"
[[ -x "$JAVACMD" ]] || { echo "ERR: java not found at $JAVACMD"; exit 1; }

# ===== build/_deps jars 采集（Escape-Hatch P4-S2-3A）=====
DEPS_DIR="$PROJECT_ROOT/planner-benchmarks/build/_deps"
[[ -d "$DEPS_DIR" ]] || { echo "ERR: deps dir not found: $DEPS_DIR. Run P4-S2-3A first (curl download jars)."; exit 1; }

ALL_JARS=$(find "$DEPS_DIR" -maxdepth 1 -name "*.jar" | sort -u | tr "\n" ":")
JAR_COUNT=$(find "$DEPS_DIR" -maxdepth 1 -name "*.jar" | wc -l)
[[ "$JAR_COUNT" -ge 8 ]] || { echo "ERR: jars count=$JAR_COUNT too low (expected 9). Re-run P4-S2-3A."; exit 1; }

# 加上编译好的 planner-benchmarks classes
CLASSES_DIR="$PROJECT_ROOT/planner-benchmarks/build/classes/kotlin/main"
[[ -d "$CLASSES_DIR" ]] || { echo "ERR: classes dir not found: $CLASSES_DIR. Run P4-S1B first."; exit 1; }
CLASS_COUNT=$(find "$CLASSES_DIR" -name "*.class" | wc -l)
[[ "$CLASS_COUNT" -gt 50 ]] || { echo "WARN: classes count=$CLASS_COUNT low (expected ~89)"; }

FULL_CP="$CLASSES_DIR:$ALL_JARS"

# ===== 执行 =====
echo "=================================================="
echo " P4-S2: GenerateLoraDataset — Escape-Hatch Runner"
echo "=================================================="
echo "  Java:    $JAVACMD $($JAVACMD -version 2>&1 | head -1)"
echo "  JVM:     -Xmx2048m -XX:MaxMetaspaceSize=768m"
echo "  JARs:    $JAR_COUNT ($DEPS_DIR)"
echo "  .class:  $CLASS_COUNT ($CLASSES_DIR)"
echo "  Params:  --n=$N --web-ratio=$WEB_RATIO --seed=$SEED"
echo "  Out:     $OUT_DIR"
echo "=================================================="
mkdir -p "$OUT_DIR"

T0=$(date +%s)
$JAVACMD \
  -Xmx2048m \
  -XX:MaxMetaspaceSize=768m \
  -XX:+UseSerialGC \
  -Dfile.encoding=UTF-8 \
  -cp "$FULL_CP" \
  com.deepseek.coder.planner.bench.tools.GenerateLoraDatasetKt \
    --n "$N" --web-ratio "$WEB_RATIO" --seed "$SEED" --out "$OUT_DIR"
T1=$(date +%s)

echo ""
echo "=================================================="
echo " 生成完成，耗时 $((T1-T0))s，文件清单："
ls -lah "$OUT_DIR" 2>/dev/null
echo "=================================================="
