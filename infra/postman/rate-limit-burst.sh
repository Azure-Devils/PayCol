#!/usr/bin/env bash
# Prueba de rate limiting (ráfaga) contra la API real en Azure.
# Por qué no se hace con Newman: Newman ejecuta las peticiones de una colección
# EN SERIE (una espera la respuesta de la anterior antes de mandar la siguiente).
# La ventana de ráfaga del filtro (RateLimitFilter) es de 10 peticiones / 1 segundo
# por origen (ver application.properties, centinela.ratelimit.burst-*). Con latencia
# de red real hacia chilecentral, 10 peticiones en serie casi siempre tardan MÁS de
# 1 segundo en total, así que nunca se agota la ráfaga -- hace falta enviarlas en
# paralelo (todas casi al mismo tiempo) para que caigan dentro de la misma ventana.
set -euo pipefail

BASE_URL="${1:-https://app-centinela-api-ewhxd5fxb7g3a9fp.chilecentral-01.azurewebsites.net}"
TOTAL_REQUESTS=15

echo "Disparando $TOTAL_REQUESTS peticiones EN PARALELO contra $BASE_URL/api/v1/transactions ..."
echo "Umbral esperado: las primeras 10 deberian responder 201, el resto 429."
echo ""

seq 1 "$TOTAL_REQUESTS" | xargs -P "$TOTAL_REQUESTS" -I{} bash -c '
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "'"$BASE_URL"'/api/v1/transactions" \
    -H "Content-Type: application/json" \
    -d "{
      \"transactionId\": \"burst-{}-$(date +%s%N)\",
      \"customerId\": \"cust-burst-test\",
      \"amountCents\": 1000,
      \"currency\": \"COP\",
      \"transactionTimestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",
      \"location\": {\"latitude\": 4.7, \"longitude\": -74.0},
      \"merchantId\": \"merch-burst-test\",
      \"merchantCategory\": \"5411\"
    }"
' | sort | uniq -c | sort -rn

echo ""
echo "Si NO aparece ningun 429 arriba: la maquina desde la que corres esto puede"
echo "estar detras de un NAT/gateway distinto por peticion, o el origen (IP/X-API-Key)"
echo "resuelto por el filtro no coincide entre peticiones concurrentes. Revisar"
echo "RateLimitFilter.resolveOrigin() y confirmar con 'curl -i' individual que la"
echo "cabecera X-RateLimit-Remaining decrece entre respuestas consecutivas."
