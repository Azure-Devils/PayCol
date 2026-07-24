// =============================================================================
// infra/main.bicep — Orquestador de Centinela: Cosmos DB (free tier) + Azure
// Budget + (Semana 2) Key Vault, Storage Queues y Postgres Flexible Server
// para el almacén de casos, todos con scope de resource group.
//
// ESTE ARCHIVO NO SE HA EJECUTADO TODAVÍA. No corre ni crea nada por sí solo:
// alguien tiene que invocar `az deployment group create` explícitamente (ver
// infra/README.md para el comando exacto, el orden recomendado y los
// pre-requisitos).
//
// Contexto de costo Semana 1 (ver docs/decisions/001-migracion-postgresql-a-cosmosdb.md):
//   - Cosmos DB tiene un free tier PERMANENTE (1000 RU/s + 25GB), independiente
//     del crédito de 200 USD del trial. Es UNA sola cuenta con free tier por
//     suscripción — por eso el README pide verificar antes de desplegar.
//   - Throughput MANUAL fijo en 1000 RU/s a nivel de base de datos (compartido
//     entre los containers). NUNCA autoscale: autoscale permite que Cosmos
//     escale sola por encima del free tier y genere cargo real.
//   - Una sola región.
//   - Budget de ~1 USD/mes con alertas al 80% y 100%, creado en el mismo
//     deployment que el primer recurso Cosmos (no al final del proyecto).
//
// Contexto de costo Semana 2 (ver docs/decisions/002-ttl-transacciones-y-respaldo-postgres-casos.md):
//   - Key Vault Standard: costo por operación, despreciable para el volumen
//     de este proyecto. No crea secretos con valores reales (ver
//     infra/key-vault.bicep) — esos se cargan después, fuera de Bicep.
//   - Storage Queue: costo por operación, prácticamente cero a bajo volumen.
//   - Postgres Flexible Server Burstable B1ms: el ÚNICO recurso nuevo de esta
//     semana con un costo mensual potencialmente NO trivial (~12-13 USD/mes)
//     SI el "12 meses gratis" de la cuenta free trial estándar de Azure no
//     aplica a esta suscripción — confirmar elegibilidad antes de desplegar
//     (ver ADR 002). Además requiere una Private DNS Zone con un cargo fijo
//     de ~0.50 USD/mes (no es free). El budget de 1 USD/mes sigue siendo un
//     "canario" (dispara la alerta ante el primer cargo real), NO un techo
//     de gasto — ver ADR 002 para el re-chequeo completo de presupuesto.
// =============================================================================

targetScope = 'resourceGroup'

@description('Región de despliegue de la cuenta Cosmos DB. El free tier aplica igual en cualquier región soportada; eastus2 se usa por defecto solo por disponibilidad amplia, no por costo. CONFIRMAR con el usuario si prefiere otra región (p. ej. la misma donde vayan el resto de recursos del proyecto).')
param location string = 'eastus2'

@description('Nombre de la cuenta de Cosmos DB. Debe ser único GLOBALMENTE (es parte del DNS: <nombre>.documents.azure.com). Si se deja el valor por defecto, se genera un sufijo único a partir del resource group para evitar colisiones.')
param cosmosAccountName string = 'centinela-cosmos-${uniqueString(resourceGroup().id)}'

@description('Nombre de la base de datos dentro de la cuenta Cosmos. Debe coincidir con AZURE_COSMOS_DATABASE en application.properties (valor actual: "centinela").')
param databaseName string = 'centinela'

@description('Throughput MANUAL COMPARTIDO (RU/s) a nivel de base de datos, heredado por ambos containers. Fijo en 1000 para calzar exactamente en el free tier permanente. NO cambiar a un recurso "autoscaleSettings" sin re-evaluar el costo con el usuario.')
@minValue(400)
@maxValue(1000)
param sharedThroughputRU int = 1000

@description('Monto mensual (USD) del budget de Cost Management a nivel de resource group. 1 USD es intencionalmente bajo: cualquier cargo real en un proyecto que debería costar 0 USD debe disparar la alerta casi de inmediato.')
param budgetAmountUsd int = 1

@description('Email que recibe las alertas de presupuesto al 80% y 100% del monto. Por defecto el email del dueño del repo; confirmar si se prefiere otro.')
param budgetContactEmail string = 'sixorca00@gmail.com'

@description('Tags aplicados a todos los recursos de este módulo, para poder identificarlos y limpiarlos fácilmente si algo se sale de control.')
param tags object = {
  project: 'centinela'
  environment: 'dev'
  managedBy: 'bicep'
}

@description('TTL por defecto (segundos) del container "transactions" de Cosmos. Ver justificación en docs/decisions/002.')
param transactionsDefaultTtlSeconds int = 7776000

