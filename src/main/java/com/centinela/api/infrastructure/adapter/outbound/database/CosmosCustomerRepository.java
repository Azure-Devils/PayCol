package com.centinela.api.infrastructure.adapter.outbound.database;

import com.azure.spring.data.cosmos.repository.CosmosRepository;

/**
 * Repositorio Spring Data para {@link CosmosCustomerDocument}. Como {@code customerId} es a
 * la vez {@code @Id} y partition key (ver comentario en el documento), toda operación de este
 * repositorio —incluido {@code findById}/{@code existsById}— es un point-read barato, sin el
 * costo cross-partition que sí tiene {@link CosmosTransactionRepository#findById}.
 */
public interface CosmosCustomerRepository extends CosmosRepository<CosmosCustomerDocument, String> {
}
