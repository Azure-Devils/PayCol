package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.ScoringRule;
import com.centinela.api.domain.model.Transaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RiskyMerchantRule implements FraudRule {

    private final Set<String> riskyMerchantIds;
    private final Set<String> riskyMerchantCategories;
    private final int points;

    public RiskyMerchantRule(Set<String> riskyMerchantIds, Set<String> riskyMerchantCategories, int points) {
        this.riskyMerchantIds = Set.copyOf(riskyMerchantIds);
        this.riskyMerchantCategories = Set.copyOf(riskyMerchantCategories);
        this.points = points;
    }

    @Override
    public Optional<RuleActivation> evaluate(Transaction current, List<Transaction> recentHistory) {
        boolean merchantFlagged = riskyMerchantIds.contains(current.merchantId());
        boolean categoryFlagged = riskyMerchantCategories.contains(current.merchantCategory());

        if (!merchantFlagged && !categoryFlagged) {
            return Optional.empty();
        }

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("merchantId", current.merchantId());
        observed.put("merchantCategory", current.merchantCategory());
        observed.put("matchedByMerchantId", merchantFlagged);
        observed.put("matchedByMerchantCategory", categoryFlagged);

        return Optional.of(new RuleActivation(ScoringRule.RISKY_MERCHANT, points, observed));
    }
}
