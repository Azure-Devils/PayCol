package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.TransactionReviewStatus;

public interface GetTransactionReviewStatusUseCase {

    TransactionReviewStatus getStatus(String transactionId);
}
