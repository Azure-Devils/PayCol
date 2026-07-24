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

/**
 * Motor de scoring (sección 2.3 del TDD de Semana 2). Lógica de dominio pura:
 * no importa Spring ni el SDK de Azure. Quien lo invoca (un adaptador de
 * mensajería asíncrono, nunca el controller) es responsabilidad de
 * infrastructure — ver el javadoc de {@link ScoreTransactionUseCase}.
 *
 * Secuencia (idéntica a la del doc):
 *  1. Recibe la transacción ya persistida (se la pasa el llamador).
 *  2. Consulta el historial reciente de la cuenta, SOLO por partition key
 *     ({@code customerId}) — ver {@link TransactionRepositoryPort#findMostRecentByCustomer}.
 *  3. Evalúa las cuatro reglas.
 *  4. Suma los puntos de las reglas activadas.
 *  5. Persiste el score y el detalle junto a la transacción.
 *  6. Si el score supera el umbral vigente, publica un mensaje de apertura de caso.
 */
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
        // Paso 2: SOLO por partition key (customerId). Acotado a historyLimit
        // transacciones para mantener el consumo de RU predecible sin importar
        // la antigüedad de la cuenta (ver docs/decisions).
        List<Transaction> history = transactionRepository
                .findMostRecentByCustomer(transaction.customerId(), historyLimit)
                .stream()
                .filter(t -> !t.transactionId().equals(transaction.transactionId()))
                .toList();

        // Pasos 3 y 4.
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

        // Paso 5.
        transactionRepository.saveScore(score);

        // Paso 6: el umbral se lee en el momento de evaluar, nunca cacheado
        // (ver ScoringThresholdPort), para que un cambio en Key Vault se
        // refleje sin redespliegue.
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
