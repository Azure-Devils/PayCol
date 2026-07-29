package com.centinela.api.infrastructure.adapter.outbound.casestore;

import com.azure.spring.data.cosmos.repository.CosmosRepository;

/**
 * Repositorio Spring Data para {@link CosmosFraudCaseDocument}. Equivalente a un
 * {@code JpaRepository}, pero generado para Cosmos DB (ver
 * {@code CosmosRepositoryConfig}, que habilita el escaneo de este paquete además del
 * paquete {@code database} donde viven los repositorios de transacciones/clientes).
 *
 * <p>A diferencia de {@code CosmosTransactionRepository#findById} (documentado ahí
 * como cross-partition porque el {@code @Id} de esa colección, {@code transactionId},
 * NO es la partition key, {@code customerId}), aquí {@code existsById}/{@code findById}
 * SÍ son point-reads baratos: el {@code @Id} de {@link CosmosFraudCaseDocument} es
 * exactamente su partition key ({@code transactionId}, ver el javadoc de esa clase).
 */
public interface CosmosFraudCaseRepository extends CosmosRepository<CosmosFraudCaseDocument, String> {
}