@description('Object ID (principal ID) de la Managed Identity del backend (App Service/Container App), en Microsoft Entra ID. VACÍO por defecto porque ese recurso de cómputo todavía no está provisionado en este repo — sin este valor, los módulos de Key Vault/Storage Queue/Postgres se crean igual pero SIN las asignaciones de rol RBAC ni el administrador AAD de Postgres. Rellenar y volver a desplegar cuando exista el backend.')
param appManagedIdentityPrincipalId string = ''

@description('Nombre descriptivo de la Managed Identity de la app (solo documentación/portal).')
param appManagedIdentityDisplayName string = 'centinela-backend-identity'

@description('Object ID del usuario humano (cuenta AAD del dueño del repo) para permisos de administración de Key Vault y de Postgres. Obtener con `az ad signed-in-user show --query id -o tsv`. Opcional pero recomendado para poder operar los recursos manualmente.')
param humanAdminObjectId string = ''

@description('User Principal Name (UPN/email AAD) del usuario humano, requerido por Postgres si se define humanAdminObjectId.')
param humanAdminUpn string = ''

module cosmos 'cosmos-db.bicep' = {
  name: 'centinela-cosmos-deployment'
  params: {
    location: location
    cosmosAccountName: cosmosAccountName
    databaseName: databaseName
    sharedThroughputRU: sharedThroughputRU
    tags: tags
    transactionsDefaultTtlSeconds: transactionsDefaultTtlSeconds
  }
}

module budget 'budget-alert.bicep' = {
  name: 'centinela-budget-deployment'
  params: {
    budgetAmountUsd: budgetAmountUsd
    budgetContactEmail: budgetContactEmail
  }
}

module keyVault 'key-vault.bicep' = {
  name: 'centinela-keyvault-deployment'
  params: {
    location: location
    tags: tags
    appManagedIdentityPrincipalId: appManagedIdentityPrincipalId
    appManagedIdentityDisplayName: appManagedIdentityDisplayName
    humanAdminObjectId: humanAdminObjectId
    // Los 4 parámetros @secure() de valores de secretos (cosmosEndpointValue,
    // cosmosKeyValue, postgresConnectionStringValue, fraudThresholdValue) se
    // dejan SIN pasar a propósito: por defecto vienen vacíos en el módulo, así
    // que ningún secreto se crea en este deployment. Ver infra/key-vault.bicep.
  }
}

module storageQueue 'storage-queue.bicep' = {
  name: 'centinela-storage-queue-deployment'
  params: {
    location: location
    tags: tags
    appManagedIdentityPrincipalId: appManagedIdentityPrincipalId
  }
}

module postgresCases 'postgres-cases.bicep' = {
  name: 'centinela-postgres-cases-deployment'
  params: {
    location: location
    tags: tags
    appManagedIdentityPrincipalId: appManagedIdentityPrincipalId
    appManagedIdentityDisplayName: appManagedIdentityDisplayName
    humanAdminObjectId: humanAdminObjectId
    humanAdminUpn: humanAdminUpn
    // enablePasswordAuth se deja en su default (false = AAD-only). Ver
    // infra/postgres-cases.bicep para el fallback documentado si esto
    // resulta demasiado complejo de cablear a tiempo en el backend.
  }
}

@description('Nombre real de la cuenta Cosmos creada (útil si se usó el valor autogenerado).')
output cosmosAccountName string = cosmos.outputs.accountName

@description('Endpoint de la cuenta Cosmos — este es el valor a poner en AZURE_COSMOS_ENDPOINT.')
output cosmosEndpoint string = cosmos.outputs.endpoint

@description('Nombre de la base de datos creada dentro de la cuenta.')
output databaseName string = cosmos.outputs.databaseName

@description('Nombre real del Key Vault creado.')
output keyVaultName string = keyVault.outputs.keyVaultName

@description('URI del Key Vault — para spring.cloud.azure.keyvault.secret.property-sources[0].endpoint.')
output keyVaultUri string = keyVault.outputs.vaultUri

@description('Nombre real de la cuenta de Storage (colas) creada.')
output storageAccountName string = storageQueue.outputs.storageAccountName

@description('Endpoint del servicio de colas — para AZURE_STORAGE_QUEUE_ENDPOINT.')
output queueEndpoint string = storageQueue.outputs.queueEndpoint

@description('Nombre real del servidor Postgres de casos creado.')
output postgresServerName string = postgresCases.outputs.postgresServerName

@description('FQDN del servidor Postgres de casos (resoluble solo dentro de la VNet integrada).')
output postgresFqdn string = postgresCases.outputs.postgresFqdn

@description('Nombre de la VNet creada para el aislamiento de red de Postgres.')
output vnetName string = postgresCases.outputs.vnetName
