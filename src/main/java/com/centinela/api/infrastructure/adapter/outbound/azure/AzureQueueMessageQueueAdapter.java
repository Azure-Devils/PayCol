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

/**
 * Adaptador real de {@link MessageQueuePort} sobre Azure Storage Queue. Reemplaza al
 * antiguo {@code NoOpMessageQueueAdapter} de la Semana 1.
 *
 * <p><b>Distribución del evento de transacción, no garantía de procesamiento.</b>
 * Este mecanismo solo NOTIFICA al motor de scoring que hay una transacción nueva;
 * si nadie la consume nunca, no pasa nada grave (el peor caso es que esa
 * transacción puntual no se puntúa) — por eso alcanza con Azure Storage Queue en
 * vez de un mecanismo con reintentos/DLQ más sofisticado. Contraste con
 * {@link AzureFraudCaseQueueAdapter}, que sí requiere esa garantía. Ver
 * docs/decisions/002-semana2-scoring-mensajeria-y-casos.md.
 *
 * <p><b>No bloquea más de lo necesario (sección 2.5 del TDD):</b> {@code sendMessage}
 * es una única llamada HTTP síncrona al Storage Queue — se espera su confirmación
 * (para no perder el evento silenciosamente), pero en ningún momento se espera al
 * motor de scoring, que corre en otro proceso lógico (el consumidor, ver
 * {@code infrastructure.adapter.inbound.messaging.TransactionEventConsumer}).
 */
@Component
public class AzureQueueMessageQueueAdapter implements MessageQueuePort {

    private static final Logger log = LoggerFactory.getLogger(AzureQueueMessageQueueAdapter.class);

    private final AzureQueueClients queues;
    private final ObjectMapper objectMapper;

    public AzureQueueMessageQueueAdapter(AzureQueueClients queues, ObjectMapper objectMapper) {
        this.queues = queues;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(Transaction transaction) {
        var client = queues.transactionEvents();
        if (client.isEmpty()) {
            log.warn("Cola 'transaction-events' no configurada; transacción {} no se publicó "
                    + "(el motor de scoring no la procesará hasta que la cola esté disponible).",
                    transaction.transactionId());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(transaction);
            // Azure Storage Queue transporta el mensaje como texto/XML; Base64 evita problemas
            // con caracteres especiales del JSON (es la práctica recomendada por el SDK).
            String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            client.get().sendMessage(encoded);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "No se pudo serializar la transacción " + transaction.transactionId()
                            + " para publicarla en 'transaction-events'", e);
        }
    }
}
