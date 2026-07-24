package com.centinela.api.infrastructure.adapter.outbound.casestore.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Entidad "Asignación" (relación caso-analista) del modelo mínimo de la sección
 * 2.2 del TDD de Semana 2. Su población (endpoints para asignar analistas a
 * casos) es alcance de una semana posterior de gestión de casos; esta Semana 2
 * solo deja el esquema listo.
 */
@Entity
@Table(name = "asignaciones")
public class AsignacionEntity {

    @Id
    @Column(name = "asignacion_id", length = 36)
    private String asignacionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "caso_id", nullable = false)
    private CasoEntity caso;

    @Column(name = "analista_id", length = 100, nullable = false)
    private String analistaId;

    @Column(name = "fecha_asignacion", nullable = false)
    private OffsetDateTime fechaAsignacion;

    protected AsignacionEntity() {
        // JPA
    }

    public AsignacionEntity(String asignacionId, CasoEntity caso, String analistaId,
                             OffsetDateTime fechaAsignacion) {
        this.asignacionId = asignacionId;
        this.caso = caso;
        this.analistaId = analistaId;
        this.fechaAsignacion = fechaAsignacion;
    }

    public String getAsignacionId() {
        return asignacionId;
    }

    public CasoEntity getCaso() {
        return caso;
    }

    public String getAnalistaId() {
        return analistaId;
    }

    public OffsetDateTime getFechaAsignacion() {
        return fechaAsignacion;
    }
}
