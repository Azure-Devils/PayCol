package com.centinela.api.infrastructure.adapter.outbound.azure;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptador no-op de {@link MessageQueuePort}.
 *
 * El adaptador real que publica en Azure Storage Queue vía Managed Identity
 * ({@code DefaultAzureCredentialBuilder}) se implementará en una semana
 * posterior (alcance DevOps/Infra). Por ahora solo se deja el puerto
 * correctamente definido e inyectable, sin tocar recursos reales de Azure.
 */
@Component
public class NoOpMessageQueueAdapter implements MessageQueuePort {

    private static final Logger log = LoggerFactory.getLogger(NoOpMessageQueueAdapter.class);

    @Override
    public void publish(Transaction transaction) {
        log.debug("[NoOpMessageQueueAdapter] Transacción {} lista para publicar (pendiente adaptador Azure real)",
                transaction.transactionId());
    }
}
