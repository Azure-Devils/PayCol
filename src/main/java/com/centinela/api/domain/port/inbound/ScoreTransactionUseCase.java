package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.Transaction;
import com.centinela.api.domain.model.TransactionScore;

public interface ScoreTransactionUseCase {

    TransactionScore score(Transaction transaction);
}
