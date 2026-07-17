package com.centinela.api.infrastructure.adapter.inbound.web;

import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.Transaction;
import org.springframework.stereotype.Component;

@Component
public class TransactionWebMapper {

    public Transaction toDomain(TransactionRequestDto dto) {
        Location location = new Location(
                null,
                dto.location().latitude(),
                dto.location().longitude(),
                dto.location().description()
        );
        return new Transaction(
                dto.transactionId(),
                dto.customerId(),
                dto.amountCents(),
                dto.currency(),
                dto.transactionTimestamp(),
                null, // ingestion_timestamp lo fija siempre el servidor en el dominio
                location,
                dto.merchantId(),
                dto.merchantCategory()
        );
    }

    public TransactionResponseDto toResponse(Transaction transaction) {
        LocationResponseDto locationDto = new LocationResponseDto(
                transaction.location().locationId(),
                transaction.location().latitude(),
                transaction.location().longitude(),
                transaction.location().description()
        );
        return new TransactionResponseDto(
                transaction.transactionId(),
                transaction.customerId(),
                transaction.amountCents(),
                transaction.currency(),
                transaction.transactionTimestamp(),
                transaction.ingestionTimestamp(),
                locationDto,
                transaction.merchantId(),
                transaction.merchantCategory()
        );
    }
}
