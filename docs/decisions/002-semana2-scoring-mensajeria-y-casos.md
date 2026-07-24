# ADR 002: Motor de scoring, mensajería asíncrona y almacén de casos (Semana 2)

**Fecha:** 2026-07-24
**Estado:** Aceptada
**Alcance:** implementación de código de aplicación (equipo de backend). Coordina con
el equipo de DevOps/Infra para el aprovisionamiento real de Storage Queue, Key Vault y el Postgres de
casos — este documento no autoriza aprovisionamiento, solo el diseño y el código.

Fuente de verdad de requisitos: `docs/weeks/Semana2-Azure.md`. Este ADR cubre exactamente
la lista de "Decisiones a incorporar en el documento de arquitectura" de la sección 3 de
ese documento.

## 1. Restricción arquitectónica central: mensajería, no invocación directa

El TDD es explícito: "la API responde al cliente antes de que el análisis concluya". Se
descartó deliberadamente la alternativa más simple de implementar — que
`IngestTransactionService.ingest()` llamara directamente a `ScoreTransactionUseCase` antes
de responder — porque:

- Acopla la latencia de la API a la del motor de scoring (que crece con el tamaño del
  historial de la cuenta).
- Impide que la ingesta siga funcionando si el motor de scoring (o su almacén) está lento
  o caído.
- Es, textualmente, "el error de diseño más frecuente" que señala el TDD para esta semana.

En su lugar, `IngestTransactionService.ingest()` solo llama a `MessageQueuePort.publish()`
(una única llamada HTTP síncrona a Azure Storage Queue para *confirmar* la publicación, no
para esperar el resultado del análisis) y responde de inmediato. Un componente
independiente (`TransactionEventConsumer`) reacciona a ese evento más tarde, en otro ciclo
de ejecución.

## 2. Motor de scoring "serverless" implementado in-process

El TDD describe el motor de scoring como "un componente serverless activado por el evento
de transacción entrante". Este proyecto es una única aplicación Spring Boot (no existe un
despliegue separado de Azure Functions Java). Se decidió implementar la activación por
evento como un **poller programado dentro del mismo proceso**
(`TransactionEventConsumer`, `@Scheduled` sobre `AzureQueueClients.transactionEvents()`)
en vez de una Azure Function real, por las siguientes razones:

- Cumple el requisito central (la API nunca invoca ni espera este componente) con la misma
  garantía de desacoplamiento que una Function real activada por cola.
- Evita una segunda unidad de despliegue, una segunda tubería de CI/CD y un segundo
  conjunto de credenciales/Managed Identity que administrar — complejidad no justificada
  para el alcance de un proyecto estudiantil de 3 semanas.
- Azure Functions Premium/EP (necesario para VNet integration coherente con la restricción
  de red de la Semana 1) tiene un costo que no encaja con el presupuesto de free tier de
  este proyecto; el plan de Consumo no resuelve por sí solo el acceso privado a Cosmos/Key
  Vault sin trabajo adicional de red.

**Trade-off aceptado:** el "serverless" real escala automáticamente instancias en paralelo
por partición de mensajes; este poller in-process procesa mensajes secuencialmente por lote
(`maxMessagesPerPoll`, hoy 10) cada `poll-interval-ms` (hoy 2s para `transaction-events`,
3s para `fraud-cases`). Es coherente con el volumen de un proyecto estudiantil. Si el
proyecto necesitara escalar horizontalmente, la migración natural sería reemplazar este
componente por una Azure Function real con trigger de Storage Queue — el puerto de dominio
(`ScoreTransactionUseCase`) no cambiaría, solo el adaptador de entrada.

## 3. Almacén de transacciones (Cosmos DB): partición, consistencia, TTL

### 3.1 Clave de partición: `customerId` (sin cambios respecto a la Semana 1)

Ya se había elegido en la migración a Cosmos (ver ADR 001). Se ratifica para la Semana 2
porque es exactamente lo que el motor de scoring necesita: "obtener las transacciones
recientes de una cuenta determinada" (sección 2.1 del TDD) cae dentro de una sola
partición con `customerId` como partition key.

- **Consulta que optimiza:** historial reciente de una cuenta —
  `CosmosTransactionRepositoryAdapter.findMostRecentByCustomer`, implementado con
  `CosmosRepository.findAll(new PartitionKey(customerId))` (heredado de
  `PagingAndSortingRepository`), que la SDK ejecuta contra una única partición física.
  También el point-read `findById(id, new PartitionKey(customerId))` usado por
  `saveScore` para escribir el resultado del scoring sin cross-partition.
