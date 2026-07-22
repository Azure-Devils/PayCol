package com.centinela.api.infrastructure.adapter.outbound.database;

import com.centinela.api.domain.model.Customer;
import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Implementa {@link TransactionRepositoryPort} sobre Azure Cosmos DB vía Spring Data Cosmos.
 * Reemplaza a {@link PostgresTransactionRepositoryAdapter} (que se conserva aislado, ver esa
 * clase) como adaptador activo por defecto — ver
 * docs/decisions/001-migracion-postgresql-a-cosmosdb.md para el contexto de la migración.
 *
 * <p>El dominio ({@code TransactionRepositoryPort}, {@code Transaction}, {@code Location})
 * no cambió en absoluto: es exactamente la ventaja de la arquitectura hexagonal — solo se
 * reemplazó este adaptador de infraestructura.
 *
 * <p>Diferencia clave frente al adaptador de Postgres: allí "ensureLocationExists" buscaba
 * una fila existente por (latitude, longitude) para reusar su location_id (evitando
 * duplicados gracias a la restricción UNIQUE de la tabla). En Cosmos no hay tabla de
 * ubicaciones ni esa restricción (la ubicación va embebida en cada documento de transacción,
 * ver {@link CosmosTransactionDocument}), así que ese paso de deduplicación simplemente
 * desaparece: cada transacción guarda su propia copia de la ubicación tal cual llegó.
 */
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
        // Cross-partition: ver el comentario sobre costo en CosmosTransactionRepository.
        return cosmosTransactionRepository.findById(transactionId)
                .map(this::toDomain);
    }

    @Override
    public Transaction save(Transaction transaction) {
        // A diferencia de una transacción SQL (@Transactional en el adapter de Postgres),
        // Cosmos DB no ofrece transacciones multi-documento entre containers distintos
        // (customers y transactions). Guardamos el customer primero y luego la transacción;
        // si el proceso fallara justo entre medio, quedaría un customer "huérfano" sin
        // transacciones, lo cual es inofensivo (a diferencia de una transacción sin
        // customer, que sí sería un problema de integridad). Este es un trade-off aceptado
        // explícitamente al migrar de un motor relacional a uno de documentos.
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

        // save() en Cosmos es un upsert: si ya existe un documento con este id (dentro de la
        // misma partición customerId), lo reemplaza en vez de fallar por clave duplicada.
        // Esto preserva la idempotencia por transactionId que exige el proyecto.
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

    private Transaction toDomain(CosmosTransactionDocument document) {
        // locationId queda en null: en Cosmos la ubicación ya no es una entidad propia con
        // id (ver el javadoc de CosmosTransactionDocument.LocationEmbedded), es solo un
        // objeto embebido dentro de la transacción.
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
