// =============================================================================
// infra/postgres-cases.bicep — Azure Database for PostgreSQL Flexible Server,
// SKU Burstable B1ms, como almacén RELACIONAL de casos de fraude (Caso,
// Estado, Asignación, Resolución, Auditoría). Ya NO almacena transacciones
// (eso lo hace Cosmos DB desde docs/decisions/001) — ver justificación
// completa de esta doble persistencia en docs/decisions/002.
//
// Requisito no negociable (Semana2-Azure.md, sección 2.2): el almacén de
// casos NO debe ser alcanzable desde internet. La opción evaluada e
// implementada aquí es "VNet integration con subnet delegada + Private DNS
// Zone" (acceso privado nativo de Flexible Server), NO Private Link/Private
// Endpoint. Motivo (ver docs/decisions/002 para el detalle completo):
//   - VNet + subnet delegada + Private DNS Zone: sin costo de cómputo/hora
//     por el mecanismo de red en sí (el Private DNS Zone SÍ tiene un cargo
//     fijo de hosteo ~0.50 USD/zona/mes + consultas, ver ADR). Es la opción
//     MÁS SIMPLE soportada nativamente por el recurso (una sola propiedad
//     "network" en el server), sin recursos adicionales de "endpoint".
//   - Private Link/Private Endpoint: mecanismo alternativo más "enterprise",
//     pero factura por HORA de cada Private Endpoint (~0.01 USD/hora ≈ 7-10
//     USD/mes) y requiere un recurso adicional (el propio Private Endpoint)
//     además del NIC que genera. Se descarta por ser estrictamente más caro
//     y más complejo para el mismo resultado (aislamiento de red) en este
//     proyecto.
//
// Autenticación: SOLO Microsoft Entra ID (AAD) — passwordAuth deshabilitado
// desde la creación. Cero contraseñas, cero "administratorLogin/Password".
// Esto cumple al pie de la letra el requisito 2.6 (cero credenciales
// estáticas, autenticación vía identidad gestionada) también para Postgres,
// igual que ya se hace con Cosmos Queue/Blob. Ver nota de fallback más abajo
// si esto resulta demasiado complejo de cablear a tiempo en el backend.
//
// apiVersion 2022-12-01 (GA/estable, NO preview) verificada contra la
// referencia oficial de Microsoft Learn para
// Microsoft.DBforPostgreSQL/flexibleServers y su recurso hijo
// "administrators" — es la primera versión estable que expone authConfig
// (activeDirectoryAuth/passwordAuth) y el recurso administrators.
// =============================================================================

@description('Región de despliegue. Debe coincidir con el resto de recursos del proyecto (eastus2 por defecto) — verificar disponibilidad de Postgres Flexible Server Burstable en la región elegida antes de desplegar.')
param location string = 'eastus2'

@description('Tags para identificación/limpieza (project=centinela, etc.). NOTA: las subredes (Microsoft.Network/virtualNetworks/subnets) no admiten "tags" como recurso independiente en ARM — solo la VNet completa sí.')
param tags object

@description('Nombre del servidor Postgres Flexible Server. Único dentro de la región (forma parte del DNS interno). Min 3 caracteres.')
param postgresServerName string = 'centinela-pg-casos-${uniqueString(resourceGroup().id)}'

@description('Nombre de la base de datos de casos dentro del servidor.')
param casesDatabaseName string = 'centinela_casos'

@description('Versión mayor de PostgreSQL.')
param postgresVersion string = '16'

@description('Tamaño de almacenamiento en GB. 32 GB es el mínimo permitido por Flexible Server — más que suficiente para el volumen bajo (Caso/Estado/Asignación/Resolución/Auditoría) descrito en la Semana 2.')
@minValue(32)
param storageSizeGB int = 32

@description('Días de retención de respaldo automático. 7 días es el valor por defecto y queda dentro del almacenamiento de backup incluido sin costo adicional (hasta el 100% del storage provisionado); ver justificación completa (periodicidad/retención/RPO) en docs/decisions/002.')
@minValue(7)
@maxValue(35)
param backupRetentionDays int = 7

@description('Nombre de red virtual (VNet) dedicada a este módulo.')
param vnetName string = 'vnet-centinela'

@description('Espacio de direcciones de la VNet.')
param vnetAddressPrefix string = '10.20.0.0/16'

@description('Subred reservada para el/los recursos de cómputo del backend (App Service/Container App) cuando se aprovisionen en una iteración futura. Se crea ahora para no tener que re-diseñar la VNet más adelante, pero SIN delegación todavía — la delegación exacta (Microsoft.Web/serverFarms o Microsoft.App/environments) depende de qué servicio de cómputo se elija, pendiente de confirmar con el usuario.')
param appSubnetPrefix string = '10.20.1.0/24'

