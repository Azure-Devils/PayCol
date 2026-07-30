package com.centinela.api.domain.service;

import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.ScoringRule;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionReviewStatus;
import com.centinela.api.domain.model.TransactionScore;
import com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GetTransactionReviewStatusServiceTest {

    @Test
    void devuelveUnderReviewCuandoHayUnCasoDeFraudeAbierto() {
        FakeFraudCaseRepository fraudCaseRepository = new FakeFraudCaseRepository();
        fraudCaseRepository.markCaseOpen("tx-1");
        FakeTransactionRepository transactionRepository = new FakeTransactionRepository();
        transactionRepository.seedScore(score("tx-1"));

        GetTransactionReviewStatusService service =
                new GetTransactionReviewStatusService(fraudCaseRepository, transactionRepository);

        assertThat(service.getStatus("tx-1")).isEqualTo(TransactionReviewStatus.UNDER_REVIEW);
    }

    @Test
    void devuelveApprovedCuandoYaSeScoreoYNoHayCasoAbierto() {
        FakeFraudCaseRepository fraudCaseRepository = new FakeFraudCaseRepository();
        FakeTransactionRepository transactionRepository = new FakeTransactionRepository();
        transactionRepository.seedScore(score("tx-2"));

        GetTransactionReviewStatusService service =
                new GetTransactionReviewStatusService(fraudCaseRepository, transactionRepository);

        assertThat(service.getStatus("tx-2")).isEqualTo(TransactionReviewStatus.APPROVED);
    }

    @Test
    void devuelveUnderReviewCuandoTodaviaNoSeScoreo() {
        FakeFraudCaseRepository fraudCaseRepository = new FakeFraudCaseRepository();
        FakeTransactionRepository transactionRepository = new FakeTransactionRepository();

        GetTransactionReviewStatusService service =
                new GetTransactionReviewStatusService(fraudCaseRepository, transactionRepository);

        assertThat(service.getStatus("tx-3")).isEqualTo(TransactionReviewStatus.UNDER_REVIEW);
    }

    private static TransactionScore score(String transactionId) {
        RuleActivation activation = new RuleActivation(ScoringRule.VELOCITY, 30, Map.of("count", 5));
        return new TransactionScore(transactionId, "acc1", 30, List.of(activation),
                Instant.parse("2026-07-24T10:00:00Z"));
    }

    private static class FakeFraudCaseRepository implements FraudCaseRepositoryPort {
        private final Set<String> openCases = new HashSet<>();

        void markCaseOpen(String transactionId) {
            openCases.add(transactionId);
        }

        @Override
        public boolean existsByTransactionId(String transactionId) {
            return openCases.contains(transactionId);
        }

        @Override
        public FraudCase openCase(FraudCaseEvent event) {
            openCases.add(event.transactionId());
            return new FraudCase(event.transactionId(), event.transactionId(), event.customerId(),
                    event.score(), null, event.openedAt());
        }
    }

    private static class FakeTransactionRepository implements TransactionRepositoryPort {
        private final List<TransactionScore> scores = new ArrayList<>();

        void seedScore(TransactionScore score) {
            scores.add(score);
        }

        @Override
        public Optional<Transaction> findById(String transactionId) {
            return Optional.empty();
        }

        @Override
        public Transaction save(Transaction transaction) {
            return transaction;
        }

        @Override
        public List<Transaction> findMostRecentByCustomer(String customerId, int limit) {
            return List.of();
        }

        @Override
        public void saveScore(TransactionScore score) {
            scores.add(score);
        }

        @Override
        public Optional<TransactionScore> findScore(String transactionId) {
            return scores.stream()
                    .filter(score -> score.transactionId().equals(transactionId))
                    .findFirst();
        }
    }
}
