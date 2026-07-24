package com.centinela.api.infrastructure.adapter.outbound.casestore.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaCasoJpaRepository extends JpaRepository<AuditoriaCasoEntity, String> {
}
