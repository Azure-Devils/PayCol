package com.centinela.api.domain.model;

import java.time.Instant;

/**
 * Caso de fraude persistido en el almacén de casos (ver sección 2.2 del TDD de
 * Semana 2). Nació como un esquema relacional nuevo en Postgres, pero ese Postgres
 * se eliminó del proyecto antes de desplegarse contra un servidor real — el almacén
 * de casos vive ahora en Cosmos DB, container {@code cases} (ver
 * {@code CosmosFraudCaseRepositoryAdapter} y
 * docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md). Es la vista de
 * dominio del caso; el detalle de reglas activadas ya vive junto a la transacción en
 * Cosmos DB (ver {@link TransactionScore}) — este record solo referencia la
 * transacción por id, evitando duplicar esa información en dos containers.
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
