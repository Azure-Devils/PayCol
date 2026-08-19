package com.centinela.api.domain.model;

import java.math.BigDecimal;

public record Location(
        Long locationId,
        BigDecimal latitude,
        BigDecimal longitude,
        String description
) {
}
