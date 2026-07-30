package com.centinela.api.domain.model;

import java.time.Instant;
import java.util.List;

public record FraudCaseEvent(
        String transactionId,
        String customerId,
        int score,
        List<RuleActivation> activations,
        Instant openedAt
) {
    public FraudCaseEvent {
        activations = List.copyOf(activations);
    }
}
