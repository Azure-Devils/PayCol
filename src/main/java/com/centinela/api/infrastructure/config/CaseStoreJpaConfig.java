package com.centinela.api.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Habilita JPA/Hibernate SOLO para el paquete del almacén de casos de fraude
 * (esquema nuevo de la Semana 2, ver docs/weeks/Semana2-Azure.md sección 2.2).
 *
 * <p>Se usan {@code basePackages} explícitos (en vez de dejar que Spring Boot
 * escanee todo {@code com.centinela.api} hacia abajo, que es su comportamiento
 * por defecto) a propósito: el paquete
 * {@code infrastructure.adapter.outbound.database} todavía contiene las
 * entidades JPA LEGADAS de transacciones ({@code TransactionEntity},
 * {@code CustomerEntity}, {@code LocationEntity}, aisladas tras la migración a
 * Cosmos DB — ver docs/decisions/001-migracion-postgresql-a-cosmosdb.md). Si
 * Hibernate las incluyera en su modelo junto con el DataSource de este almacén
 * de casos, intentaría validar/crear tablas ({@code transactions},
 * {@code customers}, {@code locations}) que no existen en este esquema nuevo, y
 * el arranque fallaría. Acotar el escaneo a {@code casestore.jpa} evita ese
 * conflicto sin tener que borrar el código legado.
 */
@Configuration
@EntityScan(basePackages = "com.centinela.api.infrastructure.adapter.outbound.casestore.jpa")
@EnableJpaRepositories(basePackages = "com.centinela.api.infrastructure.adapter.outbound.casestore.jpa")
public class CaseStoreJpaConfig {
}
