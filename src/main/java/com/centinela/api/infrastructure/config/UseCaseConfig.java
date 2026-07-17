package com.centinela.api.infrastructure.config;

import com.centinela.api.domain.port.inbound.GetTransactionUseCase;
import com.centinela.api.domain.port.inbound.IngestTransactionUseCase;
import com.centinela.api.domain.port.outbound.MessageQueuePort;
import com.centinela.api.domain.port.outbound.TransactionRepositoryPort;
import com.centinela.api.domain.service.GetTransactionService;
import com.centinela.api.domain.service.IngestTransactionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensambla los casos de uso del dominio inyectando los adaptadores concretos
 * que implementan los puertos de salida. El dominio (IngestTransactionService)
 * no tiene anotaciones de Spring; este es el único punto donde se conecta
 * con el contenedor de inyección de dependencias.
 */
@Configuration
public class UseCaseConfig {

    @Bean
    public IngestTransactionUseCase ingestTransactionUseCase(TransactionRepositoryPort transactionRepositoryPort,
                                                               MessageQueuePort messageQueuePort) {
        return new IngestTransactionService(transactionRepositoryPort, messageQueuePort);
    }

    @Bean
    public GetTransactionUseCase getTransactionUseCase(TransactionRepositoryPort transactionRepositoryPort) {
        return new GetTransactionService(transactionRepositoryPort);
    }
}
