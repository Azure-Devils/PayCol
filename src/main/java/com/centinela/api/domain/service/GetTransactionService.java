package com.centinela.api.domain.service;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.inbound.GetTransactionUseCase;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;

import java.util.Optional;

public class GetTransactionService implements GetTransactionUseCase {

    private final TransactionRepositoryPort transactionRepository;

    public GetTransactionService(TransactionRepositoryPort transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Optional<Transaction> getById(String transactionId) {
        return transactionRepository.findById(transactionId);
    }
}
