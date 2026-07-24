package com.centinela.api.domain.model;

/**
 * Catálogo de estados posibles de un caso de fraude (entidad "Estado" del modelo
 * mínimo de la sección 2.2 del TDD de Semana 2). Vive en el dominio porque es un
 * concepto de negocio, aunque el almacén de casos (JPA/Postgres, ver
 * {@code infrastructure.adapter.outbound.casestore}) lo persista además como fila
 * de una tabla catálogo ({@code estados}) para poder referenciarlo con integridad
 * referencial.
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
