# infra/ — Aprovisionamiento de Azure para Centinela

Este directorio contiene el Bicep para crear los recursos reales de Azure de Centinela:
Cosmos DB (Semana 1, incluye el container `cases` de Semana 2 — ver ADR 004), y Key Vault
y Storage Queues para el resto de la Semana 2. Ver el contexto completo de las decisiones en
[`docs/decisions/001-migracion-postgresql-a-cosmosdb.md`](../docs/decisions/001-migracion-postgresql-a-cosmosdb.md),
[`docs/decisions/002-ttl-transacciones-y-respaldo-postgres-casos.md`](../docs/decisions/002-ttl-transacciones-y-respaldo-postgres-casos.md)
y [`docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md`](../docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md).

**Nada de esto se ha ejecutado todavía.** Ningún comando de esta guía debe
correrse sin que el dueño del repo lo confirme explícitamente primero, aunque
el recurso sea gratis (free tier) o el costo parezca insignificante. Esa es la
regla de oro de este proyecto.

## Qué se va a crear (y qué NO)

| Recurso | SKU / configuración | Costo |
|---|---|---|
| Cuenta Cosmos DB (`Microsoft.DocumentDB/databaseAccounts`) | API NoSQL/Core, `enableFreeTier: true`, 1 sola región, consistencia `Session` | **0 USD** — free tier permanente de Azure (1000 RU/s + 25GB) |
| Base de datos `centinela` | Throughput **manual** compartido, **1000 RU/s** (nunca autoscale) | **0 USD** dentro del límite del free tier |
| Container `transactions` | Partition key `/customerId`, **`defaultTtl` = 90 días** (Semana 2 — ver ADR 002) | incluido arriba |
| Container `customers` | Partition key `/customerId`, sin TTL (entidad de referencia) | incluido arriba |
| Container `cases` | Partition key `/transactionId`, sin TTL — almacén de casos de fraude, reemplaza al Postgres original (ver ADR 004) | incluido arriba |
| Azure Budget (`Microsoft.Consumption/budgets`) | Scope: resource group, monto **1 USD/mes**, alertas al 80% y 100% | El budget en sí es gratis; solo notifica sobre gasto real |
| **Key Vault** (`Microsoft.KeyVault/vaults`) — nuevo | Tier `standard`, autorización por RBAC, sin secretos con valores reales | Costo por operación, despreciable (fracción de centavo/mes) para este volumen |
| RBAC Key Vault (`Microsoft.Authorization/roleAssignments`) | "Key Vault Secrets User" a la Managed Identity de la app, "Key Vault Administrator" al usuario humano | **0 USD** — las asignaciones de rol no tienen costo propio |
| **Storage Account + 2 Storage Queues** (`Microsoft.Storage/storageAccounts`) — nuevo | `Standard_LRS`, colas `transaction-events` y `fraud-cases` | ~0.0036 USD/10,000 operaciones — prácticamente cero al volumen de un proyecto académico. Sin free tier formal, pero costo despreciable. |
| RBAC Storage Queue | "Storage Queue Data Contributor" a la Managed Identity de la app | **0 USD** |

**No se crea** ningún recurso de cómputo (App Service, Container Apps, Static
Web Apps) — eso es responsabilidad de los Bicep/scripts que agregue
el equipo de DevOps/Infra cuando el backend/frontend estén listos para desplegarse, cada
uno con su propia confirmación previa. Tampoco se crean valores reales de
secretos en Key Vault (ver sección "Secretos" más abajo).

**Ya NO se crea** ningún Postgres Flexible Server, VNet ni Private DNS Zone: el
almacén de casos de fraude que iba a vivir en Postgres se eliminó del proyecto
(decisión del equipo, 2026-07-29) y se migró al container `cases` de Cosmos DB
de arriba — ver ADR 004 para el detalle completo. El módulo
`infra/postgres-cases.bicep` que existía para esto se borró del repo (nunca
llegó a desplegarse contra Azure real).

## Costo — puntos que requieren confirmación explícita antes de desplegar

Todos los recursos de este directorio son gratis o de costo despreciable
(fracciones de centavo/mes): free tier permanente de Cosmos DB, Key Vault
por operación, y Storage Queues por operación. No hay ningún recurso con
costo mensual no trivial en este directorio (el único que lo tenía, el
Postgres Flexible Server, se eliminó — ver ADR 004). Aun así, el budget de
Cost Management (`infra/budget-alert.bicep`) se mantiene como canario — ver
la sección siguiente.

