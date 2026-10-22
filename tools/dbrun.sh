#!/usr/bin/env bash
# Prefer each service's Flyway migrations. This helper runs an explicitly selected SQL file intact.
set -euo pipefail
: "${DB_NAME:?Set DB_NAME to the intended service database}"
: "${DB_USERNAME:?Set DB_USERNAME}"
: "${DB_PASSWORD:?Set DB_PASSWORD}"
export MYSQL_PWD="$DB_PASSWORD"
mysql --host="${DB_HOST:-127.0.0.1}" --port="${DB_PORT:-3306}"   --user="$DB_USERNAME" --database="$DB_NAME" --default-character-set=utf8mb4   < "${1:?Pass a SQL file}"
