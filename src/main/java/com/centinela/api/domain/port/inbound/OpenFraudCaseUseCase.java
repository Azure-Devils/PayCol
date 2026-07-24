package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;

/**
 * Caso de uso de entrada: abre un caso de fraude a partir de un evento recibido
 * de la cola {@code fraud-cases}. Lo invoca
 * {@code infrastructure.adapter.inbound.messaging.FraudCaseQueueConsumer}.
 */
public interface OpenFraudCaseUseCase {

    FraudCase openCase(FraudCaseEvent event);
}
