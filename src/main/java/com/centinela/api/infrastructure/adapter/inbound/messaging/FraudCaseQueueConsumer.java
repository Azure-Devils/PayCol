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

@Component
public class FraudCaseQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(FraudCaseQueueConsumer.class);
    private static final long MAX_DEQUEUE_COUNT = 10;

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
            return;
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
