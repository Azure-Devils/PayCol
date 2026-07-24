package com.centinela.api.infrastructure.adapter.outbound.database;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionJpaRepository extends JpaRepository<TransactionEntity, String> {

    /**
     * Historial reciente de una cuenta acotado a las primeras {@code n} filas, apoyado en
     * el índice {@code idx_tx_customer_time(customer_id, transaction_timestamp DESC)} —
     * ver V1__init.sql. Se agregó solo para que este adapter aislado siga implementando
     * {@link com.centinela.api.domain.port.outbound.TransactionRepositoryPort} completo;
     * no se ejecuta en tiempo de ejecución (perfil "postgres-legacy" inactivo).
     */
    List<TransactionEntity> findTop50ByCustomerIdOrderByTransactionTimestampDesc(String customerId);
}
