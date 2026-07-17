package com.centinela.api.domain.model;

import java.math.BigDecimal;

/**
 * Ubicación normalizada donde ocurrió una transacción.
 * {@code locationId} es null para ubicaciones nuevas aún no persistidas
 * (se asigna por la secuencia de la tabla {@code locations}).
 */
public record Location(
        Long locationId,
        BigDecimal latitude,
        BigDecimal longitude,
        String description
) {
}
