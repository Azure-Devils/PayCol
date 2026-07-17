# Centinela — Documento de Diseño Técnico (TDD)
## API de Ingesta y Persistencia (Semana 1)

Este documento define las especificaciones técnicas, decisiones de arquitectura, estructura de datos y diseño del motor de base de datos para la **API de Ingesta de Transacciones** de **Centinela**.

---

## 1. Stack Tecnológico Seleccionado

Para garantizar un sistema escalable, seguro y tolerante a fallas, hemos seleccionado las siguientes tecnologías:

### 1.1 Lenguaje: **Java 21 (LTS)**
* **¿Por qué?**
    * **Virtual Threads (Project Loom):** Permite procesar de manera concurrente miles de peticiones HTTP concurrentes en la ingesta síncrona sin agotar el pool de hilos del servidor.
    * **Records:** Utilizados para definir DTOs y modelos inmutables del dominio de forma limpia y compacta.
    * **Pattern Matching y Switch Expressions:** Facilita la legibilidad en las validaciones de negocio.

### 1.2 Framework: **Spring Boot 3.x**
* **¿Por qué?**
    * **Integración Nativa con Azure:** Mediante las librerías de `azure-spring-boot` y el SDK de `azure-identity` para gestionar autenticación basada en **Identidades Gestionadas (Managed Identities)** sin utilizar contraseñas o credenciales estáticas.
    * **Validación Declarativa:** Soporte rápido y robusto de validaciones de contratos mediante `@Validated` y `spring-boot-starter-validation`.
    * **Ecosistema Maduro:** Inyección de dependencias nativa, ideal para aislar componentes con Arquitectura Hexagonal.

### 1.3 Motor de Base de Datos: **PostgreSQL (Azure Database for PostgreSQL - Flexible Server)**
* **¿Por qué NO Cosmos DB?**
    * **Complejidad y Curva de Aprendizaje:** Cosmos DB es una base de datos NoSQL distribuida que requiere un diseño sumamente cuidadoso de la `Partition Key` para evitar consultas ineficientes (*cross-partition queries*) que disparan exponencialmente el consumo de unidades de procesamiento (RUs) y, por ende, el costo financiero.
    * **Consistencia ACID Estricta:** Las transacciones financieras y la prevención de fraude exigen consistencia inmediata para validar duplicados y saldos. Los motores relacionales aseguran integridad referencial nativa mediante llaves foráneas.
    * **Mitigación de Redundancia:** PostgreSQL permite estructurar los datos de manera normalizada (evitando duplicar información de comercios o clientes en cada transacción persistida), reduciendo el costo de almacenamiento en disco.

---

## 2. Modelo de Datos Relacional (Normalizado)

Para evitar la polución de datos e indexar de manera eficiente, el esquema de persistencia se diseña bajo un modelo estructurado normalizado en 3 NF (Tercera Forma Normal):

                 ┌──────────────────┐
                 │    customers     │
                 ├──────────────────┤
                 │ PK │ customer_id │
                 └──────┬───────────┘
                        │ 1
                        │
                        │ N

┌──────────────┐ 1   N ┌────▼─────────────┐
│  locations   ├───────►   transactions   │
├──────────────┤       ├──────────────────┤
│ PK │ loc_id  │       │ PK │ tx_id       │
└──────────────┘       │ FK │ customer_id │
│ FK │ location_id │
└──────────────────┘


