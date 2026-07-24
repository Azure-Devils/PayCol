package com.centinela.api.domain.service;

import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;
import com.centinela.api.domain.port.outbound.FraudCaseQueuePort;
import com.centinela.api.domain.port.outbound.ScoringThresholdPort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import com.centinela.api.domain.service.rule.AnomalousAmountRule;
import com.centinela.api.domain.service.rule.FraudRule;
import com.centinela.api.domain.service.rule.ImpossibleGeoRule;
import com.centinela.api.domain.service.rule.RiskyMerchantRule;
import com.centinela.api.domain.service.rule.VelocityRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el ensamblaje del motor de scoring (suma de reglas + publicación condicional)
 * con dobles de prueba escritos a mano para los puertos de salida -- coherente con el
 * espíritu del dominio hexagonal: nada de Spring/Mockito necesario para probar esta lógica.
 */
class ScoringEngineServiceTest {

    private static Transaction tx(String id, long amountCents, Instant ts, double lat, double lon,
                                   String merchantId, String merchantCategory) {
        Location location = new Location(null, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon), "test");
        return new Transaction(id, "acc1", amountCents, "COP", ts, ts, location, merchantId, merchantCategory);
    }

    private static List<FraudRule> defaultRules() {
        return List.of(
                new VelocityRule(Duration.ofMinutes(2), 5, 30),
                new AnomalousAmountRule(3, 5.0, 25),
                new ImpossibleGeoRule(900.0, 40),
                new RiskyMerchantRule(Set.of("merch_bad"), Set.of(), 50)
        );
    }

    @Test
    void publicaCasoDeFraudeCuandoElScoreSuperaElUmbral() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingFraudCaseQueue fraudCaseQueue = new RecordingFraudCaseQueue();
        ScoringEngineService engine = new ScoringEngineService(
                repository, fraudCaseQueue, () -> 50, defaultRules(), 50);

        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Transaction current = tx("cur", 1000, now, 4.7, -74.0, "merch_bad", "5411");
        repository.seed(current);

        TransactionScore score = engine.score(current);

        assertThat(score.totalScore()).isEqualTo(50); // solo comercio de riesgo
        assertThat(fraudCaseQueue.published).hasSize(1);
        assertThat(fraudCaseQueue.published.get(0).score()).isEqualTo(50);
        assertThat(repository.savedScores).hasSize(1);
    }

    @Test
    void noPublicaCasoCuandoElScoreNoSuperaElUmbral() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingFraudCaseQueue fraudCaseQueue = new RecordingFraudCaseQueue();
        ScoringEngineService engine = new ScoringEngineService(
                repository, fraudCaseQueue, () -> 60, defaultRules(), 50);

        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Transaction current = tx("cur", 1000, now, 4.7, -74.0, "merch_ok", "5411");
        repository.seed(current);

        TransactionScore score = engine.score(current);

        assertThat(score.totalScore()).isEqualTo(0);
        assertThat(fraudCaseQueue.published).isEmpty();
        assertThat(repository.savedScores).hasSize(1);
    }

    @Test
    void elUmbralSeConsultaEnCadaEvaluacion() {
        InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
        RecordingFraudCaseQueue fraudCaseQueue = new RecordingFraudCaseQueue();
        int[] threshold = {100}; // por encima del score posible: no debería publicar
        ScoringEngineService engine = new ScoringEngineService(
                repository, fraudCaseQueue, () -> threshold[0], defaultRules(), 50);

        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        Transaction current = tx("cur", 1000, now, 4.7, -74.0, "merch_bad", "5411");
        repository.seed(current);

        engine.score(current);
        assertThat(fraudCaseQueue.published).isEmpty();

        threshold[0] = 10; // "cambia en Key Vault" sin reiniciar nada
        engine.score(current);
        assertThat(fraudCaseQueue.published).hasSize(1);
    }

    /** Doble de prueba mínimo de {@link TransactionRepositoryPort}. */
    private static class InMemoryTransactionRepository implements TransactionRepositoryPort {
        private final List<Transaction> stored = new ArrayList<>();
        private final List<TransactionScore> savedScores = new ArrayList<>();

        void seed(Transaction transaction) {
            stored.add(transaction);
        }

        @Override
        public Optional<Transaction> findById(String transactionId) {
            return stored.stream().filter(t -> t.transactionId().equals(transactionId)).findFirst();
        }

        @Override
        public Transaction save(Transaction transaction) {
            stored.add(transaction);
            return transaction;
        }

        @Override
        public List<Transaction> findMostRecentByCustomer(String customerId, int limit) {
            return stored.stream()
                    .filter(t -> t.customerId().equals(customerId))
                    .sorted((a, b) -> b.transactionTimestamp().compareTo(a.transactionTimestamp()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void saveScore(TransactionScore score) {
            savedScores.add(score);
        }
    }

    private static class RecordingFraudCaseQueue implements FraudCaseQueuePort {
        private final List<FraudCaseEvent> published = new ArrayList<>();

        @Override
        public void publishCaseOpened(FraudCaseEvent event) {
            published.add(event);
        }
    }
}
