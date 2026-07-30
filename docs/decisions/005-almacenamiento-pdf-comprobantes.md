# ADR 005: Almacenamiento de PDFs de comprobante — reutilización de la cuenta de Storage existente

**Fecha:** 2026-07-30
**Estado:** Aceptada
**Autoridad:** Tarea asignada directamente por el dueño del repo (SixOrca) para la rama
`feature/transaction-pdf-receipt`.

## Contexto

La nueva feature de "comprobante de transacción" (branch `feature/transaction-pdf-receipt`)
requiere que, al ingerir una transacción, el backend genere un PDF y lo suba a Azure Blob
Storage vía `DefaultAzureCredential`/Managed Identity (código a cargo del equipo de backend,
fuera del alcance de este ADR).

Al inventariar `rg-centinela` (`az resource list --resource-group rg-centinela`) se confirmó que
ya existe una cuenta de Storage de propósito general en el resource group,
**`stcentineladev010`** (`StorageV2`, `chilecentral`, creada 2026-07-27), que pertenece a **otro
microservicio del equipo**, `app-centinela-core` (una Azure Function App en .NET 6, deployada por
separado, sin relación con este repo). Esa cuenta ya tenía, antes de este ADR:

- Un container Blob, `identify-documents`.
- Una cola, `transaction-ingest`.

Ninguno de los dos se tocó, renombró ni se leyó su contenido — se confirmó su existencia
únicamente para evitar colisión de nombres (`az storage container list` / `az storage queue list`
contra la cuenta).

`infra/storage-queue.bicep` (Semana 2, colas `transaction-events`/`fraud-cases`) ya había
definido una cuenta de Storage **propia** del proyecto para mensajería, pero a la fecha de este
ADR **no se ha desplegado todavía contra Azure real** (no aparece en `az resource list`; el App
Service `app-centinela-api` no tiene configurado `AZURE_STORAGE_QUEUE_ENDPOINT`). Es decir, este
proyecto (`backend-java-spring` / este repo) no tenía, hasta ahora, ninguna cuenta de Storage
propia y viva en Azure.

## Decisión

### (a) Reutilizar `stcentineladev010` en vez de provisionar una cuenta nueva

Se decide **no crear una tercera cuenta de Storage** solo para los PDFs de comprobante. Blob
Storage Hot tier de bajo volumen (algunos PDFs pequeños por transacción, sin alto tráfico de
lectura) cuesta fracciones de centavo al mes — el mismo orden de magnitud que ya se documentó
para las Storage Queues en `infra/storage-queue.bicep` (~0.0036 USD/10,000 operaciones, más
storage base despreciable). Ese costo no justifica una segunda cuenta de Storage cuando ya existe
una cuenta general-purpose v2 activa, en la misma región (`chilecentral`) y el mismo resource
group (`rg-centinela`), con capacidad de sobra para un container adicional.

Provisionar una cuenta nueva sumaría un recurso más que gestionar/vigilar/limpiar (otro nombre
único global, otro storage account en el Cost Management, otro punto donde configurar RBAC) sin
ningún beneficio real de aislamiento para este volumen — el mismo principio de "menos recursos,
mismo resultado" que ya aplicó el proyecto al descartar Postgres (ADR 004).

**Importante — límite de esta decisión:** `stcentineladev010` es propiedad de
`app-centinela-core`, no de este repo. No hay ningún `.bicep` en este repo que la administre como
IaC propia (a diferencia de `infra/cosmos-db.bicep` o `infra/storage-queue.bicep`) — se documenta
aquí como **recurso externo reutilizado por acuerdo explícito**, no como infraestructura nueva de
`backend-java-spring`. Si en el futuro `app-centinela-core` necesita rotar, migrar o eliminar esa
cuenta, hay que coordinar con ese equipo antes, porque ahora también depende de ella este backend.

### (b) Nombre del container: `transaction-receipts`

Se eligió `transaction-receipts` (no `receipts`, `pdfs` ni similar genérico) explícitamente para:

- No colisionar con el container ya existente `identify-documents` (verificado antes de crear:
  `az storage container list --account-name stcentineladev010` solo devolvía
  `identify-documents`).
- Dejar claro, con el nombre, a qué dominio/servicio pertenece cada container dentro de la cuenta
  compartida — mismo criterio de nomenclatura explícita que ya usan las colas
  `transaction-events`/`fraud-cases` en `infra/storage-queue.bicep`, para que cualquiera que liste
  la cuenta entienda de un vistazo qué container es de quién sin tener que preguntar.

Container creado sin acceso público (`--public-access off`) — los PDFs de comprobante son datos
sensibles del cliente, se leen siempre autenticados vía Managed Identity, nunca por URL anónima.

### (c) Alcance del RBAC otorgado

Rol: **Storage Blob Data Contributor** (lectura + escritura + borrado de blobs; no incluye
gestión del storage account en sí, no incluye acceso a Queue/Table/File del mismo recurso).

