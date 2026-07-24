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

/**
 * Adaptador de ENTRADA (sí, aunque viva bajo {@code infrastructure}, es un
 * "driving adapter" en términos hexagonales: reacciona a un evento externo e
 * invoca un caso de uso del dominio) que hace polling de la cola
 * {@code transaction-events} y activa el motor de scoring por cada mensaje.
 *
 * <p><b>Esto es, a propósito, el ÚNICO llamador de {@link ScoreTransactionUseCase}
 * en todo el proyecto.</b> El controller de ingesta ({@code TransactionController})
 * jamás lo invoca — ver el javadoc de {@link ScoreTransactionUseCase} para la
 * razón (es el error de diseño más frecuente de la Semana 2 según el TDD).
 *
 * <p><b>Decisión de arquitectura — "serverless" implementado in-process:</b> el
 * TDD describe el motor de scoring como un "componente serverless activado por
 * el evento". Este proyecto es una única app Spring Boot (no hay Azure Functions
 * Java desplegado por separado), así que la activación por evento se implementa
 * como este poller programado ({@code @Scheduled}) dentro del mismo proceso, en
 * vez de una Azure Function real. Cumple igual el requisito central de
 * desacoplamiento (la API nunca invoca ni espera este componente) con una
 * complejidad de infraestructura muchísimo menor, adecuada al alcance de un
 * proyecto estudiantil — ver la justificación completa en
 * docs/decisions/002-semana2-scoring-mensajeria-y-casos.md.
 *
 * <p><b>Manejo de fallos:</b> si el scoring de un mensaje falla, el mensaje NO se
 * borra — Azure Storage Queue lo vuelve a hacer visible automáticamente tras el
 * "visibility timeout" para reintentarlo. Para evitar reintentos infinitos ante un
 * mensaje "envenenado" (payload corrupto, bug determinístico), se descarta tras
 * {@code MAX_DEQUEUE_COUNT} intentos, dejando constancia en el log de error.
 */
@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);
    private static final long MAX_DEQUEUE_COUNT = 5;

    private final AzureQueueClients queues;
    private final ScoreTransactionUseCase scoreTransactionUseCase;
    private final ObjectMapper objectMapper;
    private final int maxMessagesPerPoll;
    private final Duration visibilityTimeout;

    public TransactionEventConsumer(AzureQueueClients queues,
                                     ScoreTransactionUseCase scoreTransactionUseCase,
                                     ObjectMapper objectMapper,
                                     @Value("${azure.queue.transaction-events-max-messages-per-poll:10}") int maxMessagesPerPoll,
                                     @Value("${azure.queue.transaction-events-visibility-timeout-seconds:30}") long visibilityTimeoutSeconds) {
        this.queues = queues;
        this.scoreTransactionUseCase = scoreTransactionUseCase;
        this.objectMapper = objectMapper;
        this.maxMessagesPerPoll = maxMessagesPerPoll;
        this.visibilityTimeout = Duration.ofSeconds(visibilityTimeoutSeconds);
    }

    @Scheduled(fixedDelayString = "${azure.queue.transaction-events-poll-interval-ms:2000}")
    public void poll() {
        var maybeClient = queues.transactionEvents();
        if (maybeClient.isEmpty()) {
            return; // cola no configurada (ver AzureQueueClients); nada que consumir todavía.
        }
        QueueClient client = maybeClient.get();
        try {
            client.receiveMessages(maxMessagesPerPoll, visibilityTimeout, null, Context.NONE)
                    .forEach(message -> processMessage(client, message));
        } catch (Exception e) {
            log.error("Error al recibir mensajes de 'transaction-events': {}", e.getMessage(), e);
        }
    }

    private void processMessage(QueueClient client, QueueMessageItem message) {
        try {
            Transaction transaction = decode(message);
            scoreTransactionUseCase.score(transaction);
            client.deleteMessage(message.getMessageId(), message.getPopReceipt());
        } catch (Exception e) {
            if (message.getDequeueCount() > MAX_DEQUEUE_COUNT) {
                log.error("Mensaje {} de 'transaction-events' superó el máximo de reintentos ({}); se descarta "
                                + "sin puntuar. Causa: {}",
                        message.getMessageId(), MAX_DEQUEUE_COUNT, e.getMessage(), e);
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
