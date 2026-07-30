package com.centinela.api.domain.service;

import com.centinela.api.domain.model.TransactionReviewStatus;
import com.centinela.api.domain.port.inbound.GetTransactionReviewStatusUseCase;
import com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;

public class GetTransactionReviewStatusService implements GetTransactionReviewStatusUseCase {

    private final FraudCaseRepositoryPort fraudCaseRepositoryPort;
    private final TransactionRepositoryPort transactionRepositoryPort;

    public GetTransactionReviewStatusService(FraudCaseRepositoryPort fraudCaseRepositoryPort,
                                              TransactionRepositoryPort transactionRepositoryPort) {
        this.fraudCaseRepositoryPort = fraudCaseRepositoryPort;
        this.transactionRepositoryPort = transactionRepositoryPort;
    }

    @Override
    public TransactionReviewStatus getStatus(String transactionId) {
        if (fraudCaseRepositoryPort.existsByTransactionId(transactionId)) {
            return TransactionReviewStatus.UNDER_REVIEW;
        }
        if (transactionRepositoryPort.findScore(transactionId).isPresent()) {
            return TransactionReviewStatus.APPROVED;
        }
        return TransactionReviewStatus.UNDER_REVIEW;
    }
}