Principal: la Managed Identity **system-assigned** de `app-centinela-api`
(`principalId 61274ca8-0463-48cf-92e0-38b8887c13ca`), la misma identidad que ya tiene asignado
`Cosmos DB Built-in Data Contributor` desde una sesión anterior — mismo patrón de "una identidad,
un rol por servicio de datos", sin credenciales estáticas ni connection strings en ningún momento.

Scope intentado: **el container `transaction-receipts`** (no la cuenta completa), para no
otorgarle a `app-centinela-api` acceso de lectura/escritura sobre `identify-documents` (container
de otro microservicio, con datos que no le corresponden). Azure permite asignar roles de datos de
Blob Storage a nivel de container individual (no solo a nivel de cuenta).

**Bloqueo encontrado al ejecutar (no un tema de diseño, sino de permisos de la cuenta usada por
este agente):** la identidad `az` actual (`sixorca00@gmail.com`) solo tiene el rol `Contributor`
sobre `rg-centinela` — ese rol explícitamente **no incluye**
`Microsoft.Authorization/roleAssignments/write` (crear asignaciones de RBAC está reservado a
`Owner`/`User Access Administrator` por diseño de Azure, para que un `Contributor` no pueda
auto-otorgarse ni otorgarle a terceros permisos más amplios). El intento de
`az role assignment create` con scope al container falló con `AuthorizationFailed`. Este es el
mismo patrón ya visto en `infra/deploy-log.md` punto 8 (registro del resource provider
`Microsoft.DocumentDB`, que también requirió que lo ejecutara Estiwar, dueño/admin de la
suscripción).

**Estado real al cierre de este ADR:** el container y los app settings quedaron creados; la
asignación de rol RBAC quedó **pendiente de ejecución por Estiwar** (o por quien tenga
`Owner`/`User Access Administrator` en la suscripción). El comando exacto a correr está
documentado en `infra/deploy-log.md`, sección correspondiente a esta feature. Hasta que ese
comando se ejecute, el código de backend que suba PDFs vía `DefaultAzureCredential` recibirá
`403 Forbidden` al intentar escribir en `transaction-receipts` — comportamiento esperado y seguro
(falla cerrado, no hay fallback a account key).

## App settings agregados a `app-centinela-api`

Siguiendo el mismo patrón de nombres que `AZURE_COSMOS_ENDPOINT`/`AZURE_STORAGE_QUEUE_ENDPOINT`
(endpoint de servicio vía Managed Identity, nunca key/connection string):

- `AZURE_STORAGE_BLOB_ENDPOINT` = `https://stcentineladev010.blob.core.windows.net/`
- `AZURE_STORAGE_RECEIPTS_CONTAINER_NAME` = `transaction-receipts`

Nombres propuestos por el equipo de infra porque, al momento de aprovisionar, el código del
adaptador de blobs del backend todavía no existía en la rama (`git diff main --stat` no mostraba
ningún archivo de blob). Coordinar con el equipo de backend si prefieren otro nombre de propiedad
en `application.properties` — son solo app settings de Azure, renombrarlos no tiene costo ni
efecto en el recurso real, solo hay que actualizar el `${...}` correspondiente en
`application.properties` y volver a correr `az webapp config appsettings set` con el nombre
nuevo.

## Consecuencias

- No se sumó ningún recurso de costo nuevo — se reutilizó infraestructura existente. El
  presupuesto acumulado del proyecto no cambia por este ADR (container + app settings + intento
  de RBAC no tienen costo propio).
- `app-centinela-api` queda con una dependencia cruzada hacia un recurso administrado por
  `app-centinela-core`. Si ese equipo decide mover, renombrar o borrar `stcentineladev010`, hay
  que avisar a este proyecto antes — el vínculo no es visible en ningún `.bicep` de este repo, así
  que solo queda documentado aquí y en `infra/deploy-log.md`.
- Queda un pendiente operativo real y bloqueante para que la feature funcione en Azure:
  ejecutar la asignación RBAC como Estiwar (u otro `Owner`/`User Access Administrator`). Sin eso,
  la subida de PDFs fallará con `403` en producción aunque el código esté perfecto.
- Si en el futuro `app-centinela-core` deja de existir o cambia de cuenta de Storage, este ADR y
  los settings de arriba quedarían obsoletos — revisar este documento en ese momento.

## Referencias

- `docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md` — mismo principio de "reusar/
  consolidar en vez de sumar otro recurso de costo" aplicado antes en el proyecto.
- `infra/storage-queue.bicep` — cuenta de Storage propia del proyecto (colas), pendiente de
  despliegue real; no es la misma cuenta que este ADR reutiliza para blobs.
- `infra/deploy-log.md` — comandos `az` reales ejecutados para esta feature, incluido el comando
  de RBAC pendiente de que lo corra Estiwar.
