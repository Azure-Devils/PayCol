package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;

/**
 * Puerto de salida hacia el almacén relacional de casos de fraude (sección 2.2
 * del TDD de Semana 2). Lo implementa un adaptador JPA/Postgres NUEVO y distinto
 * al esquema legado de transacciones (ver
 * {@code infrastructure.adapter.outbound.casestore}) — este Postgres gestiona
 * casos, no reemplaza a Cosmos DB como almacén de transacciones.
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
