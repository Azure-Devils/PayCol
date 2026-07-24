// =============================================================================
// infra/key-vault.bicep — Azure Key Vault (tier Standard) como gestor central
// de secretos de Centinela, con autorización por RBAC (no "access policies"
// clásicas) para que la Managed Identity del backend pueda LEER secretos sin
// que exista jamás una credencial estática que "obtenga credenciales".
//
// Este módulo crea el vault y (opcionalmente, solo si se le pasan los
// parámetros correspondientes) las asignaciones de rol RBAC. Deliberadamente
// NO crea los secretos con valores reales: la app necesita 4 secretos
// lógicos (endpoint/key de Cosmos, connection string de Postgres de casos,
// umbral de fraude), pero sus VALORES se cargan después del deploy vía
// `az keyvault secret set` (imperativo, fuera de Bicep) — nunca como
// parámetro de este template, para que un valor real jamás quede en el
// historial de `az deployment` ni en este repo.
//
// Referencia de precios: Key Vault "Standard" cobra por operación
// (~0.03 USD / 10,000 operaciones de secretos). Con el volumen de una app
// académica (lecturas puntuales al arrancar, no por transacción) el costo
// esperado es una fracción de centavo al mes — no hay "free tier" formal
// para Key Vault, pero el costo es despreciable frente al crédito de 200 USD.
// =============================================================================

@description('Región de despliegue. Debe coincidir con la región del resto de recursos del proyecto (eastus2 por defecto) — Key Vault no tiene beneficio de costo por región.')
param location string = 'eastus2'

@description('Nombre del Key Vault. Debe ser único GLOBALMENTE (DNS: <nombre>.vault.azure.net), 3-24 caracteres. Se trunca automáticamente con take() para no exceder el límite si el resource group tiene un nombre largo.')
param keyVaultName string = take('kv-centinela-${uniqueString(resourceGroup().id)}', 24)

@description('Tags para identificación/limpieza (project=centinela, etc.).')
param tags object

@description('Object ID (principal ID) de la Managed Identity de la app (App Service/Container App del backend) que necesita LEER secretos. Déjalo vacío ("") si ese recurso de cómputo todavía no existe — en ese caso el vault se crea igual, pero SIN la asignación de rol, y habrá que volver a desplegar este módulo (o correr `az role assignment create` a mano) cuando exista la identidad. Coordinar el valor exacto con los equipos de DevOps/Infra y de backend al provisionar el hosting del backend.')
param appManagedIdentityPrincipalId string = ''

@description('Nombre descriptivo de la Managed Identity de la app, solo para documentar en outputs/comentarios — no afecta el aprovisionamiento.')
param appManagedIdentityDisplayName string = 'centinela-backend-identity'

@description('Object ID del usuario humano (cuenta AAD del dueño del repo) al que se le quiere dar permisos de administración del vault (leer/crear/actualizar secretos vía portal o CLI). Opcional: sin este valor, NADIE podrá gestionar secretos manualmente después del deploy salvo que se asigne el rol después a mano, porque el modelo RBAC no da acceso implícito a quien despliega. Obtener con `az ad signed-in-user show --query id -o tsv`.')
param humanAdminObjectId string = ''

// -----------------------------------------------------------------------------
// Key Vault — autorización por RBAC (enableRbacAuthorization: true), NO por
// "access policies" clásicas. Soft-delete es obligatorio en versiones
// recientes de la API (no se puede desactivar) — se deja con la retención
// mínima permitida (7 días) para no complicar la limpieza entre sesiones.
//
// Purge protection se deja DESHABILITADA a propósito: con ella activada, un
// vault (o sus secretos) borrado no se puede eliminar definitivamente hasta
// que expire la retención de soft-delete, lo que estorba si el usuario quiere
// destruir TODO el entorno de una sesión para no dejar nada corriendo/latente.
// Trade-off de seguridad aceptado conscientemente para un proyecto académico
// de bajo riesgo: sin purge protection, alguien con permisos de borrado podría
// purgar secretos de forma irreversible antes de los 7 días. Revisar con el
// usuario si esto cambia (p. ej. si el proyecto pasa a manejar datos reales).
// -----------------------------------------------------------------------------
resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: keyVaultName
  location: location
  tags: tags
  properties: {
    sku: {
      family: 'A'
      name: 'standard'
    }
    tenantId: tenant().tenantId
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7
    enablePurgeProtection: false
    publicNetworkAccess: 'Enabled' // Sin restricción de red: demo académica, no hay VNet de gestión hacia el vault. Revisar si conviene restringir junto con la VNet de infra/postgres-cases.bicep.
    networkAcls: {
      defaultAction: 'Allow'
      bypass: 'AzureServices'
    }
  }
}

// -----------------------------------------------------------------------------
// RBAC: "Key Vault Secrets User" (rol built-in, solo lectura de secretos, NO
// permite crear/borrar) para la Managed Identity de la app. Constante de rol
// bien conocida en Azure: 4633458b-17de-408a-b874-0445c86b69e6.
// Condicional: si no se pasó appManagedIdentityPrincipalId, no se crea nada.
// -----------------------------------------------------------------------------
resource secretsUserRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(appManagedIdentityPrincipalId)) {
  name: guid(keyVault.id, appManagedIdentityPrincipalId, 'Key Vault Secrets User')
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')
    principalId: appManagedIdentityPrincipalId
    principalType: 'ServicePrincipal'
  }
}

