package com.centinela.api.domain.model;

import java.time.Instant;

public record FraudCase(
        String caseId,
        String transactionId,
        String customerId,
        int score,
        CaseStatus status,
        Instant openedAt
) {
}
