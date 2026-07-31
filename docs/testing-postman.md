# Guía de testing manual — Colección de Postman de Centinela

> Referencia rápida para correr `infra/postman/centinela.postman_collection.json`,
> que consolida en 6 carpetas (`01 Flujo Feliz` → `06 Carga`) la colección
> original del repo (idempotencia/contrato/errores, con checks fuertes como el
> magic-byte `%PDF`) con la aportada por el equipo (folders, `X-API-Key`,
> `status`/`receiptUploaded`, rate limit, carga) y una carpeta nueva para la
> foto de comprobante (`ReceiptPhotoDecoder`). Generada/verificada contra el
> código real (`TransactionController`, `RateLimitFilter`,
> `IngestTransactionService`, `ReceiptPhotoDecoder`).

## Variables de colección

| Variable | Valor por defecto | Notas |
|---|---|---|
| `baseUrl` | `https://app-centinela-api-ewhxd5fxb7g3a9fp.chilecentral-01.azurewebsites.net` | App Service real en Azure (`chilecentral`) |
| `apiKey` | vacío | Opcional. Sin ella, el rate limit se aplica por IP (`RateLimitFilter.resolveOrigin()`) |
| `testCustomerId` | `cust-postman-load` | Cliente fijo usado en todo el flujo feliz/idempotencia |
| `testMerchantId` | `merch-postman-load` | Comercio fijo |
| `createdTransactionId` / `idemTransactionId` / ... | se auto-rellenan | Los scripts `prerequest`/`test` de cada carpeta los generan y encadenan |

## Endpoints usados (los 3 reales de la API)

| Método | URL | Descripción |
|---|---|---|
| `POST` | `{{baseUrl}}/api/v1/transactions` | Ingesta de una transacción |
| `GET` | `{{baseUrl}}/api/v1/transactions/{transactionId}` | Consulta por id |
| `GET` | `{{baseUrl}}/api/v1/transactions/{transactionId}/receipt` | PDF de comprobante |

---

## 01 — Flujo Feliz

### POST — Crear transacción válida

```
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json
X-API-Key: {{apiKey}}
```
```json
{
  "transactionId": "{{generatedTransactionId}}",
  "customerId": "cust-postman-load",
  "amountCents": 15000,
  "currency": "USD",
  "transactionTimestamp": "{{generatedTimestamp}}",
  "location": { "latitude": 6.25184, "longitude": -75.56359, "description": "Medellin" },
  "merchantId": "merch-postman-load",
  "merchantCategory": "RETAIL",
  "photo": null,
  "photoName": null
}
```
**Esperado:** `201 Created`, body con `status` (`APPROVED`/`UNDER_REVIEW`) y `receiptUploaded` (boolean).
`transactionId`/`transactionTimestamp` los genera el `prerequest` script — no los edites a mano si quieres reusar la colección.

### GET — Obtener transacción creada

```
GET {{baseUrl}}/api/v1/transactions/{{createdTransactionId}}
```
**Esperado:** `200 OK`, mismos `transactionId`/`customerId` que el POST anterior. Depende de que la carpeta `01` ya haya corrido antes (usa la variable guardada en el test del POST).

### GET — Obtener recibo (receipt)

```
GET {{baseUrl}}/api/v1/transactions/{{createdTransactionId}}/receipt
```
**Esperado:** `200` + `Content-Type: application/pdf`, cuerpo empieza con la firma binaria `%PDF` (el PDF se genera de forma síncrona en la misma ingesta, `IngestTransactionService.java:58`). Si da `404` aquí, es señal real de que `generateAndStoreReceipt` falló silenciosamente (revisar logs del backend) — no es un resultado tolerado por el test.

---

## 02 — Idempotencia

Dos `POST` con **el mismo `transactionId`** (`{{idemTransactionId}}`), la segunda con `amountCents: 999999` en vez de `15000`.

