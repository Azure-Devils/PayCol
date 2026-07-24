package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.ScoringRule;
import com.centinela.api.domain.model.Transaction;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Regla de VELOCIDAD: cuenta cuántas transacciones de la misma cuenta cayeron
 * dentro de una ventana temporal corta que termina en la transacción actual.
 * Se activa cuando esa cantidad alcanza o supera {@code maxTransactionsInWindow}.
 */
public class VelocityRule implements FraudRule {

    private final Duration window;
    private final int maxTransactionsInWindow;
    private final int points;

    public VelocityRule(Duration window, int maxTransactionsInWindow, int points) {
        this.window = window;
        this.maxTransactionsInWindow = maxTransactionsInWindow;
        this.points = points;
    }

    @Override
    public Optional<RuleActivation> evaluate(Transaction current, List<Transaction> recentHistory) {
        Instant windowStart = current.transactionTimestamp().minus(window);

        long countInWindow = recentHistory.stream()
                .filter(t -> !t.transactionTimestamp().isBefore(windowStart))
                .count();
        // La transacción actual también cuenta como parte de la ventana.
        long totalInWindow = countInWindow + 1;

        if (totalInWindow < maxTransactionsInWindow) {
            return Optional.empty();
        }

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("windowSeconds", window.toSeconds());
        observed.put("transactionCountInWindow", totalInWindow);
        observed.put("threshold", maxTransactionsInWindow);

        return Optional.of(new RuleActivation(ScoringRule.VELOCITY, points, observed));
    }
}
