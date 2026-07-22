// =============================================================================
// infra/budget-alert.bicep — Azure Budget (Cost Management) a nivel de
// resource group, con alertas al 80% y 100% del monto. Se despliega en el
// MISMO deployment que la primera cuenta Cosmos (no como paso separado
// "para después"), tal como exige la regla de gestión de costo del proyecto.
//
// apiVersion 2024-08-01 (la más reciente estable al momento de escribir esto,
// verificada contra la referencia oficial de Microsoft Learn para
// Microsoft.Consumption/budgets).
// =============================================================================

@description('Monto mensual en USD del presupuesto. Deliberadamente bajo (1 USD por defecto): este proyecto debería costar 0 USD, así que cualquier cargo real debe disparar la alerta casi de inmediato.')
param budgetAmountUsd int = 1

@description('Email de contacto para las alertas de presupuesto.')
param budgetContactEmail string

@description('Nombre del budget (único dentro del resource group).')
param budgetName string = 'centinela-budget-mensual'

// NOTA: Microsoft.Consumption/budgets NO soporta la propiedad "tags" a nivel
// ARM (no es un recurso "taggable" convencional), por eso este módulo no
// recibe/aplica el objeto "tags" que sí llevan cosmos-db.bicep y sus hijos.

@description('Fecha de inicio del período del budget (primer día del mes de despliegue). Se calcula automáticamente al momento de desplegar; no hace falta tocarlo.')
param budgetStartDate string = '${utcNow('yyyy-MM-01')}T00:00:00Z'

resource budget 'Microsoft.Consumption/budgets@2024-08-01' = {
  name: budgetName
  properties: {
    category: 'Cost'
    amount: budgetAmountUsd
    timeGrain: 'Monthly'
    timePeriod: {
      startDate: budgetStartDate
      // Sin "endDate": Azure lo asume automáticamente en 10 años desde el
      // inicio, es decir, el budget sigue vigente mes a mes sin necesidad de
      // recrearlo. No implica ningún costo por sí mismo: el budget es gratis,
      // solo notifica sobre el consumo real de otros recursos.
    }
    notifications: {
      alerta80Porciento: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 80
        thresholdType: 'Actual'
        contactEmails: [
          budgetContactEmail
        ]
      }
      alerta100Porciento: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 100
        thresholdType: 'Actual'
        contactEmails: [
          budgetContactEmail
        ]
      }
    }
  }
}

@description('Nombre del budget creado, para referencia.')
output name string = budget.name
