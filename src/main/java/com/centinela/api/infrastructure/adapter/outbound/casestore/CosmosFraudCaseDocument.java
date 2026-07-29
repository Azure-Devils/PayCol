package com.centinela.api.infrastructure.adapter.outbound.casestore;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import org.springframework.data.annotation.Id;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Documento de Cosmos DB para un caso de fraude (ver ADR
 * {@code docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md}). Reemplaza al
 * esquema relacional NUEVO de casos (Postgres, "casos"/"estados"/"auditoria_casos")
 * introducido en la Semana 2 y nunca llegó a desplegarse contra un servidor real: el
 * equipo decidió eliminar PostgreSQL del proyecto por completo antes de que eso
 * ocurriera, en vez de asumir su costo real (~12-13 USD/mes de Postgres Flexible
 * Server), y consolidar también los casos en el free tier permanente de Cosmos DB.
 *
 * <p><b>Partition key y {@code @Id}: {@code transactionId}</b> (mismo campo para
 * ambos, igual patrón que usa {@code CosmosCustomerDocument} con {@code customerId}).
 * A diferencia de {@code CosmosTransactionDocument} (que particiona por
 * {@code customerId} porque sus consultas dominantes son "todas las transacciones
 * recientes de una cuenta"), los dos únicos métodos de
 * {@link com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort}
 * ({@code existsByTransactionId}, {@code openCase}) operan siempre sobre
 * {@code transactionId} y nunca reciben el {@code customerId} como parámetro. El
 * esquema relacional legado además modelaba {@code transaction_id} como
 * {@code UNIQUE} (un caso por transacción). Usar {@code transactionId} como partition
 * key convierte el chequeo de idempotencia (¿ya existe un caso para esta
 * transacción?) y la apertura del caso en <b>point-reads/point-writes</b> — la
 * operación más barata posible en RU, sin recorrido cross-partition. Si eligiéramos
 * {@code customerId} en su lugar, {@code existsByTransactionId(transactionId)} no
 * tendría forma de saber en qué partición buscar (el puerto no recibe
 * {@code customerId}) y degeneraría en una consulta cross-partition en cada
 * evaluación de idempotencia — justo el patrón que el proyecto evita para
 * {@code transactions} (ver el cross-partition documentado y aceptado en
 * {@code CosmosTransactionRepository#findById}).
 *
 * <p><b>Trade-off aceptado a futuro:</b> si una semana posterior agrega un caso de
 * uso como "listar todos los casos de un cliente", esa consulta sería cross-partition
 * con este diseño (tendría que filtrar por {@code customerId} sin poder dirigirse a
 * una sola partición). Se acepta explícitamente porque hoy ningún puerto de dominio
 * necesita esa consulta — el mismo criterio de "no modelar para un acceso que no
 * existe todavía" que ya se aplicó al no migrar las entidades "Asignación" y
 * "Resolución" del esquema legado (ver más abajo).
 *
 * <p><b>Alcance deliberadamente reducido frente al modelo relacional legado:</b> las
 * entidades "Asignación" (analista-caso) y "Resolución" (decisión final) del esquema
 * Postgres nunca tuvieron un puerto/caso de uso de dominio que las poblara (sus propios
 * Javadocs las marcaban como "alcance de una semana posterior"). No se migran a Cosmos
 * ahora para evitar modelar campos sin consumidor; se agregarán como containers o
 * campos embebidos nuevos cuando exista el puerto correspondiente. Lo que sí se
 * conserva es el concepto de <b>auditoría inmutable</b> de cambios de estado: en vez
 * de una tabla aparte con FK (imposible en Cosmos sin joins), se embebe como un
 * arreglo de solo-append {@code auditTrail} dentro del propio documento del caso —
 * mismo patrón de denormalización que ya usa {@code CosmosTransactionDocument} para
 * embeber {@code location} dentro de la transacción.
 *
 * <p>Sin TTL: a diferencia del container {@code transactions} (que expira a los 90
 * días porque es evidencia con ventana temporal acotada), los casos de fraude son
 * registros de gestión que deben persistir mientras el caso exista, sin importar su
 * antigüedad.
 */
@Container(containerName = "cases")
public class CosmosFraudCaseDocument {

    /**
     * El {@code @Id} de Cosmos SIEMPRE se serializa como el campo "id" del documento
     * JSON. Coincide con la partition key (ver el javadoc de la clase) — mismo patrón
     * que {@code CosmosCustomerDocument#customerId}.
     */
    @Id
    @PartitionKey
    private String transactionId;

    /**
     * Identificador de negocio del caso (UUID generado al abrir, igual que
     * {@code CasoEntity#casoId} en el esquema legado). Deliberadamente distinto del
     * {@code @Id} de Cosmos: el {@code @Id}/partition key se elige por el patrón de
     * acceso (ver arriba), no por cuál es "el identificador natural" del caso para un
     * humano.
     */
    private String caseId;

    private String customerId;
    private int score;

    /** Serializa {@link com.centinela.api.domain.model.CaseStatus#name()}. */
    private String status;

    private OffsetDateTime openedAt;

    /**
     * Registro de auditoría embebido, solo-append (ver javadoc de la clase). El
     * código nunca reescribe ni elimina entradas existentes, solo agrega — la
     * inmutabilidad se sostiene por convención de la aplicación, igual que ocurría en
     * el esquema relacional legado (donde tampoco había UPDATE/DELETE sobre
     * {@code auditoria_casos}, solo INSERT).
     */
    private List<AuditEntryEmbedded> auditTrail = new ArrayList<>();

    protected CosmosFraudCaseDocument() {
        // Requerido por el SDK de Cosmos para deserializar documentos leídos de la base.
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

    /**
     * Entrada de auditoría embebida (equivalente a una fila de la extinta tabla
     * {@code auditoria_casos}). Igual que allí, {@code previousStatus} guarda texto
     * plano en vez de referenciar un catálogo, para que la entrada siga siendo legible
     * tal cual ocurrió el cambio aunque el catálogo de estados cambie en el futuro.
     */
    public static class AuditEntryEmbedded {
        private String previousStatus;
        private String newStatus;
        private String changedBy;
        private OffsetDateTime changedAt;
        private String detail;

        protected AuditEntryEmbedded() {
            // Requerido por el SDK de Cosmos para deserializar documentos leídos de la base.
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
