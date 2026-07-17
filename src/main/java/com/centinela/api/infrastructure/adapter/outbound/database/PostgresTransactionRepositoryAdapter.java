package com.centinela.api.infrastructure.adapter.outbound.database;

import com.centinela.api.domain.model.Customer;
import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Implementa {@link TransactionRepositoryPort} sobre Postgres vía Spring Data JPA.
 * Garantiza que customer y location existan (los crea si es necesario) antes de
 * insertar la transacción, ya que la tabla transactions tiene FKs a ambas.
 */
@Component
public class PostgresTransactionRepositoryAdapter implements TransactionRepositoryPort {

    private final TransactionJpaRepository transactionJpaRepository;
    private final CustomerJpaRepository customerJpaRepository;
    private final LocationJpaRepository locationJpaRepository;

    public PostgresTransactionRepositoryAdapter(TransactionJpaRepository transactionJpaRepository,
                                                  CustomerJpaRepository customerJpaRepository,
                                                  LocationJpaRepository locationJpaRepository) {
        this.transactionJpaRepository = transactionJpaRepository;
        this.customerJpaRepository = customerJpaRepository;
        this.locationJpaRepository = locationJpaRepository;
    }

    @Override
    public Optional<Transaction> findById(String transactionId) {
        return transactionJpaRepository.findById(transactionId)
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public Transaction save(Transaction transaction) {
        ensureCustomerExists(transaction.customerId());
        Integer locationId = ensureLocationExists(transaction.location());

        TransactionEntity entity = new TransactionEntity(
                transaction.transactionId(),
                transaction.customerId(),
                transaction.amountCents(),
                transaction.currency(),
                transaction.transactionTimestamp().atOffset(ZoneOffset.UTC),
                transaction.ingestionTimestamp() != null
                        ? transaction.ingestionTimestamp().atOffset(ZoneOffset.UTC)
                        : null,
                locationId,
                transaction.merchantId(),
                transaction.merchantCategory()
        );

        TransactionEntity saved = transactionJpaRepository.save(entity);
        Location savedLocation = new Location(locationId.longValue(), transaction.location().latitude(),
                transaction.location().longitude(), transaction.location().description());
        return toDomain(saved, savedLocation);
    }

    private void ensureCustomerExists(String customerId) {
        if (customerJpaRepository.existsById(customerId)) {
            return;
        }
        customerJpaRepository.save(new CustomerEntity(customerId, OffsetDateTime.now(ZoneOffset.UTC),
                Customer.DEFAULT_STATUS));
    }

    private Integer ensureLocationExists(Location location) {
        if (location.locationId() != null) {
            return location.locationId().intValue();
        }
        return locationJpaRepository.findByLatitudeAndLongitude(location.latitude(), location.longitude())
                .map(LocationEntity::getLocationId)
                .orElseGet(() -> locationJpaRepository.save(new LocationEntity(
                        location.latitude(), location.longitude(), location.description()
                )).getLocationId());
    }

    private Transaction toDomain(TransactionEntity entity) {
        LocationEntity locationEntity = locationJpaRepository.findById(entity.getLocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Location referenciada por la transacción no existe: " + entity.getLocationId()));
        Location location = new Location(locationEntity.getLocationId().longValue(), locationEntity.getLatitude(),
                locationEntity.getLongitude(), locationEntity.getDescription());
        return toDomain(entity, location);
    }

    private Transaction toDomain(TransactionEntity entity, Location location) {
        return new Transaction(
                entity.getTransactionId(),
                entity.getCustomerId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getTransactionTimestamp().toInstant(),
                entity.getIngestionTimestamp() != null ? entity.getIngestionTimestamp().toInstant() : null,
                location,
                entity.getMerchantId(),
                entity.getMerchantCategory()
        );
    }
}
