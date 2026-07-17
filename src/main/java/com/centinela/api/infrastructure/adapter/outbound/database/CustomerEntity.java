package com.centinela.api.infrastructure.adapter.outbound.database;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    @Column(name = "customer_id", length = 50)
    private String customerId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "status", length = 20)
    private String status;

    protected CustomerEntity() {
        // JPA
    }

    public CustomerEntity(String customerId, OffsetDateTime createdAt, String status) {
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
