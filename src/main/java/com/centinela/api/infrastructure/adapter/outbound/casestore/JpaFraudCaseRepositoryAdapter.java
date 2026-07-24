package com.centinela.api.infrastructure.adapter.outbound.casestore;

import com.centinela.api.domain.model.CaseStatus;
import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort;
import com.centinela.api.infrastructure.adapter.outbound.casestore.jpa.AuditoriaCasoEntity;
import com.centinela.api.infrastructure.adapter.outbound.casestore.jpa.AuditoriaCasoJpaRepository;
import com.centinela.api.infrastructure.adapter.outbound.casestore.jpa.CasoEntity;
import com.centinela.api.infrastructure.adapter.outbound.casestore.jpa.CasoJpaRepository;
import com.centinela.api.infrastructure.adapter.outbound.casestore.jpa.EstadoEntity;
import com.centinela.api.infrastructure.adapter.outbound.casestore.jpa.EstadoJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Implementa {@link FraudCaseRepositoryPort} sobre el almacén relacional NUEVO de
 * casos de fraude (Postgres, esquema {@code db/casestore/migration}) — ver
 * docs/decisions/002-semana2-scoring-mensajeria-y-casos.md. No tiene relación con
 * el adaptador Postgres legado de transacciones ({@code PostgresTransactionRepositoryAdapter}):
 * usa un DataSource/esquema completamente distinto.
 *
 * <p>Lo invoca {@code infrastructure.adapter.inbound.messaging.FraudCaseQueueConsumer}
 * DESPUÉS de recibir un mensaje de la cola {@code fraud-cases}; solo si esta llamada
 * retorna con éxito el consumidor borra el mensaje de la cola (garantía de no
 * pérdida — ver esa clase).
 */
@Component
public class JpaFraudCaseRepositoryAdapter implements FraudCaseRepositoryPort {

    private static final String SYSTEM_ACTOR = "system:fraud-case-consumer";

    private final CasoJpaRepository casoJpaRepository;
    private final EstadoJpaRepository estadoJpaRepository;
    private final AuditoriaCasoJpaRepository auditoriaCasoJpaRepository;

    public JpaFraudCaseRepositoryAdapter(CasoJpaRepository casoJpaRepository,
                                          EstadoJpaRepository estadoJpaRepository,
                                          AuditoriaCasoJpaRepository auditoriaCasoJpaRepository) {
        this.casoJpaRepository = casoJpaRepository;
        this.estadoJpaRepository = estadoJpaRepository;
        this.auditoriaCasoJpaRepository = auditoriaCasoJpaRepository;
    }

    @Override
    public boolean existsByTransactionId(String transactionId) {
        return casoJpaRepository.existsByTransactionId(transactionId);
    }

    @Override
    @Transactional
    public FraudCase openCase(FraudCaseEvent event) {
        // Idempotencia: un redelivery del mismo mensaje (p.ej. el proceso murió después de
        // persistir pero antes de borrar el mensaje de la cola) no debe abrir un caso duplicado.
        var existing = casoJpaRepository.findByTransactionId(event.transactionId());
        if (existing.isPresent()) {
            return toDomain(existing.get());
        }

        EstadoEntity abierto = estadoJpaRepository.findById(CaseStatus.ABIERTO.name())
                .orElseThrow(() -> new IllegalStateException(
                        "Catálogo de estados sin '" + CaseStatus.ABIERTO.name()
                                + "' — falta aplicar V1__init_casos.sql"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CasoEntity caso = new CasoEntity(
                UUID.randomUUID().toString(),
                event.transactionId(),
                event.customerId(),
                event.score(),
                abierto,
                now
        );
        CasoEntity saved = casoJpaRepository.save(caso);

        // Primer registro de auditoría: transición nula -> ABIERTO, inmutable desde este momento.
        AuditoriaCasoEntity auditoria = new AuditoriaCasoEntity(
                UUID.randomUUID().toString(),
                saved,
                null,
                CaseStatus.ABIERTO.name(),
                SYSTEM_ACTOR,
                now,
                "Caso abierto automáticamente por el motor de scoring (score=" + event.score() + ")"
        );
        auditoriaCasoJpaRepository.save(auditoria);

        return toDomain(saved);
    }

    private FraudCase toDomain(CasoEntity entity) {
        return new FraudCase(
                entity.getCasoId(),
                entity.getTransactionId(),
                entity.getCustomerId(),
                entity.getScore(),
                CaseStatus.valueOf(entity.getEstado().getEstadoId()),
                entity.getFechaApertura().toInstant()
        );
    }
}
