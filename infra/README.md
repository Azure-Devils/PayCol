# infra/ — Aprovisionamiento de Azure para Centinela

Este directorio contiene el Bicep para crear los recursos reales de Azure de Centinela:
Cosmos DB (Semana 1), y Key Vault, Storage Queues y Postgres Flexible Server para el
almacén de casos (Semana 2). Ver el contexto completo de las decisiones en
[`docs/decisions/001-migracion-postgresql-a-cosmosdb.md`](../docs/decisions/001-migracion-postgresql-a-cosmosdb.md)
y [`docs/decisions/002-ttl-transacciones-y-respaldo-postgres-casos.md`](../docs/decisions/002-ttl-transacciones-y-respaldo-postgres-casos.md).

**Nada de esto se ha ejecutado todavía.** Ningún comando de esta guía debe
correrse sin que el dueño del repo lo confirme explícitamente primero, aunque
el recurso sea gratis (free tier) o el costo parezca insignificante. Esa es la
regla de oro de este proyecto.

## Qué se va a crear (y qué NO)

| Recurso | SKU / configuración | Costo |
|---|---|---|
| Cuenta Cosmos DB (`Microsoft.DocumentDB/databaseAccounts`) | API NoSQL/Core, `enableFreeTier: true`, 1 sola región, consistencia `Session` | **0 USD** — free tier permanente de Azure (1000 RU/s + 25GB) |
| Base de datos `centinela` | Throughput **manual** compartido, **1000 RU/s** (nunca autoscale) | **0 USD** dentro del límite del free tier |
| Container `transactions` | Partition key `/customerId`, **`defaultTtl` = 90 días** (nuevo, Semana 2 — ver ADR 002) | incluido arriba |
| Container `customers` | Partition key `/customerId`, sin TTL (entidad de referencia) | incluido arriba |
| Azure Budget (`Microsoft.Consumption/budgets`) | Scope: resource group, monto **1 USD/mes**, alertas al 80% y 100% | El budget en sí es gratis; solo notifica sobre gasto real |
| **Key Vault** (`Microsoft.KeyVault/vaults`) — nuevo | Tier `standard`, autorización por RBAC, sin secretos con valores reales | Costo por operación, despreciable (fracción de centavo/mes) para este volumen |
| RBAC Key Vault (`Microsoft.Authorization/roleAssignments`) | "Key Vault Secrets User" a la Managed Identity de la app, "Key Vault Administrator" al usuario humano | **0 USD** — las asignaciones de rol no tienen costo propio |
| **Storage Account + 2 Storage Queues** (`Microsoft.Storage/storageAccounts`) — nuevo | `Standard_LRS`, colas `transaction-events` y `fraud-cases` | ~0.0036 USD/10,000 operaciones — prácticamente cero al volumen de un proyecto académico. Sin free tier formal, pero costo despreciable. |
| RBAC Storage Queue | "Storage Queue Data Contributor" a la Managed Identity de la app | **0 USD** |
| **VNet + 2 subredes** (`Microsoft.Network/virtualNetworks`) — nuevo | `snet-app` (sin delegar aún), `snet-postgres` (delegada a Postgres Flexible Server, `/28`) | **0 USD** — VNet y subredes no tienen costo propio |
| **Private DNS Zone + link** (`Microsoft.Network/privateDnsZones`) — nuevo | Zona reservada `privatelink.postgres.database.azure.com` | **~0.50 USD/mes de hosteo de zona** + ~0.40 USD/millón de consultas (despreciable en volumen) — **es un costo real, aunque pequeño**, requiere confirmación explícita |
| **Postgres Flexible Server** (`Microsoft.DBforPostgreSQL/flexibleServers`) — nuevo | SKU **Burstable B1ms**, 32 GB storage, HA deshabilitada, geo-backup deshabilitado, integrado a `snet-postgres` (sin IP pública), autenticación **AAD-only** (`passwordAuth: Disabled`) | **Depende de elegibilidad**: si la suscripción incluye el beneficio "12 meses gratis" de Azure (750 hrs/mes de B1ms + 32GB), **0 USD**; si NO aplica, **~12-13 USD/mes** — CONFIRMAR ANTES DE DESPLEGAR (ver sección de costo más abajo) |
| Base de datos `centinela_casos` | Dentro del servidor Postgres | incluido arriba |
| Administradores AAD del servidor (`.../administrators`) | Managed Identity de la app + (opcional) usuario humano | **0 USD** |

**No se crea** ningún recurso de cómputo (App Service, Container Apps, Static
Web Apps) — eso es responsabilidad de los Bicep/scripts que agregue
el equipo de DevOps/Infra cuando el backend/frontend estén listos para desplegarse, cada
uno con su propia confirmación previa. Tampoco se crean valores reales de
secretos en Key Vault (ver sección "Secretos" más abajo).

## Costo — puntos que requieren confirmación explícita antes de desplegar

