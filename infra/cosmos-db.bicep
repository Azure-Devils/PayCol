// =============================================================================
// infra/cosmos-db.bicep — Cuenta Cosmos DB (API NoSQL/Core), free tier,
// base de datos "centinela" con throughput manual compartido, y los dos
// containers que ya existen en el código:
//   - transactions (partition key /customerId) — ver CosmosTransactionDocument.java
//   - customers    (partition key /customerId) — ver CosmosCustomerDocument.java
//
// apiVersion 2023-11-15 verificado contra el quickstart oficial de Microsoft
// para Cosmos DB free tier (azure-quickstart-templates,
// quickstarts/microsoft.documentdb/cosmosdb-free).
// =============================================================================

@description('Región de despliegue.')
param location string

@description('Nombre único (global) de la cuenta de Cosmos DB.')
param cosmosAccountName string

@description('Nombre de la base de datos.')
param databaseName string = 'centinela'

@description('Throughput manual compartido (RU/s) a nivel de base de datos. Fijo en 1000 para calzar en el free tier. NUNCA autoscale.')
param sharedThroughputRU int = 1000

@description('Tags para identificación/limpieza.')
param tags object

@description('TTL por defecto (segundos) del container "transactions": 90 días (7776000s). Justificación completa en docs/decisions/002-ttl-transacciones-y-respaldo-postgres-casos.md — resumen: la ventana más larga de las cuatro reglas de detección es la de "monto atípico" (comportamiento histórico de la cuenta), que necesita varias semanas de historial para ser confiable; 90 días da margen holgado sin acumular indefinidamente el volumen de escritura constante que describe la Semana 2, ayudando a permanecer dentro de los 25 GB del free tier. NO se aplica al container "customers" (entidad de referencia, no debe expirar).')
@minValue(0)
param transactionsDefaultTtlSeconds int = 7776000

// -----------------------------------------------------------------------------
// Cuenta Cosmos DB
// -----------------------------------------------------------------------------
resource cosmosAccount 'Microsoft.DocumentDB/databaseAccounts@2023-11-15' = {
  name: cosmosAccountName
  location: location
  kind: 'GlobalDocumentDB' // Kind correcto para la API NoSQL/Core (NO confundir con "Cosmos DB for PostgreSQL"/Citus, que es un recurso distinto sin free tier).
  tags: tags
  properties: {
    databaseAccountOfferType: 'Standard'
    enableFreeTier: true // Beneficio único por suscripción: 1000 RU/s + 25GB gratis de por vida. Verificar antes de desplegar que no exista ya otra cuenta con este flag en la suscripción (ver README).
    consistencyPolicy: {
      defaultConsistencyLevel: 'Session' // Más barato en RU que Strong/BoundedStaleness; suficiente para "leer tu propia escritura" en este proyecto.
    }
    locations: [
      {
        locationName: location
        failoverPriority: 0
        isZoneRedundant: false
      }
    ]
    // Intencionalmente UNA sola entrada en "locations": agregar una segunda región
    // multiplicaría el consumo de RU/storage contra el mismo pool gratuito.
    publicNetworkAccess: 'Enabled' // Sin restricción de red: es una demo académica, no hay VNet. Revisar si el backend termina necesitando IP allowlisting.
    disableKeyBasedMetadataWriteAccess: false
  }
}

// -----------------------------------------------------------------------------
// Base de datos "centinela" con throughput MANUAL compartido (1000 RU/s)
// -----------------------------------------------------------------------------
resource sqlDatabase 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases@2023-11-15' = {
  parent: cosmosAccount
  name: databaseName
  properties: {
    resource: {
      id: databaseName
    }
    options: {
      throughput: sharedThroughputRU
      // NOTA: "throughput" (manual) aquí, NUNCA "autoscaleSettings". Autoscale
      // permite que Cosmos escale sola por encima del free tier y genere cargo
      // real; con manual, exceder la capacidad devuelve 429 en vez de facturar.
    }
  }
}

// -----------------------------------------------------------------------------
// Container "transactions" — sin throughput propio: hereda el compartido de
// la base de datos. Partition key /customerId (ver CosmosTransactionDocument.java).
// -----------------------------------------------------------------------------
resource transactionsContainer 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2023-11-15' = {
  parent: sqlDatabase
  name: 'transactions'
  properties: {
    resource: {
      id: 'transactions'
      partitionKey: {
        paths: [
          '/customerId'
        ]
        kind: 'Hash'
      }
      // defaultTtl activa la expiración automática de documentos: cada
      // transacción se borra sola transactionsDefaultTtlSeconds (90 días por
      // defecto) después de su última modificación, sin intervención manual
      // ni job de limpieza aparte. Un valor por documento (si algún día se
      // necesita) podría sobreescribir este default con su propio "ttl".
      // Justificación completa: docs/decisions/002-ttl-transacciones-y-respaldo-postgres-casos.md.
      defaultTtl: transactionsDefaultTtlSeconds
    }
    // Sin "options.throughput" a propósito: si este container tuviera throughput
    // propio, dejaría de compartir el pool del free tier con "customers" y la
    // suma de ambos podría superar 1000 RU/s.
  }
}

// -----------------------------------------------------------------------------
// Container "customers" — mismo patrón, partition key /customerId
// (ver CosmosCustomerDocument.java: aquí customerId es tanto @Id como @PartitionKey).
// -----------------------------------------------------------------------------
resource customersContainer 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2023-11-15' = {
  parent: sqlDatabase
  name: 'customers'
  properties: {
    resource: {
      id: 'customers'
      partitionKey: {
        paths: [
          '/customerId'
        ]
        kind: 'Hash'
      }
    }
  }
}

// -----------------------------------------------------------------------------
// Container "cases" — almacén de casos de fraude (reemplaza al Postgres
// Flexible Server que nunca se desplegó, ver docs/decisions/004). Partition key
// /transactionId (no /customerId): los dos accesos del puerto de dominio
// (existsByTransactionId, openCase) siempre filtran por transactionId, así que
// esto convierte el chequeo de idempotencia en un point-read barato en vez de
// un cross-partition scan. Sin defaultTtl a propósito: un caso de fraude no
// debe expirar solo. Sin throughput propio: comparte el pool de 1000 RU/s.
// -----------------------------------------------------------------------------
resource casesContainer 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2023-11-15' = {
  parent: sqlDatabase
  name: 'cases'
  properties: {
    resource: {
      id: 'cases'
      partitionKey: {
        paths: [
          '/transactionId'
        ]
        kind: 'Hash'
      }
    }
  }
}

@description('Nombre real de la cuenta creada.')
output accountName string = cosmosAccount.name

@description('Endpoint HTTPS de la cuenta — va en AZURE_COSMOS_ENDPOINT.')
output endpoint string = cosmosAccount.properties.documentEndpoint

@description('Nombre de la base de datos creada.')
output databaseName string = sqlDatabase.name
