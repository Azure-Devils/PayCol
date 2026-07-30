package com.centinela.api.infrastructure.adapter.inbound.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record TransactionRequestDto(
        @NotBlank @Size(max = 100) String transactionId,
        @NotBlank @Size(max = 50) String customerId,
        @NotNull @Positive Long amountCents,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull @PastOrPresent @JsonFormat(shape = JsonFormat.Shape.STRING) Instant transactionTimestamp,
        @NotNull @Valid LocationRequestDto location,
        @NotBlank @Size(max = 50) String merchantId,
        @NotBlank @Size(max = 10) String merchantCategory
) {
}
