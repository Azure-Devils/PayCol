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
 * Entidad "Auditoría": registro INMUTABLE de cada cambio de estado de un caso
 * (qué cambió, quién y cuándo) — sección 2.2 del TDD de Semana 2. Guarda
 * {@code estadoAnterior}/{@code estadoNuevo} como texto plano (no FK al catálogo
 * {@code estados}) para que la fila siga siendo legible tal cual ocurrió el
 * cambio aunque el catálogo cambie en el futuro. La aplicación nunca hace
 * UPDATE/DELETE sobre esta tabla (solo INSERT) — ver nota de endurecimiento en
 * V1__init_casos.sql.
 */
@Entity
@Table(name = "auditoria_casos")
public class AuditoriaCasoEntity {

    @Id
    @Column(name = "auditoria_id", length = 36)
    private String auditoriaId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "caso_id", nullable = false)
    private CasoEntity caso;

    @Column(name = "estado_anterior", length = 30)
    private String estadoAnterior;

    @Column(name = "estado_nuevo", length = 30, nullable = false)
    private String estadoNuevo;

    @Column(name = "cambiado_por", length = 100, nullable = false)
    private String cambiadoPor;

    @Column(name = "cambiado_en", nullable = false)
    private OffsetDateTime cambiadoEn;

    @Column(name = "detalle")
    private String detalle;

    protected AuditoriaCasoEntity() {
        // JPA
    }

    public AuditoriaCasoEntity(String auditoriaId, CasoEntity caso, String estadoAnterior, String estadoNuevo,
                                String cambiadoPor, OffsetDateTime cambiadoEn, String detalle) {
        this.auditoriaId = auditoriaId;
        this.caso = caso;
        this.estadoAnterior = estadoAnterior;
        this.estadoNuevo = estadoNuevo;
        this.cambiadoPor = cambiadoPor;
        this.cambiadoEn = cambiadoEn;
        this.detalle = detalle;
    }

    public String getAuditoriaId() {
        return auditoriaId;
    }

    public CasoEntity getCaso() {
        return caso;
    }

    public String getEstadoAnterior() {
        return estadoAnterior;
    }

    public String getEstadoNuevo() {
        return estadoNuevo;
    }

    public String getCambiadoPor() {
        return cambiadoPor;
    }

    public OffsetDateTime getCambiadoEn() {
        return cambiadoEn;
    }

    public String getDetalle() {
        return detalle;
    }
}
