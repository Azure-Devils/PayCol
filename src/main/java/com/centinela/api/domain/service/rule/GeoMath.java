package com.centinela.api.domain.service.rule;

import java.math.BigDecimal;

/**
 * Utilidad de distancia geográfica (fórmula de Haversine), usada por
 * {@link ImpossibleGeoRule}. Aislada en su propia clase para poder testearla
 * sin necesidad de construir una {@code Transaction} completa.
 */
final class GeoMath {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoMath() {
    }

    /** Distancia en kilómetros entre dos coordenadas (línea recta sobre la esfera terrestre). */
    static double distanceKm(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double phi1 = Math.toRadians(lat1.doubleValue());
        double phi2 = Math.toRadians(lat2.doubleValue());
        double deltaPhi = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double deltaLambda = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
