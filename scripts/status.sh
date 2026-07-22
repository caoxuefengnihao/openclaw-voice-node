#!/usr/bin/env bash
# status.sh — 查看进程和端口状态

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$PROJECT_ROOT/run"

echo "=== PID 文件 ==="
for f in "$RUN_DIR"/*.pid; do
  [[ -f "$f" ]] || continue
  name=$(basename "$f" .pid)
  pid=$(cat "$f")
  if kill -0 "$pid" 2>/dev/null; then
    echo "  $name: 运行中 (PID $pid)"
  else
    echo "  $name: PID $pid 已死（残留文件）"
  fi
done

echo ""
echo "=== 端口监听 ==="
for port in "${BACKEND_PORT:-8090}" "${FRONTEND_PORT:-5174}"; do
  if command -v lsof >/dev/null && lsof -i ":$port" -sTCP:LISTEN >/dev/null 2>&1; then
    pid=$(lsof -t -i ":$port" -sTCP:LISTEN | head -1)
    echo "  $port: 监听中 (PID $pid)"
  else
    echo "  $port: 未监听"
  fi
done

echo ""
echo "=== 后端健康 ==="
if command -v curl >/dev/null; then
  curl -s --max-time 3 "http://localhost:${BACKEND_PORT:-8090}/api/status" | head -20
else
  echo "  (curl 不在 PATH，跳过)"
fi