- **Consulta que sacrifica:** buscar una transacción por `transactionId` sin conocer su
  `customerId` (lo que hace `GetTransactionUseCase`, expuesto por
  `GET /api/v1/transactions/{id}`) es cross-partition — recorre todas las particiones. Se
  acepta este costo mayor en una consulta de bajo volumen (consulta puntual desde soporte/
  operación) a cambio de que la consulta de alto volumen (una vez por transacción
  ingerida, desde el motor de scoring) sea barata.
- **Alternativas descartadas:** partición por `transactionId` (la consulta dominante se
  volvería cross-partition, inaceptable); partición compuesta `customerId + mes` (reduce
  el "hot partition" de cuentas muy activas, pero completa la ventana de historial en dos
  particiones cuando una transacción cae justo en el cambio de mes — complejidad no
  justificada para el volumen actual).

### 3.2 Nivel de consistencia: Session

Sin cambios respecto a la Semana 1 (ver ADR 001): es el nivel más fuerte disponible sin
costo adicional en el free tier. Para el motor de scoring es suficiente: dentro del mismo
proceso que hizo `save(transaction)`, la siguiente lectura de historial
(`findMostRecentByCustomer`) ve esa misma escritura (garantía read-your-writes de Session),
que es exactamente lo que se necesita para que la transacción recién ingerida participe en
su propia evaluación de velocidad si el consumidor la procesa muy rápido. No se requiere
consistencia **Strong**: encarecería la latencia de cada escritura sin aportar nada, dado
que el único consumidor de esta base de datos es esta misma aplicación (no hay múltiples
regiones ni lectores externos que necesiten ver la última escritura global de inmediato).

### 3.3 Política de expiración (TTL): 90 días

Configurada a nivel de contenedor en `CosmosTransactionDocument`
(`@Container(timeToLive = 7_776_000)`, 90 días). Justificación en función de las ventanas
temporales de las reglas (sección 2.3 del TDD):

- **Velocidad:** ventana de 120 segundos (configurable, `centinela.scoring.rules.velocity.window-seconds`).
- **Geo-imposible:** solo necesita la transacción inmediatamente anterior (minutos/horas).
- **Monto atípico:** necesita una línea base histórica razonable del comportamiento de
  gasto de la cuenta — la que más historial demanda de las cuatro. 90 días cubre patrones
  mensuales (varios ciclos de facturación) sin acumular indefinidamente almacenamiento del
  free tier (25 GB).
- **Comercio de riesgo:** no usa historial en absoluto.

El motor de scoring además acota cada consulta a `centinela.scoring.history-limit`
(por defecto 50 transacciones, ordenadas de la más nueva a la más vieja) independientemente
del TTL — el TTL protege el costo de **almacenamiento** a largo plazo; el límite de
resultados protege el costo de **lectura (RU)** de cada evaluación puntual, para que no
dependa de cuán activa haya sido históricamente una cuenta.

### 3.4 Nivel de servicio (free tier)

Sin cambios respecto a ADR 001: free tier permanente de Cosmos DB (1000 RU/s + 25 GB de
storage, throughput manual — nunca autoscale — una sola región). Límite de capacidad:
exceder 1000 RU/s devuelve `429` en vez de facturar de más; límite de almacenamiento: 25 GB
agregados entre los containers `transactions` y `customers`.

## 4. Almacén de casos de fraude (Postgres): esquema nuevo, aislado del legado

Se reactivan `spring-boot-starter-data-jpa`, `postgresql` y `flyway-*` (ya estaban en el
`pom.xml`, inactivos desde ADR 001), pero apuntando a un **esquema y una base de datos
completamente nuevos** (`centinela_casos`, migraciones en
`src/main/resources/db/casestore/migration`), no al esquema legado de transacciones
(`db/migration/V1__init.sql`, que sigue aislado detrás del perfil `postgres-legacy`).

- **Aislamiento de Hibernate:** `CaseStoreJpaConfig` usa `@EntityScan`/`@EnableJpaRepositories`
  con `basePackages` explícito, acotado a
  `infrastructure.adapter.outbound.casestore.jpa`, para que Hibernate nunca intente
  validar/crear las tablas del esquema legado de transacciones (`TransactionEntity`,
  `CustomerEntity`, `LocationEntity`, que siguen en el paquete `...outbound.database` solo
  para que `PostgresTransactionRepositoryAdapter` compile).
- **Modelo de datos:** `estados` (catálogo), `casos` (referencia a `transactionId` de
  Cosmos — no hay FK real posible entre motores distintos), `asignaciones`,
  `resoluciones`, `auditoria_casos` (inmutable por convención de código: solo INSERT).
  Ver `V1__init_casos.sql` para el detalle completo y los comentarios de diseño.
