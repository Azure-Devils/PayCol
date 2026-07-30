package com.centinela.api.infrastructure.config;

import com.azure.spring.data.cosmos.repository.config.EnableCosmosRepositories;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCosmosRepositories(basePackages = "com.centinela.api.infrastructure.adapter.outbound")
public class CosmosRepositoryConfig {
}
