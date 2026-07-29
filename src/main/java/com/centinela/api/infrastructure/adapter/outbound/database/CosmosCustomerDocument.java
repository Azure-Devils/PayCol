package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import org.springframework.data.annotation.Id;

import java.time.OffsetDateTime;

/**
 * Documento de Cosmos DB para un cliente. Equivalente a la extinta entidad JPA
 * {@code CustomerEntity} del esquema legado de transacciones en Postgres (eliminado
 * por completo del proyecto — ver
 * docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md).
 *
 * <p>Se usa {@code customerId} tanto como {@code @Id} (identificador único del documento)
 * como partition key: como este container es pequeño y cada cliente se consulta siempre por
 * su propio id (nunca se listan "todos los clientes" en este proyecto), no gana nada
 * repartir por otro campo. Usar el mismo valor para id y partition key es el patrón más
 * simple posible en Cosmos y el recomendado cuando no hay una consulta de rango que lo
 * justifique.
 */
@Container(containerName = "customers")
public class CosmosCustomerDocument {

    @Id
    @PartitionKey
    private String customerId;

    private OffsetDateTime createdAt;
    private String status;

    protected CosmosCustomerDocument() {
        // Requerido por el SDK de Cosmos para deserializar documentos leídos de la base.
    }

    public CosmosCustomerDocument(String customerId, OffsetDateTime createdAt, String status) {
        this.customerId = customerId;
        this.createdAt = createdAt;
        this.status = status;
    }

    public String getCustomerId() {
        return customerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getStatus() {
        return status;
    }
}
