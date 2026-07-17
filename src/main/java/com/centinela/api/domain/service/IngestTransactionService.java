package com.centinela.api.domain.service;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.inbound.IngestTransactionUseCase;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementación del caso de uso de ingesta. Lógica de dominio pura:
 * no importa Spring ni el SDK de Azure.
 *
 * Responsabilidades (sección 4 del TDD):
 *  - Validar el contrato de negocio.
 *  - Capturar el timestamp de servidor (ingestion_timestamp) ignorando
 *    cualquier valor que pudiera venir del cliente para ese campo.
 *  - Garantizar idempotencia: si el transaction_id ya fue ingerido, no se
 *    duplica ni se genera un nuevo id.
 */
public class IngestTransactionService implements IngestTransactionUseCase {

    private final TransactionRepositoryPort transactionRepository;
    private final MessageQueuePort messageQueue;

    public IngestTransactionService(TransactionRepositoryPort transactionRepository,
                                     MessageQueuePort messageQueue) {
        this.transactionRepository = transactionRepository;
        this.messageQueue = messageQueue;
    }

    @Override
    public Transaction ingest(Transaction transaction) {
        validate(transaction);

        Optional<Transaction> existing = transactionRepository.findById(transaction.transactionId());
        if (existing.isPresent()) {
            // Idempotencia: la misma transaction_id ya fue procesada, no se re-inserta.
            return existing.get();
        }

        Transaction withServerTimestamp = transaction.withIngestionTimestamp(Instant.now());
        Transaction persisted = transactionRepository.save(withServerTimestamp);
        messageQueue.publish(persisted);
        return persisted;
    }

    private void validate(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction no puede ser null");
        require(transaction.transactionId() != null && !transaction.transactionId().isBlank(),
                "transactionId es obligatorio");
        require(transaction.customerId() != null && !transaction.customerId().isBlank(),
                "customerId es obligatorio");
        require(transaction.amountCents() > 0, "amountCents debe ser mayor que cero");
        require(transaction.currency() != null && transaction.currency().length() == 3,
                "currency debe ser un código ISO 4217 de 3 letras");
        require(transaction.transactionTimestamp() != null,
                "transactionTimestamp es obligatorio");
        require(transaction.location() != null, "location es obligatorio");
        require(transaction.merchantId() != null && !transaction.merchantId().isBlank(),
                "merchantId es obligatorio");
        require(transaction.merchantCategory() != null && !transaction.merchantCategory().isBlank(),
                "merchantCategory es obligatorio");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