## Presupuesto — canario, no techo de gasto

El budget de Cost Management se mantiene en **1 USD/mes**. No es un "techo de
gasto" — es un **canario**: existe para que la PRIMERA alerta llegue lo antes
posible ante cualquier cargo real (algo que no debería pasar en este
directorio, dado que todo es free-tier o de costo despreciable), no para
representar cuánto se espera gastar.

Recomendación operativa (no un cambio de Bicep): revisar Cost Management
manualmente de vez en cuando de todas formas, por si algún recurso queda mal
configurado (p. ej. throughput autoscale en Cosmos por error).

## Pre-requisitos (todo pendiente ahora mismo)

1. **Instalar Azure CLI** — no está instalado en esta máquina:
   ```bash
   curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
   az version
   ```
2. **Iniciar sesión**:
   ```bash
   az login
   ```
3. **Confirmar la suscripción correcta** (puede haber más de una si el usuario
   también activó Azure for Students / GitHub Student Pack en algún momento —
   preguntar antes de asumir cuál usar):
   ```bash
   az account list -o table
   az account show -o table
   # si hace falta cambiar de suscripción activa:
   az account set --subscription "<nombre-o-id-de-la-suscripción>"
   ```
4. **Verificar que no exista ya otra cuenta Cosmos con free tier en la
   suscripción** (el free tier es único por suscripción):
   ```bash
   az cosmosdb list --query "[].{name:name, freeTier:enableFreeTier}" -o table
   ```
5. **Obtener el Object ID del usuario humano (opcional pero recomendado)**,
   para poder gestionar Key Vault manualmente después del deploy:
   ```bash
   az ad signed-in-user show --query id -o tsv
   ```
6. **Confirmar (o crear) el resource group destino.** Este Bicep se despliega
   con scope de resource group (`az deployment group create`), así que el
   grupo debe existir antes:
   ```bash
   az group create \
     --name rg-centinela \
     --location eastus2 \
     --tags project=centinela
   ```
7. **Registrar el resource provider `Microsoft.DocumentDB`** si es la primera
   vez que se usa Cosmos DB en esta suscripción (acción a nivel de
   suscripción completa, la tiene que correr quien sea dueño/administrador
   de la suscripción):
   ```bash
   az provider register --namespace Microsoft.DocumentDB
   az provider show --namespace Microsoft.DocumentDB --query registrationState -o tsv
   ```

## Orden de deploy recomendado

Todos los módulos están orquestados desde `infra/main.bicep`, así que **un solo
comando `az deployment group create` los crea todos en el orden correcto**
(Bicep resuelve las dependencias automáticamente). Aun así, si se prefiere
desplegar por partes (por ejemplo, para revisar costos incrementalmente), el
orden lógico es:

1. `cosmos-db.bicep` + `budget-alert.bicep` (ya validado desde la Semana 1;
   incluye ahora el container `cases` de Semana 2, ver ADR 004).
2. `key-vault.bicep` (sin secretos aún — estructura únicamente).
3. `storage-queue.bicep` (costo despreciable).

**IMPORTANTE:** mientras `appManagedIdentityPrincipalId` y `humanAdminObjectId`
no tengan un valor real (porque el recurso de cómputo del backend todavía no
existe en este repo), Key Vault/Storage Queue se crean SIN las asignaciones de
rol RBAC. Esto es intencional (permite crear el "esqueleto" de infraestructura
antes de que exista el backend), pero hay que recordar completar ese segundo
paso una vez exista la Managed Identity real (ver `infra/grant-cosmos-rbac.sh`
para el equivalente en Cosmos DB, que usa su propio sistema de RBAC de datos
en vez de `Microsoft.Authorization/roleAssignments`).

## Comando de despliegue (NO ejecutar sin aprobación explícita)

```bash
az deployment group create \
  --resource-group rg-centinela \
  --name centinela-semana2-$(date +%Y%m%d%H%M%S) \
  --template-file infra/main.bicep \
  --parameters infra/main.parameters.json
```

Antes de correrlo, revisar/editar `infra/main.parameters.json`:
- `location` (por defecto `eastus2` — el resource group real `rg-centinela`
  vive en `chilecentral`, confirmar cuál usar antes de desplegar),
