#!/usr/bin/env bash
# 本地查库小工具 —— 凭据从 application.yml 读，不写在命令行上（免得进 shell 历史/日志）。
#   用法: bash tools/dbq.sh "SELECT COUNT(*) FROM movie;"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
YML="$ROOT/src/main/resources/application.yml"

export MYSQL_PWD="$(sed -n 's/.*password: \${MYSQL_PASSWORD:\([^}]*\)}.*/\1/p' "$YML" | head -1)"
DB="$(sed -n 's#.*url: jdbc:mysql://[^/]*/\([^?]*\).*#\1#p' "$YML" | head -1)"

# mysql.exe 在 Windows 下输出 CRLF，行尾的 \r 会污染 `while read` 拿到的变量
# （拿去拼 URL 会变成 malformed），所以统一去掉。
"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
  -uroot "$DB" --default-character-set=utf8mb4 -N -B -e "$1" | tr -d '\r'
