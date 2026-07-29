package com.centinela.api.infrastructure.adapter.outbound.casestore;

import com.centinela.api.domain.model.CaseStatus;
import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Implementa {@link FraudCaseRepositoryPort} sobre Azure Cosmos DB (container
 * {@code cases}, ver {@link CosmosFraudCaseDocument} para el razonamiento completo de
 * modelado y partition key). Reemplaza a {@code JpaFraudCaseRepositoryAdapter}
 * (Postgres, eliminado — ver
 * {@code docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md}) como
 * implementación activa de este puerto. El dominio ({@code FraudCaseRepositoryPort},
 * {@code FraudCase}, {@code FraudCaseEvent}, {@code CaseStatus}) no cambió: es de
 * nuevo la ventaja de la arquitectura hexagonal — solo se reemplazó este adaptador de
 * infraestructura.
 *
 * <p>Lo invoca {@code infrastructure.adapter.inbound.messaging.FraudCaseQueueConsumer}
 * DESPUÉS de recibir un mensaje de la cola {@code fraud-cases}; solo si esta llamada
 * retorna con éxito el consumidor borra el mensaje de la cola (garantía de no
 * pérdida — ver esa clase). A diferencia del adaptador Postgres eliminado, aquí no
 * hace falta {@code @Transactional}: cada operación toca un único documento
 * (incluida la auditoría, embebida dentro del mismo documento), y una escritura de
 * documento en Cosmos ya es atómica por sí sola.
 */
@Component
public class CosmosFraudCaseRepositoryAdapter implements FraudCaseRepositoryPort {

    private static final String SYSTEM_ACTOR = "system:fraud-case-consumer";

    private final CosmosFraudCaseRepository cosmosFraudCaseRepository;

    public CosmosFraudCaseRepositoryAdapter(CosmosFraudCaseRepository cosmosFraudCaseRepository) {
        this.cosmosFraudCaseRepository = cosmosFraudCaseRepository;
    }

    @Override
    public boolean existsByTransactionId(String transactionId) {
        // Point-read: transactionId ES el id/partition key del documento (ver
        // CosmosFraudCaseDocument) -> la operación más barata posible en RU, sin
        // recorrido cross-partition.
        return cosmosFraudCaseRepository.existsById(transactionId);
    }

    @Override
    public FraudCase openCase(FraudCaseEvent event) {
        // Idempotencia: un redelivery del mismo mensaje (p.ej. el proceso murió después
        // de persistir pero antes de borrar el mensaje de la cola) no debe abrir un
        // caso duplicado. Al ser point-read (mismo argumento que existsByTransactionId
        // arriba), no cuesta más que el chequeo de idempotencia que ya hacía el
        // adaptador Postgres eliminado.
        return cosmosFraudCaseRepository.findById(event.transactionId())
                .map(this::toDomain)
                .orElseGet(() -> createCase(event));
    }

    private FraudCase createCase(FraudCaseEvent event) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Primer (y único, hasta que exista un caso de uso que haga transicionar el
        // estado) registro de auditoría: transición nula -> ABIERTO, igual que hacía
        // el adaptador Postgres eliminado con la extinta tabla auditoria_casos.
        CosmosFraudCaseDocument.AuditEntryEmbedded firstEntry = new CosmosFraudCaseDocument.AuditEntryEmbedded(
                null,
                CaseStatus.ABIERTO.name(),
                SYSTEM_ACTOR,
                now,
                "Caso abierto automáticamente por el motor de scoring (score=" + event.score() + ")"
        );

        CosmosFraudCaseDocument document = new CosmosFraudCaseDocument(
                event.transactionId(),
                UUID.randomUUID().toString(),
                event.customerId(),
                event.score(),
                CaseStatus.ABIERTO.name(),
                now,
                List.of(firstEntry)
        );

        CosmosFraudCaseDocument saved = cosmosFraudCaseRepository.save(document);
        return toDomain(saved);
    }

    private FraudCase toDomain(CosmosFraudCaseDocument document) {
        return new FraudCase(
                document.getCaseId(),
                document.getTransactionId(),
                document.getCustomerId(),
                document.getScore(),
                CaseStatus.valueOf(document.getStatus()),
                document.getOpenedAt().toInstant()
        );
    }
}
