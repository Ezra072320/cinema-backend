#!/usr/bin/env bash
# 历史工具——现在推荐 mvn spring-boot:run 由 Flyway 自动迁移；本脚本仅用于无 Flyway 场景。
# 把整个 SQL 文件先拼成单行 (去掉注释和空行, 换行 -> 空格), 再按 ; 切单语句跑.
# 用法: bash tools/dbrun.sh <file.sql>
# 注意: 本脚本按 ; 切语句, 含 DELIMITER 的脚本会被切碎, 不要用来跑迁移.
# 语句出错即以非零码退出, 不再吞错.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
YML="$ROOT/src/main/resources/application.yml"
export MYSQL_PWD="$(sed -n 's/.*password: \${MYSQL_PASSWORD:\([^}]*\)}.*/\1/p' "$YML" | head -1)"
DB="$(sed -n 's#.*url: jdbc:mysql://[^/]*/\([^?]*\).*#\1#p' "$YML" | head -1)"

# mysql 命令必须在 PATH 中, 不再使用固定安装路径
if ! command -v mysql >/dev/null 2>&1; then
    echo "error: PATH 里找不到 mysql 命令, 请安装 MySQL 客户端并加入 PATH 后重试" >&2
    exit 1
fi

# 1) 过滤注释行 + 空行
# 2) 整个文件 cat 后 tr 把换行 -> 空格, 多空格压成一个
# 3) 按 ; 切, 去掉前后空格, 非空段逐条交给 mysql -e
FILTERED=$(grep -vE '^\s*(--|$)' "$1" | tr '\n' ' ' | tr -s ' ')

while IFS= read -r stmt; do
    # 去掉首尾空格
    s=$(echo "$stmt" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    [ -z "$s" ] && continue
    echo ">>> $(echo "$s" | head -c 160)..."
    mysql -uroot "$DB" --default-character-set=utf8mb4 -e "$s" 2>&1 | tr -d '\r' | tail -5
    echo "---"
done < <(echo "$FILTERED" | tr ';' '\n')
