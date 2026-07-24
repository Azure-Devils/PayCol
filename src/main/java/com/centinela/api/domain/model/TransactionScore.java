package com.centinela.api.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Resultado del motor de scoring para una transacción puntual: la suma de puntos
 * de las reglas activadas y el detalle de cada una (ver {@link RuleActivation}).
 *
 * Se persiste junto a la transacción (no en un almacén aparte) porque la consulta
 * dominante de la Semana 3 (explicador de casos) siempre parte de una transacción
 * concreta — ver {@code CosmosTransactionDocument}.
 */
public record TransactionScore(
        String transactionId,
        String customerId,
        int totalScore,
        List<RuleActivation> activations,
        Instant scoredAt
) {
    public TransactionScore {
        activations = List.copyOf(activations);
    }
}
