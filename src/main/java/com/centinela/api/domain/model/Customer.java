package com.centinela.api.domain.model;

import java.time.Instant;

public record Customer(
        String customerId,
        Instant createdAt,
        String status
) {
    public static final String DEFAULT_STATUS = "ACTIVE";
}
