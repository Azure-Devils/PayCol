package com.centinela.api.infrastructure.adapter.outbound.database;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Entidad JPA plana: las claves foráneas (customer_id, location_id) se
 * modelan como columnas simples, no como relaciones @ManyToOne. La
 * integridad referencial ya la garantiza Postgres (FOREIGN KEY) y el
 * adaptador se encarga de crear customer/location antes de insertar la
 * transacción. Esto mantiene el modelo simple para la Semana 1.
 */
@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "customer_id", length = 50, nullable = false)
    private String customerId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "transaction_timestamp", nullable = false)
    private OffsetDateTime transactionTimestamp;

    @Column(name = "ingestion_timestamp")
    private OffsetDateTime ingestionTimestamp;

    @Column(name = "location_id", nullable = false)
    private Integer locationId;

    @Column(name = "merchant_id", length = 50, nullable = false)
    private String merchantId;

    @Column(name = "merchant_category", length = 10, nullable = false)
    private String merchantCategory;

    protected TransactionEntity() {
        // JPA
    }

    public TransactionEntity(String transactionId, String customerId, long amount, String currency,
                              OffsetDateTime transactionTimestamp, OffsetDateTime ingestionTimestamp,
                              Integer locationId, String merchantId, String merchantCategory) {
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.transactionTimestamp = transactionTimestamp;
        this.ingestionTimestamp = ingestionTimestamp;
        this.locationId = locationId;
        this.merchantId = merchantId;
        this.merchantCategory = merchantCategory;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OffsetDateTime getTransactionTimestamp() {
        return transactionTimestamp;
    }

    public OffsetDateTime getIngestionTimestamp() {
        return ingestionTimestamp;
    }

    public Integer getLocationId() {
        return locationId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }
}
