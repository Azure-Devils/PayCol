package com.centinela.api.infrastructure.adapter.outbound.casestore.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Catálogo de estados posibles de un caso de fraude (tabla {@code estados} en el
 * Postgres NUEVO de gestión de casos — ver V1__init_casos.sql). Coincide 1:1 con
 * {@link com.centinela.api.domain.model.CaseStatus}.
 */
@Entity
@Table(name = "estados")
public class EstadoEntity {

    @Id
    @Column(name = "estado_id", length = 30)
    private String estadoId;

    @Column(name = "descripcion", nullable = false)
    private String descripcion;

    protected EstadoEntity() {
        // JPA
    }

    public EstadoEntity(String estadoId, String descripcion) {
        this.estadoId = estadoId;
        this.descripcion = descripcion;
    }

    public String getEstadoId() {
        return estadoId;
    }

    public String getDescripcion() {
        return descripcion;
    }
}
