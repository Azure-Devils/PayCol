package com.centinela.api.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Mensaje publicado en la cola {@code fraud-cases} cuando el motor de scoring
 * determina que una transacción superó el umbral configurado (ver
 * {@code ScoringThresholdPort}).
 *
 * A diferencia del evento de {@code transaction-events} (que distribuye la
 * transacción cruda para que el motor de scoring la analice, sin garantía de
 * procesamiento), este mensaje SÍ requiere garantía de no pérdida: el flujo de
 * gestión de casos debe procesarlo tarde o temprano aunque esté caído en el
 * momento en que se publica. Ver docs/decisions para la justificación completa.
 */
public record FraudCaseEvent(
        String transactionId,
        String customerId,
        int score,
        List<RuleActivation> activations,
        Instant openedAt
) {
    public FraudCaseEvent {
        activations = List.copyOf(activations);
    }
}
