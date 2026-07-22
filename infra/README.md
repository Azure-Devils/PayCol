# infra/ — Aprovisionamiento de Azure para Centinela (Cosmos DB)

Este directorio contiene el Bicep para crear la **cuenta real de Azure Cosmos DB**
(API NoSQL/Core, free tier) usada por la aplicación, más la alerta de presupuesto
que la acompaña. Ver el contexto completo de la decisión en
[`docs/decisions/001-migracion-postgresql-a-cosmosdb.md`](../docs/decisions/001-migracion-postgresql-a-cosmosdb.md).

**Nada de esto se ha ejecutado todavía.** Ningún comando de esta guía debe
correrse sin que el dueño del repo lo confirme explícitamente primero, aunque
el recurso sea gratis (free tier). Esa es la regla de oro de este proyecto.

## Qué se va a crear (y qué NO)

| Recurso | SKU / configuración | Costo |
|---|---|---|
| Cuenta Cosmos DB (`Microsoft.DocumentDB/databaseAccounts`) | API NoSQL/Core, `enableFreeTier: true`, 1 sola región, consistencia `Session` | **0 USD** — free tier permanente de Azure (1000 RU/s + 25GB), independiente del crédito de 200 USD del trial |
| Base de datos `centinela` | Throughput **manual** compartido, **1000 RU/s** (nunca autoscale) | **0 USD** dentro del límite del free tier; si se excediera el RU/s contratado, Cosmos responde `429` en vez de facturar de más |
| Container `transactions` | Partition key `/customerId`, sin throughput propio (hereda el compartido) | incluido arriba |
| Container `customers` | Partition key `/customerId`, sin throughput propio (hereda el compartido) | incluido arriba |
| Azure Budget (`Microsoft.Consumption/budgets`) | Scope: resource group, monto **1 USD/mes**, alertas al 80% y 100% a `sixorca00@gmail.com` | El budget en sí es gratis; solo notifica sobre gasto real de otros recursos |

**No se crea** ningún recurso de cómputo (App Service, Container Apps, Static
Web Apps) ni de red desde este directorio — eso es responsabilidad de los
Bicep/scripts que agregue el equipo de DevOps/Infra para el backend/frontend cuando
corresponda, cada uno con su propia confirmación previa.

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
   suscripción** (el free tier es único por suscripción; si ya hay una cuenta
   con `freeTier: true`, esta nueva cuenta se facturaría desde el RU/s 0):
   ```bash
   az cosmosdb list --query "[].{name:name, freeTier:enableFreeTier}" -o table
   ```
5. **Confirmar (o crear) el resource group destino.** Este Bicep se despliega
   con scope de resource group (`az deployment group create`), así que el
   grupo debe existir antes. Si el usuario ya tiene uno para Centinela,
   usar ese nombre; si no, un ejemplo (el nombre `rg-centinela` es solo una
   propuesta, a confirmar):
   ```bash
   az group create \
     --name rg-centinela \
     --location eastus2 \
     --tags project=centinela
   ```
   `az group create` no tiene costo por sí mismo (un resource group vacío no
   se factura), pero igual requiere tu confirmación antes de correrlo porque
   es el primer comando que toca la suscripción real.

## Comando de despliegue (NO ejecutar sin aprobación explícita)

```bash
az deployment group create \
  --resource-group rg-centinela \
  --name centinela-cosmos-$(date +%Y%m%d%H%M%S) \
  --template-file infra/main.bicep \
  --parameters infra/main.parameters.json
```

Antes de correrlo, revisar/editar `infra/main.parameters.json` si se quiere:
- otra región (`location`, por defecto `eastus2`),
- otro email de alertas (`budgetContactEmail`, por defecto `sixorca00@gmail.com`),
- otro monto de budget (`budgetAmountUsd`, por defecto `1`).

`cosmosAccountName` y `databaseName` se dejan con su valor por defecto del
Bicep (nombre único autogenerado + `"centinela"`) salvo que se quiera fijar
un nombre concreto.

### Después de desplegar

El comando anterior imprime en sus `outputs` el `cosmosEndpoint` real. Con eso:

1. Poner ese valor en `AZURE_COSMOS_ENDPOINT` (variable de entorno local /
   GitHub Actions secret) — **nunca commitear el valor en `application.properties`**.
2. Obtener la clave primaria (mientras no se migre a Managed Identity — ver
   nota abajo):
   ```bash
   az cosmosdb keys list \
     --name <cosmosAccountName-del-output> \
     --resource-group rg-centinela \
     --type keys \
     --query primaryMasterKey -o tsv
   ```
   Ese valor va en `AZURE_COSMOS_KEY` como secreto (GitHub Actions secret o
   variable de entorno local), **nunca en el repo**.
3. **Pendiente de coordinar con el equipo de backend**: el proyecto ya usa
   Managed Identity para Azure Queue/Blob; Cosmos hoy sigue leyendo
   `AZURE_COSMOS_KEY` porque `DefaultAzureCredential` para Cosmos SQL API
   todavía no está cableado en el código (ver nota en el ADR). Recomendado
   para una próxima iteración: asignar el rol `Cosmos DB Built-in Data
   Contributor` a la Managed Identity del backend y dejar `AZURE_COSMOS_KEY`
   vacío. Esto no bloquea el despliegue de hoy, es una mejora posterior.

## Cómo destruir (recomendado entre sesiones de trabajo)

Dado que este resource group probablemente termine compartido con los
recursos de backend/frontend que agregue el equipo de DevOps/Infra más adelante, hay dos
niveles de limpieza:

**Opción A — borrar solo lo de este módulo (Cosmos + budget), sin tocar nada
más del resource group:**
```bash
az cosmosdb delete \
  --name <cosmosAccountName-del-output> \
  --resource-group rg-centinela \
  --yes

az consumption budget delete \
  --budget-name centinela-budget-mensual \
  --resource-group rg-centinela
```

**Opción B — borrar todo el resource group** (todo lo que haya adentro,
incluyendo lo que agreguen otros módulos de infra más adelante):
```bash
az group delete --name rg-centinela --yes --no-wait
```

Cualquiera de las dos detiene por completo el consumo de RU/s y storage de
Cosmos. Ninguna de las dos se debe correr sin confirmación explícita tampoco
(aunque sea "solo para ahorrar", sigue siendo un comando real sobre la
suscripción).

## Recordatorio de la ventana de 30 días

El usuario está en el **trial estándar de Azure (200 USD, 30 días)**, no en
Azure for Students. El free tier de Cosmos sobrevive al vencimiento del
crédito (es un beneficio aparte), pero Azure de todas formas **no cobra
automáticamente** al vencer el trial — los recursos se pausan/deshabilitan
salvo que el usuario apruebe explícitamente pasar a Pay-As-You-Go. Aun así,
conviene tener presente la fecha de activación del trial al planear las 3
semanas del proyecto.

## Verificación de sintaxis (sin tocar Azure)

Una vez instalado `az` con la extensión de Bicep, se puede validar que el
archivo compila sin desplegar nada:
```bash
az bicep build --file infra/main.bicep
```
Esto es local y no requiere `az login` ni toca la suscripción — se puede
correr en cualquier momento para revisar sintaxis.
