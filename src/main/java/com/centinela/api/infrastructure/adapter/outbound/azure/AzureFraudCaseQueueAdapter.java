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

@Component
public class AzureFraudCaseQueueAdapter implements FraudCaseQueuePort {

    private static final Logger log = LoggerFactory.getLogger(AzureFraudCaseQueueAdapter.class);

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
            client.get().sendMessageWithResponse(encoded, null, NEVER_EXPIRES, null, Context.NONE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "No se pudo serializar el FraudCaseEvent de la transacción " + event.transactionId(), e);
        }
    }
}
