package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.cosmos.models.PartitionKey;
import com.centinela.api.domain.model.Customer;
import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.RuleActivation;
import com.centinela.api.domain.model.ScoringRule;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Component
public class CosmosTransactionRepositoryAdapter implements TransactionRepositoryPort {

    private final CosmosTransactionRepository cosmosTransactionRepository;
    private final CosmosCustomerRepository cosmosCustomerRepository;

    public CosmosTransactionRepositoryAdapter(CosmosTransactionRepository cosmosTransactionRepository,
                                               CosmosCustomerRepository cosmosCustomerRepository) {
        this.cosmosTransactionRepository = cosmosTransactionRepository;
        this.cosmosCustomerRepository = cosmosCustomerRepository;
    }

    @Override
    public Optional<Transaction> findById(String transactionId) {
        return cosmosTransactionRepository.findById(transactionId)
                .map(this::toDomain);
    }

    @Override
    public Transaction save(Transaction transaction) {
        ensureCustomerExists(transaction.customerId());

        CosmosTransactionDocument.LocationEmbedded location = new CosmosTransactionDocument.LocationEmbedded(
                transaction.location().latitude(),
                transaction.location().longitude(),
                transaction.location().description()
        );

        CosmosTransactionDocument document = new CosmosTransactionDocument(
                transaction.transactionId(),
                transaction.customerId(),
                transaction.amountCents(),
                transaction.currency(),
                transaction.transactionTimestamp().atOffset(ZoneOffset.UTC),
                transaction.ingestionTimestamp() != null
                        ? transaction.ingestionTimestamp().atOffset(ZoneOffset.UTC)
                        : null,
                location,
                transaction.merchantId(),
                transaction.merchantCategory()
        );

        CosmosTransactionDocument saved = cosmosTransactionRepository.save(document);
        return toDomain(saved);
    }

    private void ensureCustomerExists(String customerId) {
        if (cosmosCustomerRepository.existsById(customerId)) {
            return;
        }
        cosmosCustomerRepository.save(new CosmosCustomerDocument(
                customerId, OffsetDateTime.now(ZoneOffset.UTC), Customer.DEFAULT_STATUS));
    }

    @Override
    public List<Transaction> findMostRecentByCustomer(String customerId, int limit) {
        Iterable<CosmosTransactionDocument> partitionDocuments =
                cosmosTransactionRepository.findAll(new PartitionKey(customerId));

        return StreamSupport.stream(partitionDocuments.spliterator(), false)
                .sorted(Comparator.comparing(CosmosTransactionDocument::getTransactionTimestamp).reversed())
                .limit(limit)
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void saveScore(TransactionScore score) {
        CosmosTransactionDocument document = cosmosTransactionRepository
                .findById(score.transactionId(), new PartitionKey(score.customerId()))
                .orElseThrow(() -> new IllegalStateException(
                        "No se encontró la transacción " + score.transactionId()
                                + " para persistir el score (¿se llamó antes de que la ingesta la persistiera?)"));

        List<CosmosTransactionDocument.RuleActivationEmbedded> activations = score.activations().stream()
                .map(this::toEmbedded)
                .toList();

        document.applyScore(score.totalScore(), activations, score.scoredAt().atOffset(ZoneOffset.UTC));
        cosmosTransactionRepository.save(document);
    }

    @Override
    public Optional<TransactionScore> findScore(String transactionId) {
        return cosmosTransactionRepository.findById(transactionId)
                .filter(document -> document.getScore() != null)
                .map(this::toScore);
    }

    private CosmosTransactionDocument.RuleActivationEmbedded toEmbedded(RuleActivation activation) {
        return new CosmosTransactionDocument.RuleActivationEmbedded(
                activation.rule().name(), activation.points(), activation.observedValues());
    }

    private TransactionScore toScore(CosmosTransactionDocument document) {
        List<RuleActivation> activations = document.getRuleActivations().stream()
                .map(embedded -> new RuleActivation(
                        ScoringRule.valueOf(embedded.getRuleId()),
                        embedded.getPoints(),
                        embedded.getObservedValues()))
                .toList();

        return new TransactionScore(
                document.getId(),
                document.getCustomerId(),
                document.getScore(),
                activations,
                document.getScoredAt().toInstant());
    }

    private Transaction toDomain(CosmosTransactionDocument document) {
        Location location = new Location(
                null,
                document.getLocation().getLatitude(),
                document.getLocation().getLongitude(),
                document.getLocation().getDescription()
        );

        return new Transaction(
                document.getId(),
                document.getCustomerId(),
                document.getAmountCents(),
                document.getCurrency(),
                document.getTransactionTimestamp().toInstant(),
                document.getIngestionTimestamp() != null ? document.getIngestionTimestamp().toInstant() : null,
                location,
                document.getMerchantId(),
                document.getMerchantCategory()
        );
    }
}
