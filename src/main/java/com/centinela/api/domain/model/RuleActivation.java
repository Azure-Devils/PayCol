package com.centinela.api.domain.model;

import java.util.Map;

/**
 * Registro de la activación de una regla de detección de fraude.
 *
 * <p>Requisito explícito de la Semana 2 (sección 2.3 del TDD): no basta con guardar
 * el identificador de la regla activada — hay que guardar también los <b>valores
 * concretos observados</b> que la activaron (cantidad de transacciones en la ventana,
 * monto observado frente al promedio histórico, distancia/tiempo transcurrido, etc.).
 * El explicador de casos de la Semana 3 se construye sobre esta información; su
 * ausencia obligaría a reprocesar transacciones o rehacer el motor.
 *
 * <p>{@code observedValues} es deliberadamente un mapa (no un record tipado por regla)
 * porque cada una de las cuatro reglas observa cosas distintas; el contrato mínimo es
 * que las claves sean legibles por humanos y los valores serializables a JSON tal cual
 * (String, Number, Boolean) — ver cada implementación de {@code FraudRule} para el
 * detalle exacto de qué claves emite.
 */
public record RuleActivation(
        ScoringRule rule,
        int points,
        Map<String, Object> observedValues
) {
    public RuleActivation {
        observedValues = Map.copyOf(observedValues);
    }
}
