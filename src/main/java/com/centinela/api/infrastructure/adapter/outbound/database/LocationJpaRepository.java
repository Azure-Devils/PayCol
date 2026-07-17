package com.centinela.api.infrastructure.adapter.outbound.database;

import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.Optional;

public interface LocationJpaRepository extends JpaRepository<LocationEntity, Integer> {

    Optional<LocationEntity> findByLatitudeAndLongitude(BigDecimal latitude, BigDecimal longitude);
}
