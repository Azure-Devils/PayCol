package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static com.centinela.api.domain.service.rule.RuleTestSupport.tx;

class VelocityRuleTest {

    private final VelocityRule rule = new VelocityRule(Duration.ofMinutes(2), 5, 30);

    @Test
    void seActivaCuandoHayCincoOMasTransaccionesEnLaVentana() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Transaction current = tx("tx5", "acc1", 1000, now, 4.7, -74.0, "m1", "5411");

        List<Transaction> history = List.of(
                tx("tx4", "acc1", 1000, now.minusSeconds(20), 4.7, -74.0, "m1", "5411"),
                tx("tx3", "acc1", 1000, now.minusSeconds(40), 4.7, -74.0, "m1", "5411"),
                tx("tx2", "acc1", 1000, now.minusSeconds(60), 4.7, -74.0, "m1", "5411"),
                tx("tx1", "acc1", 1000, now.minusSeconds(80), 4.7, -74.0, "m1", "5411")
        );

        Optional<RuleActivation> activation = rule.evaluate(current, history);

        assertThat(activation).isPresent();
        assertThat(activation.get().points()).isEqualTo(30);
        assertThat(activation.get().observedValues())
                .containsEntry("transactionCountInWindow", 5L)
                .containsEntry("threshold", 5);
    }

    @Test
    void noSeActivaConPocasTransaccionesEnLaVentana() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Transaction current = tx("tx2", "acc1", 1000, now, 4.7, -74.0, "m1", "5411");
        List<Transaction> history = List.of(
                tx("tx1", "acc1", 1000, now.minusSeconds(30), 4.7, -74.0, "m1", "5411")
        );

        assertThat(rule.evaluate(current, history)).isEmpty();
    }

    @Test
    void ignoraTransaccionesFueraDeLaVentana() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Transaction current = tx("tx5", "acc1", 1000, now, 4.7, -74.0, "m1", "5411");
        List<Transaction> history = List.of(
                tx("tx4", "acc1", 1000, now.minusSeconds(30), 4.7, -74.0, "m1", "5411"),
                tx("tx3", "acc1", 1000, now.minus(Duration.ofHours(2)), 4.7, -74.0, "m1", "5411"),
                tx("tx2", "acc1", 1000, now.minus(Duration.ofHours(3)), 4.7, -74.0, "m1", "5411"),
                tx("tx1", "acc1", 1000, now.minus(Duration.ofHours(4)), 4.7, -74.0, "m1", "5411")
        );

        assertThat(rule.evaluate(current, history)).isEmpty();
    }
}
