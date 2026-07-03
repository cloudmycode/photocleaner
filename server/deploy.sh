#!/bin/bash

# 编译 Linux 二进制并上传到服务器
#
# 用法: ./deploy.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"

SERVER_IP="45.79.40.29"
REMOTE_USER="root"
REMOTE_DIR="/home/www/websites/cleaner.digsaw.cc"
REMOTE_BIN="${REMOTE_DIR}/smart-search"

BUILD_VERSION="$(date -u +%Y%m%d-%H%M%S)"
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
LDFLAGS="-X main.BuildVersion=${BUILD_VERSION} -X main.BuildTime=${BUILD_TIME}"

echo "编译 Linux amd64..."
cd src/go
GOOS=linux GOARCH=amd64 go build -ldflags "${LDFLAGS}" -o smart-search .
cd "${SCRIPT_DIR}"

echo "停止服务器上的 smart-search 进程..."
# 先发 SIGTERM，轮询等待进程真正退出（释放可执行文件），必要时用 SIGKILL 兜底。
# 若不等待就直接 scp，正在运行的二进制会导致 "Text file busy" 覆盖失败。
ssh "${REMOTE_USER}@${SERVER_IP}" "bash -s" <<'REMOTE_STOP'
set -e
mkdir -p "/home/www/websites/cleaner.digsaw.cc"
if pgrep -x smart-search >/dev/null; then
  pkill -x smart-search || true
  for i in $(seq 1 20); do
    pgrep -x smart-search >/dev/null || break
    sleep 0.5
  done
  if pgrep -x smart-search >/dev/null; then
    echo "进程未在 10s 内退出，强制 SIGKILL..."
    pkill -9 -x smart-search || true
    sleep 1
  fi
fi
if pgrep -x smart-search >/dev/null; then
  echo "错误：smart-search 仍在运行，无法安全更新二进制" >&2
  exit 1
fi
echo "进程已停止"
REMOTE_STOP

echo "上传到 ${REMOTE_USER}@${SERVER_IP}:${REMOTE_BIN} ..."
scp src/go/smart-search "${REMOTE_USER}@${SERVER_IP}:${REMOTE_BIN}"
ssh "${REMOTE_USER}@${SERVER_IP}" "chmod +x '${REMOTE_BIN}'"

echo "启动 smart-search 服务..."
ssh "${REMOTE_USER}@${SERVER_IP}" "cd '${REMOTE_DIR}' && nohup ./smart-search </dev/null > smart-search.log 2>&1 & sleep 1; pgrep -x smart-search >/dev/null && echo '启动成功' || { echo '启动失败，请查看 smart-search.log' >&2; exit 1; }"

echo "完成 buildVersion=${BUILD_VERSION}"
