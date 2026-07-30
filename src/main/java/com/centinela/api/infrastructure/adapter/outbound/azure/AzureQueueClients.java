package com.centinela.api.infrastructure.adapter.outbound.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueServiceClient;
import com.azure.storage.queue.QueueServiceClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AzureQueueClients {

    private static final Logger log = LoggerFactory.getLogger(AzureQueueClients.class);

    private final QueueClient transactionEventsClient;
    private final QueueClient fraudCasesClient;

    public AzureQueueClients(
            @Value("${azure.queue.endpoint:}") String endpoint,
            @Value("${azure.queue.transaction-events-name:transaction-events}") String transactionEventsQueueName,
            @Value("${azure.queue.fraud-cases-name:fraud-cases}") String fraudCasesQueueName) {

        if (endpoint == null || endpoint.isBlank()) {
            log.warn("azure.queue.endpoint (env var AZURE_STORAGE_QUEUE_ENDPOINT) no configurado; "
                    + "las colas de Azure Storage Queue quedan deshabilitadas (modo degradado). "
                    + "La API de ingesta y el resto de la aplicación siguen funcionando; simplemente "
                    + "no se publican ni consumen eventos hasta que el equipo de DevOps/Infra provisione la cuenta real.");
            this.transactionEventsClient = null;
            this.fraudCasesClient = null;
            return;
        }

        QueueServiceClient serviceClient;
        try {
            serviceClient = new QueueServiceClientBuilder()
                    .endpoint(endpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        } catch (Exception e) {
            log.error("No se pudo inicializar QueueServiceClient contra '{}': {}. Colas deshabilitadas.",
                    endpoint, e.getMessage());
            this.transactionEventsClient = null;
            this.fraudCasesClient = null;
            return;
        }

        this.transactionEventsClient = prepareQueue(serviceClient, transactionEventsQueueName);
        this.fraudCasesClient = prepareQueue(serviceClient, fraudCasesQueueName);
    }

    private QueueClient prepareQueue(QueueServiceClient serviceClient, String queueName) {
        try {
            QueueClient client = serviceClient.getQueueClient(queueName);
            client.createIfNotExists();
            return client;
        } catch (Exception e) {
            log.error("No se pudo preparar la cola '{}': {}. Quedará deshabilitada.", queueName, e.getMessage());
            return null;
        }
    }

    public Optional<QueueClient> transactionEvents() {
        return Optional.ofNullable(transactionEventsClient);
    }

    public Optional<QueueClient> fraudCases() {
        return Optional.ofNullable(fraudCasesClient);
    }
}
