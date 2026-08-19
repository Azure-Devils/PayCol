package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.centinela.api.domain.service.rule.RuleTestSupport.tx;
import static org.assertj.core.api.Assertions.assertThat;

class ImpossibleGeoRuleTest {

    private final ImpossibleGeoRule rule = new ImpossibleGeoRule(900.0, 40);

    @Test
    void seActivaCuandoLaVelocidadImplicitaEsFisicamenteImposible() {
        Instant t1 = Instant.parse("2026-07-24T10:00:00Z");
        Instant t2 = t1.plusSeconds(600);

        Transaction previous = tx("prev", "acc1", 1000, t1, 4.710989, -74.072092, "m1", "5411");
        Transaction current = tx("cur", "acc1", 1000, t2, 35.6762, 139.6503, "m1", "5411");

        Optional<RuleActivation> activation = rule.evaluate(current, List.of(previous));

        assertThat(activation).isPresent();
        assertThat(activation.get().points()).isEqualTo(40);
        assertThat((double) activation.get().observedValues().get("impliedSpeedKmh")).isGreaterThan(900.0);
    }

    @Test
    void noSeActivaConUnDesplazamientoPlausible() {
        Instant t1 = Instant.parse("2026-07-24T10:00:00Z");
        Instant t2 = t1.plusSeconds(3600);

        Transaction previous = tx("prev", "acc1", 1000, t1, 4.710989, -74.072092, "m1", "5411");
        Transaction current = tx("cur", "acc1", 1000, t2, 4.720989, -74.082092, "m1", "5411");

        assertThat(rule.evaluate(current, List.of(previous))).isEmpty();
    }

    @Test
    void noSeActivaSinTransaccionAnterior() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Transaction current = tx("cur", "acc1", 1000, now, 4.710989, -74.072092, "m1", "5411");

        assertThat(rule.evaluate(current, List.of())).isEmpty();
    }
}
