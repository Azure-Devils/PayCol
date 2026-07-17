# API de Centinela

> Documentación viva de la API de Centinela (sistema de detección de fraude transaccional).
> Generada a partir del código fuente real (controllers, DTOs, `GlobalExceptionHandler`, `pom.xml`, `application.properties`).
> Estado del proyecto: **Semana 1 — existen el endpoint de ingesta y el endpoint de consulta por ID de transacciones.** No hay endpoints de reglas de fraude todavía.

---

## 1. Stack tecnológico

| Componente | Valor | Fuente |
|---|---|---|
| Lenguaje | Java 21 | `pom.xml` (`<java.version>21</java.version>`) |
| Framework | Spring Boot 3.3.4 (parent `spring-boot-starter-parent`) | `pom.xml` |
| Web | `spring-boot-starter-web` | `pom.xml` |
| Validación | `spring-boot-starter-validation` (Jakarta Bean Validation, `jakarta.validation.constraints.*`) | `pom.xml` |
| Persistencia | `spring-boot-starter-data-jpa` sobre PostgreSQL (driver `org.postgresql:postgresql`, runtime) | `pom.xml` |
| Base de datos | PostgreSQL 16 (imagen `postgres:16-alpine` en `docker-compose.yml`), local en `localhost:5432/centinela` | `application.properties`, `docker-compose.yml` |
| Migraciones | Flyway (`flyway-core` + `flyway-database-postgresql`), ubicación `classpath:db/migration`, esquema actual en `V1__init.sql`. Hibernate solo valida el esquema (`spring.jpa.hibernate.ddl-auto=validate`), no lo genera. | `pom.xml`, `application.properties` |
| Serialización JSON | Jackson (incluido en `spring-boot-starter-web`) | `pom.xml` |
| Servicios externos | Azure Storage Queue (`azure-storage-queue`) + Azure Identity (`azure-identity`, pensado para Managed Identity vía `DefaultAzureCredentialBuilder`). **Aún no implementado**: el adaptador real es un placeholder no-op (`NoOpMessageQueueAdapter`) que solo loguea; la integración real con Azure queda para una semana posterior (alcance DevOps/Infra). | `pom.xml`, `NoOpMessageQueueAdapter.java` |
| Testing | `spring-boot-starter-test` | `pom.xml` |
| Documentación OpenAPI/Swagger automática | **No configurada.** No hay dependencia `springdoc-openapi` (ni ninguna equivalente) en `pom.xml`. Este documento es Markdown escrito a mano con formato inspirado en OpenAPI, no una spec real generada por el código. | `pom.xml` (ausencia verificada) |

---

## 2. Convenciones globales

- **Puerto del servidor**: `8081` (`server.port=8081` en `application.properties`).
- **Prefijo de rutas**: `/api/v1/...` (versión fija en la ruta, ver `@RequestMapping("/api/v1/transactions")`).
- **Formato de fechas**: ISO-8601 / RFC 3339 en UTC, tipo `Instant` de Java serializado como string (`@JsonFormat(shape = JsonFormat.Shape.STRING)` en `transactionTimestamp`). Ejemplo: `"2026-07-17T14:30:00Z"`.
- **Payload estricto (anti-polución)**: `spring.jackson.deserialization.fail-on-unknown-properties=true`. Cualquier campo no declarado en el DTO de request provoca un error — ver sección de errores abajo (comentario en `TransactionRequestDto.java`: "sección 4.2 del TDD").
- **Zona horaria de persistencia**: `hibernate.jdbc.time_zone=UTC`.
- **Manejo de errores**: centralizado en `GlobalExceptionHandler` (`@RestControllerAdvice`). Mapea:
  - `HttpMessageNotReadableException` cuya causa es `UnrecognizedPropertyException` (campo no reconocido en el payload) → **422 Unprocessable Entity**.
  - `HttpMessageNotReadableException` por cualquier otra causa (JSON malformado) → **400 Bad Request**.
  - `MethodArgumentNotValidException` (violación de anotaciones `@Valid`/`@NotBlank`/`@Positive`/etc. del DTO) → **422 Unprocessable Entity**, incluye mapa `fieldErrors` con el detalle por campo.
  - `IllegalArgumentException` (violaciones de las validaciones de negocio en `IngestTransactionService.validate(...)`, capa de dominio) → **422 Unprocessable Entity**.
  - Todo body de error sigue esta forma base:
    ```json
    {
      "timestamp": "2026-07-17T14:30:00.123Z",
      "status": 422,
      "error": "Unprocessable Entity",
      "message": "descripción del problema"
    }
    ```
    (y opcionalmente `"fieldErrors": { "campo": "mensaje" }` cuando el error viene de `@Valid`).

---

## 3. Endpoints

### `POST /api/v1/transactions`

**Summary:** Ingesta de una transacción financiera cruda.

**Descripción:** Recibe una transacción desde el sistema emisor (origen), la valida contra el contrato de negocio, le asigna un `ingestionTimestamp` fijado siempre por el servidor (el cliente no puede enviarlo ni sobreescribirlo), la persiste en PostgreSQL y la publica en la cola de mensajería asíncrona (actualmente un adaptador no-op, ver stack). Es idempotente: si `transactionId` ya fue ingerido previamente, devuelve la transacción existente sin duplicarla ni volver a publicarla en la cola.

