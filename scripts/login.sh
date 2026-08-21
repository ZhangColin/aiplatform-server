#!/usr/bin/env bash
# ============================================================================
# A2 登录链路冒烟脚本（票 #19）：curl cookie-jar 走完整授权码链，产出 jar 供
# 后续 `curl -b` 用。全程不落凭据进仓库（账号/密码从环境变量或 .env.local 读）。
#
# 用法：
#   SSO_ACCOUNT=you@example.com SSO_PASSWORD=... ./scripts/login.sh [jar路径]
#
# 前提：
#   - 本服务（8888，带 SSO_CLIENT_ID/SSO_CLIENT_SECRET 环境变量启动）在跑
#   - identity（identity.localhost:10001）在跑
#   - identity 侧已登记 redirect_uri = http://localhost:3333/auth/callback 白名单
#
# 链路（浏览器闭环的 curl 等价物）：
#   /auth/login（种 oauth_txn）→ identity /authorize（无 SSO 会话则密码登录）
#   → /auth/callback（种 aiplatform_session）→ /api/me 验证
#   identity 侧已有 SSO 会话时免密直通（「服务重启一次 SSO 弹回重登」的验证面）。
# ============================================================================
set -euo pipefail

BACKEND="${AIPLATFORM_BACKEND:-http://localhost:8888}"
IDENTITY="${SSO_ISSUER:-http://identity.localhost:10001}"
JAR="${1:-/tmp/aiplatform-cookie-jar}"
RETURN_TO="${SSO_RETURN_TO:-/}"

# 凭据：优先环境变量，其次仓库根 .env.local（gitignored，格式 SSO_ACCOUNT=...）
if [[ -z "${SSO_ACCOUNT:-}" || -z "${SSO_PASSWORD:-}" ]] && [[ -f .env.local ]]; then
  set -a; source .env.local; set +a
fi
: "${SSO_ACCOUNT:?需要 identity 测试账号：SSO_ACCOUNT（可放 .env.local，不进仓库）}"
: "${SSO_PASSWORD:?需要 identity 测试账号密码：SSO_PASSWORD（可放 .env.local，不进仓库）}"

step() { printf '\n==> %s\n' "$*"; }
urldecode() { python3 -c 'import sys,urllib.parse; print(urllib.parse.unquote(sys.stdin.read().strip()))'; }
query_of() { printf '%s' "$1" | sed -n "s/.*[?&]$2=\([^&]*\).*/\1/p" | urldecode; }
path_and_query() { printf '%s' "$1" | sed -E 's#^[a-zA-Z]+://[^/]+##'; }

rm -f "$JAR"

# 1. 登录发起：种 oauth_txn 事务 cookie，拿 /authorize 跳转
step "GET $BACKEND/auth/login?returnTo=$RETURN_TO"
AUTHORIZE_URL=$(curl -sf -c "$JAR" -o /dev/null -w '%{redirect_url}' \
  "$BACKEND/auth/login?returnTo=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$RETURN_TO")")
echo "authorize: $AUTHORIZE_URL"
[[ "$AUTHORIZE_URL" == "$IDENTITY/authorize"* ]] || { echo "!! 跳转非预期（服务端未配 sso.*？）"; exit 1; }

# 2. 走 authorize：有 identity SSO 会话 → 直接发 code 回调；无 → 302 登录页
step "GET /authorize（探测 identity SSO 会话）"
NEXT_URL=$(curl -sf -b "$JAR" -c "$JAR" -o /dev/null -w '%{redirect_url}' "$AUTHORIZE_URL")
echo "next: $NEXT_URL"

# 3. 未登录：密码登录（参数取自 authorize URL，与登录页 SPA 同源回传）
if [[ "$NEXT_URL" != *"/auth/callback"* ]]; then
  step "POST $IDENTITY/api/sso/login（密码登录）"
  LOGIN_BODY=$(python3 - "$SSO_ACCOUNT" "$SSO_PASSWORD" \
    "$(query_of "$AUTHORIZE_URL" client_id)" \
    "$(query_of "$AUTHORIZE_URL" redirect_uri)" \
    "$(query_of "$AUTHORIZE_URL" scope)" \
    "$(query_of "$AUTHORIZE_URL" state)" \
    "$(query_of "$AUTHORIZE_URL" nonce)" <<'PY'
import json, sys
account, password, client_id, redirect_uri, scope, state, nonce = sys.argv[1:]
print(json.dumps({"account": account, "password": password, "clientId": client_id,
                  "redirectUri": redirect_uri, "scope": scope, "state": state, "nonce": nonce}))
PY
  )
  LOGIN_RESP=$(curl -s -b "$JAR" -c "$JAR" -H 'Content-Type: application/json' \
    -d "$LOGIN_BODY" -w '\n%{http_code}' "$IDENTITY/api/sso/login")
  LOGIN_CODE=$(printf '%s' "$LOGIN_RESP" | tail -n1)
  LOGIN_BODY_RESP=$(printf '%s' "$LOGIN_RESP" | sed '$d')
  echo "login: HTTP $LOGIN_CODE $LOGIN_BODY_RESP"
  [[ "$LOGIN_CODE" == "200" ]] || { echo "!! 登录失败"; exit 1; }
  NEXT_URL=$(printf '%s' "$LOGIN_BODY_RESP" | python3 -c '
import json,sys
d = json.load(sys.stdin)
print((d.get("data") or {}).get("redirectUrl") or d.get("redirectUrl") or "")')
  [[ -n "$NEXT_URL" ]] || { echo "!! 登录响应无 redirectUrl"; exit 1; }
  echo "redirectUrl: $NEXT_URL"
fi

# 4. 回调：3333 域的回调改写为本服务直连（cookie 按 localhost 域共享，不分端口；
#    token 交换的 redirect_uri 由服务端配置提供，与被改写的入口无关）
step "GET /auth/callback"
case "$NEXT_URL" in
  *"/auth/callback"*)
    CALLBACK_URL="$BACKEND$(path_and_query "$NEXT_URL")" ;;
  *)
    CALLBACK_URL="$NEXT_URL" ;;   # identity 内部再跳（如重进 authorize），原样跟随
esac
FINAL_URL=$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{redirect_url}' "$CALLBACK_URL")
echo "callback → $FINAL_URL"

# 5. 验证：业务 cookie 在 jar 里，/api/me 返回 accountId + displayName
step "GET $BACKEND/api/me（凭 jar）"
ME=$(curl -sf -b "$JAR" "$BACKEND/api/me")
echo "$ME"
printf '%s' "$ME" | grep -q '"accountId"' \
  || { echo "!! /api/me 未返回 accountId（会话未建立）"; exit 1; }

printf '\n登录链路 OK ✓  cookie jar: %s\n  后续验收：curl -b %s %s/api/...\n' "$JAR" "$JAR" "$BACKEND"
