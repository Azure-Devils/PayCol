package com.centinela.api.infrastructure.adapter.inbound.web;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.port.inbound.GetTransactionReceiptUseCase;
import com.centinela.api.domain.port.inbound.GetTransactionUseCase;
import com.centinela.api.domain.port.inbound.IngestTransactionUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final IngestTransactionUseCase ingestTransactionUseCase;
    private final GetTransactionUseCase getTransactionUseCase;
    private final GetTransactionReceiptUseCase getTransactionReceiptUseCase;
    private final TransactionWebMapper mapper;

    public TransactionController(IngestTransactionUseCase ingestTransactionUseCase,
                                  GetTransactionUseCase getTransactionUseCase,
                                  GetTransactionReceiptUseCase getTransactionReceiptUseCase,
                                  TransactionWebMapper mapper) {
        this.ingestTransactionUseCase = ingestTransactionUseCase;
        this.getTransactionUseCase = getTransactionUseCase;
        this.getTransactionReceiptUseCase = getTransactionReceiptUseCase;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<TransactionResponseDto> ingest(@Valid @RequestBody TransactionRequestDto request) {
        Transaction ingested = ingestTransactionUseCase.ingest(mapper.toDomain(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(ingested));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponseDto> getById(@PathVariable String transactionId) {
        return getTransactionUseCase.getById(transactionId)
                .map(mapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{transactionId}/receipt")
    public ResponseEntity<byte[]> getReceipt(@PathVariable String transactionId) {
        return getTransactionReceiptUseCase.getReceipt(transactionId)
                .map(pdfBytes -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(pdfBytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
