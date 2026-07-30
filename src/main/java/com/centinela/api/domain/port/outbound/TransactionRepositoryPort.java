package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;

import java.util.List;
import java.util.Optional;

public interface TransactionRepositoryPort {

    Optional<Transaction> findById(String transactionId);

    Transaction save(Transaction transaction);

    List<Transaction> findMostRecentByCustomer(String customerId, int limit);

    void saveScore(TransactionScore score);
}
