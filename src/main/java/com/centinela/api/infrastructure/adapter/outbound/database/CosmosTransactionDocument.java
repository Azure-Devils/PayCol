package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import org.springframework.data.annotation.Id;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>TTL (Semana 2):</b> {@code timeToLive = 7_776_000} segundos (90 días). Justificación
 * (ver docs/decisions/002-semana2-scoring-mensajeria-y-casos.md para el detalle completo):
 * la regla de MONTO ATÍPICO necesita una línea base histórica razonable del comportamiento
 * de gasto de la cuenta (capturar patrones mensuales/estacionales), mientras que VELOCIDAD y
 * GEO-IMPOSIBLE solo miran los últimos minutos/la transacción inmediatamente anterior. 90 días
 * cubre generosamente el caso más exigente (monto atípico) sin acumular indefinidamente
 * almacenamiento del free tier (25 GB). El motor de scoring además acota cada consulta a
 * {@code historyLimit} documentos (ver {@code CosmosTransactionRepositoryAdapter}), así que el
 * TTL protege el costo de almacenamiento, no el de RU de cada consulta puntual.
 */
@Container(containerName = "transactions", timeToLive = CosmosTransactionDocument.TTL_SECONDS)
public class CosmosTransactionDocument {

    /** 90 días. Ver el javadoc de la clase para la justificación completa. */
    static final int TTL_SECONDS = 90 * 24 * 60 * 60;

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

    /**
     * Campos de scoring (Semana 2), poblados por el motor de scoring en un segundo
     * escritura (read-modify-write) DESPUÉS de que la ingesta ya insertó el documento
     * base — ver {@code CosmosTransactionRepositoryAdapter#saveScore}. Quedan en
     * {@code null}/vacío entre el instante en que la API responde y el instante en que
     * el consumidor de {@code transaction-events} procesa el evento; eso es exactamente
     * el desacoplamiento que exige la Semana 2.
     */
    private Integer score;
    private List<RuleActivationEmbedded> ruleActivations = new ArrayList<>();
    private OffsetDateTime scoredAt;

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

    public Integer getScore() {
        return score;
    }

    public List<RuleActivationEmbedded> getRuleActivations() {
        return ruleActivations;
    }

    public OffsetDateTime getScoredAt() {
        return scoredAt;
    }

    /**
     * Aplica el resultado del motor de scoring sobre un documento ya existente
     * (mutación in-place antes de volver a guardarlo con {@code save()}, que hace
     * upsert). No hay un setter por campo individual a propósito: el score y su
     * detalle siempre se escriben juntos, nunca parcialmente.
     */
    public void applyScore(int score, List<RuleActivationEmbedded> ruleActivations, OffsetDateTime scoredAt) {
        this.score = score;
        this.ruleActivations = new ArrayList<>(ruleActivations);
        this.scoredAt = scoredAt;
    }

    /**
     * Detalle de una regla activada, embebido dentro del documento de la transacción.
     * {@code observedValues} persiste los valores CONCRETOS observados (no solo el id
     * de la regla) — requisito explícito de la Semana 2 del que depende el explicador
     * de casos de la Semana 3 (ver {@link com.centinela.api.domain.model.RuleActivation}).
     */
    public static class RuleActivationEmbedded {
        private String ruleId;
        private int points;
        private Map<String, Object> observedValues;

        protected RuleActivationEmbedded() {
            // Requerido por el SDK de Cosmos para deserializar documentos leídos de la base.
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
