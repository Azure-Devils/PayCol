package com.centinela.api.infrastructure.adapter.outbound.casestore.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CasoJpaRepository extends JpaRepository<CasoEntity, String> {

    boolean existsByTransactionId(String transactionId);

    Optional<CasoEntity> findByTransactionId(String transactionId);
}
