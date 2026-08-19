package com.centinela.api.domain.service.rule;

import com.centinela.api.domain.model.Location;
import com.centinela.api.domain.model.Transaction;

import java.math.BigDecimal;
import java.time.Instant;

final class RuleTestSupport {

    private RuleTestSupport() {
    }

    static Transaction tx(String id, String customerId, long amountCents, Instant timestamp,
                          double lat, double lon, String merchantId, String merchantCategory) {
        Location location = new Location(null, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon), "test");
        return new Transaction(id, customerId, amountCents, "COP", timestamp, timestamp, location,
                merchantId, merchantCategory);
    }
}
