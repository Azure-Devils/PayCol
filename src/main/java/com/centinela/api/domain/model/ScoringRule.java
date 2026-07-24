package com.centinela.api.domain.model;

/**
 * Catálogo de reglas de detección de fraude evaluadas por el motor de scoring
 * (sección 2.3 del TDD de Semana 2, {@code docs/weeks/Semana2-Azure.md}).
 *
 * El identificador ({@code name()}) es lo que se persiste como {@code ruleId}
 * junto a cada activación — ver {@link RuleActivation}.
 */
public enum ScoringRule {

    /** Cantidad de transacciones de la cuenta dentro de una ventana temporal corta. */
    VELOCITY,

    /** Desviación del monto respecto al comportamiento histórico de la cuenta. */
    ANOMALOUS_AMOUNT,

    /** Distancia/tiempo entre dos transacciones consecutivas físicamente incompatible. */
    IMPOSSIBLE_GEO,

    /** El comercio o su categoría pertenece a la lista de entidades marcadas como riesgosas. */
    RISKY_MERCHANT
}