1. **Postgres Flexible Server B1ms (~12-13 USD/mes si no aplica el beneficio "12 meses gratis").**
   La cuenta free trial estándar (200 USD/30 días) de Azure suele incluir, además del
   crédito, un conjunto de servicios "gratis por 12 meses" para cuentas NUEVAS, que
   históricamente incluye Postgres Flexible Server B1ms (750 horas + 32GB). **Esto
   depende de que la suscripción sea efectivamente elegible** (cuentas ya usadas
   antes, o ciertas combinaciones de oferta, pueden no calificar). Verificar en el
   portal ("Free services" / facturación) o preguntando directamente antes de
   desplegar este módulo — si no aplica, este es, de lejos, el recurso más caro de
   todo el proyecto hasta la fecha.
2. **Private DNS Zone (~0.50 USD/mes fijo).** Pequeño pero real, y recurrente
   mientras exista la zona — no es free tier. Ver ADR 002 para la comparación
   contra la alternativa (Private Link), más cara.
3. El resto de los recursos nuevos de esta semana (Key Vault, Storage Queues, RBAC,
   VNet/subredes) son gratis o de costo despreciable (fracciones de centavo).

## Re-chequeo de presupuesto — Semana 2

El budget de Cost Management (`infra/budget-alert.bicep`) se mantiene en **1 USD/mes**,
sin cambios. Justificación de por qué NO se sube pese a que Postgres podría costar
~12-13 USD/mes si no aplica el beneficio de 12 meses gratis:

- El budget de 1 USD nunca fue un "techo de gasto" — es un **canario**: existe para
  que la PRIMERA alerta llegue lo antes posible ante cualquier cargo real, no para
  representar cuánto se espera gastar. Subirlo a, por ejemplo, 15 USD retrasaría la
  notificación hasta haber acumulado 12-15 USD de gasto real antes de enterarse.
- El criterio de aceptación de la Semana 2 exige mantenerse **por debajo de 40 USD
  acumulados** al cierre de la semana — con el budget en 1 USD, cualquier
  acumulación hacia ese techo se notifica de inmediato en el primer dólar gastado,
  dando tiempo de sobra para reaccionar (pausar/borrar el servidor Postgres) mucho
  antes de acercarse a los 40 USD.
- Recomendación operativa (no un cambio de Bicep): revisar Cost Management
  manualmente al menos una vez por sesión de trabajo mientras el Postgres real esté
  desplegado, dado que es el único recurso de esta semana con un costo diario
  no despreciable si el beneficio de 12 meses no aplica.

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
5. **Confirmar elegibilidad del beneficio "12 meses gratis" para Postgres
   Flexible Server** — revisar en el portal (Cost Management > Free services)
   o preguntar directamente al usuario. Este paso es específico de la Semana 2
   y determina si `infra/postgres-cases.bicep` cuesta 0 USD o ~12-13 USD/mes.
6. **Obtener el Object ID del usuario humano (opcional pero recomendado)**,
   para poder gestionar Key Vault/Postgres manualmente después del deploy:
   ```bash
   az ad signed-in-user show --query id -o tsv
   ```
7. **Confirmar (o crear) el resource group destino.** Este Bicep se despliega
   con scope de resource group (`az deployment group create`), así que el
   grupo debe existir antes:
   ```bash
   az group create \
     --name rg-centinela \
     --location eastus2 \
     --tags project=centinela
   ```

## Orden de deploy recomendado

Todos los módulos están orquestados desde `infra/main.bicep`, así que **un solo
comando `az deployment group create` los crea todos en el orden correcto**
(Bicep resuelve las dependencias automáticamente: p. ej. el link de la Private
DNS Zone antes que el servidor Postgres). Aun así, si se prefiere desplegar por
partes (por ejemplo, para revisar costos incrementalmente), el orden lógico es:

1. `cosmos-db.bicep` + `budget-alert.bicep` (ya validado desde la Semana 1).
2. `key-vault.bicep` (sin secretos aún — estructura únicamente).
3. `storage-queue.bicep` (costo despreciable).
4. `postgres-cases.bicep` (el módulo con el único costo potencialmente no
   trivial — desplegar SOLO después de confirmar el punto 5 de los
   pre-requisitos).

**IMPORTANTE:** mientras `appManagedIdentityPrincipalId` y `humanAdminObjectId`
no tengan un valor real (porque el recurso de cómputo del backend todavía no
existe en este repo), Key Vault/Storage Queue se crean SIN las asignaciones de
rol RBAC, y Postgres se crea SIN ningún administrador AAD configurado (con
`passwordAuth: Disabled`, eso significa que **nadie** podrá conectarse hasta
volver a desplegar este módulo con al menos uno de esos dos valores). Esto es
intencional (permite crear el "esqueleto" de infraestructura antes de que
exista el backend), pero hay que recordar completar ese segundo paso.

## Comando de despliegue (NO ejecutar sin aprobación explícita)

```bash
az deployment group create \
  --resource-group rg-centinela \
  --name centinela-semana2-$(date +%Y%m%d%H%M%S) \
  --template-file infra/main.bicep \
  --parameters infra/main.parameters.json
```

Antes de correrlo, revisar/editar `infra/main.parameters.json`:
- `location` (por defecto `eastus2`),
- `budgetContactEmail` / `budgetAmountUsd` (por defecto `sixorca00@gmail.com` / `1`),
- `transactionsDefaultTtlSeconds` (por defecto 90 días — ver ADR 002 si se quiere ajustar),
- `appManagedIdentityPrincipalId` / `appManagedIdentityDisplayName` — llenar
  cuando exista la Managed Identity del backend,
