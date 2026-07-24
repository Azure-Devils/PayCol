package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida hacia la persistencia de transacciones.
 * El dominio no conoce JPA ni el SDK de Cosmos; lo implementa un adaptador en infrastructure.
 */
public interface TransactionRepositoryPort {

    Optional<Transaction> findById(String transactionId);

    Transaction save(Transaction transaction);

    /**
     * Historial reciente de UNA cuenta, ordenado del más nuevo al más viejo, acotado
     * a lo sumo a {@code limit} transacciones. Es la única consulta que usa el motor
     * de scoring (sección 2.3 del TDD de Semana 2) y debe resolverse SIEMPRE dentro
     * de la partición de {@code customerId} — nunca como un recorrido cross-partition.
     * El límite existe para acotar el consumo de RU/lectura sin importar cuán larga
     * sea la historia de la cuenta (ver justificación de la política de expiración en
     * docs/decisions).
     */
    List<Transaction> findMostRecentByCustomer(String customerId, int limit);

    /**
     * Persiste el score total y el detalle de reglas activadas calculado por el
     * motor de scoring, junto al documento de la transacción ya existente
     * (identificada por {@code transactionId}/{@code customerId}). Esta escritura
     * ocurre siempre DESPUÉS de que la API ya respondió al cliente (ver
     * {@code ScoringEngineService}), nunca en el camino síncrono de la ingesta.
     */
    void saveScore(TransactionScore score);
}
