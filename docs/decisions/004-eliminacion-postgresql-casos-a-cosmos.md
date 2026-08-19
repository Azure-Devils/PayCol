# ADR 004: Eliminación completa de PostgreSQL — el almacén de casos de fraude migra a Cosmos DB

**Fecha:** 2026-07-29
**Estado:** Aceptada
**Autoridad:** Decisión del líder del proyecto, tomada el 2026-07-29 y confirmada directamente por el usuario (dueño del repo, SixOrca).

## Contexto

Al desplegar el backend real a Azure App Service (`app-centinela-api`, `rg-centinela`) para
validar una historia de usuario de infraestructura, la aplicación no arrancaba: fallaba con
`503` porque, al no existir `CASESTORE_DB_URL` en el entorno del App Service, el datasource caía
al valor por defecto `jdbc:postgresql://localhost:5433/centinela_casos` y, como
`spring.flyway.enabled=true` fuerza una conexión real al arrancar, el contenedor moría por
timeout de conexión (~87s) antes de levantar Tomcat.

La causa raíz era que el Postgres real del almacén de casos (`infra/postgres-cases.bicep`,
diseñado en el ADR 003) **nunca llegó a desplegarse** en Azure. El ADR 003 ya había señalado un
costo real no trivial para este recurso: sin el beneficio de 12 meses gratis de la suscripción,
el cómputo B1ms de Postgres Flexible Server ronda **~12-13 USD/mes** — muy por encima de
cualquier otro recurso del proyecto, todos pensados para caer dentro de niveles gratuitos.

Ante esto, el líder del proyecto decidió, en vez de aprovisionar ese Postgres real:

- Eliminar PostgreSQL del proyecto **por completo** (no solo el almacén de casos, sino también
  el perfil `postgres-legacy` de transacciones que el ADR 001 había dejado como referencia/
  rollback — ya no tiene sentido conservarlo si se está retirando Postgres del proyecto entero).
- Modelar también los casos de fraude en Azure Cosmos DB, igual que `transactions` y
  `customers`, consolidando toda la persistencia en el único motor que el proyecto usa
  activamente y que además ya está cubierto por el free tier permanente (1000 RU/s + 25 GB,
  ver ADR 001).

## Decisión

Se elimina PostgreSQL del proyecto en su totalidad:

- El datasource/JPA/Flyway del almacén de casos (`spring.datasource.*`, `spring.jpa.*`,
  `spring.flyway.*` en `application.properties`; `CaseStoreJpaConfig`; las entidades JPA
  `CasoEntity`/`EstadoEntity`/`AsignacionEntity`/`ResolucionEntity`/`AuditoriaCasoEntity` y sus
  `JpaRepository`; `JpaFraudCaseRepositoryAdapter`; la migración
  `db/casestore/migration/V1__init_casos.sql`).
- El perfil `postgres-legacy` de transacciones (pre-Cosmos, ver ADR 001):
  `PostgresTransactionRepositoryAdapter`, `TransactionEntity`, `CustomerEntity`,
  `LocationEntity` y sus `JpaRepository`, y la migración `db/migration/V1__init.sql`.
- Las dependencias de Maven `spring-boot-starter-data-jpa`, `org.postgresql:postgresql`,
  `flyway-core` y `flyway-database-postgresql`.
- Los servicios `postgres` y `postgres-casos` de `docker-compose.yml`.

Los casos de fraude se persisten ahora en un **container nuevo de Cosmos DB, `cases`**, en la
misma cuenta/base de datos (`centinela`) que ya usan `transactions` y `customers`. Implementado
siguiendo exactamente el mismo patrón hexagonal que esos dos containers:

- `CosmosFraudCaseDocument` (documento) — `infrastructure.adapter.outbound.casestore`.
- `CosmosFraudCaseRepository` (interfaz `CosmosRepository<CosmosFraudCaseDocument, String>`).
- `CosmosFraudCaseRepositoryAdapter` (implementa `FraudCaseRepositoryPort`, sin cambios al
  dominio ni al puerto — solo se reemplazó el adaptador de infraestructura).

### Partition key: `transactionId` (no `customerId`)

`CosmosTransactionDocument` particiona por `customerId` porque su consulta dominante es "todas
las transacciones recientes de una cuenta". El almacén de casos tiene un patrón de acceso
distinto: los dos únicos métodos de `FraudCaseRepositoryPort`
(`existsByTransactionId(transactionId)`, `openCase(FraudCaseEvent)`) operan siempre sobre
`transactionId` y **nunca reciben `customerId` como parámetro de búsqueda**. El propio esquema
relacional legado ya modelaba `transaction_id` como `UNIQUE` en la tabla `casos` (un caso por
transacción).

Por eso se eligió `transactionId` como partition key **y** como `@Id` del documento (mismo
campo para ambos, igual patrón que ya usa `CosmosCustomerDocument` con `customerId`). Esto
convierte el chequeo de idempotencia (¿ya existe un caso para esta transacción?, necesario
porque el consumidor de `fraud-cases` puede recibir el mismo mensaje más de una vez) y la
apertura del caso en **point-reads/point-writes** — la operación más barata posible en RU, sin
recorrido cross-partition. Si se hubiera elegido `customerId` en su lugar,
`existsByTransactionId` no tendría forma de saber en qué partición buscar (el puerto no recibe
`customerId`) y degeneraría en una consulta cross-partition en **cada** evaluación de
idempotencia — justo el patrón de costo que el proyecto ya evita conscientemente en otros
lugares (ver el cross-partition documentado y aceptado en `CosmosTransactionRepository#findById`,
que sí lo acepta porque es una operación puntual, no una que se ejecuta en cada mensaje de una
cola de alto volumen).

