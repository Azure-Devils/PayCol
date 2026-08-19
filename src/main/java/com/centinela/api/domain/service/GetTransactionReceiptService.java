package com.centinela.api.domain.service;

import com.centinela.api.domain.port.inbound.GetTransactionReceiptUseCase;
import com.centinela.api.domain.port.outbound.TransactionReceiptStoragePort;

import java.util.Optional;

public class GetTransactionReceiptService implements GetTransactionReceiptUseCase {

    private final TransactionReceiptStoragePort receiptStorage;

    public GetTransactionReceiptService(TransactionReceiptStoragePort receiptStorage) {
        this.receiptStorage = receiptStorage;
    }

    @Override
    public Optional<byte[]> getReceipt(String transactionId) {
        return receiptStorage.retrieve(transactionId);
    }
}
