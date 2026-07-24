package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.ScoringRule;
import com.centinela.api.domain.model.Transaction;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Regla de GEO-IMPOSIBLE: toma la transacción inmediatamente anterior de la
 * cuenta y calcula la velocidad implícita (distancia / tiempo) necesaria para
 * que ambas ubicaciones sean reales. Si esa velocidad supera un umbral
 * físicamente imposible de alcanzar por medios de transporte comunes
 * (por defecto, más rápido que un vuelo comercial), se activa la regla.
 */
public class ImpossibleGeoRule implements FraudRule {

    private final double maxPlausibleSpeedKmh;
    private final int points;

    public ImpossibleGeoRule(double maxPlausibleSpeedKmh, int points) {
        this.maxPlausibleSpeedKmh = maxPlausibleSpeedKmh;
        this.points = points;
    }

    @Override
    public Optional<RuleActivation> evaluate(Transaction current, List<Transaction> recentHistory) {
        Optional<Transaction> previous = recentHistory.stream()
                .filter(t -> t.transactionTimestamp().isBefore(current.transactionTimestamp()))
                .max(Comparator.comparing(Transaction::transactionTimestamp));

        if (previous.isEmpty()) {
            return Optional.empty();
        }

        Transaction prev = previous.get();
        double distanceKm = GeoMath.distanceKm(
                prev.location().latitude(), prev.location().longitude(),
                current.location().latitude(), current.location().longitude());

        Duration elapsed = Duration.between(prev.transactionTimestamp(), current.transactionTimestamp());
        double elapsedHours = Math.max(elapsed.toSeconds() / 3600.0, 1.0 / 3600.0); // evita división por cero

        double impliedSpeedKmh = distanceKm / elapsedHours;

        if (impliedSpeedKmh < maxPlausibleSpeedKmh) {
            return Optional.empty();
        }

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("distanceKm", Math.round(distanceKm * 100.0) / 100.0);
        observed.put("elapsedMinutes", Math.round(elapsed.toSeconds() / 60.0 * 100.0) / 100.0);
        observed.put("impliedSpeedKmh", Math.round(impliedSpeedKmh * 100.0) / 100.0);
        observed.put("thresholdKmh", maxPlausibleSpeedKmh);
        observed.put("previousTransactionId", prev.transactionId());

        return Optional.of(new RuleActivation(ScoringRule.IMPOSSIBLE_GEO, points, observed));
    }
}
