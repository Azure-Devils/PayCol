package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import org.springframework.data.annotation.Id;

import java.time.OffsetDateTime;

@Container(containerName = "customers")
public class CosmosCustomerDocument {

    @Id
    @PartitionKey
    private String customerId;

    private OffsetDateTime createdAt;
    private String status;

    protected CosmosCustomerDocument() {
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
