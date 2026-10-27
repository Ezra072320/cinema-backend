#!/usr/bin/env bash
set -euo pipefail
# Only the two volumes attached to this one-off init role are touched.
mkdir -p /store /logs/rocketmqlogs
chown -R 3000:3000 /store /logs