**Esperado en ambas:** `201 Created`. **Clave:** la segunda respuesta debe seguir mostrando `amountCents: 15000` (el valor de la *primera* ingesta) — confirma que `IngestTransactionService` devuelve la transacción ya existente sin sobreescribirla ni volver a publicar el evento.

---

## 03 — Validaciones

| Caso | Qué envía | Esperado |
|---|---|---|
| JSON mal formado | body con `{` sin cerrar | `400 Bad Request` |
| Campo extra `ingestionTimestamp` | payload válido + campo no declarado | `422` (anti-polución, `fail-on-unknown-properties`) |
| `amountCents: 0` | viola `@Positive` | `422` + `fieldErrors.amountCents` |
| `currency: "US"` (2 letras) | viola `@Size(min=3,max=3)` | `422` |
| `transactionTimestamp` futuro | +1 año | `422` (`@PastOrPresent`) |
| `latitude: 200` | fuera de `[-90,90]` | `422` |
| `transactionId: ""` | viola `@NotBlank` | `422` |
| `GET` a transacción inexistente | — | `404` **sin body** (no pasa por `GlobalExceptionHandler`) |
| `GET` receipt inexistente | — | `404` |

---

## 04 — Foto de comprobante (`ReceiptPhotoDecoder`)

Ejercita las 3 validaciones reales de `ReceiptPhotoDecoder.decode(...)` además del caso feliz:

| Caso | `photo` enviado | Esperado |
|---|---|---|
| PNG válido (1x1, base64) | `data:image/png;base64,...` | `201` + `receiptUploaded: true` |
| Data URL sin prefijo `data:image/...` | solo el base64, sin el prefijo | `422`, regex de Data URL falla |
| Base64 corrupto | `data:image/png;base64,!!!no-es-base64!!!` | `422`, `Base64.getDecoder().decode` lanza excepción |
| Bytes decodificados que no son ninguna imagen soportada | base64 válido de texto plano (`"hello world..."`) | `422`, ninguna firma binaria (PNG/JPEG/GIF/WEBP) coincide |

No incluye un caso de "supera 5MB" porque generar ese payload en un `raw` body de Postman es poco práctico — si se quiere ejercitar `MAX_DECODED_SIZE_BYTES`, mejor un script `pm.sendRequest` que arme un base64 grande en runtime, o una prueba de integración en JUnit.

## 05 — Rate Limit

Dos `POST` consecutivos que inspeccionan `X-RateLimit-Remaining` (solo presente cuando la petición SÍ fue aceptada, ver `RateLimitFilter.java:64`) y `Retry-After` (solo presente en `429`).

**Advertencia:** Postman ejecuta las peticiones **en serie**. La ráfaga es 10 req/1s por origen — 2 peticiones secuenciales casi nunca la agotan por la latencia real de red. Para forzar un `429` real, usar el script ya existente en el repo, que dispara en paralelo:

```bash
./infra/postman/rate-limit-burst.sh
```

## 06 — Carga (Collection Runner)

Un único `POST` parametrizado con `pm.info.iteration` en el `transactionId`, pensado para correr con el botón **Runner** de Postman (varias iteraciones, delay bajo). Acepta `201` **o** `429` como resultado válido — el objetivo es generar volumen, no que todas pasen.

---

## Límites que estos tests ejercitan (referencia rápida)

| Límite | Valor | Config |
|---|---|---|
| Ráfaga rate limit | 10 req / 1s por origen | `centinela.ratelimit.burst-*` |
| Sostenido rate limit | 60 req / 60s por origen | `centinela.ratelimit.sustained-*` |
| Tamaño máx. foto de comprobante | 5 MB decodificado | `ReceiptPhotoDecoder.MAX_DECODED_SIZE_BYTES` |
| `amountCents` | > 0 (sin tope superior) | `@Positive` en el DTO |
| `currency` | exactamente 3 letras | `@Size(min=3,max=3)` |
