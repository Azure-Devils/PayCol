package com.centinela.api.infrastructure.adapter.outbound.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.centinela.api.domain.port.outbound.ScoringThresholdPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implementa {@link ScoringThresholdPort} leyendo el umbral de score directamente
 * de Azure Key Vault en CADA evaluación (nunca cacheado), vía
 * {@link DefaultAzureCredentialBuilder} (Managed Identity) — sin credencial
 * destinada a obtener credenciales, sin secretos en el código ni en el repo.
 *
 * <p>Se decidió consultar el {@link SecretClient} directamente en vez de depender
 * de {@code spring-cloud-azure-starter-keyvault-secrets} como property source: esa
 * librería no expone (en la versión usada, 5.20.1) un intervalo de refresco
 * automático fuera de la integración con Spring Cloud Config/Actuator refresh, lo
 * cual habría dejado el valor efectivamente cacheado hasta un refresh manual —
 * justo lo que el requisito prohíbe ("su modificación no puede requerir un nuevo
 * despliegue"). Consultar el secreto en cada llamada es más simple, más explícito,
 * y cuesta una sola operación de lectura de Key Vault por transacción puntuada
 * (el free tier de Key Vault permite ~10.000 operaciones/mes sin costo, más que
 * suficiente para el volumen de este proyecto).
 *
 * <p><b>Degradación sin Key Vault real:</b> mientras {@code azure.keyvault.endpoint}
 * (env var {@code AZURE_KEYVAULT_ENDPOINT}) esté vacío o el secreto no exista/no sea
 * accesible, se usa el valor de respaldo {@code centinela.scoring.threshold}
 * (application.properties / env var {@code CENTINELA_SCORING_THRESHOLD}) — mismo
 * patrón de placeholders vacíos ya usado para Cosmos y Storage Queue.
 */
@Component
public class KeyVaultScoringThresholdAdapter implements ScoringThresholdPort {

    private static final Logger log = LoggerFactory.getLogger(KeyVaultScoringThresholdAdapter.class);

    private final SecretClient secretClient;
    private final String secretName;
    private final int fallbackThreshold;

    public KeyVaultScoringThresholdAdapter(
            @Value("${azure.keyvault.endpoint:}") String keyVaultEndpoint,
            @Value("${azure.keyvault.scoring-threshold-secret-name:fraud-threshold}") String secretName,
            @Value("${centinela.scoring.threshold:60}") int fallbackThreshold) {
        this.secretName = secretName;
        this.fallbackThreshold = fallbackThreshold;

        if (keyVaultEndpoint == null || keyVaultEndpoint.isBlank()) {
            log.warn("azure.keyvault.endpoint (env var AZURE_KEYVAULT_ENDPOINT) no configurado; el umbral de "
                    + "scoring usa el valor local de respaldo ({}) y NO es modificable sin redeploy hasta que "
                    + "el equipo de DevOps/Infra provisione Key Vault.", fallbackThreshold);
            this.secretClient = null;
            return;
        }

        SecretClient client;
        try {
            client = new SecretClientBuilder()
                    .vaultUrl(keyVaultEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        } catch (Exception e) {
            log.error("No se pudo inicializar el cliente de Key Vault contra '{}': {}. Se usa el umbral de "
                    + "respaldo ({}).", keyVaultEndpoint, e.getMessage(), fallbackThreshold);
            client = null;
        }
        this.secretClient = client;
    }

    @Override
    public int currentThreshold() {
        if (secretClient == null) {
            return fallbackThreshold;
        }
        try {
            // Sin caché: se lee Key Vault en cada evaluación del motor de scoring, para que
            // un cambio de secreto se refleje de inmediato, sin redespliegue ni reinicio.
            String raw = secretClient.getSecret(secretName).getValue();
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            log.error("No se pudo leer el secreto '{}' de Key Vault ({}); se usa el umbral de respaldo ({}).",
                    secretName, e.getMessage(), fallbackThreshold);
            return fallbackThreshold;
        }
    }
}
