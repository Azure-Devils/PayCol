package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.Transaction;

import java.util.Optional;

public interface GetTransactionUseCase {

    Optional<Transaction> getById(String transactionId);
}
