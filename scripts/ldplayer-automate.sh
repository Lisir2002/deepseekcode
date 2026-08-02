#!/usr/bin/env bash
# =========================================================================
# DeepCoder · 雷电模拟器自动化验证脚本（P4-1）
# 使用场景：本地/云端启动「雷电模拟器 (LDPlayer) / MuMu / 夜神」后，一键：
#   1) adb connect
#   2) 安装 DeepCoder APK
#   3) 启动 MainActivity
#   4) 5 步 UI 自动化截图（启动页 → 首屏 → 设置页注入 Key → 返回首屏 → 发第 1 条消息）
#   5) 录屏 30s → /sdcard/deepcoder-e2e.mp4
#
# 使用方法（Windows / macOS / Linux 通用，只要 PATH 里有 adb）：
#   A) 先在雷电模拟器里 → 设置 → 其他设置 → 开启「ADB调试」
#   B) 打开 PowerShell / Terminal，运行：
#        export ADB_PORT=5555   # 雷电默认 5555；雷电多开器 5556 / 5557；夜神 62001；MuMu 7555
#        bash scripts/ldplayer-automate.sh install-and-screenshot
# =========================================================================
set -euo pipefail

ADB_PORT="${ADB_PORT:-5555}"
ADB_TARGET="127.0.0.1:${ADB_PORT}"
PKG="com.deepseek.coder.debug"
MAIN_ACTIVITY="${PKG}/com.deepseek.coder.MainActivity"
APK_DEFAULT="releases/DeepCoder-v1.0.0-debug.apk"
OUT_DIR="${OUT_DIR:-artifacts/ldplayer-$(date +%Y%m%d-%H%M)}"
DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx}"

mkdir -p "$OUT_DIR"
log() { echo -e "\033[36m[LD]\033[0m $*"; }
die() { echo -e "\033[31m[FAIL]\033[0m $*" >&2; exit 1; }

cmd_connect()  { adb connect "$ADB_TARGET" | tee "$OUT_DIR/00-adb-connect.log"; adb devices; }
cmd_devices()  { adb -s "$ADB_TARGET" devices -l; }
cmd_install()  {
  local apk="${1:-$APK_DEFAULT}"
  [ -f "$apk" ] || die "APK 不存在: $apk"
  log "install $apk → $ADB_TARGET"
  adb -s "$ADB_TARGET" install -r -d "$apk" | tee "$OUT_DIR/01-install.log"
  adb -s "$ADB_TARGET" shell pm list packages | grep -E "deepseek.coder" | tee "$OUT_DIR/02-pkg-installed.log"
}
cmd_launch()   {
  log "启动 MainActivity"
  adb -s "$ADB_TARGET" shell am start -n "$MAIN_ACTIVITY" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | tee "$OUT_DIR/03-launch.log"
}
cmd_screenshots() {
  log "5 步自动化截图"
  snap() { local n="$1" sleep_sec="$2";
    sleep "$sleep_sec"
    adb -s "$ADB_TARGET" shell screencap -p /sdcard/deepcoder-${n}.png
    adb -s "$ADB_TARGET" pull /sdcard/deepcoder-${n}.png "$OUT_DIR/${n}-$(date +%H%M%S).png" >/dev/null
    log "  截图 $n → $OUT_DIR/${n}-*.png"
  }
  # --- Step 1: 启动页（Launcher / Splash）---
  snap "S1-launch-screen" 3
  # --- Step 2: 首屏（Chat Screen）---
  snap "S2-home-screen" 3
  # --- Step 3: 跳转设置页 + 注入 API Key（am start 模拟）---
  log "  启动 Settings Screen（如果有 deeplink 才有效）"
  adb -s "$ADB_TARGET" shell am start -n "$MAIN_ACTIVITY" --es screen "settings" --es api_key "$DEEPSEEK_API_KEY" 2>/dev/null \
    || log "  (无 deeplink，忽略：请在 UI 里手动粘贴 API Key)"
  snap "S3-settings-api-key-injected" 4
  # --- Step 4: 返回首屏 ---
  adb -s "$ADB_TARGET" shell input keyevent KEYCODE_BACK 2>/dev/null || true
  snap "S4-home-after-settings" 2
  # --- Step 5: 发第 1 条消息（通过 am broadcast / input text，仅最佳努力）---
  log "  点击聊天输入框（坐标占位：540 1800，1080p 近似），输入第 1 条消息"
  adb -s "$ADB_TARGET" shell input tap 540 1800 2>/dev/null || true
  adb -s "$ADB_TARGET" shell input text "Hello%20DeepCoder%2C%20write%20a%20kotlin%20hello%20world" 2>/dev/null || true
  adb -s "$ADB_TARGET" shell input tap 1000 1800 2>/dev/null || true
  snap "S5-first-message-sent" 6
}
cmd_record_screen_30s() {
  log "后台录屏 30 秒 → /sdcard/deepcoder-e2e.mp4"
  adb -s "$ADB_TARGET" shell screenrecord --time-limit 30 --size 720x1280 /sdcard/deepcoder-e2e.mp4 &
  REC_PID=$!
  sleep 32
  wait "$REC_PID" 2>/dev/null || true
  adb -s "$ADB_TARGET" pull /sdcard/deepcoder-e2e.mp4 "$OUT_DIR/deepcoder-e2e.mp4" >/dev/null
  log "录屏完成 → $OUT_DIR/deepcoder-e2e.mp4 ($(du -h "$OUT_DIR/deepcoder-e2e.mp4" | awk '{print $1}'))"
}
cmd_e2e_smoke() {
  cmd_connect
  cmd_devices
  cmd_install "${1:-$APK_DEFAULT}"
  cmd_launch
  cmd_screenshots
  cmd_record_screen_30s
  echo
  log "✅ 自动化完成，产物目录：$OUT_DIR"
  ls -la "$OUT_DIR"
}
cmd_clean() {
  log "uninstall pkg $PKG"
  adb -s "$ADB_TARGET" uninstall "$PKG" 2>&1 | tee "$OUT_DIR/99-uninstall.log" || true
}
# ===== Entry =====
case "${1:-install-and-screenshot}" in
  connect)         cmd_connect ;;
  devices)         cmd_connect ; cmd_devices ;;
  install)         cmd_connect ; cmd_install "${2:-$APK_DEFAULT}" ;;
  launch)          cmd_connect ; cmd_launch ;;
  screenshots)     cmd_connect ; cmd_screenshots ;;
  record)          cmd_connect ; cmd_record_screen_30s ;;
  install-and-screenshot|e2e) cmd_e2e_smoke "${2:-$APK_DEFAULT}" ;;
  clean)           cmd_connect ; cmd_clean ;;
  *)
    echo "用法: $0 {connect|devices|install [APK]|launch|screenshots|record|install-and-screenshot|clean}"
    echo "环境变量：ADB_PORT (默认 5555)  DEEPSEEK_API_KEY  OUT_DIR"
    exit 1
    ;;
esac
