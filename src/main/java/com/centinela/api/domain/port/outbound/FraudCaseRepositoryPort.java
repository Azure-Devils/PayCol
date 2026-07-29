package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;

/**
 * Puerto de salida hacia el almacén de casos de fraude (sección 2.2 del TDD de
 * Semana 2). Lo implementa {@code CosmosFraudCaseRepositoryAdapter}, sobre un
 * container de Cosmos DB ({@code cases}) separado del container
 * {@code transactions} — ver {@code infrastructure.adapter.outbound.casestore} y
 * docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md. Este puerto llegó a
 * tener una implementación relacional (Postgres) durante la Semana 2, pero ese
 * Postgres se eliminó del proyecto por completo antes de desplegarse contra un
 * servidor real.
 */
public interface FraudCaseRepositoryPort {

    /**
     * Verifica si ya existe un caso abierto para esta transacción. Necesario para
     * que el consumidor de {@code fraud-cases} sea idempotente: un redelivery del
     * mismo mensaje (por ejemplo, tras un fallo justo después de persistir pero
     * antes de borrar el mensaje de la cola) no debe crear un caso duplicado.
     */
    boolean existsByTransactionId(String transactionId);

    /**
     * Crea el caso en estado {@link com.centinela.api.domain.model.CaseStatus#ABIERTO}
     * junto con el primer registro de auditoría (transición nula -> ABIERTO).
     */
    FraudCase openCase(FraudCaseEvent event);
}
