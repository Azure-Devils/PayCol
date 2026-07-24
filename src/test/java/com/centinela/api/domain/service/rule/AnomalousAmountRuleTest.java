package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.centinela.api.domain.service.rule.RuleTestSupport.tx;
import static org.assertj.core.api.Assertions.assertThat;

class AnomalousAmountRuleTest {

    private final AnomalousAmountRule rule = new AnomalousAmountRule(3, 5.0, 25);

    @Test
    void seActivaCuandoElMontoSuperaCincoVecesElPromedioHistorico() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        List<Transaction> history = List.of(
                tx("h1", "acc1", 10_000, now.minusSeconds(3600), 4.7, -74.0, "m1", "5411"),
                tx("h2", "acc1", 10_000, now.minusSeconds(7200), 4.7, -74.0, "m1", "5411"),
                tx("h3", "acc1", 10_000, now.minusSeconds(10800), 4.7, -74.0, "m1", "5411")
        );
        Transaction current = tx("cur", "acc1", 100_000, now, 4.7, -74.0, "m1", "5411");

        Optional<RuleActivation> activation = rule.evaluate(current, history);

        assertThat(activation).isPresent();
        assertThat(activation.get().points()).isEqualTo(25);
        assertThat(activation.get().observedValues()).containsEntry("historicalAverageCents", 10_000L);
    }

    @Test
    void noSeActivaSinMuestraHistoricaSuficiente() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        List<Transaction> history = List.of(
                tx("h1", "acc1", 10_000, now.minusSeconds(3600), 4.7, -74.0, "m1", "5411")
        );
        Transaction current = tx("cur", "acc1", 100_000, now, 4.7, -74.0, "m1", "5411");

        assertThat(rule.evaluate(current, history)).isEmpty();
    }

    @Test
    void noSeActivaConMontoDentroDelRangoHabitual() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        List<Transaction> history = List.of(
                tx("h1", "acc1", 10_000, now.minusSeconds(3600), 4.7, -74.0, "m1", "5411"),
                tx("h2", "acc1", 12_000, now.minusSeconds(7200), 4.7, -74.0, "m1", "5411"),
                tx("h3", "acc1", 9_000, now.minusSeconds(10800), 4.7, -74.0, "m1", "5411")
        );
        Transaction current = tx("cur", "acc1", 11_000, now, 4.7, -74.0, "m1", "5411");

        assertThat(rule.evaluate(current, history)).isEmpty();
    }
}
