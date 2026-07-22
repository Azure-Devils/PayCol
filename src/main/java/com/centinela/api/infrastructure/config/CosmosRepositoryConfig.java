package com.centinela.api.infrastructure.config;

import com.azure.spring.data.cosmos.repository.config.EnableCosmosRepositories;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita el escaneo de interfaces {@code CosmosRepository} (equivalente a
 * {@code @EnableJpaRepositories}, pero para Cosmos DB). Sin esta anotación, Spring Data
 * Cosmos no genera implementaciones para {@code CosmosTransactionRepository} ni
 * {@code CosmosCustomerRepository} y su inyección fallaría al arrancar la aplicación.
 */
@Configuration
@EnableCosmosRepositories(basePackages = "com.centinela.api.infrastructure.adapter.outbound.database")
public class CosmosRepositoryConfig {
}
