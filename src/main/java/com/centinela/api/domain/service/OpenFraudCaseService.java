package com.centinela.api.domain.service;

import com.centinela.api.domain.model.FraudCase;
import com.centinela.api.domain.model.FraudCaseEvent;
import com.centinela.api.domain.port.inbound.OpenFraudCaseUseCase;
import com.centinela.api.domain.port.outbound.FraudCaseRepositoryPort;

public class OpenFraudCaseService implements OpenFraudCaseUseCase {

    private final FraudCaseRepositoryPort fraudCaseRepository;

    public OpenFraudCaseService(FraudCaseRepositoryPort fraudCaseRepository) {
        this.fraudCaseRepository = fraudCaseRepository;
    }

    @Override
    public FraudCase openCase(FraudCaseEvent event) {
        return fraudCaseRepository.openCase(event);
    }
}
