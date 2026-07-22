package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import org.springframework.data.annotation.Id;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Documento de Cosmos DB para una transacción. Es el equivalente a {@link TransactionEntity}
 * (JPA), pero para una base de datos NoSQL orientada a documentos en vez de una tabla
 * relacional. Conceptos nuevos frente a Postgres/JPA, explicados para quien nunca usó Cosmos:
 *
 * <p><b>Container:</b> es el equivalente aproximado a una "tabla", pero sin un esquema fijo:
 * cada documento del container puede tener campos distintos. Aquí lo usamos con un esquema
 * consistente (todos los campos de {@link com.centinela.api.domain.model.Transaction}) solo
 * porque nos conviene, no porque Cosmos lo exija.
 *
 * <p><b>Partition key:</b> Cosmos DB no es una sola base de datos, sino que reparte
 * físicamente los documentos entre varios nodos ("particiones") según el valor de un campo
 * elegido de antemano — la partition key. Todas las consultas que filtran por esa key son
 * baratas (van directo a la partición correcta); las que NO la usan (como buscar por
 * transactionId sin saber el customerId) recorren TODAS las particiones y cuestan más RU
 * (Request Units, la moneda de costo/rendimiento de Cosmos).
 *
 * <p>Elegimos <b>{@code customerId}</b> como partition key (no {@code transactionId}, que es
 * el {@code @Id}) porque las reglas de velocidad/fraude de las próximas semanas necesitan
 * "todas las transacciones de un mismo cliente ordenadas por tiempo" — exactamente la consulta
 * que el índice {@code idx_tx_customer_time} optimizaba en Postgres. Con {@code customerId}
 * como partition key, esa consulta cae dentro de una sola partición y es barata. La
 * contrapartida: buscar una transacción por su {@code transactionId} sin conocer el
 * {@code customerId} (lo que hace {@code GetTransactionUseCase} hoy) se vuelve una consulta
 * cross-partition, más cara en RU — ver el comentario en {@link CosmosTransactionRepository}.
 *
 * <p>A diferencia del modelo relacional en 3FN que teníamos, aquí <b>denormalizamos</b>: la
 * ubicación ({@code location}) se embebe completa dentro del documento de la transacción en
 * vez de vivir en un container/tabla aparte con foreign key. Es la forma idiomática de
 * modelar "uno-a-uno" en NoSQL — evita una consulta extra (Cosmos no soporta JOIN entre
 * containers), a costa de duplicar los datos de ubicación si dos transacciones ocurren en el
 * mismo lugar. Esa duplicación es aceptable aquí porque una ubicación pesa pocos bytes.
 */
@Container(containerName = "transactions")
public class CosmosTransactionDocument {

    /**
     * El {@code @Id} de Cosmos SIEMPRE se serializa como el campo "id" del documento JSON.
     * Usamos el transactionId de negocio como id (igual que en Postgres) para mantener la
     * idempotencia: reinsertar el mismo transactionId hace un upsert, no un duplicado.
     */
    @Id
    private String id;

    /**
     * Partition key física del container (ver el comentario de la clase). Se guarda también
     * como campo normal del documento, no solo como metadato, porque Cosmos lo necesita para
     * poder ubicar el documento en cada operación de escritura/lectura puntual.
     */
    @PartitionKey
    private String customerId;

    private long amountCents;
    private String currency;
    private OffsetDateTime transactionTimestamp;
    private OffsetDateTime ingestionTimestamp;
    private LocationEmbedded location;
    private String merchantId;
    private String merchantCategory;

    protected CosmosTransactionDocument() {
        // Requerido por el SDK de Cosmos para deserializar documentos leídos de la base.
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

    /**
     * Ubicación embebida dentro del documento de la transacción (ver comentario de
     * denormalización en {@link CosmosTransactionDocument}). A diferencia de
     * {@link LocationEntity} en Postgres, no tiene su propio id: no es una fila en otra
     * tabla, es solo un objeto JSON anidado dentro de este documento. Por eso tampoco existe
     * ya la restricción {@code UNIQUE (latitude, longitude)} que había en Postgres — en
     * Cosmos esa unicidad tendría que resolverse a nivel de aplicación si se necesitara,
     * porque Cosmos solo soporta "unique keys" dentro de una misma partición, no globales.
     */
    public static class LocationEmbedded {
        private BigDecimal latitude;
        private BigDecimal longitude;
        private String description;

        protected LocationEmbedded() {
            // Requerido por el SDK de Cosmos para deserializar documentos leídos de la base.
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
