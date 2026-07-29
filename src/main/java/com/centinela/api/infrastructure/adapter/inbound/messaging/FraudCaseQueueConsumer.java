package com.centinela.api.infrastructure.adapter.inbound.messaging;

import com.azure.core.util.Context;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.port.inbound.OpenFraudCaseUseCase;
import com.centinela.api.infrastructure.adapter.outbound.azure.AzureQueueClients;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Adaptador de entrada que hace polling de la cola {@code fraud-cases} y abre el
 * caso correspondiente en el almacén de casos (Cosmos DB, container {@code cases} —
 * ver {@code CosmosFraudCaseRepositoryAdapter} y
 * docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md).
 *
 * <p><b>Garantía de no pérdida (requisito central de esta cola, sección 2.4 del
 * TDD):</b> el mensaje SOLO se borra de la cola después de que
 * {@link OpenFraudCaseUseCase#openCase(FraudCaseEvent)} retorna con éxito (es
 * decir, después de que el caso quedó persistido en Cosmos DB). Si este proceso
 * está caído, los mensajes simplemente se acumulan en la cola — Azure Storage
 * Queue los retiene hasta 7 días por defecto — y se procesan todos, sin pérdidas,
 * en cuanto el consumidor se restablece. Esto es exactamente lo que pide el
 * "Requerimiento de validación" de la sección 2.4: detener este componente no debe
 * afectar la ingesta (que sigue publicando en otra cola, {@code transaction-events},
 * de forma independiente) ni perder casos.
 */
@Component
public class FraudCaseQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudCaseQueueConsumer.class);
    private static final long MAX_DEQUEUE_COUNT = 10; // más tolerante que transaction-events:
                                                       // perder un caso es más grave que perder un score.

    private final AzureQueueClients queues;
    private final OpenFraudCaseUseCase openFraudCaseUseCase;
    private final ObjectMapper objectMapper;
    private final int maxMessagesPerPoll;
    private final Duration visibilityTimeout;

    public FraudCaseQueueConsumer(AzureQueueClients queues,
                                   OpenFraudCaseUseCase openFraudCaseUseCase,
                                   ObjectMapper objectMapper,
                                   @Value("${azure.queue.fraud-cases-max-messages-per-poll:10}") int maxMessagesPerPoll,
                                   @Value("${azure.queue.fraud-cases-visibility-timeout-seconds:60}") long visibilityTimeoutSeconds) {
        this.queues = queues;
        this.openFraudCaseUseCase = openFraudCaseUseCase;
        this.objectMapper = objectMapper;
        this.maxMessagesPerPoll = maxMessagesPerPoll;
        this.visibilityTimeout = Duration.ofSeconds(visibilityTimeoutSeconds);
    }

    @Scheduled(fixedDelayString = "${azure.queue.fraud-cases-poll-interval-ms:3000}")
    public void poll() {
        var maybeClient = queues.fraudCases();
        if (maybeClient.isEmpty()) {
            return; // cola no configurada todavía (ver AzureQueueClients).
        }
        QueueClient client = maybeClient.get();
        try {
            client.receiveMessages(maxMessagesPerPoll, visibilityTimeout, null, Context.NONE)
                    .forEach(message -> processMessage(client, message));
        } catch (Exception e) {
            log.error("Error al recibir mensajes de 'fraud-cases': {}", e.getMessage(), e);
        }
    }

    private void processMessage(QueueClient client, QueueMessageItem message) {
        try {
            FraudCaseEvent event = decode(message);
            openFraudCaseUseCase.openCase(event);
            // Solo se borra tras persistir con éxito: es la garantía de no pérdida de esta cola.
            client.deleteMessage(message.getMessageId(), message.getPopReceipt());
        } catch (Exception e) {
            if (message.getDequeueCount() > MAX_DEQUEUE_COUNT) {
                log.error("Mensaje {} de 'fraud-cases' superó el máximo de reintentos ({}). Se descarta SIN "
                                + "abrir el caso — requiere revisión manual. Causa: {}",
                        message.getMessageId(), MAX_DEQUEUE_COUNT, e.getMessage(), e);
                client.deleteMessage(message.getMessageId(), message.getPopReceipt());
            } else {
                log.warn("Fallo abriendo el caso del mensaje {} (intento {}/{}); NO se borra, quedará visible "
                                + "de nuevo tras el visibility timeout. Causa: {}",
                        message.getMessageId(), message.getDequeueCount(), MAX_DEQUEUE_COUNT, e.getMessage());
            }
        }
    }

    private FraudCaseEvent decode(QueueMessageItem message) throws Exception {
        String json = new String(Base64.getDecoder().decode(message.getMessageText()), StandardCharsets.UTF_8);
        return objectMapper.readValue(json, FraudCaseEvent.class);
    }
}
