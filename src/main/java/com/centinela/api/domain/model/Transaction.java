package com.centinela.api.domain.model;

import java.time.Instant;

public record Transaction(
        String transactionId,
        String customerId,
        long amountCents,
        String currency,
        Instant transactionTimestamp,
        Instant ingestionTimestamp,
        Location location,
        String merchantId,
        String merchantCategory
) {

    public Transaction withIngestionTimestamp(Instant serverTimestamp) {
        return new Transaction(
                transactionId,
                customerId,
                amountCents,
                currency,
                transactionTimestamp,
                serverTimestamp,
                location,
                merchantId,
                merchantCategory
        );
    }
}
