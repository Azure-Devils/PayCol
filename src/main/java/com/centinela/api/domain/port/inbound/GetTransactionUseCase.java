package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.Transaction;

import java.util.Optional;

/**
 * Caso de uso de entrada: consulta de una transacción ya persistida
 * por su {@code transactionId} (clave de negocio provista por el emisor).
 */
public interface GetTransactionUseCase {

    /**
     * Busca una transacción por su transactionId. Operación de solo lectura,
     * sin efectos secundarios sobre la persistencia.
     */
    Optional<Transaction> getById(String transactionId);
}
