package com.centinela.api.infrastructure.adapter.outbound.azure;

import com.azure.core.util.Context;
import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.port.outbound.FraudCaseQueuePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Adaptador real de {@link FraudCaseQueuePort} (productor) sobre Azure Storage
 * Queue, cola {@code fraud-cases}. Lo invoca el motor de scoring cuando el score
 * de una transacción supera el umbral vigente.
 *
 * <p>La garantía de no pérdida de esta cola NO la da este productor (publicar es
 * publicar, punto), la da el patrón de consumo en
 * {@code infrastructure.adapter.inbound.messaging.FraudCaseQueueConsumer}: el
 * mensaje permanece en la cola hasta que el caso se persiste con éxito en el
 * almacén relacional, y solo entonces se borra.
 */
@Component
public class AzureFraudCaseQueueAdapter implements FraudCaseQueuePort {

    private static final Logger log = LoggerFactory.getLogger(AzureFraudCaseQueueAdapter.class);

    /**
     * Azure Storage Queue expira y BORRA SILENCIOSAMENTE cualquier mensaje al que no se le
     * indique {@code timeToLive} explícito al enviarlo — el default del servicio es 7 días
     * (ver la nota de {@code infra/storage-queue.bicep}, escrita por el equipo de DevOps/Infra). Para
     * "transaction-events" ese default es aceptable (es una notificación best-effort, ver
     * docs/decisions/002-semana2-scoring-mensajeria-y-casos.md), pero para "fraud-cases" es
     * INACEPTABLE: violaría directamente el requisito de cero pérdida si el consumidor de
     * casos estuviera caído más de 7 días. {@code Duration.ofSeconds(-1)} es el valor
     * sentinela documentado por la API de Storage Queue para "el mensaje nunca expira".
     */
    private static final Duration NEVER_EXPIRES = Duration.ofSeconds(-1);

    private final AzureQueueClients queues;
    private final ObjectMapper objectMapper;

    public AzureFraudCaseQueueAdapter(AzureQueueClients queues, ObjectMapper objectMapper) {
        this.queues = queues;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishCaseOpened(FraudCaseEvent event) {
        var client = queues.fraudCases();
        if (client.isEmpty()) {
            log.error("Cola 'fraud-cases' no configurada; el caso de la transacción {} (score {}) "
                    + "NO se encoló. Esto es una pérdida real de un caso marcado — requiere atención "
                    + "en cuanto la cola esté disponible.", event.transactionId(), event.score());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(event);
            String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            // visibilityTimeout=null (visible de inmediato), timeToLive=NEVER_EXPIRES (ver arriba),
            // timeout=null (usa el default del cliente HTTP, no el de la cola).
            client.get().sendMessageWithResponse(encoded, null, NEVER_EXPIRES, null, Context.NONE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "No se pudo serializar el FraudCaseEvent de la transacción " + event.transactionId(), e);
        }
    }
}
