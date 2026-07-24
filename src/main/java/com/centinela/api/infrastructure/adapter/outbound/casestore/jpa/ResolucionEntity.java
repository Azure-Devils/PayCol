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
 * Entidad "Resolución" (decisión final, analista responsable, fecha, observaciones)
 * del modelo mínimo de la sección 2.2 del TDD de Semana 2. Igual que
 * {@link AsignacionEntity}, su población es alcance de una semana posterior; aquí
 * solo se deja el esquema listo.
 */
@Entity
@Table(name = "resoluciones")
public class ResolucionEntity {

    @Id
    @Column(name = "resolucion_id", length = 36)
    private String resolucionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "caso_id", nullable = false, unique = true)
    private CasoEntity caso;

    @Column(name = "decision", length = 30, nullable = false)
    private String decision;

    @Column(name = "analista_id", length = 100, nullable = false)
    private String analistaId;

    @Column(name = "fecha_resolucion", nullable = false)
    private OffsetDateTime fechaResolucion;

    @Column(name = "observaciones")
    private String observaciones;

    protected ResolucionEntity() {
        // JPA
    }

    public ResolucionEntity(String resolucionId, CasoEntity caso, String decision, String analistaId,
                             OffsetDateTime fechaResolucion, String observaciones) {
        this.resolucionId = resolucionId;
        this.caso = caso;
        this.decision = decision;
        this.analistaId = analistaId;
        this.fechaResolucion = fechaResolucion;
        this.observaciones = observaciones;
    }

    public String getResolucionId() {
        return resolucionId;
    }

    public CasoEntity getCaso() {
        return caso;
    }

    public String getDecision() {
        return decision;
    }

    public String getAnalistaId() {
        return analistaId;
    }

    public OffsetDateTime getFechaResolucion() {
        return fechaResolucion;
    }

    public String getObservaciones() {
        return observaciones;
    }
}
