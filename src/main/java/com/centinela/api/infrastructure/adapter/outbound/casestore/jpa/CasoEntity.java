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
 * Entidad "Caso" del modelo mínimo (sección 2.2 del TDD de Semana 2): referencia a
 * la transacción (en Cosmos DB, por eso {@code transactionId} es solo un String,
 * sin FK real posible entre motores distintos), score obtenido, estado actual y
 * fecha de apertura.
 */
@Entity
@Table(name = "casos")
public class CasoEntity {

    @Id
    @Column(name = "caso_id", length = 36)
    private String casoId;

    @Column(name = "transaction_id", length = 100, nullable = false, unique = true)
    private String transactionId;

    @Column(name = "customer_id", length = 50, nullable = false)
    private String customerId;

    @Column(name = "score", nullable = false)
    private int score;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estado_id", nullable = false)
    private EstadoEntity estado;

    @Column(name = "fecha_apertura", nullable = false)
    private OffsetDateTime fechaApertura;

    protected CasoEntity() {
        // JPA
    }

    public CasoEntity(String casoId, String transactionId, String customerId, int score,
                       EstadoEntity estado, OffsetDateTime fechaApertura) {
        this.casoId = casoId;
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.score = score;
        this.estado = estado;
        this.fechaApertura = fechaApertura;
    }

    public String getCasoId() {
        return casoId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public int getScore() {
        return score;
    }

    public EstadoEntity getEstado() {
        return estado;
    }

    public OffsetDateTime getFechaApertura() {
        return fechaApertura;
    }
}
