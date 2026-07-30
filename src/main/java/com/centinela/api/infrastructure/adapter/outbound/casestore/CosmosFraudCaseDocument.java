package com.centinela.api.infrastructure.adapter.outbound.casestore;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import org.springframework.data.annotation.Id;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Container(containerName = "cases")
public class CosmosFraudCaseDocument {

    @Id
    @PartitionKey
    private String transactionId;

    private String caseId;

    private String customerId;
    private int score;

    private String status;

    private OffsetDateTime openedAt;

    private List<AuditEntryEmbedded> auditTrail = new ArrayList<>();

    protected CosmosFraudCaseDocument() {
    }

    public CosmosFraudCaseDocument(String transactionId, String caseId, String customerId, int score,
                                    String status, OffsetDateTime openedAt,
                                    List<AuditEntryEmbedded> auditTrail) {
        this.transactionId = transactionId;
        this.caseId = caseId;
        this.customerId = customerId;
        this.score = score;
        this.status = status;
        this.openedAt = openedAt;
        this.auditTrail = new ArrayList<>(auditTrail);
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public int getScore() {
        return score;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getOpenedAt() {
        return openedAt;
    }

    public List<AuditEntryEmbedded> getAuditTrail() {
        return auditTrail;
    }

    public static class AuditEntryEmbedded {
        private String previousStatus;
        private String newStatus;
        private String changedBy;
        private OffsetDateTime changedAt;
        private String detail;

        protected AuditEntryEmbedded() {
        }

        public AuditEntryEmbedded(String previousStatus, String newStatus, String changedBy,
                                   OffsetDateTime changedAt, String detail) {
            this.previousStatus = previousStatus;
            this.newStatus = newStatus;
            this.changedBy = changedBy;
            this.changedAt = changedAt;
            this.detail = detail;
        }

        public String getPreviousStatus() {
            return previousStatus;
        }

        public String getNewStatus() {
            return newStatus;
        }

        public String getChangedBy() {
            return changedBy;
        }

        public OffsetDateTime getChangedAt() {
            return changedAt;
        }

        public String getDetail() {
            return detail;
        }
    }
}
