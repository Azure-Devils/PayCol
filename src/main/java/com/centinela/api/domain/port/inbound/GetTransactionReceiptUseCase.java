package com.centinela.api.domain.port.inbound;

import java.util.Optional;

public interface GetTransactionReceiptUseCase {

    Optional<byte[]> getReceipt(String transactionId);
}
