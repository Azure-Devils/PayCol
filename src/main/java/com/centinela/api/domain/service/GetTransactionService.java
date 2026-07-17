package com.centinela.api.domain.service;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.inbound.GetTransactionUseCase;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;

import java.util.Optional;

/**
 * Implementación del caso de uso de consulta. Lógica de dominio pura:
 * no importa Spring ni el SDK de Azure.
 *
 * Delega directamente en el puerto de salida; una consulta simple por
 * clave primaria no requiere lógica de negocio adicional.
 */
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
