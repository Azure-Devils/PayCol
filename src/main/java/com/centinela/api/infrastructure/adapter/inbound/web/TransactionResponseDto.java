package com.centinela.api.infrastructure.adapter.inbound.web;

import java.time.Instant;

public record TransactionResponseDto(
        String transactionId,
        String customerId,
        long amountCents,
        String currency,
        Instant transactionTimestamp,
        Instant ingestionTimestamp,
        LocationResponseDto location,
        String merchantId,
        String merchantCategory
) {
}
