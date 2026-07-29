package com.centinela.api.domain.model;

/**
 * Catálogo de estados posibles de un caso de fraude (concepto "Estado" del modelo
 * mínimo de la sección 2.2 del TDD de Semana 2). Vive en el dominio porque es un
 * concepto de negocio. El almacén de casos (Cosmos DB, container {@code cases} —
 * ver {@code infrastructure.adapter.outbound.casestore} y
 * docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md) lo persiste como
 * texto plano ({@code CosmosFraudCaseDocument#status}), no como fila de un catálogo
 * relacional con integridad referencial (ese catálogo, {@code estados}, existió
 * brevemente en un esquema Postgres que se eliminó del proyecto antes de
 * desplegarse).
 */
public enum CaseStatus {

    /** Caso recién creado por el consumidor de {@code fraud-cases}, sin asignar. */
    ABIERTO,

    /** Un analista lo tomó y lo está evaluando. */
    EN_REVISION,

    /** Un analista confirmó que la transacción es fraudulenta. */
    CONFIRMADO_FRAUDE,

    /** Un analista determinó que la activación fue un falso positivo. */
    DESCARTADO,

    /** Caso resuelto y cerrado (terminal). */
    CERRADO
}
