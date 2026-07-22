#!/usr/bin/env bash
# logs.sh — 跟踪日志
#
# 用法：
#   ./scripts/logs.sh             # 全部
#   ./scripts/logs.sh backend     # 仅后端
#   ./scripts/logs.sh frontend    # 仅前端
#   ./scripts/logs.sh b           # 同 backend

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS_DIR="$PROJECT_ROOT/logs"

case "${1:-all}" in
  backend|b)
    tail -f "$LOGS_DIR/backend.log"
    ;;
  frontend|f)
    tail -f "$LOGS_DIR/frontend.log"
    ;;
  all|*)
    tail -f "$LOGS_DIR/backend.log" "$LOGS_DIR/frontend.log"
    ;;
esac
