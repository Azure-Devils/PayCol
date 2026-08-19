package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.Transaction;

import java.util.List;
import java.util.Optional;

public interface FraudRule {

    Optional<RuleActivation> evaluate(Transaction current, List<Transaction> recentHistory);
}
