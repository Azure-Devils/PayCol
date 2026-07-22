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
