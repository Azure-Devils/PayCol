package com.centinela.api.domain.service;

import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.port.inbound.OpenFraudCaseUseCase;
import com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort;

/**
 * Implementación del caso de uso de apertura de casos de fraude. Lógica de
 * dominio pura, invocada por el consumidor de la cola {@code fraud-cases}
 * (ver {@code infrastructure.adapter.inbound.messaging.FraudCaseQueueConsumer}).
 *
 * Es idempotente a propósito: un mismo evento redelivered por la cola (por
 * ejemplo, si el proceso murió justo después de persistir pero antes de borrar
 * el mensaje) no debe crear un caso duplicado.
 */
public class OpenFraudCaseService implements OpenFraudCaseUseCase {

    private final FraudCaseRepositoryPort fraudCaseRepository;

    public OpenFraudCaseService(FraudCaseRepositoryPort fraudCaseRepository) {
        this.fraudCaseRepository = fraudCaseRepository;
    }

    @Override
    public FraudCase openCase(FraudCaseEvent event) {
        return fraudCaseRepository.openCase(event);
    }
}
