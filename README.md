# Centinela — API de Ingesta de Transacciones

Sistema de detección de fraude transaccional. Estado actual: Semana 1 (ingesta y consulta
de transacciones por id). Ver `docs/API.md` para el contrato completo de la API y
`docs/decisions/` para el historial de decisiones de arquitectura.

## Requisitos

- Java 21
- Maven (o el wrapper `./mvnw` si el repo lo trae)
- Docker + Docker Compose

## Cómo correr el proyecto localmente (motor actual: Cosmos DB)

Desde la migración documentada en `docs/decisions/001-migracion-postgresql-a-cosmosdb.md`,
el motor de persistencia activo es **Azure Cosmos DB**, no PostgreSQL. Para desarrollo local
se usa el emulador de Cosmos DB en Docker — no hace falta ninguna cuenta real de Azure para
correr ni probar la app.

**1. Levantar el emulador de Cosmos DB:**

```bash
docker compose up -d cosmosdb-emulator
```

La primera vez tarda 1-3 minutos en quedar listo. Podés chequear que ya responde con:

```bash
curl -sk https://localhost:8085/_explorer/emulator.pem -o /dev/null && echo "emulador listo"
```

**2. Generar el certificado/truststore local** (solo hace falta una vez, o cada vez que se
recree el contenedor del emulador con `--force-recreate`, porque genera un certificado nuevo):

```bash
mkdir -p .local
curl -sk https://localhost:8085/_explorer/emulator.pem -o .local/cosmos-emulator.pem
cp "$JAVA_HOME/lib/security/cacerts" .local/cacerts-cosmos
chmod u+w .local/cacerts-cosmos
keytool -importcert -noprompt -alias cosmosdb-emulator \
  -file .local/cosmos-emulator.pem \
  -keystore .local/cacerts-cosmos \
  -storepass changeit
```

**3. Correr la app:**

```bash
./.local/run-local-cosmos.sh
```

Este script hace todo el trabajo: valida que el emulador esté arriba, exporta
`AZURE_COSMOS_ENDPOINT`/`AZURE_COSMOS_KEY`/`AZURE_COSMOS_DATABASE`, apunta el truststore de
Java al certificado del emulador, y corre `mvn spring-boot:run`. **No lo reemplaces por el
botón "Run" del IDE sin más** — sin esas variables de entorno, el arranque falla con
`cannot buildAsyncClient client without service endpoint` (Spring intenta crear el cliente
de Cosmos con un endpoint vacío).

### Correr desde IntelliJ (o cualquier IDE) en vez del script

Si preferís usar el botón Run del IDE, agregá estas variables de entorno a la configuración
de Run/Debug (Run → Edit Configurations → Environment variables):

```
AZURE_COSMOS_ENDPOINT=https://localhost:8085
AZURE_COSMOS_KEY=C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==
AZURE_COSMOS_DATABASE=centinela
```

y estas VM options:

```
-Djavax.net.ssl.trustStore=<ruta-absoluta-al-repo>/.local/cacerts-cosmos
-Djavax.net.ssl.trustStorePassword=changeit
-Dio.netty.handler.ssl.noOpenSsl=true
```

(La `AZURE_COSMOS_KEY` de arriba no es un secreto: es la clave fija de desarrollo que
Microsoft publica para el emulador de Cosmos DB, siempre la misma para todo el mundo.)

**4. Probar que funciona:**

```bash
curl -X POST http://localhost:8081/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "tx_001",
    "customerId": "acc_001",
    "amountCents": 15050,
    "currency": "COP",
    "transactionTimestamp": "2026-07-22T10:00:00Z",
    "location": {"latitude": 4.710989, "longitude": -74.072092, "description": "Bogotá"},
    "merchantId": "merch_001",
    "merchantCategory": "mcc_5812"
  }'

curl http://localhost:8081/api/v1/transactions/tx_001
```

## Motor de persistencia anterior (PostgreSQL, aislado)

El código de PostgreSQL se conserva para referencia/rollback (ver
`PostgresTransactionRepositoryAdapter`, perfil Spring `postgres-legacy`, inactivo por
defecto). Reactivarlo requiere más que levantar el contenedor: hay que activar ese perfil
Y quitar las exclusiones de autoconfiguración de JPA/DataSource/Flyway en
`application.properties`. No es el camino recomendado salvo que se decida revertir la
migración — ver el ADR para el contexto completo.

## Aprovisionamiento real en Azure

Bicep listo (no desplegado) en `infra/` — ver `infra/README.md` para el comando de
deployment y los pre-requisitos (Azure CLI, `az login`, verificar free tier único por
suscripción).
