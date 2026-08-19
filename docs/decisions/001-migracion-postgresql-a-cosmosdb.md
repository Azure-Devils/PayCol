# ADR 001: Migración de persistencia de PostgreSQL a Azure Cosmos DB

**Fecha:** 2026-07-22
**Estado:** Aceptada
**Autoridad:** Decisión del líder del proyecto, confirmada directamente por el dueño del repo (SixOrca).

## Contexto

Centinela adoptó originalmente PostgreSQL (Azure Database for PostgreSQL Flexible Server) como
único motor de persistencia, descartando explícitamente Cosmos DB por costo y complejidad de
partition keys.

El líder del proyecto exigió revertir esa decisión y migrar la persistencia a Azure Cosmos DB.
El usuario confirmó explícitamente esta dirección.

Antes de proceder, se evaluó el riesgo de costo dado que el proyecto opera bajo el trial estándar
de Azure (200 USD de crédito, ventana de 30 días, sin Azure for Students) y no puede absorber
cargos inesperados.

## Decisión

Se adopta **Azure Cosmos DB for NoSQL (Core/SQL API)** como nuevo motor de persistencia, bajo
las siguientes condiciones no negociables:

- **Free tier permanente** de Cosmos DB (1000 RU/s + 25 GB de storage, gratis de por vida, una
  sola cuenta por suscripción) — es un beneficio independiente del crédito de 200 USD del trial.
- **Throughput manual fijo en 1000 RU/s, nunca autoscale.** Autoscale permite que Cosmos escale
  por encima del free tier automáticamente y genere cargo real; con throughput manual, exceder
  la capacidad devuelve `429 Too Many Requests` en vez de facturar de más.
- **Una sola región**, para no dividir/multiplicar el consumo del pool gratuito.
- **Alerta de presupuesto** (Azure Budget a nivel de resource group, ~1 USD/mes) configurada en el
  mismo momento en que se cree el primer recurso Cosmos.
- Verificar antes de crear la cuenta que no exista ya otra cuenta Cosmos con free tier activo en
  la suscripción (`az cosmosdb list --query "[].{name:name, freeTier:enableFreeTier}"`), ya que
  el free tier es único por suscripción.
- **Nunca usar "Azure Cosmos DB for PostgreSQL" (ex Hyperscale/Citus)** — comparte nombre pero es
  un producto de precios distinto, sin free tier, facturado desde el minuto uno.

Modelo de documentos propuesto (a validar con el código real por el equipo de backend):
- Contenedor `transactions`, partition key `/customerId` — mantiene la consulta de velocidad
  `(customerId, timestamp)` dentro de una sola partición.
- Contenedor `customers`, partition key `/customerId` (o `/id`).
- `locations` se embebe dentro del documento de `transaction` en vez de mantenerse como entidad
  separada con FK — Cosmos no soporta joins nativos.
- La restricción `UNIQUE (latitude, longitude)` de `locations` no tiene equivalente directo en
  Cosmos (unique keys solo aplican dentro de una misma partición) — requiere resolución a nivel
  de aplicación si sigue siendo necesaria.

El código y esquema de PostgreSQL existentes se conservan en el repo, aislados/deshabilitados,
para referencia y posible rollback.

## Consecuencias

- El adapter de salida (`PostgresTransactionRepositoryAdapter`) se reemplaza por un nuevo adapter
  Cosmos que implementa el mismo puerto de dominio (`TransactionRepositoryPort`) — el dominio no
  cambia, solo la infraestructura, gracias a la arquitectura hexagonal.
- Flyway deja de aplicar (Cosmos no usa migraciones SQL); se retira o anota como no aplicable.
- Queda pendiente que el equipo de DevOps/Infra confirme el SKU/RU exacto de aprovisionamiento antes de crear
  cualquier recurso real en Azure — este documento no autoriza aprovisionamiento, solo el cambio
  de rumbo arquitectónico y de código.

## Referencias

- Evaluación de costo/viabilidad realizada por el equipo de DevOps/Infra (2026-07-22): confirma
  free tier vigente, recomienda API NoSQL/Core, throughput manual, alertas de presupuesto.

## Implementación (2026-07-22)

El modelo de documentos propuesto arriba se implementó tal cual, con algunos detalles que
solo aparecen al escribir el código real:

- **Dependencia añadida** (`pom.xml`): `com.azure.spring:spring-cloud-azure-starter-data-cosmos`,
  con versiones fijadas vía el BOM `com.azure.spring:spring-cloud-azure-dependencies:5.20.1`.
  Se mantienen `spring-boot-starter-data-jpa`, `postgresql` y `flyway-*` en el classpath solo
  para que el adapter de Postgres aislado siga compilando — no se usan en tiempo de ejecución.
- **Documentos** (`CosmosTransactionDocument`, `CosmosCustomerDocument`): equivalentes a las
  entidades JPA, pero para Cosmos. `CosmosTransactionDocument` usa `customerId` como partition
  key (`@PartitionKey`) para que las futuras reglas de velocidad (`(customerId, timestamp)`)
  caigan dentro de una sola partición, igual que hacía el índice `idx_tx_customer_time` en
  Postgres. La ubicación se embebe como `LocationEmbedded` dentro del documento (denormalizado,
  sin container propio) — por eso `Location.locationId()` queda siempre en `null` al leer desde
  Cosmos, ya que dejó de ser una entidad con id propio.
