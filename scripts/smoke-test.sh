#!/usr/bin/env bash
set -euo pipefail

base_url="${BASE_URL:-http://localhost:8080}"
event_key="smoke-order-$(date +%s)"

echo "==> Health"
curl --fail --silent --show-error "$base_url/actuator/health"
echo

echo "==> Escrita e leitura de cache com TTL"
curl --fail --silent --show-error \
  -X POST "$base_url/api/cache/smoke-customer" \
  -H "Content-Type: application/json" \
  -d '{"value":"active","ttl":"PT2M"}'
cache_result="$(curl --fail --silent --show-error "$base_url/api/cache/smoke-customer")"
if [[ "$cache_result" != *'"found":true'* ]] || [[ "$cache_result" != *'"value":"active"'* ]]; then
  echo "FAIL: leitura do cache não retornou o valor esperado: $cache_result" >&2
  exit 1
fi
echo "✅ Cache key/value com TTL passou"

echo "==> Publicação idempotente no Redis Stream"
first="$(curl --fail --silent --show-error \
  -X POST "$base_url/api/events" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $event_key" \
  -d '{"orderId":42,"status":"CREATED"}')"
second="$(curl --fail --silent --show-error \
  -X POST "$base_url/api/events" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $event_key" \
  -d '{"orderId":42,"status":"CREATED"}')"
first_id="$(printf '%s' "$first" | sed -E 's/.*"redisId":"([^"]+)".*/\1/')"
second_id="$(printf '%s' "$second" | sed -E 's/.*"redisId":"([^"]+)".*/\1/')"
if [[ -z "$first_id" ]] || [[ "$first_id" != "$second_id" ]]; then
  echo "FAIL: publicação não foi idempotente: $first / $second" >&2
  exit 1
fi
echo "✅ Producer idempotente passou: redisId=$first_id"

echo "==> Status regional e circuit breaker"
curl --fail --silent --show-error "$base_url/api/cache-status"
echo
echo "✅ Health e status regional passaram"
echo "✅ Smoke test completo passou"
