package com.centinela.api.domain.port.outbound;

import java.util.Optional;

public interface TransactionReceiptStoragePort {

    void store(String transactionId, byte[] pdfBytes);

    Optional<byte[]> retrieve(String transactionId);
}
