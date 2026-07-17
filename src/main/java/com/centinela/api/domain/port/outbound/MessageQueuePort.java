package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.Transaction;

/**
 * Puerto de salida hacia el sistema de mensajería asíncrona (Azure Storage Queue
 * en producción, vía Managed Identity). La implementación real con el SDK de
 * Azure se aborda en una semana posterior (DevOps/Infra); por ahora solo se
 * define el contrato y un adaptador no-op para poder inyectar el caso de uso.
 */
public interface MessageQueuePort {

    void publish(Transaction transaction);
}
