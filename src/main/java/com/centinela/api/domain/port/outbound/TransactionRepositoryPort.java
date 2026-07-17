package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.Transaction;

import java.util.Optional;

/**
 * Puerto de salida hacia la persistencia de transacciones.
 * El dominio no conoce JPA; lo implementa un adaptador en infrastructure.
 */
public interface TransactionRepositoryPort {

    Optional<Transaction> findById(String transactionId);

    Transaction save(Transaction transaction);
}
