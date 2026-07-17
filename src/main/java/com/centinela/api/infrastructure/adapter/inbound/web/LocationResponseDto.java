package com.centinela.api.infrastructure.adapter.inbound.web;

import java.math.BigDecimal;

public record LocationResponseDto(
        Long locationId,
        BigDecimal latitude,
        BigDecimal longitude,
        String description
) {
}