- `budgetContactEmail` / `budgetAmountUsd` (por defecto `sixorca00@gmail.com` / `1`),
- `transactionsDefaultTtlSeconds` (por defecto 90 días — ver ADR 002 si se quiere ajustar),
- `appManagedIdentityPrincipalId` / `appManagedIdentityDisplayName` — llenar
  cuando exista la Managed Identity del backend,
- `humanAdminObjectId` — llenar con la cuenta AAD del dueño del repo si se
  quiere poder gestionar Key Vault manualmente.

`cosmosAccountName`, `databaseName`, `keyVaultName` (dentro de
`key-vault.bicep`) y `storageAccountName` (dentro de `storage-queue.bicep`)
se dejan con su valor por defecto autogenerado salvo que se quiera fijar un
nombre concreto.

### Después de desplegar

Los `outputs` del comando anterior incluyen `cosmosEndpoint`, `keyVaultUri`,
`queueEndpoint`, entre otros. Con eso:

1. **Cosmos:** completar `AZURE_COSMOS_ENDPOINT` como app setting del backend.
   Preferir Managed Identity (`Cosmos DB Built-in Data Contributor` vía
   `az cosmosdb sql role assignment create`, ver `infra/grant-cosmos-rbac.sh`)
   en vez de `AZURE_COSMOS_KEY` — el backend ya usa `DefaultAzureCredential`
   automáticamente cuando la key viene vacía.
2. **Key Vault:** cargar los 3 secretos lógicos definidos en la estructura,
   uno por uno, imperativamente (nunca vía Bicep, para que un valor real
   nunca quede en el historial de `az deployment` ni en un `parameters.json`):
   ```bash
   az keyvault secret set --vault-name <keyVaultName-del-output> --name cosmos-endpoint --value "<valor>"
   az keyvault secret set --vault-name <keyVaultName-del-output> --name cosmos-key --value "<valor>"
   az keyvault secret set --vault-name <keyVaultName-del-output> --name fraud-threshold --value "<valor>"
   ```
3. **Storage Queue:** el backend consume `AZURE_STORAGE_QUEUE_ENDPOINT` (el
   output `queueEndpoint`) + `DefaultAzureCredential` — sin connection string
   ni claves de acceso. Coordinar con el equipo de backend el reemplazo de
   `NoOpMessageQueueAdapter` por el adapter real, y **no olvidar** el TTL de
   mensaje al encolar en `fraud-cases` (ver comentario en
   `infra/storage-queue.bicep`, es crítico para el requisito de cero pérdida
   de casos).

## Cómo destruir (recomendado entre sesiones de trabajo)

**Opción A — borrar solo lo nuevo de la Semana 2, sin tocar Cosmos/budget:**
```bash
az storage account delete \
  --name <storageAccountName-del-output> \
  --resource-group rg-centinela --yes

az keyvault delete --name <keyVaultName-del-output> --resource-group rg-centinela
# Key Vault queda en soft-delete (7 días) por defecto. Para purgarlo de inmediato
# (recomendado si se quiere limpiar por completo entre sesiones, dado que
# enablePurgeProtection quedó en false a propósito):
az keyvault purge --name <keyVaultName-del-output>
```

**Opción B — borrar todo el resource group** (todo lo que haya adentro,
incluyendo Cosmos DB y el budget):
```bash
az group delete --name rg-centinela --yes --no-wait
```

Cualquiera de las dos detiene por completo el consumo de todos los recursos de
este directorio. Ninguna se debe correr sin confirmación explícita tampoco,
aunque sea "solo para ahorrar".

## Recordatorio de la ventana de 30 días

El usuario está en el **trial estándar de Azure (200 USD, 30 días)**, no en
Azure for Students — aunque los recursos reales de este proyecto corren bajo
la suscripción de un compañero de equipo, no bajo esta cuenta (ver notas de
sesión). El free tier de Cosmos sobrevive al vencimiento de cualquier crédito
de prueba. Azure **no cobra automáticamente** al vencer un trial — los
recursos se pausan/deshabilitan salvo que se apruebe explícitamente pasar a
Pay-As-You-Go.

## Verificación de sintaxis (sin tocar Azure)

Una vez instalado `az` con la extensión de Bicep, se puede validar que el
archivo compila sin desplegar nada:
```bash
az bicep build --file infra/main.bicep
```
Esto es local y no requiere `az login` ni toca la suscripción — se puede
correr en cualquier momento para revisar sintaxis.
