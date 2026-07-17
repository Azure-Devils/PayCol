package com.centinela.api.domain.model;

import java.time.Instant;

/**
 * Transacción financiera cruda tal como la ingiere el sistema.
 *
 * El monto SIEMPRE se representa en centavos como entero ({@code amountCents}),
 * nunca como punto flotante, para evitar errores de redondeo binario (IEEE 754).
 *
 * {@code transactionId} es la clave de negocio provista por el emisor: nunca se
 * genera uno nuevo en el dominio (garantiza idempotencia en la ingesta).
 */
public record Transaction(
        String transactionId,
        String customerId,
        long amountCents,
        String currency,
        Instant transactionTimestamp,
        Instant ingestionTimestamp,
        Location location,
        String merchantId,
        String merchantCategory
) {

    /**
     * Devuelve una copia de esta transacción con el timestamp de ingestión
     * fijado por el servidor (nunca confiar en el valor enviado por el cliente).
     */
    public Transaction withIngestionTimestamp(Instant serverTimestamp) {
        return new Transaction(
                transactionId,
                customerId,
                amountCents,
                currency,
                transactionTimestamp,
                serverTimestamp,
                location,
                merchantId,
                merchantCategory
        );
    }
}
