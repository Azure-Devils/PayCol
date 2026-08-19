package com.centinela.api.domain.port.outbound;

import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;

public interface FraudCaseRepositoryPort {

    boolean existsByTransactionId(String transactionId);

    FraudCase openCase(FraudCaseEvent event);
}
