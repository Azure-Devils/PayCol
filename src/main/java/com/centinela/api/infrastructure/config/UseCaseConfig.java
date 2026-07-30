package com.centinela.api.infrastructure.config;

import com.centinela.api.domain.port.inbound.GetTransactionReceiptUseCase;
import com.centinela.api.domain.port.inbound.GetTransactionReviewStatusUseCase;
import com.centinela.api.domain.port.inbound.GetTransactionUseCase;
import com.centinela.api.domain.port.inbound.IngestTransactionUseCase;
import com.centinela.api.domain.port.inbound.OpenFraudCaseUseCase;
import com.centinela.api.domain.port.inbound.ScoreTransactionUseCase;
import com.centinela.api.domain.port.outbound.FraudCaseQueuePort;
import com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import com.centinela.api.domain.port.outbound.ScoringThresholdPort;
import com.centinela.api.domain.port.outbound.TransactionReceiptStoragePort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import com.centinela.api.domain.service.GetTransactionReceiptService;
import com.centinela.api.domain.service.GetTransactionReviewStatusService;
import com.centinela.api.domain.service.GetTransactionService;
import com.centinela.api.domain.service.IngestTransactionService;
import com.centinela.api.domain.service.OpenFraudCaseService;
import com.centinela.api.domain.service.ScoringEngineService;
import com.centinela.api.domain.service.TransactionReceiptGenerator;
import com.centinela.api.domain.service.rule.FraudRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class UseCaseConfig {

    @Bean
    public TransactionReceiptGenerator transactionReceiptGenerator() {
        return new TransactionReceiptGenerator();
    }

    @Bean
    public IngestTransactionUseCase ingestTransactionUseCase(TransactionRepositoryPort transactionRepositoryPort,
                                                               MessageQueuePort messageQueuePort,
                                                               TransactionReceiptGenerator transactionReceiptGenerator,
                                                               TransactionReceiptStoragePort transactionReceiptStoragePort) {
        return new IngestTransactionService(transactionRepositoryPort, messageQueuePort,
                transactionReceiptGenerator, transactionReceiptStoragePort);
    }

    @Bean
    public GetTransactionUseCase getTransactionUseCase(TransactionRepositoryPort transactionRepositoryPort) {
        return new GetTransactionService(transactionRepositoryPort);
    }

    @Bean
    public GetTransactionReceiptUseCase getTransactionReceiptUseCase(
            TransactionReceiptStoragePort transactionReceiptStoragePort) {
        return new GetTransactionReceiptService(transactionReceiptStoragePort);
    }

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

    @Bean
    public GetTransactionReviewStatusUseCase getTransactionReviewStatusUseCase(
            FraudCaseRepositoryPort fraudCaseRepositoryPort, TransactionRepositoryPort transactionRepositoryPort) {
        return new GetTransactionReviewStatusService(fraudCaseRepositoryPort, transactionRepositoryPort);
    }
}
