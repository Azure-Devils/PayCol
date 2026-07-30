package com.centinela.api.domain.model;

import java.time.Instant;
import java.util.List;

public record TransactionScore(
        String transactionId,
        String customerId,
        int totalScore,
        List<RuleActivation> activations,
        Instant scoredAt
) {
    public TransactionScore {
        activations = List.copyOf(activations);
    }
}
