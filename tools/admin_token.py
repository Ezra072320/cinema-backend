#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
拿一个管理员 JWT，供本地联调/验证用。

登录链路有图形验证码，答案存在 Redis 的 `captcha:<ticket>` 里（见 CaptchaService），
所以这里直接读 Redis 把答案取出来，不用 OCR。

token 写到文件（默认 .admin_token，已在 .gitignore 覆盖范围内），**不打印到 stdout**，
免得凭据进日志/终端回滚缓冲。

用法:
    python tools/admin_token.py                    # 默认 admin/admin123
    python tools/admin_token.py -u admin -p ***    # 指定账号
    python tools/admin_token.py --out /tmp/t.txt
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import urllib.request
import uuid

BASE = "http://localhost:8080"
REDIS_HOST, REDIS_PORT = "127.0.0.1", 6379


def redis_get(key: str) -> str | None:
    """极简 RESP 客户端：只要一个 GET，不值得为它装 redis 依赖。"""
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=5) as s:
        payload = f"*2\r\n$3\r\nGET\r\n${len(key)}\r\n{key}\r\n".encode()
        s.sendall(payload)
        data = s.recv(4096)
    if not data:
        return None
    if data[:1] == b"$":                       # bulk string
        head, _, rest = data[1:].partition(b"\r\n")
        if head == b"-1":                      # nil
            return None
        return rest[: int(head)].decode()
    return None


def http_json(url: str, method: str = "GET", body: dict | None = None,
              token: str | None = None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def main() -> int:
    ap = argparse.ArgumentParser(description="获取管理员 JWT")
    ap.add_argument("-u", "--user", default="admin")
    ap.add_argument("-p", "--password", default="admin123")
    ap.add_argument("--out", default=".admin_token")
    args = ap.parse_args()

    ticket = str(uuid.uuid4())

    # 1) 取图 —— 这一步才会把答案写进 Redis
    try:
        urllib.request.urlopen(
            f"{BASE}/api/captcha/image?ticket={ticket}", timeout=15
        ).read()
    except Exception as e:
        print(f"取验证码失败: {e}", file=sys.stderr)
        return 1

    # 2) 直接从 Redis 取答案
    code = redis_get(f"captcha:{ticket}")
    if not code:
        print("Redis 里没有 captcha:<ticket>，检查 Redis 是否在跑、以及和服务的库是否一致",
              file=sys.stderr)
        return 1

    # 3) 登录
    try:
        r = http_json(f"{BASE}/api/admin/login", "POST", {
            "name": args.user,
            "password": args.password,
            "captchaTicket": ticket,
            "captchaCode": code,
        })
    except Exception as e:
        print(f"登录请求失败: {e}", file=sys.stderr)
        return 1

    if r.get("code") != 1 or not r.get("data"):
        print(f"登录失败: {r.get('msg')}", file=sys.stderr)
        return 1

    with open(args.out, "w", encoding="utf-8") as f:
        f.write(r["data"])
    print(f"OK: token 已写入 {args.out}（未打印到终端）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