- **Verificado en esta sesión:** con un Postgres real levantado vía
  `docker compose up -d postgres-casos`, la app arranca, Flyway aplica
  `V1__init_casos.sql` sin errores y Hibernate valida el mapeo de las 5 entidades JPA
  contra el esquema real (`spring.jpa.hibernate.ddl-auto=validate`) sin fallos.
- **Acceso restringido a la subred de aplicación:** a coordinar con el equipo de DevOps/Infra —
  mismo mecanismo de red ya configurado en la Semana 1 para que el almacén no sea
  alcanzable desde internet (Azure Database for PostgreSQL Flexible Server con acceso
  privado/VNet integration, o regla de firewall restringida a la subred de la app).
- **Estrategia de respaldo propuesta (pendiente de confirmación por el equipo de DevOps/Infra sobre
  el SKU real):** backups automáticos diarios con retención de 7 días (mínimo del nivel
  gratuito/burstable de Azure Database for PostgreSQL Flexible Server), lo que implica una
  pérdida máxima tolerable (RPO) de hasta 24 horas para los casos abiertos ese día — el
  volumen de escritura de este almacén es bajo (un caso por transacción marcada, no por
  transacción ingerida), así que ese RPO es aceptable: en el peor escenario, un caso
  perdido puede reabrirse reprocesando el score ya persistido en Cosmos DB (que si tiene
  TTL de 90 días) hacia una nueva fila en `casos`.

## 5. Mensajería: dos colas, dos garantías distintas

Ambas sobre **Azure Storage Queue** (no Service Bus): el free tier de Storage Queue no
tiene límite de mensajes/mes con costo relevante para este volumen, y el proyecto no
necesita las características avanzadas de Service Bus (sesiones, tópicos/suscripciones,
DLQ nativa) — encaja con la nota de alcance del TDD sobre no asumir SKUs caros.

| | `transaction-events` | `fraud-cases` |
|---|---|---|
| **Propósito** | Notificar que hay una transacción nueva para puntuar | Garantizar que un caso marcado se procese |
| **Productor** | `AzureQueueMessageQueueAdapter` (implementa `MessageQueuePort`), invocado por `IngestTransactionService` | `AzureFraudCaseQueueAdapter` (implementa `FraudCaseQueuePort`), invocado por `ScoringEngineService` |
| **Consumidor** | `TransactionEventConsumer` | `FraudCaseQueueConsumer` |
| **Si nadie consume nunca** | Se pierde el scoring de esa transacción puntual — degradación aceptable, no crítica | Se pierde un caso de fraude sin abrir — inaceptable |
| **Cuándo se borra el mensaje** | Tras puntuar con éxito (o tras superar `MAX_DEQUEUE_COUNT=5` reintentos, para no reintentar payloads envenenados indefinidamente) | Tras persistir el caso con éxito en Postgres (o tras `MAX_DEQUEUE_COUNT=10`, más tolerante porque perder un caso es más grave que perder un score) |

**Diferencia funcional (requisito explícito del TDD):** notificar la ocurrencia de un
evento (`transaction-events`) es "avisar y seguir": si nadie escucha, el sistema sigue
funcionando con una degradación acotada y conocida. Garantizar el procesamiento
(`fraud-cases`) es "no avanzar hasta confirmar": el mensaje permanece en la cola — Azure
Storage Queue lo retiene hasta 7 días por defecto — hasta que el consumidor confirma el
efecto secundario (la fila en `casos`) borrándolo explícitamente. Usar el mismo mecanismo
para ambos casos sería o bien demasiado costoso (tratar cada evento de transacción con la
misma garantía fuerte que un caso) o insuficiente (arriesgar perder casos reales).

