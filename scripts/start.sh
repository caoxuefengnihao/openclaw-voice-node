#!/usr/bin/env bash
# start.sh — 启动后端 + 前端（后台，日志到 logs/）
#
# 用法：
#   ./scripts/start.sh                # 用默认端口 8090/5174
#   BACKEND_PORT=9090 FRONTEND_PORT=6173 ./scripts/start.sh
#
# 停止：
#   ./scripts/stop.sh

set -e

# ===== 配置 =====
BACKEND_PORT="${BACKEND_PORT:-8090}"
FRONTEND_PORT="${FRONTEND_PORT:-5174}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$PROJECT_ROOT/run"
LOGS_DIR="$PROJECT_ROOT/logs"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"

mkdir -p "$RUN_DIR" "$LOGS_DIR"

# 工具检查
for cmd in mvn npm lsof; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "❌ '$cmd' 不在 PATH 中"
    exit 1
  fi
done

port_busy() {
  lsof -i ":$1" -sTCP:LISTEN >/dev/null 2>&1
}

# ===== 后端 =====
if [[ -f "$BACKEND_PID_FILE" ]] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
  echo "▶ 后端已在运行 (PID $(cat "$BACKEND_PID_FILE"))"
elif port_busy "$BACKEND_PORT"; then
  echo "⚠️  端口 $BACKEND_PORT 已被占用，跳过后端"
else
  echo "▶ 启动后端 (port $BACKEND_PORT)..."
  cd "$PROJECT_ROOT"
  if [[ -z "$OPENCLAW_TOKEN" ]]; then
    echo "  ⚠️  OPENCLAW_TOKEN 未设置（首次连接会失败）"
  fi
  SERVER_PORT="$BACKEND_PORT" nohup mvn spring-boot:run > "$LOGS_DIR/backend.log" 2>&1 &
  echo $! > "$BACKEND_PID_FILE"
  echo "  → PID $(cat "$BACKEND_PID_FILE")，日志: $LOGS_DIR/backend.log"
fi

# ===== 前端 =====
if [[ -f "$FRONTEND_PID_FILE" ]] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
  echo "▶ 前端已在运行 (PID $(cat "$FRONTEND_PID_FILE"))"
elif port_busy "$FRONTEND_PORT"; then
  echo "⚠️  端口 $FRONTEND_PORT 已被占用，跳过前端"
else
  echo "▶ 启动前端 (port $FRONTEND_PORT)..."
  cd "$PROJECT_ROOT/frontend"
  if [[ ! -d node_modules ]]; then
    echo "  → 第一次跑，先 npm install..."
    npm install
  fi
  FRONTEND_PORT="$FRONTEND_PORT" BACKEND_PORT="$BACKEND_PORT" nohup npm run dev > "$LOGS_DIR/frontend.log" 2>&1 &
  echo $! > "$FRONTEND_PID_FILE"
  echo "  → PID $(cat "$FRONTEND_PID_FILE")，日志: $LOGS_DIR/frontend.log"
fi

# ===== 健康检查 =====
echo ""
sleep 4
echo "=== 健康检查 ==="
check_one() {
  local name=$1
  local port=$2
  if port_busy "$port"; then
    local pid=$(lsof -t -i ":$port" -sTCP:LISTEN | head -1)
    echo "  ✅ $name 端口 $port 监听中 (PID $pid)"
  else
    echo "  ❌ $name 端口 $port 未起，看 logs/$name.log"
  fi
}
check_one "后端" "$BACKEND_PORT"
check_one "前端" "$FRONTEND_PORT"

echo ""
echo "✅ 启动完成"
echo "   后端:  http://localhost:$BACKEND_PORT/api/status"
echo "   前端:  http://localhost:$FRONTEND_PORT"
echo "   日志:  tail -f $LOGS_DIR/*.log"
echo "   停止:  ./scripts/stop.sh"
