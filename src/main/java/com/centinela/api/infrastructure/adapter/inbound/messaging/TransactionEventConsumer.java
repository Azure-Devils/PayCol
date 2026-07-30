package com.centinela.api.infrastructure.adapter.inbound.messaging;

import com.azure.core.util.Context;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.inbound.ScoreTransactionUseCase;
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
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);
    private static final long MAX_DEQUEUE_COUNT = 5;

    private final AzureQueueClients queues;
    private final ScoreTransactionUseCase scoreTransactionUseCase;
    private final ObjectMapper objectMapper;
    private final int maxMessagesPerPoll;
    private final Duration visibilityTimeout;
    private final String transactionQueueName;

    public TransactionEventConsumer(AzureQueueClients queues,
                                     ScoreTransactionUseCase scoreTransactionUseCase,
                                     ObjectMapper objectMapper,
                                     @Value("${azure.queue.transaction-events-max-messages-per-poll:10}") int maxMessagesPerPoll,
                                     @Value("${azure.queue.transaction-events-visibility-timeout-seconds:30}") long visibilityTimeoutSeconds,
                                     @Value("${azure.queue.transaction-events-name:transaction-ingest}") String transactionQueueName) {
        this.queues = queues;
        this.scoreTransactionUseCase = scoreTransactionUseCase;
        this.objectMapper = objectMapper;
        this.maxMessagesPerPoll = maxMessagesPerPoll;
        this.visibilityTimeout = Duration.ofSeconds(visibilityTimeoutSeconds);
        this.transactionQueueName = transactionQueueName;
    }

    @Scheduled(fixedDelayString = "${azure.queue.transaction-events-poll-interval-ms:2000}")
    public void poll() {
        var maybeClient = queues.transactionEvents();
        if (maybeClient.isEmpty()) {
            return;
        }
        QueueClient client = maybeClient.get();
        try {
            client.receiveMessages(maxMessagesPerPoll, visibilityTimeout, null, Context.NONE)
                    .forEach(message -> processMessage(client, message));
        } catch (Exception e) {
            log.error("Error al recibir mensajes de '{}': {}", transactionQueueName, e.getMessage(), e);
        }
    }

    private void processMessage(QueueClient client, QueueMessageItem message) {
        try {
            Transaction transaction = decode(message);
            scoreTransactionUseCase.score(transaction);
            client.deleteMessage(message.getMessageId(), message.getPopReceipt());
        } catch (Exception e) {
            if (message.getDequeueCount() > MAX_DEQUEUE_COUNT) {
                log.error("Mensaje {} de '{}' superó el máximo de reintentos ({}); se descarta "
                                + "sin puntuar. Causa: {}",
                        message.getMessageId(), transactionQueueName, MAX_DEQUEUE_COUNT, e.getMessage(), e);
                client.deleteMessage(message.getMessageId(), message.getPopReceipt());
            } else {
                log.warn("Fallo puntuando el mensaje {} (intento {}/{}); quedará visible de nuevo tras el "
                                + "visibility timeout. Causa: {}",
                        message.getMessageId(), message.getDequeueCount(), MAX_DEQUEUE_COUNT, e.getMessage());
            }
        }
    }

    private Transaction decode(QueueMessageItem message) throws Exception {
        String json = new String(Base64.getDecoder().decode(message.getMessageText()), StandardCharsets.UTF_8);
        return objectMapper.readValue(json, Transaction.class);
    }
}
