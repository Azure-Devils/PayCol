package com.centinela.api.infrastructure.adapter.inbound.web;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.inbound.IngestTransactionUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final IngestTransactionUseCase ingestTransactionUseCase;
    private final TransactionWebMapper mapper;

    public TransactionController(IngestTransactionUseCase ingestTransactionUseCase, TransactionWebMapper mapper) {
        this.ingestTransactionUseCase = ingestTransactionUseCase;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<TransactionResponseDto> ingest(@Valid @RequestBody TransactionRequestDto request) {
        Transaction ingested = ingestTransactionUseCase.ingest(mapper.toDomain(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(ingested));
    }
}
