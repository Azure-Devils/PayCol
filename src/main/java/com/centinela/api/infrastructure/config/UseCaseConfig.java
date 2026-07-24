package com.centinela.api.infrastructure.config;

import com.centinela.api.domain.port.inbound.GetTransactionUseCase;
import com.centinela.api.domain.port.inbound.IngestTransactionUseCase;
import com.centinela.api.domain.port.inbound.OpenFraudCaseUseCase;
import com.centinela.api.domain.port.inbound.ScoreTransactionUseCase;
import com.centinela.api.domain.port.outbound.FraudCaseQueuePort;
import com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import com.centinela.api.domain.port.outbound.ScoringThresholdPort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import com.centinela.api.domain.service.GetTransactionService;
import com.centinela.api.domain.service.IngestTransactionService;
import com.centinela.api.domain.service.OpenFraudCaseService;
import com.centinela.api.domain.service.ScoringEngineService;
import com.centinela.api.domain.service.rule.FraudRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Ensambla los casos de uso del dominio inyectando los adaptadores concretos
 * que implementan los puertos de salida. El dominio (IngestTransactionService,
 * ScoringEngineService, OpenFraudCaseService) no tiene anotaciones de Spring;
 * este es el único punto donde se conecta con el contenedor de inyección de
 * dependencias.
 */
@Configuration
public class UseCaseConfig {

    @Bean
    public IngestTransactionUseCase ingestTransactionUseCase(TransactionRepositoryPort transactionRepositoryPort,
                                                               MessageQueuePort messageQueuePort) {
        return new IngestTransactionService(transactionRepositoryPort, messageQueuePort);
    }

    @Bean
    public GetTransactionUseCase getTransactionUseCase(TransactionRepositoryPort transactionRepositoryPort) {
        return new GetTransactionService(transactionRepositoryPort);
    }

    /**
     * Invocado EXCLUSIVAMENTE por
     * {@code infrastructure.adapter.inbound.messaging.TransactionEventConsumer}
     * (ver el javadoc de {@link ScoreTransactionUseCase}) — nunca por el controller.
     */
    @Bean
    public ScoreTransactionUseCase scoreTransactionUseCase(TransactionRepositoryPort transactionRepositoryPort,
                                                            FraudCaseQueuePort fraudCaseQueuePort,
                                                            ScoringThresholdPort scoringThresholdPort,
                                                            List<FraudRule> fraudRules,
                                                            @Value("${centinela.scoring.history-limit:50}") int historyLimit) {
        return new ScoringEngineService(transactionRepositoryPort, fraudCaseQueuePort, scoringThresholdPort,
                fraudRules, historyLimit);
    }

    @Bean
    public OpenFraudCaseUseCase openFraudCaseUseCase(FraudCaseRepositoryPort fraudCaseRepositoryPort) {
        return new OpenFraudCaseService(fraudCaseRepositoryPort);
    }
}
