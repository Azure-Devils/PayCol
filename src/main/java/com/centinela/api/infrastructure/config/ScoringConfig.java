package com.centinela.api.infrastructure.config;

import com.centinela.api.domain.service.rule.AnomalousAmountRule;
import com.centinela.api.domain.service.rule.FraudRule;
import com.centinela.api.domain.service.rule.ImpossibleGeoRule;
import com.centinela.api.domain.service.rule.RiskyMerchantRule;
import com.centinela.api.domain.service.rule.VelocityRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class ScoringConfig {

    @Bean
    public VelocityRule velocityRule(
            @Value("${centinela.scoring.rules.velocity.window-seconds:120}") long windowSeconds,
            @Value("${centinela.scoring.rules.velocity.max-transactions:5}") int maxTransactions,
            @Value("${centinela.scoring.rules.velocity.points:30}") int points) {
        return new VelocityRule(Duration.ofSeconds(windowSeconds), maxTransactions, points);
    }

    @Bean
    public AnomalousAmountRule anomalousAmountRule(
            @Value("${centinela.scoring.rules.anomalous-amount.min-sample-size:3}") int minSampleSize,
            @Value("${centinela.scoring.rules.anomalous-amount.deviation-multiplier:5.0}") double deviationMultiplier,
            @Value("${centinela.scoring.rules.anomalous-amount.points:25}") int points) {
        return new AnomalousAmountRule(minSampleSize, deviationMultiplier, points);
    }

    @Bean
    public ImpossibleGeoRule impossibleGeoRule(
            @Value("${centinela.scoring.rules.impossible-geo.max-plausible-speed-kmh:900.0}") double maxPlausibleSpeedKmh,
            @Value("${centinela.scoring.rules.impossible-geo.points:40}") int points) {
        return new ImpossibleGeoRule(maxPlausibleSpeedKmh, points);
    }

    @Bean
    public RiskyMerchantRule riskyMerchantRule(
            @Value("${centinela.scoring.rules.risky-merchant.merchant-ids:}") String riskyMerchantIdsCsv,
            @Value("${centinela.scoring.rules.risky-merchant.merchant-categories:}") String riskyCategoriesCsv,
            @Value("${centinela.scoring.rules.risky-merchant.points:50}") int points) {
        return new RiskyMerchantRule(toSet(riskyMerchantIdsCsv), toSet(riskyCategoriesCsv), points);
    }

    @Bean
    public List<FraudRule> fraudRules(VelocityRule velocityRule,
                                       AnomalousAmountRule anomalousAmountRule,
                                       ImpossibleGeoRule impossibleGeoRule,
                                       RiskyMerchantRule riskyMerchantRule) {
        return List.of(velocityRule, anomalousAmountRule, impossibleGeoRule, riskyMerchantRule);
    }

    private static Set<String> toSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
