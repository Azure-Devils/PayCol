package com.centinela.api.infrastructure.adapter.outbound.casestore;

import com.azure.spring.data.cosmos.repository.CosmosRepository;

public interface CosmosFraudCaseRepository extends CosmosRepository<CosmosFraudCaseDocument, String> {
}
