package com.centinela.api.domain.service;

import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;
import com.centinela.api.domain.port.inbound.ScoreTransactionUseCase;
import com.centinela.api.domain.port.outbound.FraudCaseQueuePort;
import com.centinela.api.domain.port.outbound.ScoringThresholdPort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import com.centinela.api.domain.service.rule.FraudRule;

import java.time.Instant;
import java.util.List;

public class ScoringEngineService implements ScoreTransactionUseCase {

    private final TransactionRepositoryPort transactionRepository;
    private final FraudCaseQueuePort fraudCaseQueue;
    private final ScoringThresholdPort thresholdPort;
    private final List<FraudRule> rules;
    private final int historyLimit;

    public ScoringEngineService(TransactionRepositoryPort transactionRepository,
                                 FraudCaseQueuePort fraudCaseQueue,
                                 ScoringThresholdPort thresholdPort,
                                 List<FraudRule> rules,
                                 int historyLimit) {
        this.transactionRepository = transactionRepository;
        this.fraudCaseQueue = fraudCaseQueue;
        this.thresholdPort = thresholdPort;
        this.rules = List.copyOf(rules);
        this.historyLimit = historyLimit;
    }

    @Override
    public TransactionScore score(Transaction transaction) {
        List<Transaction> history = transactionRepository
                .findMostRecentByCustomer(transaction.customerId(), historyLimit)
                .stream()
                .filter(t -> !t.transactionId().equals(transaction.transactionId()))
                .toList();

        List<RuleActivation> activations = rules.stream()
                .map(rule -> rule.evaluate(transaction, history))
                .flatMap(java.util.Optional::stream)
                .toList();

        int totalScore = activations.stream().mapToInt(RuleActivation::points).sum();

        TransactionScore score = new TransactionScore(
                transaction.transactionId(),
                transaction.customerId(),
                totalScore,
                activations,
                Instant.now()
        );

        transactionRepository.saveScore(score);

        int threshold = thresholdPort.currentThreshold();
        if (totalScore >= threshold) {
            fraudCaseQueue.publishCaseOpened(new FraudCaseEvent(
                    transaction.transactionId(),
                    transaction.customerId(),
                    totalScore,
                    activations,
                    Instant.now()
            ));
        }

        return score;
    }
}
