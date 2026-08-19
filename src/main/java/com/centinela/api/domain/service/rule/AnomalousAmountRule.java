package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.ScoringRule;
import com.centinela.api.domain.model.Transaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AnomalousAmountRule implements FraudRule {

    private final int minSampleSize;
    private final double deviationMultiplier;
    private final int points;

    public AnomalousAmountRule(int minSampleSize, double deviationMultiplier, int points) {
        this.minSampleSize = minSampleSize;
        this.deviationMultiplier = deviationMultiplier;
        this.points = points;
    }

    @Override
    public Optional<RuleActivation> evaluate(Transaction current, List<Transaction> recentHistory) {
        if (recentHistory.size() < minSampleSize) {
            return Optional.empty();
        }

        double averageAmountCents = recentHistory.stream()
                .mapToLong(Transaction::amountCents)
                .average()
                .orElse(0);

        if (averageAmountCents <= 0) {
            return Optional.empty();
        }

        double deviationRatio = current.amountCents() / averageAmountCents;
        if (deviationRatio < deviationMultiplier) {
            return Optional.empty();
        }

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("amountCents", current.amountCents());
        observed.put("historicalAverageCents", Math.round(averageAmountCents));
        observed.put("sampleSize", recentHistory.size());
        observed.put("deviationRatio", Math.round(deviationRatio * 100.0) / 100.0);
        observed.put("thresholdRatio", deviationMultiplier);

        return Optional.of(new RuleActivation(ScoringRule.ANOMALOUS_AMOUNT, points, observed));
    }
}
