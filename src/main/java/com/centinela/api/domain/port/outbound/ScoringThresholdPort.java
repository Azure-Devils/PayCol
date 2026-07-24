package com.centinela.api.domain.port.outbound;

/**
 * Puerto de salida hacia el gestor de secretos/configuración donde vive el
 * umbral de score a partir del cual una transacción abre un caso de fraude
 * (sección 2.3 y 2.6 del TDD de Semana 2).
 *
 * <p>Contrato deliberadamente sin caché: cada llamada a {@link #currentThreshold()}
 * debe reflejar el valor vigente en ese instante, para que el umbral pueda
 * modificarse sin redespliegue (requisito explícito del doc). La implementación
 * de infraestructura decide de dónde lo lee (Azure Key Vault, variable de entorno
 * de respaldo, etc.) — el dominio solo necesita "dame el umbral de ahora mismo".
 */
public interface ScoringThresholdPort {

    int currentThreshold();
}