**Prueba de desacoplamiento (procedimiento reproducible, entregable #10 del TDD):**
1. Detener `FraudCaseQueueConsumer` (por ejemplo, comentando `@Scheduled` o parando la app
   completa mientras Storage Queue sigue en pie).
2. Enviar transacciones que disparen el umbral — deben seguir respondiendo `201 Created`
   con normalidad (la ingesta no depende de este consumidor).
3. Verificar en el portal de Azure (o `az storage queue`) que los mensajes se acumulan en
   `fraud-cases` sin ser reclamados.
4. Restablecer el consumidor — debe procesar todos los mensajes acumulados y crear sus
   casos correspondientes, sin pérdidas (verificable comparando la cantidad de mensajes
   encolados contra las filas nuevas en `casos`).

**No bloquear más de lo necesario (sección 2.5 del TDD):** `sendMessage()` del SDK de
Storage Queue es una única llamada HTTP síncrona; se espera su confirmación (para no
perder el evento silenciosamente) pero jamás se espera al motor de scoring, que corre en
otro ciclo de ejecución del `TransactionEventConsumer`.

## 6. Umbral de scoring configurable sin redeploy

**Valor elegido: 60 puntos** (`centinela.scoring.threshold`, respaldo local si Key Vault no
está disponible). Puntos por regla (configurables, ver `application.properties`):
Velocidad 30, Monto atípico 25, Geo-imposible 40, Comercio de riesgo 50.

**Criterio:** con 60 como umbral, ninguna regla "barata" activada en solitario (Velocidad o
Monto atípico) abre un caso por sí sola — hace falta que se combinen al menos dos señales,
o que se active la señal más fuerte (Comercio de riesgo o Geo-imposible) junto con
cualquier otra. Esto prioriza reducir falsos positivos de una señal ruidosa aislada (por
ejemplo, un cliente que genuinamente hace varias compras seguidas) a costa de exigir
corroboración cruzada — un trade-off razonable para un sistema en fase de calibración
inicial, donde el costo de investigar un falso positivo (tiempo de un analista) se
considera mayor que el de tardar un poco más en detectar un patrón mixto. Este valor debe
recalibrarse con datos reales de producción; por eso el requisito de que sea modificable
sin redeploy es central, no cosmético.

**Mecanismo de lectura sin caché:** `KeyVaultScoringThresholdAdapter` consulta
`SecretClient.getSecret(...)` directamente en **cada** invocación de
`ScoringEngineService.score()` (una vez por transacción puntuada), sin ningún campo
cacheado. Se descartó depender de `spring-cloud-azure-starter-keyvault-secrets` como
property source porque, en la versión usada (5.20.1), no expone un intervalo de refresco
automático fuera de la integración con Spring Cloud Config/Actuator `/refresh` — hubiera
dejado el valor efectivamente cacheado hasta un refresh manual, incumpliendo el requisito.
Costo: una operación de lectura de Key Vault por transacción puntuada — dentro del free
tier de Key Vault (~10.000 operaciones/mes sin costo), suficiente para el volumen de este
proyecto.

## 7. Control de tasa de la API de ingesta

Implementado en la capa de aplicación con Bucket4j (`RateLimitFilter`, servlet filter sobre
`POST /api/v1/transactions`), sin infraestructura adicional (no hay Redis ni API Gateway
dedicado — nota de alcance de la sección 2.7 del TDD). Origen: `X-API-Key` si el cliente la
envía, o IP en su defecto.

**Límites elegidos:**
- **Ráfaga:** 10 peticiones / 1 segundo por origen — permite lotes pequeños o reintentos
  legítimos casi simultáneos sin bloquear de inmediato.
- **Sostenido:** 60 peticiones / 60 segundos por origen (1 req/s en promedio) — generoso
  para pruebas de un proyecto estudiantil, pero acota a un actor que intente saturar la API
  de forma sostenida (cada petición aceptada de más cuesta una escritura en Cosmos DB más
  una ejecución del motor de scoring).

Ambos límites se exigen simultáneamente (bucket con dos bandas). Al superarse, la API
responde `429 Too Many Requests` con cabecera `Retry-After`. Ver el javadoc de
`RateLimitFilter` para el detalle completo, incluida la limitación conocida (buckets en
memoria, por instancia — no aplica a un despliegue con múltiples réplicas sin migrar a un
backend distribuido de Bucket4j).

## 8. Secretos

Todo secreto/endpoint de esta semana sigue el mismo patrón ya usado para Cosmos DB en la
Semana 1: variable de entorno con placeholder vacío por defecto
(`AZURE_STORAGE_QUEUE_ENDPOINT`, `AZURE_KEYVAULT_ENDPOINT`, `CASESTORE_DB_PASSWORD`), nunca
hardcodeado ni commiteado. Autenticación contra Azure Storage Queue y Key Vault siempre vía
`DefaultAzureCredentialBuilder` (Managed Identity) — nunca connection strings ni claves de
cuenta. `CASESTORE_DB_PASSWORD` queda pendiente de migrar a Key Vault también (hoy es una
variable de entorno de respaldo, igual que el resto); a coordinar con el equipo de DevOps/Infra cuando
Key Vault esté provisionado, para no dejar ninguna contraseña como variable de entorno
configurada a mano en el estado final.

## 9. Estado pendiente (a cerrar con el equipo de DevOps/Infra)

- Aprovisionar la cuenta real de Azure Storage Queue (colas `transaction-events` y
  `fraud-cases`) y confirmar el endpoint/nombres exactos.
- Aprovisionar Key Vault real y el secreto `fraud-threshold` (nombre alineado con
  `infra/key-vault.bicep`, ver sección 10 de este documento).
- Aprovisionar el Postgres de casos con acceso restringido a la subred de aplicación y
  confirmar la estrategia de respaldo real (SKU, retención, RPO) — la propuesta de la
  sección 4 de este documento es una hipótesis de trabajo, no una confirmación de infra.
- Verificar consumo de RU real de `findMostRecentByCustomer` contra una cuenta Cosmos real
  (criterio de aceptación "demostrable mediante la métrica de consumo de la consulta").
- Reporte de crédito consumido acumulado y proyección al cierre del proyecto (entregable
  #13 del TDD) — depende de que exista infraestructura real desplegada; no aplicable
  todavía con placeholders vacíos.

## 10. Coordinación con el equipo de DevOps/Infra (IaC en `infra/`, esta misma sesión)

el equipo de DevOps/Infra avanzó en paralelo el Bicep de esta semana (`infra/storage-queue.bicep`,
`infra/key-vault.bicep`, `infra/postgres-cases.bicep`, ver
`docs/decisions/003-ttl-transacciones-y-respaldo-postgres-casos.md`). Puntos verificados y
ajustados en el código de aplicación para que ambos lados queden consistentes:

- **TTL de Cosmos DB (90 días):** coincide exactamente con el valor elegido de forma
  independiente en la sección 3.3 de este documento (`7_776_000` segundos) — sin conflicto.
- **TTL por MENSAJE en `fraud-cases` (corregido en esta sesión):** `infra/storage-queue.bicep`
  advierte que Azure Storage Queue expira y borra silenciosamente cualquier mensaje al que
  no se le indique `timeToLive` explícito al enviarlo (default del servicio: 7 días) — esto
  hubiera violado el requisito de cero pérdida si el consumidor de casos estuviera caído más
  de una semana. Se corrigió `AzureFraudCaseQueueAdapter` para enviar con
  `timeToLive = Duration.ofSeconds(-1)` (nunca expira) en vez del `sendMessage` simple usado
  inicialmente. `transaction-events` conserva el default de 7 días a propósito (es una
  notificación best-effort, ver sección 5 de este documento).
- **Nombre del secreto de umbral:** se alineó `azure.keyvault.scoring-threshold-secret-name`
  (antes `centinela-scoring-threshold`, ahora `fraud-threshold`) con el nombre ya fijado en
  `infra/key-vault.bicep`.
- **Nombres de colas y endpoint:** `infra/storage-queue.bicep` fija los mismos nombres que
  ya usaba el código (`transaction-events`, `fraud-cases`) y expone `AZURE_STORAGE_QUEUE_ENDPOINT`
  como output — coincide con la convención ya usada por `AzureQueueClients`.

**Puntos abiertos, NO resueltos en esta sesión (requieren una decisión conjunta antes del
despliegue real):**

- **Autenticación del Postgres de casos:** `infra/postgres-cases.bicep` propone AAD-only
  (`passwordAuth: Disabled`) por defecto, lo que requeriría autenticar JDBC con un token de
  Microsoft Entra ID en vez de usuario/contraseña — típicamente vía el starter
  `spring-cloud-azure-starter-jdbc-postgresql`, que HOY NO está en el `pom.xml`. El código
  de esta sesión (`spring.datasource.username`/`spring.datasource.password`, ver
  `application.properties`) asume el modelo tradicional de usuario/contraseña, consistente
  con el fallback `enablePasswordAuth=true` que el propio Bicep de el equipo de DevOps/Infra ya
  contempla para este escenario exacto. **Decisión pendiente:** si el equipo confirma
  AAD-only para producción, hace falta una sesión de trabajo dedicada a agregar el starter
  de autenticación AAD-JDBC y adaptar `application.properties` — no se hizo aquí para no
  invertir el tiempo de esta sesión en una integración que podría descartarse.
- **Secreto único `postgres-connection-string` vs. propiedades discretas:**
  `infra/key-vault.bicep` modela un único secreto con el connection string completo; el
  código de esta sesión sigue leyendo `spring.datasource.url`/`username`/`password` como
  variables de entorno discretas (mismo patrón que el resto del proyecto), no como un
  connection string único parseado desde Key Vault. Reconciliar esto es parte del mismo
  trabajo pendiente del punto anterior — no bloquea el arranque local (los placeholders
  vacíos actuales apuntan al Postgres de Docker Compose).
