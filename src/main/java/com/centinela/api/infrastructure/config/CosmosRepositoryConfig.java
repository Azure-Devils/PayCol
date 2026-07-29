package com.centinela.api.infrastructure.config;

import com.azure.spring.data.cosmos.repository.config.EnableCosmosRepositories;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita el escaneo de interfaces {@code CosmosRepository} (equivalente a
 * {@code @EnableJpaRepositories}, pero para Cosmos DB). Sin esta anotación, Spring Data
 * Cosmos no genera implementaciones para {@code CosmosTransactionRepository},
 * {@code CosmosCustomerRepository} ni {@code CosmosFraudCaseRepository} y su inyección
 * fallaría al arrancar la aplicación.
 *
 * <p>{@code basePackages} apunta al paquete padre
 * {@code infrastructure.adapter.outbound} (en vez de solo {@code .database}) desde que
 * el almacén de casos de fraude también pasó a Cosmos DB (container {@code cases}, ver
 * {@code CosmosFraudCaseRepository} en el paquete {@code .casestore}) —
 * ver docs/decisions/004-eliminacion-postgresql-casos-a-cosmos.md. Esto también cubre el
 * paquete {@code .azure} (adaptadores de Storage Queue), que no declara ningún
 * {@code CosmosRepository}, así que ampliar el escaneo no tiene efecto sobre él.
 */
@Configuration
@EnableCosmosRepositories(basePackages = "com.centinela.api.infrastructure.adapter.outbound")
public class CosmosRepositoryConfig {
}