### 2.1 Tabla: `customers`
Almacena la información de las cuentas de origen y sus clientes.
```sql
CREATE TABLE customers (
    customer_id VARCHAR(50) PRIMARY KEY, -- Ej. 'acc_992384710'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE' -- ACTIVE, SUSPENDED, BLOCKED
);

2.2 Tabla: locations

Almacena coordenadas normalizadas para optimizar el cálculo de distancias.
SQL

CREATE TABLE locations (
    location_id SERIAL PRIMARY KEY,
    latitude NUMERIC(9,6) NOT NULL, -- Precisión exacta sin errores de float
    longitude NUMERIC(9,6) NOT NULL,
    description VARCHAR(255),
    CONSTRAINT unique_coordinates UNIQUE (latitude, longitude)
);

2.3 Tabla: transactions

Tabla central donde se persisten las transacciones crudas recibidas.
SQL

CREATE TABLE transactions (
    transaction_id VARCHAR(100) PRIMARY KEY, -- ID provisto por el emisor (idempotencia)
    customer_id VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL, -- Monto en centavos (Ej. 150.50 COP -> 15050)
    currency VARCHAR(3) NOT NULL, -- ISO 4217 (COP, USD)
    transaction_timestamp TIMESTAMP WITH TIME ZONE NOT NULL, -- Hora de la transacción
    ingestion_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, -- Hora del servidor
    location_id INT NOT NULL,
    merchant_id VARCHAR(50) NOT NULL,
    merchant_category VARCHAR(10) NOT NULL, -- MCC (Ej. 'mcc_5812')
    
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    FOREIGN KEY (location_id) REFERENCES locations(location_id)
);

-- Índices de velocidad optimizados para la Semana 2 (Reglas de velocidad)
CREATE INDEX idx_tx_customer_time ON transactions(customer_id, transaction_timestamp DESC);

3. Diseño de la Arquitectura Hexagonal

Para aislar por completo el núcleo de negocio de la infraestructura técnica de Azure y Spring Boot, estructuramos el código en capas desacopladas por puertos y adaptadores:

                            INFRAESTRUCTURA
  ┌─────────────────────────────────────────────────────────────────┐
  │  [Adaptador de Entrada]                                         │
  │     TransactionController (Spring MVC)                          │
  │               │                                                 │
  │               ▼                                                 │
  │       ┌───────────────┐                                         │
  │       │     PORT      │ IngestTransactionUseCase (Inbound)      │
  │       └───────┬───────┘                                         │
  │               │                                                 │
  │               │      DOMINIO (Lógica Pura)                      │
  │               │   ┌──────────────────────────────────────────┐  │
  │               └───► IngestTransactionService (Service)        │  │
  │                   │                                          │  │
  │                   │ - Valida Contrato                        │  │
  │                   │ - Captura Timestamp de Servidor          │  │
  │                   │                                          │  │
  │                   │   ┌───────────────┐                      │  │
  │                   └───►     PORT      │ (Outbound Ports)     │  │
  │                       └───────┬───────┘                      │  │
  │                               │                              │  │
  │   ┌───────────────────────────┴──────────────────────────┐   │  │
  │   │   TransactionRepositoryPort    MessageQueuePort      │   │  │
  │   └───────────────────────────┬──────────────────────────┘   │  │
  │                               │                              │  │
  │                               ▼                              │  │
  │  [Adaptadores de Salida]                                        │
  │     PostgresAdapter (JPA)      AzureQueueAdapter (SDK)          │
  └─────────────────────────────────────────────────────────────────┘

3.1 Paquetes del Sistema

La estructura en el proyecto de Spring Boot se organiza bajo este esquema de directorios:
Plaintext

com.centinela.api
├── domain/                          # Dominio Puro (No usa Spring ni Azure SDK)
│   ├── model/                       # Modelos de Dominio (Transaction, Location)
│   ├── port/
│   │   ├── inbound/                 # Interfaces para Casos de Uso
│   │   └── outbound/                # Interfaces que la Infraestructura debe implementar
│   └── service/                     # Casos de Uso (Lógica de validación sintáctica)
│
└── infrastructure/                  # Adaptadores de Tecnología (Spring Boot, Azure)
    ├── adapter/
    │   ├── inbound/
    │   │   └── web/                 # Controladores REST, DTOs de Entrada
    │   └── outbound/
    │       ├── azure/               # Implementación de Azure Queue y Blob Storage
    │       └── database/            # Implementación de persistencia con Postgres
    └── config/                      # Inyección de beans y configuraciones de Azure SDK

4. Implementaciones Críticas y Políticas
4.1 Evitar Redondeos Binarios (Montos Financieros)

Los campos monetarios se definen en el modelo y en la base de datos como enteros largos (Long / BIGINT) representando los centavos. El cálculo e ingreso de punto flotante en dinero queda prohibido en el sistema para evitar las inconsistencias de la especificación IEEE 754:
Java

// Correcto
long amountInCents = 15050; // Equivale a 150.50 COP

// Incorrecto
double amount = 150.50; // Tiende a perder precisión acumulada

4.2 Restricción Estricta de Campos Desconocidos (Anti-Polución)

Para mitigar que se inyecte payload basura al sistema, la API se configura mediante Jackson para rechazar peticiones HTTP con campos no contemplados en el contrato original:
Properties

# application.properties
spring.jackson.deserialization.fail-on-unknown-properties=true

Cualquier propiedad extra lanzará una excepción UnrecognizedPropertyException, la cual es interceptada por el GlobalExceptionHandler para retornar inmediatamente un código de estado 422 Unprocessable Entity.
4.3 Integración Limpia con Azure (Managed Identity)

Para conectar con las colas y blobs de Azure sin usar contraseñas estáticas, el sistema inicializa los clientes de infraestructura mediante DefaultAzureCredential:
Java

@Configuration
public class AzureClientConfig {

    @Value("${azure.storage.queue-endpoint}")
    private String queueEndpoint;

    @Bean
    public QueueServiceClient queueServiceClient() {
        return new QueueServiceClientBuilder()
            .endpoint(queueEndpoint)
            .credential(new DefaultAzureCredentialBuilder().build()) // Identidad Gestionada sin claves
            .buildClient();
    }
}

5. Próximos Pasos para el Equipo de Desarrollo

    Configurar el entorno local: Levantar una instancia local de PostgreSQL utilizando Docker.

    Implementar el Dominio: Escribir los modelos de dominio (Transaction, Location) utilizando Records de Java 21 y definir las interfaces de los puertos.

    Escribir el Adaptador Web: Crear el TransactionController con sus respectivas anotaciones @Valid para el control estricto de rangos numéricos y formatos.

    Validar Localmente con Azure CLI: Iniciar sesión localmente en la terminal con az login para que la API local pueda conectarse a los recursos de desarrollo de Azure de manera directa utilizando DefaultAzureCredential.


---

### Opción 2: Reintentar la generación del enlace
He vuelto a guardar el documento en el entorno para intentar limpiar cualquier error temporal. Prueba a descargarlo usando este enlace:

[file-tag: code-generated-file-927690e9-ff51-42a8-a24c-3b13f91d79bd]

Cualquier duda o si el resto de tu equipo necesita que preparemos la estructura del código inicial en base a esto, avísame. ¡Mucho éxito en es