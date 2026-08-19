package com.centinela.api.domain.port.inbound;

import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;

public interface OpenFraudCaseUseCase {

    FraudCase openCase(FraudCaseEvent event);
}
