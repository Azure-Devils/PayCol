package com.centinela.api.infrastructure.adapter.outbound.casestore.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EstadoJpaRepository extends JpaRepository<EstadoEntity, String> {
}
