package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.Transaction;

/**
 * Caso de uso de entrada: ingesta y persistencia de una transacción cruda.
 */
public interface IngestTransactionUseCase {

    /**
     * Valida el contrato de negocio, captura el timestamp de servidor
     * (ingestion_timestamp) y persiste la transacción de forma idempotente
     * usando {@code transactionId} como clave de negocio.
     */
    Transaction ingest(Transaction transaction);
}
