#!/bin/sh
set -eu
# Only base64 secrets are accepted, so shell/SQL quoting cannot change the statement.
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
for domain in identity catalog trade ai; do
  case "$domain" in
    identity) domain_password=$IDENTITY_DB_PASSWORD;;
    catalog) domain_password=$CATALOG_DB_PASSWORD;;
    trade) domain_password=$TRADE_DB_PASSWORD;;
    ai) domain_password=$AI_DB_PASSWORD;;
  esac
  case "$domain_password" in *[!A-Za-z0-9+/=_-]*|'') echo 'Database passwords must be nonempty base64 secrets' >&2; exit 1;; esac
  mysql -uroot <<SQL
CREATE DATABASE cinema_${domain} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'cinema_${domain}'@'%' IDENTIFIED BY '${domain_password}';
GRANT ALL PRIVILEGES ON cinema_${domain}.* TO 'cinema_${domain}'@'%';
SQL
done
