// =============================================================================
// infra/storage-queue.bicep — Cuenta de Azure Storage + dos Storage Queues:
//   - transaction-events: distribución del evento de transacción (la API
//     publica y sigue; el motor de scoring reacciona de forma independiente).
//   - fraud-cases: cola de casos marcados por el motor de scoring; debe
//     garantizar cero pérdida si el consumidor de casos está caído.
//
// Se eligió Azure Storage Queue (no Service Bus) por costo: Storage Queue no
// tiene un tier "Premium" caro, cobra por operación a un precio casi
// simbólico, y alcanza sobradamente el volumen de un proyecto académico.
// Referencia de precio (a la fecha de escribir esto): ~0.0036 USD por 10,000
// operaciones (Standard LRS) + costo de storage base (GB) despreciable para
// mensajes efímeros. No existe un "free tier" formal como el de Cosmos, pero
// el costo esperado para este proyecto es una fracción de centavo al mes.
// =============================================================================

@description('Región de despliegue. Debe coincidir con el resto de recursos del proyecto (eastus2 por defecto).')
param location string = 'eastus2'

@description('Nombre de la cuenta de Storage. Debe ser único GLOBALMENTE, 3-24 caracteres, SOLO letras minúsculas y dígitos (sin guiones: los nombres de storage account no admiten guiones, a diferencia de Cosmos/Key Vault).')
param storageAccountName string = take('centinelaq${uniqueString(resourceGroup().id)}', 24)

@description('Tags para identificación/limpieza (project=centinela, etc.).')
param tags object

@description('Object ID (principal ID) de la Managed Identity de la app que necesita publicar/consumir mensajes en las colas. Déjalo vacío ("") si el recurso de cómputo del backend todavía no existe — el storage account y las colas se crean igual, pero sin la asignación de rol RBAC.')
param appManagedIdentityPrincipalId string = ''

// Nombre de la cola de distribución del evento de transacción. Fijo (no
// parametrizable) porque el equipo de backend ya coordina este nombre exacto
// en el adapter real de MessageQueuePort.
var transactionEventsQueueName = 'transaction-events'

// Nombre de la cola de casos marcados por el motor de scoring, pendientes de
// gestión por el flujo de casos.
var fraudCasesQueueName = 'fraud-cases'

// -----------------------------------------------------------------------------
// Storage Account — Standard_LRS (la redundancia más barata; no se requiere
// alta disponibilidad geográfica para colas efímeras de un proyecto
// académico), StorageV2, HTTPS-only, TLS 1.2 mínimo, sin acceso público a
// blobs (no usamos blobs en este módulo, pero se deja denegado por defecto
// como buena práctica de bajo costo/bajo riesgo, no afecta el precio).
// -----------------------------------------------------------------------------
resource storageAccount 'Microsoft.Storage/storageAccounts@2023-01-01' = {
  name: storageAccountName
  location: location
  tags: tags
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: {
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    accessTier: 'Hot' // Irrelevante para colas (accessTier solo afecta Blob), se deja el default explícito por claridad.
  }
}

// -----------------------------------------------------------------------------
// Servicio de colas (properties por defecto, sin CORS ni soft-delete de
// colas configurado — no aporta valor para mensajes efímeros de este caso
// de uso y evita complejidad innecesaria).
// -----------------------------------------------------------------------------
resource queueService 'Microsoft.Storage/storageAccounts/queueServices@2023-01-01' = {
  parent: storageAccount
  name: 'default'
}

resource transactionEventsQueue 'Microsoft.Storage/storageAccounts/queueServices/queues@2023-01-01' = {
  parent: queueService
  name: transactionEventsQueueName
}

resource fraudCasesQueue 'Microsoft.Storage/storageAccounts/queueServices/queues@2023-01-01' = {
  parent: queueService
  name: fraudCasesQueueName
}

// -----------------------------------------------------------------------------
// RBAC: "Storage Queue Data Contributor" (lectura/escritura/borrado de
// mensajes, NO acceso a otros servicios del storage account como Blob/Table)
// para la Managed Identity de la app — reemplaza el uso de connection
// strings/claves de acceso estáticas. Constante de rol bien conocida:
// 974c5e8b-45b9-4653-ba55-5f855dd0fb88. Se asigna a nivel de storage account
// (cubre ambas colas). Condicional: si no hay identidad aún, no se crea.
// -----------------------------------------------------------------------------
resource queueDataContributorRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(appManagedIdentityPrincipalId)) {
  name: guid(storageAccount.id, appManagedIdentityPrincipalId, 'Storage Queue Data Contributor')
  scope: storageAccount
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '974c5e8b-45b9-4653-ba55-5f855dd0fb88')
    principalId: appManagedIdentityPrincipalId
    principalType: 'ServicePrincipal'
  }
}

// -----------------------------------------------------------------------------
// NOTA IMPORTANTE para el equipo de backend (no configurable desde Bicep,
// es responsabilidad del código que encola mensajes):
//
// Azure Storage Queue aplica, por defecto, un TTL de 7 días a cada MENSAJE
// individual si el productor no especifica lo contrario al invocar
// `sendMessage` (parámetro timeToLive). Pasado ese TTL, el mensaje se
// EXPIRA Y SE PIERDE silenciosamente, incluso si el consumidor nunca llegó
// a leerlo — esto violaría directamente el requisito de "cero pérdida de
// casos con el consumidor caído" (Semana2-Azure.md, sección 2.4) si el
// consumidor estuviera detenido más de 7 días seguidos.
//
// Para la cola "fraud-cases" en particular, se recomienda que el adapter
// real invoque el envío con timeToLive = -1 (infinito) o un valor
// suficientemente largo frente a cualquier ventana de mantenimiento
// esperada. Coordinar este detalle con el equipo de backend al implementar
// el adapter real que reemplace NoOpMessageQueueAdapter.
//
// Límites relevantes de Storage Queue a documentar (no configurables, son
// límites del servicio):
//   - Tamaño máximo por mensaje: 64 KB (base64).
//   - TTL máximo por mensaje: sin límite superior si se usa -1; por defecto 7 días.
//   - Tamaño máximo de una cola: 500 TB (irrelevante para este volumen).
//   - Límite de throughput por cuenta: ~20,000 mensajes/s (muy por encima del
//     volumen esperado de un proyecto académico).
// -----------------------------------------------------------------------------

@description('Nombre real de la cuenta de Storage creada.')
output storageAccountName string = storageAccount.name

@description('Endpoint del servicio de colas — va en AZURE_STORAGE_QUEUE_ENDPOINT (para usarse junto con DefaultAzureCredential, sin connection string).')
output queueEndpoint string = storageAccount.properties.primaryEndpoints.queue

@description('Nombre de la cola de eventos de transacción.')
output transactionEventsQueueName string = transactionEventsQueue.name

@description('Nombre de la cola de casos de fraude.')
output fraudCasesQueueName string = fraudCasesQueue.name
