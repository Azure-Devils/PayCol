package com.centinela.api.domain.model;

import java.util.Map;

public record RuleActivation(
        ScoringRule rule,
        int points,
        Map<String, Object> observedValues
) {
    public RuleActivation {
        observedValues = Map.copyOf(observedValues);
    }
}
