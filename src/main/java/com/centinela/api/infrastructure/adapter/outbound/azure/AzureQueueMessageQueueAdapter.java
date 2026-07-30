package com.centinela.api.infrastructure.adapter.outbound.azure;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class AzureQueueMessageQueueAdapter implements MessageQueuePort {

    private static final Logger log = LoggerFactory.getLogger(AzureQueueMessageQueueAdapter.class);

    private final AzureQueueClients queues;
    private final ObjectMapper objectMapper;
    private final String transactionQueueName;

    public AzureQueueMessageQueueAdapter(AzureQueueClients queues, ObjectMapper objectMapper,
                                         @org.springframework.beans.factory.annotation.Value("${azure.queue.transaction-events-name:transaction-ingest}")
                                         String transactionQueueName) {
        this.queues = queues;
        this.objectMapper = objectMapper;
        this.transactionQueueName = transactionQueueName;
    }

    @Override
    public void publish(Transaction transaction) {
        var client = queues.transactionEvents();
        if (client.isEmpty()) {
            log.warn("Cola '{}' no configurada; transacción {} no se publicó "
                    + "(el motor de scoring no la procesará hasta que la cola esté disponible).",
                    transactionQueueName, transaction.transactionId());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(transaction);
            String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            client.get().sendMessage(encoded);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "No se pudo serializar la transacción " + transaction.transactionId()
                            + " para publicarla en '" + transactionQueueName + "'", e);
        }
    }
}