- `humanAdminObjectId` / `humanAdminUpn` — llenar con la cuenta AAD del dueño
  del repo si se quiere poder gestionar Key Vault/Postgres manualmente.

`cosmosAccountName`, `databaseName`, `keyVaultName` (dentro de
`key-vault.bicep`), `storageAccountName` (dentro de `storage-queue.bicep`) y
`postgresServerName` (dentro de `postgres-cases.bicep`) se dejan con su valor
por defecto autogenerado salvo que se quiera fijar un nombre concreto.

### Después de desplegar

Los `outputs` del comando anterior incluyen `cosmosEndpoint`, `keyVaultUri`,
`queueEndpoint`, `postgresFqdn`, entre otros. Con eso:

1. **Cosmos:** igual que en la Semana 1 — completar `AZURE_COSMOS_ENDPOINT`/`AZURE_COSMOS_KEY`
   como secretos (nunca en el repo). Pendiente, como mejora, migrar a
   `Cosmos DB Built-in Data Contributor` vía Managed Identity.
2. **Key Vault:** cargar los 4 secretos lógicos definidos en la estructura,
   uno por uno, imperativamente (nunca vía Bicep, para que un valor real
   nunca quede en el historial de `az deployment` ni en un `parameters.json`):
   ```bash
   az keyvault secret set --vault-name <keyVaultName-del-output> --name cosmos-endpoint --value "<valor>"
   az keyvault secret set --vault-name <keyVaultName-del-output> --name cosmos-key --value "<valor>"
   az keyvault secret set --vault-name <keyVaultName-del-output> --name postgres-connection-string --value "<valor>"
   az keyvault secret set --vault-name <keyVaultName-del-output> --name fraud-threshold --value "<valor>"
   ```
3. **Storage Queue:** el backend consume `AZURE_STORAGE_QUEUE_ENDPOINT` (el
   output `queueEndpoint`) + `DefaultAzureCredential` — sin connection string
   ni claves de acceso. Coordinar con el equipo de backend el reemplazo de
   `NoOpMessageQueueAdapter` por el adapter real, y **no olvidar** el TTL de
   mensaje al encolar en `fraud-cases` (ver comentario en
   `infra/storage-queue.bicep`, es crítico para el requisito de cero pérdida
   de casos).
4. **Postgres:** con autenticación AAD-only, el backend obtiene un token vía
   `DefaultAzureCredential` y lo usa como password JDBC (requiere el starter
   `spring-cloud-azure-starter-jdbc-postgresql` o equivalente — hoy NO está en
   `pom.xml`, pendiente de coordinar con el equipo de backend). Ver el
   fallback de password documentado en `infra/postgres-cases.bicep` y en el
   ADR 002 si esto resulta demasiado complejo de cablear a tiempo.

## Cómo destruir (recomendado entre sesiones de trabajo)

**Opción A — borrar solo lo nuevo de la Semana 2, sin tocar Cosmos/budget:**
```bash
az postgres flexible-server delete \
  --name <postgresServerName-del-output> \
  --resource-group rg-centinela \
  --yes

az network private-dns link vnet delete \
  --resource-group rg-centinela \
  --zone-name privatelink.postgres.database.azure.com \
  --name link-vnet-centinela --yes
az network private-dns zone delete \
  --resource-group rg-centinela \
  --name privatelink.postgres.database.azure.com --yes
az network vnet delete --resource-group rg-centinela --name vnet-centinela

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

Cualquiera de las dos detiene por completo el consumo de cómputo/storage de
Postgres (el recurso más caro de esta semana) y de todo lo demás. Ninguna se
debe correr sin confirmación explícita tampoco, aunque sea "solo para ahorrar".

## Recordatorio de la ventana de 30 días

El usuario está en el **trial estándar de Azure (200 USD, 30 días)**, no en
Azure for Students. El free tier de Cosmos sobrevive al vencimiento del
crédito, pero el beneficio de "12 meses gratis" (si aplica) para Postgres
tiene su propia ventana de 12 meses independiente del crédito de 200 USD/30
días — ninguno de los dos cubre el proyecto completo automáticamente si algo
sale mal, así que sigue siendo necesario vigilar Cost Management. Azure **no
cobra automáticamente** al vencer el trial — los recursos se pausan/deshabilitan
salvo que el usuario apruebe explícitamente pasar a Pay-As-You-Go.

## Verificación de sintaxis (sin tocar Azure)

Una vez instalado `az` con la extensión de Bicep, se puede validar que el
archivo compila sin desplegar nada:
```bash
az bicep build --file infra/main.bicep
```
Esto es local y no requiere `az login` ni toca la suscripción — se puede
correr en cualquier momento para revisar sintaxis. **No se pudo ejecutar esta
verificación en esta sesión** porque `az`/Bicep CLI no está instalado en esta
máquina (mismo estado que dejó la Semana 1) — pendiente de correr antes del
primer despliegue real.