@description('Subred delegada exclusivamente a Postgres Flexible Server. Mínimo permitido por Azure: /28 (16 direcciones). Debe quedar vacía de otros recursos: la delegación es exclusiva.')
param postgresSubnetPrefix string = '10.20.2.0/28'

@description('Object ID (principal ID) de la Managed Identity de la app en Microsoft Entra ID. Se usa para: (a) darle de alta como "administrator" AAD del servidor Postgres (única forma de autenticación, ver authConfig más abajo). Requerido para que el backend pueda conectarse — sin este valor, el servidor queda creado pero SIN NINGÚN administrador AAD hasta que se vuelva a desplegar este módulo con el valor real.')
param appManagedIdentityPrincipalId string = ''

@description('Nombre descriptivo de la Managed Identity de la app, solo para el campo principalName del administrator AAD (documentación/portal, no afecta autenticación).')
param appManagedIdentityDisplayName string = 'centinela-backend-identity'

@description('Object ID del usuario humano (cuenta AAD del dueño del repo) a dar de alta como administrator AAD adicional — recomendado para poder conectarse manualmente (psql con `az account get-access-token`) sin depender de que el backend ya esté desplegado. Opcional: vacío por defecto.')
param humanAdminObjectId string = ''

@description('User Principal Name (UPN/email AAD) del usuario humano, requerido por Azure para el campo principalName cuando principalType es "User". Vacío por defecto (solo se usa si humanAdminObjectId no está vacío).')
param humanAdminUpn string = ''

@description('Interruptor de emergencia/fallback: si autenticar Postgres SOLO vía AAD resulta demasiado complejo de cablear a tiempo en Spring Boot (requiere el starter `spring-cloud-azure-starter-jdbc-postgresql` o equivalente para pasar un access token como password JDBC), se puede cambiar esto a true para habilitar TAMBIÉN autenticación por password. Aun así, la password NUNCA debe hardcodearse aquí: se debe pasar como parámetro @secure() en el momento del deploy real, tomado de una fuente que no sea el repo, y el valor final se guarda como el secreto "postgres-connection-string" en Key Vault (infra/key-vault.bicep), nunca en application.properties. Por defecto queda en false: AAD-only.')
param enablePasswordAuth bool = false

@description('Login administrador de password, SOLO relevante si enablePasswordAuth=true. Vacío por defecto.')
param administratorLogin string = ''

@description('Password administrador, SOLO relevante si enablePasswordAuth=true. Debe pasarse como parámetro seguro en tiempo de deploy real, jamás con un valor por defecto ni committeado.')
@secure()
param administratorLoginPassword string = ''

// -----------------------------------------------------------------------------
// Red: VNet con dos subredes. La subred de Postgres queda delegada
// exclusivamente al servicio (requisito de Azure para "VNet integration"
// nativa de Flexible Server, distinta de Private Link).
// -----------------------------------------------------------------------------
resource vnet 'Microsoft.Network/virtualNetworks@2023-09-01' = {
  name: vnetName
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [
        vnetAddressPrefix
      ]
    }
    subnets: [
      {
        name: 'snet-app'
        properties: {
          addressPrefix: appSubnetPrefix
          // Sin "delegation" todavía: pendiente de definir cuando se elija
          // App Service (Microsoft.Web/serverFarms) o Container Apps
          // (Microsoft.App/environments) para el backend.
        }
      }
      {
        name: 'snet-postgres'
        properties: {
          addressPrefix: postgresSubnetPrefix
          delegations: [
            {
              name: 'delegation-postgres-flexible'
              properties: {
                serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers'
              }
            }
          ]
        }
      }
    ]
  }
}

// Referencia a la subred delegada, para usarla en el server más abajo.
resource postgresSubnet 'Microsoft.Network/virtualNetworks/subnets@2023-09-01' existing = {
  parent: vnet
  name: 'snet-postgres'
}

// -----------------------------------------------------------------------------
// Private DNS Zone — nombre RESERVADO exacto exigido por el servicio para la
// resolución interna del servidor dentro de la VNet. Costo: ~0.50 USD/mes de
// hosteo de la zona + ~0.40 USD por millón de consultas DNS (ver ADR 002)
// — es el único componente de este módulo de red que sí genera un cargo fijo
// mensual, aunque pequeño. Requiere confirmación explícita del usuario antes
// de desplegar, igual que cualquier otro recurso con costo real.
// -----------------------------------------------------------------------------
resource postgresPrivateDnsZone 'Microsoft.Network/privateDnsZones@2020-06-01' = {
  name: 'privatelink.postgres.database.azure.com'
  location: 'global' // Las Private DNS Zones son recursos globales, no regionales.
  tags: tags
}

resource postgresPrivateDnsZoneLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = {
  parent: postgresPrivateDnsZone
  name: 'link-${vnetName}'
  location: 'global'
  tags: tags
  properties: {
    virtualNetwork: {
      id: vnet.id
    }
    registrationEnabled: false // No necesitamos auto-registro de VMs; solo resolución de nombres de Postgres.
  }
}

// -----------------------------------------------------------------------------
// Servidor Postgres Flexible Server — Burstable B1ms, integrado a la subred
// delegada (sin IP pública, "no alcanzable desde internet"). authConfig
// deshabilita passwordAuth por defecto (AAD-only) salvo que
// enablePasswordAuth=true.
//
// dependsOn explícito al link de la Private DNS Zone: Azure exige que la
// zona ya esté enlazada a la VNet ANTES de crear el servidor con esa zona
// asignada, si no, el deployment falla.
// -----------------------------------------------------------------------------
resource postgresServer 'Microsoft.DBforPostgreSQL/flexibleServers@2022-12-01' = {
  name: postgresServerName
  location: location
  tags: tags
  sku: {
    name: 'Standard_B1ms' // Burstable — el SKU más barato con crédito de CPU, nunca General Purpose/Memory Optimized sin pedido explícito.
    tier: 'Burstable'
  }
  properties: {
    version: postgresVersion
    storage: {
      storageSizeGB: storageSizeGB
      autoGrow: 'Disabled' // Evita que el storage crezca (y facture) automáticamente sin aviso; el volumen de casos es bajo y predecible.
    }
    backup: {
      backupRetentionDays: backupRetentionDays
      geoRedundantBackup: 'Disabled' // Geo-redundancia duplicaría el costo de almacenamiento de backup; no se justifica para una demo académica.
    }
    highAvailability: {
      mode: 'Disabled' // HA duplica el cómputo (un standby adicional facturado); no se justifica para 3 semanas de proyecto estudiantil.
    }
    network: {
      delegatedSubnetResourceId: postgresSubnet.id
      privateDnsZoneArmResourceId: postgresPrivateDnsZone.id
      // Sin "publicNetworkAccess": al integrar con VNet, esa propiedad no aplica
      // (solo es válida para servidores SIN integración de red propia).
    }
    authConfig: {
      activeDirectoryAuth: 'Enabled'
      passwordAuth: enablePasswordAuth ? 'Enabled' : 'Disabled'
      tenantId: tenant().tenantId
    }
    // administratorLogin/administratorLoginPassword solo se incluyen si el
    // fallback de password está activo; con AAD-only no hace falta ninguno.
    administratorLogin: enablePasswordAuth ? administratorLogin : null
    administratorLoginPassword: enablePasswordAuth ? administratorLoginPassword : null
  }
  dependsOn: [
    postgresPrivateDnsZoneLink
  ]
}

resource casesDatabase 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2022-12-01' = {
  parent: postgresServer
  name: casesDatabaseName
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

// -----------------------------------------------------------------------------
// Administradores AAD del servidor. Sin al menos UNO de estos dos, el
// servidor queda inaccesible (ni password —deshabilitado por defecto— ni
// AAD admin configurado). Ambos son condicionales; el README/ADR insiste en
// llenar al menos appManagedIdentityPrincipalId antes de desplegar de verdad.
// -----------------------------------------------------------------------------
resource appIdentityAdmin 'Microsoft.DBforPostgreSQL/flexibleServers/administrators@2022-12-01' = if (!empty(appManagedIdentityPrincipalId)) {
  parent: postgresServer
  name: appManagedIdentityPrincipalId
  properties: {
    principalType: 'ServicePrincipal'
    principalName: appManagedIdentityDisplayName
    tenantId: tenant().tenantId
  }
}

resource humanAdmin 'Microsoft.DBforPostgreSQL/flexibleServers/administrators@2022-12-01' = if (!empty(humanAdminObjectId)) {
  parent: postgresServer
  name: humanAdminObjectId
  properties: {
    principalType: 'User'
    principalName: humanAdminUpn
    tenantId: tenant().tenantId
  }
}

@description('Nombre real del servidor Postgres creado.')
output postgresServerName string = postgresServer.name

@description('FQDN del servidor (solo resoluble dentro de la VNet integrada, ver Private DNS Zone) — base para el connection string de casos.')
output postgresFqdn string = postgresServer.properties.fullyQualifiedDomainName

@description('Nombre de la base de datos de casos.')
output casesDatabaseName string = casesDatabase.name

@description('Nombre de la VNet creada, útil para integrar el futuro recurso de cómputo del backend a la subred snet-app.')
output vnetName string = vnet.name

@description('Resource ID de la subred snet-app (para VNet-integrar el backend cuando se aprovisione).')
output appSubnetId string = '${vnet.id}/subnets/snet-app'
