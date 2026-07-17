package com.centinela.api.infrastructure.adapter.outbound.database;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerJpaRepository extends JpaRepository<CustomerEntity, String> {
}