- **Repositorios** (`CosmosTransactionRepository`, `CosmosCustomerRepository`): interfaces
  `CosmosRepository<T, String>`, equivalentes a `JpaRepository`. Habilitados vía
  `@EnableCosmosRepositories` en `CosmosRepositoryConfig`.
- **Adapter** (`CosmosTransactionRepositoryAdapter`): reemplaza a `PostgresTransactionRepositoryAdapter`
  como implementación activa de `TransactionRepositoryPort` — el dominio no se tocó. Diferencia
  de comportamiento a tener presente: Cosmos no tiene transacciones multi-documento entre
  containers distintos, así que guardar `customer` y `transaction` ya no es atómico como lo era
  dentro de la `@Transactional` de Postgres (se documenta el trade-off en el código).
- **`findById(transactionId)` sin `customerId`**: como el puerto de dominio no recibe el
  customerId al buscar por id, esa búsqueda es cross-partition (recorre todas las particiones),
  más cara en RU que un point-read dirigido. Aceptable para el volumen de la Semana 1; documentado
  en `CosmosTransactionRepository` como limitación conocida, no como bug.
- **Adapter de Postgres aislado**: `PostgresTransactionRepositoryAdapter` quedó marcado
  `@Profile("postgres-legacy")` (perfil inactivo por defecto) en vez de borrarse, para permitir
  rollback. `application.properties` excluye explícitamente la autoconfiguración de
  JPA/DataSource/Flyway (`spring.autoconfigure.exclude=...`) porque, aunque el adapter esté
  inactivo, esas librerías siguen en el classpath y Spring Boot igual intentaría conectarse a
  Postgres al arrancar si no se excluyen — es la causa exacta del error original
  (`Connection to localhost:5432 refused`) que motivó revisar este tema.
- **Configuración de Cosmos** (`application.properties`): `spring.cloud.azure.cosmos.endpoint`
  y `.key` se leen de variables de entorno (`AZURE_COSMOS_ENDPOINT`, `AZURE_COSMOS_KEY`), nunca
  hardcodeadas. En Azure real se debe preferir autenticación Azure AD (Managed Identity /
  `DefaultAzureCredential`) dejando `.key` vacío, igual que ya se hace con Azure Queue/Blob en
  este proyecto — pendiente de habilitar cuando el equipo de DevOps/Infra provisione la cuenta real.
- Verificado con `mvn clean compile`: build exitoso, sin necesidad de una cuenta Cosmos real
  (no se ejecutó la aplicación contra un endpoint vivo en esta sesión).

**Pendiente (actualizado 2026-07-22 por el equipo de DevOps/Infra):** el SKU/RU ya está confirmado y el
Bicep está listo en `infra/` (`infra/main.bicep`, `infra/cosmos-db.bicep`,
`infra/budget-alert.bicep`, `infra/main.parameters.json`, con guía completa en
`infra/README.md`): cuenta Cosmos DB API NoSQL/Core con free tier habilitado, una sola región,
consistencia Session, base de datos `centinela` con throughput manual compartido en 1000 RU/s
(nunca autoscale), containers `transactions` y `customers` con partition key `/customerId`
(verificados contra `CosmosTransactionDocument.java`/`CosmosCustomerDocument.java`), y un Azure
Budget de ~1 USD/mes con alertas al 80%/100% en el mismo deployment.

Este Bicep **todavía no se ha ejecutado contra Azure real**. Falta, en este orden:

1. Instalar `az` CLI en la máquina (no está instalado hoy) y correr `az login`.
2. Confirmar con el usuario la suscripción/tenant correctos (`az account show`) — no asumir
   si hay más de una suscripción disponible (p. ej. si más adelante se activa Azure for
   Students / GitHub Student Pack, sería una suscripción o crédito distinto al trial actual).
3. Verificar que no exista ya otra cuenta Cosmos con free tier activo en la suscripción
   (`az cosmosdb list --query "[].{name:name, freeTier:enableFreeTier}"`), dado que el free
   tier es único por suscripción.
4. Confirmar/crear el resource group destino.
5. **Aprobación explícita del usuario** para correr
   `az deployment group create --resource-group <rg> --template-file infra/main.bicep
   --parameters infra/main.parameters.json` — sin esa aprobación, el equipo de DevOps/Infra no ejecuta
   el deployment aunque el recurso sea gratuito (free tier).

Una vez creada la cuenta real, completar `AZURE_COSMOS_ENDPOINT`/`AZURE_COSMOS_KEY` (como
secretos, nunca en el repo) y correr la app contra Cosmos real. Queda además pendiente, como
mejora posterior (no bloqueante), migrar la autenticación de Cosmos de clave estática a
Managed Identity (`DefaultAzureCredential`), igual que ya se hace con Azure Queue/Blob en este
proyecto — a coordinar con el equipo de backend.
