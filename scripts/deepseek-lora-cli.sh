#!/usr/bin/env bash
# =========================================================================
# P4-2b · DeepSeek LoRA 训练 CLI 模板（请确认训练预算后再执行，调用会产生费用）
# 参考文档： https://platform.deepseek.com/docs/api-reference/fine-tuning/create
# =========================================================================
set -euo pipefail

BASE_URL="${DEEPSEEK_BASE_URL:-https://api.deepseek.com/v1}"
API_KEY="${DEEPSEEK_API_KEY:-sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx}"
AUTH_HEADER="Authorization: Bearer ${API_KEY}"
CT_JSON="Content-Type: application/json"

CURL() { curl -sS -H "$AUTH_HEADER" -H "$CT_JSON" "$@" ; }
log()  { echo -e "\033[36m[LoRA-CLI]\033[0m $*"; }
die()  { echo -e "\033[31m[FAIL]\033[0m $*" >&2; exit 1; }

case "${1:-help}" in
  upload-file)
    FILE="${2:?用法: $0 upload-file <jsonl_path>}"
    [ -f "$FILE" ] || die "文件不存在: $FILE"
    log "上传训练集 → DeepSeek Files: $FILE"
    CURL -X POST "$BASE_URL/files" \
         -F "purpose=fine-tune" \
         -F "file=@$FILE"
    ;;
  list-files)
    CURL -X GET "$BASE_URL/files" | python3 -m json.tool
    ;;
  create-job)
    FILE_ID="${2:?用法: $0 create-job <file_id> [model] [suffix]}"
    MODEL="${3:-deepseek-chat}"
    SUFFIX="${4:-deepcoder-planner-v1}"
    EPOCHS="${N_EPOCHS:-3}"
    LR="${LR_MULTIPLIER:-1.0}"
    log "创建 LoRA job: file=$FILE_ID  model=$MODEL  suffix=$SUFFIX epochs=$EPOCHS lr=$LR"
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
    CURL -X POST "$BASE_URL/fine_tuning/jobs" -d "$BODY" | python3 -m json.tool
    ;;
  list-jobs)
    CURL -X GET "$BASE_URL/fine_tuning/jobs" | python3 -m json.tool
    ;;
  job-status)
    JOB_ID="${2:?用法: $0 job-status <ft_job_id>}"
    CURL -X GET "$BASE_URL/fine_tuning/jobs/$JOB_ID" | python3 -m json.tool
    ;;
  job-events)
    JOB_ID="${2:?用法: $0 job-events <ft_job_id>}"
    CURL -X GET "$BASE_URL/fine_tuning/jobs/$JOB_ID/events" | python3 -m json.tool
    ;;
  wait-for-success)
    # 轮询直到 job 达到 succeeded/failed/cancelled（每 60 秒查一次）
    JOB_ID="${2:?用法: $0 wait-for-success <ft_job_id>}"
    SLEEP="${3:-60}"
    while true; do
      STATUS=$(CURL -X GET "$BASE_URL/fine_tuning/jobs/$JOB_ID" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('status','?'))")
      log "JOB $JOB_ID  status=$STATUS  $(date '+%H:%M:%S')"
      case "$STATUS" in
        succeeded) log "🎉 job succeeded → 下一步回填 model_id 到 AppSettings.customFineTuneModelId";
                   CURL -X GET "$BASE_URL/fine_tuning/jobs/$JOB_ID" | python3 -c "
import sys,json;d=json.load(sys.stdin)
print('FT_JOB_ID=' + d.get('id',''))
print('MODEL_ID='  + d.get('fine_tuned_model',''))
print('CREATED_AT='+ str(d.get('created_at','')))
"; exit 0 ;;
        failed|cancelled) die "job 终止: $STATUS" ;;
        *) sleep "$SLEEP" ;;
      esac
    done
    ;;
  usage)
    CURL -X GET "$BASE_URL/fine_tuning/jobs" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for j in d.get('data',[])[:10]:
    print(j['id'], j['status'], j.get('fine_tuned_model'), j.get('estimated_finish_at') or j.get('created_at'))
"
    ;;
  help|*)
    cat <<USAGE
DeepSeek LoRA CLI（调用会扣费，确认预算再跑）
用法:
  1) 上传 JSONL → 拿到 file_id
     $0 upload-file planner-benchmarks/build/lora/lora-train.jsonl
  2) 创建训练 Job → 拿到 ft_job_id (注意：下面命令会扣费)
     [N_EPOCHS=3] [LR_MULTIPLIER=1.0] \\
     $0 create-job file-xxxx [deepseek-chat] [deepcoder-planner-v1]
  3) 轮询状态 (每 60s) → 最终拿到 fine_tuned_model
     $0 wait-for-success ftjob-xxxx [60]
  其他:
     $0 list-files              # 看用户所有 files
     $0 list-jobs               # 最近 LoRA jobs
     $0 job-status ftjob-xxxx   # 单次状态
     $0 job-events ftjob-xxxx   # 进度事件
环境变量:
  DEEPSEEK_API_KEY     必填
  DEEPSEEK_BASE_URL    可选，默认 https://api.deepseek.com/v1
USAGE
    ;;
esac
