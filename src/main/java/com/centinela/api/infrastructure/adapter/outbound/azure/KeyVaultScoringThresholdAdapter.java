package com.centinela.api.infrastructure.adapter.outbound.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.centinela.api.domain.port.outbound.ScoringThresholdPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
            String raw = secretClient.getSecret(secretName).getValue();
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            log.error("No se pudo leer el secreto '{}' de Key Vault ({}); se usa el umbral de respaldo ({}).",
                    secretName, e.getMessage(), fallbackThreshold);
            return fallbackThreshold;
        }
    }
}