**Headers requeridos:**

| Header | Requerido | Valor |
|---|---|---|
| `Content-Type` | Sí | `application/json` |

**Request body:** `TransactionRequestDto` (JSON)

| Campo | Tipo | Requerido | Validaciones (Bean Validation) |
|---|---|---|---|
| `transactionId` | `string` | Sí | `@NotBlank`, `@Size(max = 100)` |
| `customerId` | `string` | Sí | `@NotBlank`, `@Size(max = 50)` |
| `amountCents` | `integer` (Long, en centavos) | Sí | `@NotNull`, `@Positive` (> 0) |
| `currency` | `string` | Sí | `@NotBlank`, `@Size(min = 3, max = 3)` (código ISO 4217, ej. `COP`, `USD`) |
| `transactionTimestamp` | `string` (ISO-8601, UTC) | Sí | `@NotNull`, `@PastOrPresent` (no puede ser una fecha futura) |
| `location` | `object` (`LocationRequestDto`) | Sí | `@NotNull`, `@Valid` (se validan sus campos internos, ver abajo) |
| `location.latitude` | `number` (decimal) | Sí | `@NotNull`, `@DecimalMin("-90.0")`, `@DecimalMax("90.0")` |
| `location.longitude` | `number` (decimal) | Sí | `@NotNull`, `@DecimalMin("-180.0")`, `@DecimalMax("180.0")` |
| `location.description` | `string` | No | `@Size(max = 255)` |
| `merchantId` | `string` | Sí | `@NotBlank`, `@Size(max = 50)` |
| `merchantCategory` | `string` | Sí | `@NotBlank`, `@Size(max = 10)` |

Nota importante: **no se acepta `ingestionTimestamp` (ni ningún otro campo fuera de esta lista) en el request.** Con `fail-on-unknown-properties=true`, cualquier campo extra (incluido un intento de enviar `ingestionTimestamp`) provoca 422.

**Ejemplo de request:**

```json
{
  "transactionId": "txn-2026-0717-000123",
  "customerId": "cust-88421",
  "amountCents": 4599900,
  "currency": "COP",
  "transactionTimestamp": "2026-07-17T14:30:00Z",
  "location": {
    "latitude": 4.710989,
    "longitude": -74.072092,
    "description": "Bogotá, Colombia"
  },
  "merchantId": "merch-00987",
  "merchantCategory": "5411"
}
```

*(`merchantCategory` como código MCC de 4 dígitos es un ejemplo plausible, no un valor obligatorio impuesto por el código; el DTO solo exige `@NotBlank` y máximo 10 caracteres.)*

**Responses:**

| Código | Motivo |
|---|---|
| **201 Created** | Transacción validada y persistida exitosamente (o ya existente, devuelta por idempotencia). Body: `TransactionResponseDto`. |
| **400 Bad Request** | JSON malformado / no parseable (sintaxis inválida), distinto de un campo desconocido. |
| **422 Unprocessable Entity** | Alguna de estas causas: (a) el payload contiene un campo no declarado en el contrato (`UnrecognizedPropertyException`); (b) una o más anotaciones `@Valid` del DTO fallan (incluye `fieldErrors` con detalle); (c) una regla de negocio de `IngestTransactionService.validate(...)` falla (p. ej. `currency` no tiene 3 letras, aunque esto ya debería quedar cubierto por el DTO). |

**Ejemplo de response exitosa (201):**

```json
{
  "transactionId": "txn-2026-0717-000123",
  "customerId": "cust-88421",
  "amountCents": 4599900,
  "currency": "COP",
  "transactionTimestamp": "2026-07-17T14:30:00Z",
  "ingestionTimestamp": "2026-07-17T14:30:01.842Z",
  "location": {
    "locationId": 17,
    "latitude": 4.710989,
    "longitude": -74.072092,
    "description": "Bogotá, Colombia"
  },
  "merchantId": "merch-00987",
  "merchantCategory": "5411"
}
```

**Ejemplo de response de error (422, campo desconocido):**

```json
{
  "timestamp": "2026-07-17T14:30:01.842Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Campo no reconocido en el payload: 'discountApplied'"
}
```

**Ejemplo de response de error (422, validación de campos):**

```json
{
  "timestamp": "2026-07-17T14:30:01.842Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Error de validación del contrato",
  "fieldErrors": {
    "amountCents": "debe ser mayor que 0",
    "currency": "el tamaño debe estar entre 3 y 3"
  }
}
```

---

### `GET /api/v1/transactions/{transactionId}`

**Summary:** Consulta de una transacción ya ingerida por su `transactionId`.

**Descripción:** Operación de solo lectura. Delega directamente en `TransactionRepositoryPort.findById` (`GetTransactionService`) sin aplicar ninguna lógica de negocio adicional; es una búsqueda simple por clave. Útil para verificar el estado de una transacción previamente enviada al `POST /api/v1/transactions` (por ejemplo, confirmar su `ingestionTimestamp` o los datos con los que quedó persistida).

