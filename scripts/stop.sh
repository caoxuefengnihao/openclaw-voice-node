#!/usr/bin/env bash
# stop.sh — 停止后端 + 前端
#
# 用法：
#   ./scripts/stop.sh
#   BACKEND_PORT=9090 FRONTEND_PORT=6173 ./scripts/stop.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$PROJECT_ROOT/run"
BACKEND_PORT="${BACKEND_PORT:-8090}"
FRONTEND_PORT="${FRONTEND_PORT:-5174}"

stop_one() {
  local name=$1
  local port=$2
  local pid_file="$RUN_DIR/$3.pid"

  local pid=""
  if [[ -f "$pid_file" ]]; then
    pid=$(cat "$pid_file")
  fi

  # 优先 PID 文件，否则用端口
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    echo "▶ 停止 $name (PID $pid)..."
    kill -TERM "$pid" 2>/dev/null || true
  elif command -v lsof >/dev/null && lsof -t -i ":$port" -sTCP:LISTEN >/dev/null 2>&1; then
    pid=$(lsof -t -i ":$port" -sTCP:LISTEN | head -1)
    echo "▶ 停止 $name (port $port, PID $pid)..."
    kill -TERM "$pid" 2>/dev/null || true
  else
    echo "→ $name 未运行"
    rm -f "$pid_file"
    return
  fi

  # 等 5s 优雅退出
  for i in 1 2 3 4 5; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
      echo "  → 已停止"
      return
    fi
    sleep 1
  done

  # 强杀
  echo "  → 5s 未退出，强制 kill -9"
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$pid_file"
}

stop_one "后端" "$BACKEND_PORT" "backend"
stop_one "前端" "$FRONTEND_PORT" "frontend"
echo ""
echo "✅ 停止完成"
