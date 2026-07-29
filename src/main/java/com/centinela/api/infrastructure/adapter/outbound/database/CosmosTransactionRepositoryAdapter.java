package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.cosmos.models.PartitionKey;
import com.centinela.api.domain.model.Customer;
import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.RuleActivation;
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

/**
 * Implementa {@link TransactionRepositoryPort} sobre Azure Cosmos DB vía Spring Data Cosmos.
 * Reemplazó a la extinta {@code PostgresTransactionRepositoryAdapter} (perfil
 * {@code postgres-legacy}, eliminada del repo junto con el resto de PostgreSQL — ver
 * docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md) como adaptador activo por
 * defecto — ver docs/decisions/001-migracion-postgresql-a-cosmosdb.md para el contexto
 * original de la migración.
 *
 * <p>El dominio ({@code TransactionRepositoryPort}, {@code Transaction}, {@code Location})
 * no cambió en absoluto: es exactamente la ventaja de la arquitectura hexagonal — solo se
 * reemplazó este adaptador de infraestructura.
 *
 * <p>Diferencia clave frente al extinto adaptador de Postgres: allí "ensureLocationExists"
 * buscaba una fila existente por (latitude, longitude) para reusar su location_id (evitando
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

    @Override
    public List<Transaction> findMostRecentByCustomer(String customerId, int limit) {
        // findAll(PartitionKey) es el método que expone CosmosRepository (heredado de
        // PagingAndSortingRepository) para iterar SOLO los documentos de una partición —
        // exactamente el requisito de la Semana 2: "consulta el historial de una única
        // cuenta", nunca un recorrido cross-partition. El orden/límite se aplican en memoria
        // porque acá no hace falta paginar contra Cosmos: el TTL del container (ver
        // CosmosTransactionDocument) ya acota el volumen por partición a una ventana de
        // 90 días, así que iterar toda la partición y ordenar en memoria es barato.
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
        // Point-read dirigido a la partición correcta (customerId), NO un findById cross-
        // partition: evita el costo cross-partition documentado en CosmosTransactionRepository
        // para la búsqueda por id que hace GetTransactionUseCase.
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

    private CosmosTransactionDocument.RuleActivationEmbedded toEmbedded(RuleActivation activation) {
        return new CosmosTransactionDocument.RuleActivationEmbedded(
                activation.rule().name(), activation.points(), activation.observedValues());
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
