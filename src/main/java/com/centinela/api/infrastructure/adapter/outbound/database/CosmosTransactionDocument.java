package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import org.springframework.data.annotation.Id;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Container(containerName = "transactions", timeToLive = CosmosTransactionDocument.TTL_SECONDS)
public class CosmosTransactionDocument {

    static final int TTL_SECONDS = 90 * 24 * 60 * 60;

    @Id
    private String id;

    @PartitionKey
    private String customerId;

    private long amountCents;
    private String currency;
    private OffsetDateTime transactionTimestamp;
    private OffsetDateTime ingestionTimestamp;
    private LocationEmbedded location;
    private String merchantId;
    private String merchantCategory;

    private Integer score;
    private List<RuleActivationEmbedded> ruleActivations = new ArrayList<>();
    private OffsetDateTime scoredAt;

    protected CosmosTransactionDocument() {
    }

    public CosmosTransactionDocument(String id, String customerId, long amountCents, String currency,
                                      OffsetDateTime transactionTimestamp, OffsetDateTime ingestionTimestamp,
                                      LocationEmbedded location, String merchantId, String merchantCategory) {
        this.id = id;
        this.customerId = customerId;
        this.amountCents = amountCents;
        this.currency = currency;
        this.transactionTimestamp = transactionTimestamp;
        this.ingestionTimestamp = ingestionTimestamp;
        this.location = location;
        this.merchantId = merchantId;
        this.merchantCategory = merchantCategory;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public OffsetDateTime getTransactionTimestamp() {
        return transactionTimestamp;
    }

    public OffsetDateTime getIngestionTimestamp() {
        return ingestionTimestamp;
    }

    public LocationEmbedded getLocation() {
        return location;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public Integer getScore() {
        return score;
    }

    public List<RuleActivationEmbedded> getRuleActivations() {
        return ruleActivations;
    }

    public OffsetDateTime getScoredAt() {
        return scoredAt;
    }

    public void applyScore(int score, List<RuleActivationEmbedded> ruleActivations, OffsetDateTime scoredAt) {
        this.score = score;
        this.ruleActivations = new ArrayList<>(ruleActivations);
        this.scoredAt = scoredAt;
    }

    public static class RuleActivationEmbedded {
        private String ruleId;
        private int points;
        private Map<String, Object> observedValues;

        protected RuleActivationEmbedded() {
        }

        public RuleActivationEmbedded(String ruleId, int points, Map<String, Object> observedValues) {
            this.ruleId = ruleId;
            this.points = points;
            this.observedValues = observedValues;
        }

        public String getRuleId() {
            return ruleId;
        }

        public int getPoints() {
            return points;
        }

        public Map<String, Object> getObservedValues() {
            return observedValues;
        }
    }

    public static class LocationEmbedded {
        private BigDecimal latitude;
        private BigDecimal longitude;
        private String description;

        protected LocationEmbedded() {
        }

        public LocationEmbedded(BigDecimal latitude, BigDecimal longitude, String description) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.description = description;
        }

        public BigDecimal getLatitude() {
            return latitude;
        }

        public BigDecimal getLongitude() {
            return longitude;
        }

        public String getDescription() {
            return description;
        }
    }
}