**Headers requeridos:** ninguno específico (no requiere `Content-Type`, no tiene body de request).

**Parámetros:**

| Parámetro | Ubicación | Tipo | Requerido | Descripción |
|---|---|---|---|---|
| `transactionId` | path | `string` | Sí | Identificador de negocio de la transacción (el mismo `transactionId` enviado en el `POST`). No tiene validación de formato/tamaño en el controller (es un `@PathVariable String` sin anotaciones `@Valid`/`@Size`); cualquier valor produce una búsqueda, y si no hay match, 404. |

**Ejemplo de request:**

```
GET /api/v1/transactions/txn-2026-0717-000123
```

**Responses:**

| Código | Motivo |
|---|---|
| **200 OK** | La transacción existe. Body: `TransactionResponseDto`. |
| **404 Not Found** | No existe ninguna transacción con ese `transactionId`. **Sin body** (`ResponseEntity.notFound().build()` en el controller) — no pasa por `GlobalExceptionHandler`, así que no incluye el JSON de error `{ "timestamp", "status", "error", "message" }` que sí devuelven los 422/400 del resto de la API. Ver nota en "Pendientes / inconsistencias". |

**Ejemplo de response exitosa (200):**

```json
{
  "transactionId": "txn-2026-0717-000123",
  "customerId": "cust-88421",
  "amountCents": 4599900,
  "currency": "COP",
  "transactionTimestamp": "2026-07-17T14:30:00Z",
  "ingestionTimestamp": "2026-07-17T14:30:01.842Z",
  "location": {
    "locationId": 17,
    "latitude": 4.710989,
    "longitude": -74.072092,
    "description": "Bogotá, Colombia"
  },
  "merchantId": "merch-00987",
  "merchantCategory": "5411"
}
```

**Ejemplo de response 404:** cuerpo vacío (sin JSON), solo el status HTTP 404.

---

## Pendientes / inconsistencias

- **No existe `especificaciones.md` en el repositorio.** Varios comentarios en el código (`TransactionRequestDto.java`, `application.properties`, `GlobalExceptionHandler.java`) referencian "sección 4.2 del TDD" o "especificaciones.md, sección 4.2", pero ese archivo no está presente en el árbol del proyecto al momento de documentar. No se pudo contrastar el contrato contra la especificación original.
- **`IllegalArgumentException` mapeada a 422 sin distinguir "duplicado" de "inválido".** `IngestTransactionService` usa `IllegalArgumentException` genéricas para todas las validaciones de negocio (`require(...)`), y todas caen en el mismo handler → 422 con solo un `message`, sin `fieldErrors`. Si en el futuro se quiere diferenciar tipos de error de negocio (ej. moneda inválida vs. transacción duplicada), no hay excepciones específicas todavía.
- **No hay manejo explícito de conflicto en la capa JPA.** Si dos requests concurrentes con el mismo `transactionId` llegan casi al mismo tiempo, la verificación de idempotencia (`findById` antes de `save`) no es atómica; una posible violación de constraint única a nivel de base de datos (si existe en `V1__init.sql`) no tiene un `@ExceptionHandler` dedicado en `GlobalExceptionHandler` (por ejemplo, no se captura `DataIntegrityViolationException`). Esto podría resultar en un 500 no documentado. Se recomienda verificar con el equipo de backend si esto es un riesgo real o si Postgres/Spring absorben la condición de carrera.
- **`amountCents` documentado como `Long`/entero en el DTO, pero no hay tope máximo (`@Max`).** Solo se valida `@Positive`; no hay límite superior declarado en el código, a diferencia de otros campos con `@Size`. No se debe asumir un límite de negocio no declarado.
- **`merchantCategory` no tiene un formato validado (ej. regex de MCC).** Solo `@NotBlank` y `@Size(max = 10)`; el ejemplo `"5411"` usado en este documento es ilustrativo, no una regla del código.
- **El adaptador de mensajería a Azure Storage Queue es un no-op** (`NoOpMessageQueueAdapter`). El endpoint de ingesta "publica" en la cola, pero en la práctica hoy solo genera un log de nivel `debug`; no hay entrega real a Azure todavía. Cualquier documentación de consumidores de la cola queda pendiente de esa implementación futura.
- **`GET /api/v1/transactions/{transactionId}` devuelve 404 sin body de error**, mientras que el resto de la API (400/422 vía `GlobalExceptionHandler`) sí devuelve un JSON `{ "timestamp", "status", "error", "message" }` consistente. El 404 se construye directamente en `TransactionController.getById` con `ResponseEntity.notFound().build()` y nunca pasa por `GlobalExceptionHandler`. Esto es una inconsistencia de contrato entre endpoints (un cliente que parsea siempre el mismo shape de error fallará en el caso 404). No se corrige aquí, solo se documenta.
- **`transactionId` como path variable en el `GET` no tiene ninguna validación** (ni `@Size`, ni patrón, ni `@NotBlank` — de hecho no podría estar en blanco al ser parte de la ruta, pero tampoco hay tope de longitud). No hay endpoints de reglas de fraude/velocidad todavía. No se documenta funcionalidad planeada.
