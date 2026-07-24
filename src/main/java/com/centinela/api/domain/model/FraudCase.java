package com.centinela.api.domain.model;

import java.time.Instant;

/**
 * Caso de fraude persistido en el almacén relacional de casos (ver sección 2.2
 * del TDD de Semana 2). Es la vista de dominio de la entidad "Caso"; el detalle
 * de reglas activadas ya vive junto a la transacción en Cosmos DB (ver
 * {@link TransactionScore}) — este record solo referencia la transacción por id,
 * evitando duplicar esa información en dos almacenes.
 */
public record FraudCase(
        String caseId,
        String transactionId,
        String customerId,
        int score,
        CaseStatus status,
        Instant openedAt
) {
}
