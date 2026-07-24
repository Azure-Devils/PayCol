package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.centinela.api.domain.service.rule.RuleTestSupport.tx;
import static org.assertj.core.api.Assertions.assertThat;

class RiskyMerchantRuleTest {

    private final RiskyMerchantRule rule = new RiskyMerchantRule(Set.of("merch_bad"), Set.of("7995"), 50);

    @Test
    void seActivaPorMerchantIdMarcado() {
        Transaction current = tx("cur", "acc1", 1000, Instant.now(), 4.7, -74.0, "merch_bad", "5411");

        Optional<RuleActivation> activation = rule.evaluate(current, List.of());

        assertThat(activation).isPresent();
        assertThat(activation.get().observedValues()).containsEntry("matchedByMerchantId", true);
    }

    @Test
    void seActivaPorCategoriaMarcada() {
        Transaction current = tx("cur", "acc1", 1000, Instant.now(), 4.7, -74.0, "merch_ok", "7995");

        Optional<RuleActivation> activation = rule.evaluate(current, List.of());

        assertThat(activation).isPresent();
        assertThat(activation.get().observedValues()).containsEntry("matchedByMerchantCategory", true);
    }

    @Test
    void noSeActivaSiNoEstaMarcado() {
        Transaction current = tx("cur", "acc1", 1000, Instant.now(), 4.7, -74.0, "merch_ok", "5411");

        assertThat(rule.evaluate(current, List.of())).isEmpty();
    }
}
