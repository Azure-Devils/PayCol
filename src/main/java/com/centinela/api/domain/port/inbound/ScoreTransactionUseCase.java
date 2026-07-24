package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;

/**
 * Caso de uso de entrada: puntúa una transacción ya persistida contra el
 * historial reciente de su cuenta.
 *
 * <p><b>Nunca lo invoca el controller.</b> Es el error de diseño más frecuente
 * de la Semana 2 (sección 5 del TDD): si la API llama este caso de uso
 * directamente y espera su resultado, dos transacciones en paralelo dejan de
 * estar desacopladas y la API queda bloqueada por la duración del scoring.
 * El único llamador legítimo es un adaptador de entrada asíncrono que reacciona
 * al evento de la cola {@code transaction-events} — ver
 * {@code infrastructure.adapter.inbound.messaging.TransactionEventConsumer}.
 */
public interface ScoreTransactionUseCase {

    TransactionScore score(Transaction transaction);
}
