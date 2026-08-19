package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.spring.data.cosmos.repository.CosmosRepository;

public interface CosmosTransactionRepository extends CosmosRepository<CosmosTransactionDocument, String> {
}
