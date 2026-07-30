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

@Component
public class CosmosFraudCaseRepositoryAdapter implements FraudCaseRepositoryPort {

    private static final String SYSTEM_ACTOR = "system:fraud-case-consumer";

    private final CosmosFraudCaseRepository cosmosFraudCaseRepository;

    public CosmosFraudCaseRepositoryAdapter(CosmosFraudCaseRepository cosmosFraudCaseRepository) {
        this.cosmosFraudCaseRepository = cosmosFraudCaseRepository;
    }

    @Override
    public boolean existsByTransactionId(String transactionId) {
        return cosmosFraudCaseRepository.existsById(transactionId);
    }

    @Override
    public FraudCase openCase(FraudCaseEvent event) {
        return cosmosFraudCaseRepository.findById(event.transactionId())
                .map(this::toDomain)
                .orElseGet(() -> createCase(event));
    }

    private FraudCase createCase(FraudCaseEvent event) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

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
