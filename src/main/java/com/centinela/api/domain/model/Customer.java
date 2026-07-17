package com.centinela.api.domain.model;

import java.time.Instant;

/**
 * Cliente/cuenta de origen de una transacción.
 */
public record Customer(
        String customerId,
        Instant createdAt,
        String status
) {
    public static final String DEFAULT_STATUS = "ACTIVE";
}