// -----------------------------------------------------------------------------
// RBAC: "Key Vault Administrator" (rol built-in, gestión completa de
// secretos/llaves/certificados vía portal/CLI) para el usuario humano dueño
// del repo, para que pueda cargar los valores reales de los secretos después
// del deploy. Constante de rol: 00482a5a-887f-4fb3-b363-3b7fe8e74483.
// Condicional: si no se pasó humanAdminObjectId, no se crea nada — y en ese
// caso NADIE tendrá acceso de gestión hasta asignarlo manualmente.
// -----------------------------------------------------------------------------
resource adminRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(humanAdminObjectId)) {
  name: guid(keyVault.id, humanAdminObjectId, 'Key Vault Administrator')
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '00482a5a-887f-4fb3-b363-3b7fe8e74483')
    principalId: humanAdminObjectId
    principalType: 'User'
  }
}

// -----------------------------------------------------------------------------
// Secretos lógicos — SOLO ESTRUCTURA, sin valores reales.
//
// Diseño deliberado: estos 4 recursos "Microsoft.KeyVault/vaults/secrets" son
// CONDICIONALES sobre un parámetro @secure() que por defecto viene vacío. Si
// nadie pasa un valor real al desplegar (que es el caso de HOY: este Bicep no
// se ha ejecutado), NINGÚN secreto se crea — evita que un valor de prueba o
// placeholder quede grabado como "el secreto real" y evita que un redeploy
// futuro sobreescriba silenciosamente un secreto ya cargado a mano.
//
// Flujo recomendado en la práctica:
//   1. Desplegar este módulo una vez SIN pasar valores (secretos no se crean).
//   2. Cargar los valores reales imperativamente, fuera de Bicep:
//        az keyvault secret set --vault-name <kv> --name cosmos-endpoint --value "<valor>"
//        az keyvault secret set --vault-name <kv> --name cosmos-key --value "<valor>"
//        az keyvault secret set --vault-name <kv> --name postgres-connection-string --value "<valor>"
//        az keyvault secret set --vault-name <kv> --name fraud-threshold --value "<valor>"
//   3. NO volver a pasar esos parámetros en despliegues futuros de este mismo
//      módulo, para no arriesgarse a sobreescribir un valor real con uno
//      viejo/de prueba que haya quedado en un parameters.json local.
// -----------------------------------------------------------------------------

@description('Endpoint HTTPS de la cuenta Cosmos DB (AZURE_COSMOS_ENDPOINT). No es estrictamente sensible (es una URL pública), pero se centraliza aquí junto al resto de config de conexión. Vacío por defecto: no se crea el secreto.')
@secure()
param cosmosEndpointValue string = ''

@description('Clave primaria de Cosmos DB (AZURE_COSMOS_KEY) — SÍ es sensible. Idealmente debe dejar de usarse una vez que el equipo de backend cablee DefaultAzureCredential para Cosmos SQL API (ver docs/decisions/001). Vacío por defecto: no se crea el secreto.')
@secure()
param cosmosKeyValue string = ''

@description('Connection string (o los componentes host/db/sslmode, sin password si se usa auth AAD — ver infra/postgres-cases.bicep) del Postgres de casos de fraude. Vacío por defecto: no se crea el secreto.')
@secure()
param postgresConnectionStringValue string = ''

@description('Umbral de score de fraude (fraud threshold) a partir del cual se abre un caso. Se modela como secreto dinámico (no una env var fija) precisamente para que el equipo de backend pueda leerlo/modificarlo sin requerir un nuevo despliegue — requisito explícito de la Semana 2 (umbral configurable sin redeploy). Vacío por defecto: no se crea el secreto; el valor inicial debe acordarse con el equipo de backend según el criterio de falsos positivos vs. fraude no detectado.')
@secure()
param fraudThresholdValue string = ''

resource secretCosmosEndpoint 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = if (!empty(cosmosEndpointValue)) {
  parent: keyVault
  name: 'cosmos-endpoint'
  properties: {
    value: cosmosEndpointValue
  }
}

resource secretCosmosKey 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = if (!empty(cosmosKeyValue)) {
  parent: keyVault
  name: 'cosmos-key'
  properties: {
    value: cosmosKeyValue
  }
}

resource secretPostgresConnectionString 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = if (!empty(postgresConnectionStringValue)) {
  parent: keyVault
  name: 'postgres-connection-string'
  properties: {
    value: postgresConnectionStringValue
  }
}

resource secretFraudThreshold 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = if (!empty(fraudThresholdValue)) {
  parent: keyVault
  name: 'fraud-threshold'
  properties: {
    value: fraudThresholdValue
  }
}

@description('Nombre real del Key Vault creado.')
output keyVaultName string = keyVault.name

@description('URI del vault — va en spring.cloud.azure.keyvault.secret.property-sources[0].endpoint (o equivalente) en application.properties.')
output vaultUri string = keyVault.properties.vaultUri