**Trade-off aceptado a futuro:** si una semana posterior agrega un caso de uso como "listar
todos los casos abiertos de un cliente", esa consulta sería cross-partition con este diseño. Se
acepta explícitamente porque ningún puerto de dominio necesita esa consulta hoy.

**Sin TTL:** a diferencia de `transactions` (TTL de 90 días, ver ADR 003), el container `cases`
no tiene expiración automática — los casos de fraude son registros de gestión que deben
persistir mientras el caso exista, sin importar su antigüedad.

### Alcance deliberadamente reducido frente al modelo relacional que se elimina

El TDD de Semana 2 pedía un modelo con "Caso, Estado, Asignación, Resolución, Auditoría". El
esquema relacional que se elimina llegó a modelar las cinco entidades, pero las tablas
`asignaciones` y `resoluciones` nunca tuvieron un puerto de dominio ni un caso de uso que las
poblara — sus propios comentarios las marcaban como "alcance de una semana posterior". Migrar
esas dos entidades a Cosmos ahora, sin ningún consumidor real, violaría el mismo principio de
"no modelar para un puerto que no existe" que ya sigue el resto del proyecto. Quedan diferidas
hasta que exista el caso de uso correspondiente (probablemente como containers propios o campos
embebidos nuevos, a decidir en ese momento según el patrón de consulta real que se necesite).

Lo que sí se conserva es el concepto de **auditoría inmutable** de cambios de estado: en vez de
una tabla aparte con FK (imposible en Cosmos sin joins), se embebe como un arreglo de
solo-append (`auditTrail`) dentro del propio documento del caso — mismo patrón de
denormalización que ya usa `CosmosTransactionDocument` para embeber `location` dentro de la
transacción. El primer (y hoy único) evento de auditoría que escribe el adaptador es la
transición `null -> ABIERTO` al abrir el caso, igual que hacía el adaptador Postgres eliminado.

## Consecuencias

- **Se pierde SQL relacional para reportería de casos.** Cualquier reporte futuro que necesite
  JOINs o agregaciones ad-hoc sobre casos/asignaciones/resoluciones tendría que resolverse con
  consultas Cosmos SQL (más limitadas que SQL relacional, sin joins entre containers) o
  exportando a un motor analítico aparte si el volumen/complejidad lo justificara.
- **Sin integridad referencial nativa.** Si en el futuro se agregan "Asignación"/"Resolución",
  no habrá FKs reales entre ellas y el caso — la consistencia tendría que garantizarse a nivel
  de aplicación, igual que ya se documentó para `transactions`/`customers` en el ADR 001.
- **El error original queda resuelto de raíz, no parcheado:** al no depender más de ningún
  `DataSource`/Flyway al arrancar, la aplicación ya no puede fallar por un Postgres inexistente
  o mal configurado — el escenario completo (`CASESTORE_DB_URL` ausente → timeout de conexión →
  503) deja de ser posible porque el código que lo causaba se eliminó, no se le agregó un
  valor por defecto más tolerante.
- **`infra/postgres-cases.bicep` queda obsoleto.** El equipo de DevOps/Infra debe retirar el
  módulo `postgresCases` de `infra/main.bicep` (hoy todavía referenciado ahí, junto con sus
  outputs `postgresServerName`/`postgresFqdn`/`vnetName`) y el secreto
  `postgres-connection-string` planeado en `infra/key-vault.bicep`, ya que nunca se llegó a
  desplegar contra Azure real — no hay ningún recurso vivo que dar de baja, solo definiciones
  Bicep que dejarán de tener sentido.
- **Nuevo recurso a coordinar con DevOps/Infra:** agregar el container `cases` a
  `infra/cosmos-db.bicep` (mismo patrón que `transactions`/`customers`): partition key
  `/transactionId`, sin `defaultTtl`, sin throughput propio (comparte el pool de 1000 RU/s de
  la base de datos `centinela`).
- No se requiere ninguna variable de entorno nueva: el container `cases` vive en la misma
  cuenta/base de datos que ya configuran `AZURE_COSMOS_ENDPOINT`/`AZURE_COSMOS_KEY`/
  `AZURE_COSMOS_DATABASE`.

## Referencias

- `docs/decisions/001-migracion-postgresql-a-cosmosdb.md` — mismo patrón de decisión (revertir
  Postgres a favor de Cosmos), aplicado ahora al resto del proyecto.
- `docs/decisions/003-ttl-transacciones-y-respaldo-postgres-casos.md` — diseño de aislamiento de
  red y respaldo del Postgres de casos que este ADR deja sin efecto, y donde ya se había
  señalado el costo real de ~12-13 USD/mes como riesgo pendiente de confirmar.
- `infra/postgres-cases.bicep`, `infra/main.bicep` (módulo `postgresCases`) — código de
  infraestructura que queda obsoleto, a retirar por el equipo de DevOps/Infra.
