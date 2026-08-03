#!/usr/bin/env bash
# ==========================================================================
# P4-S2+S3 · Planner LoRA 训练一键启动器（Phase 2 Step 3/4 对接 DeepSeek 企业渠道）
# 封装: 上传 JSONL → 创建 LoRA job → 轮询等待成功 → 回填 fine_tuned_model 到 SPEC
# ==========================================================================
set -euo pipefail

# --- defaults / ENV overrides ---
BASE_URL="${DEEPSEEK_BASE_URL:-https://api.deepseek.com/v1}"
API_KEY="${DEEPSEEK_API_KEY:-}"
DATASET="${DATASET_PATH:-/workspace/DeepCoder/planner-benchmarks/build/lora-200k/lora-train.jsonl}"
MODEL="${BASE_MODEL:-deepseek-chat}"
SUFFIX="${SUFFIX:-deepcoder-planner-v07-p2-s3}"
EPOCHS="${N_EPOCHS:-3}"
LR="${LR_MULTIPLIER:-1.0}"

# --- helpers ---
CURL() { curl -sS -H "Authorization: Bearer ${API_KEY}" -H "Content-Type: application/json" "$@" ; }
log()  { echo -e "\033[36m[P4-S2+S3 🚀]\033[0m $*"; }
die()  { echo -e "\033[31m[FAIL]\033[0m $*" >&2; exit 1; }
require() { [ -n "$API_KEY" ] || die "请 export DEEPSEEK_API_KEY=sk-... （企业渠道 key）"; }

# --- core steps ---
step_upload() {
  require
  [ -f "$DATASET" ] || die "DATASET_PATH 不存在: $DATASET"
  SZ_MB=$(awk -v b="$(wc -c < "$DATASET")" 'BEGIN{printf "%.1f", b/1048576}')
  LINES=$(wc -l < "$DATASET")
  log "STEP 1/4: 上传 LoRA 训练集: $DATASET ($LINES 行, $SZ_MB MB) → $BASE_URL/files"
  OUT=$(curl -sS -H "Authorization: Bearer ${API_KEY}" \
             -F "purpose=fine-tune" \
             -F "file=@$DATASET" \
             "$BASE_URL/files")
  echo "$OUT" | python3 -m json.tool 2>/dev/null || echo "$OUT"
  FID=$(echo "$OUT" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('id',''))")
  [ -n "$FID" ] || die "上传未返回 file_id"
  log "→ file_id = $FID  (export FILE_ID=$FID 跳过上传)"
  export FILE_ID="$FID"
}

step_create_job() {
  require
  : "${FILE_ID:?用法: $0 pipeline 或 export FILE_ID=file-xxx 后 step_create_job}"
  log "STEP 2/4: 创建 LoRA fine-tuning job | file=$FILE_ID  model=$MODEL  suffix=$SUFFIX  epochs=$EPOCHS  lr=$LR"
  BODY=$(cat <<JSON
{
  "model": "$MODEL",
  "training_file": "$FILE_ID",
  "suffix": "$SUFFIX",
  "hyperparameters": {
    "n_epochs": $EPOCHS,
    "learning_rate_multiplier": $LR
  }
}
JSON
)
  OUT=$(CURL -X POST "$BASE_URL/fine_tuning/jobs" -d "$BODY")
  echo "$OUT" | python3 -m json.tool 2>/dev/null || echo "$OUT"
  JID=$(echo "$OUT" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('id',''))")
  [ -n "$JID" ] || die "create-job 未返回 job id"
  log "→ ft_job_id = $JID"
  export JOB_ID="$JID"
}

step_wait() {
  require
  : "${JOB_ID:?用法: 先 step_create_job 或 export JOB_ID=ftjob-xxx}"
  log "STEP 3/4: 轮询 job 直到 succeeded (每 90s)...  (Ctrl+C 中断后可手动: scripts/deepseek-lora-cli.sh wait-for-success $JOB_ID)"
  scripts/deepseek-lora-cli.sh wait-for-success "$JOB_ID" 90
}

step_report() {
  log "STEP 4/4: 生成回填指令（写入 SPEC §11 + App settings）"
  cat <<OUT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📤 Planner LoRA v0.7 训练产物回填指南
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  FT_JOB_ID       = ${JOB_ID:-<从 wait-for-success 输出取>}
  FINE_TUNED_MDL  = <从 wait-for-success 输出的 MODEL_ID= 取>
  DATASET_N       = $(wc -l < "$DATASET" 2>/dev/null || echo n=200000)
  SCOPE_WEB_RATIO = 40.0%  (WEB_FRONTEND 专项训练配比)
  EPOCHS          = $EPOCHS
  BASE_MODEL      = $MODEL
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
回填位置:
  1) SPEC-Planner-v0.7.md §11 Phase 进度追踪 → 新增一行:
     ✅ Phase 2 Step 4: LoRA 训练完成 (job=$JOB_ID, model=$FINE_TUNED_MDL, N=200k)
  2) data 层 AppSettings.kt  → fineTuneModelId = "$FINE_TUNED_MDL"
     或运行时 UI 「自定义模型名」填入 $FINE_TUNED_MDL 即可实时生效
  3) P4-S4 Live F1 回测:  scripts/run-f1-backtest.sh live 200 --model $FINE_TUNED_MDL
     (对比基准: deepseek-chat → 预期 CAP/Scope 准确率 ↑ 15~25pp)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
OUT
}

# --- CLI ---
case "${1:-help}" in
  upload|step1)  step_upload ;;
  create|step2)  step_create_job ;;
  wait|step3)    step_wait ;;
  report|step4)  step_report ;;
  pipeline)
    log "完整一键流水线: upload → create → wait → report (会扣费，确认预算再跑，Ctrl+C 安全中断)";
    sleep 3
    step_upload
    step_create_job
    step_wait
    step_report
    ;;
  help|*)
    cat <<USAGE
P4-S2+S3 Planner LoRA 一键对接 DeepSeek 企业渠道（调用会扣费，预算确认后再跑）

用法:
  # (1) 推荐: 完整流水线 (上传→创建→等待→回填指南)
  export DEEPSEEK_API_KEY=sk-<企业渠道或平台key>
  $0 pipeline

  # (2) 分步执行 (适合想单步观察)
  $0 upload        # STEP 1: 上传 JSONL → FILE_ID=xxx
  $0 create        # STEP 2: export FILE_ID=xxx → 创建 job → JOB_ID=xxx
  $0 wait          # STEP 3: export JOB_ID=xxx → 轮询等待 succeeded
  $0 report        # STEP 4: 生成 §11 + App 设置回填指南

可选覆盖:
  DATASET_PATH=  默认: planner-benchmarks/build/lora-200k/lora-train.jsonl (200k 条，Q1-Q10 100%)
  BASE_MODEL=    默认: deepseek-chat
  SUFFIX=        默认: deepcoder-planner-v07-p2-s3
  N_EPOCHS=      默认: 3
  LR_MULTIPLIER= 默认: 1.0
USAGE
  ;;
esac
