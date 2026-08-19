package com.centinela.api.infrastructure.adapter.outbound.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.centinela.api.domain.port.outbound.ReceiptPhotoStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
public class AzureBlobReceiptPhotoStorageAdapter implements ReceiptPhotoStoragePort {

    private static final Logger log = LoggerFactory.getLogger(AzureBlobReceiptPhotoStorageAdapter.class);

    private final BlobContainerClient containerClient;

    public AzureBlobReceiptPhotoStorageAdapter(
            @Value("${azure.blob.endpoint:}") String endpoint,
            @Value("${azure.blob.receipts-container-name:identify-documents}") String containerName) {

        if (endpoint == null || endpoint.isBlank()) {
            log.warn("azure.blob.endpoint (env var AZURE_STORAGE_BLOB_ENDPOINT) no configurado; "
                    + "el almacenamiento de fotos de comprobante queda deshabilitado (modo degradado). "
                    + "La ingesta de transacciones sigue funcionando; simplemente no se almacenan fotos "
                    + "hasta que el equipo de DevOps/Infra provisione la cuenta real.");
            this.containerClient = null;
            return;
        }

        this.containerClient = prepareContainer(endpoint, containerName);
    }

    private BlobContainerClient prepareContainer(String endpoint, String containerName) {
        try {
            BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                    .endpoint(endpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            BlobContainerClient client = serviceClient.getBlobContainerClient(containerName);
            client.createIfNotExists();
            return client;
        } catch (Exception e) {
            log.error("No se pudo inicializar el contenedor de blobs '{}' contra '{}': {}. "
                    + "Almacenamiento de fotos de comprobante deshabilitado.", containerName, endpoint, e.getMessage());
            return null;
        }
    }

    @Override
    public void store(String transactionId, byte[] content, String contentType) {
        if (containerClient == null) {
            log.warn("Contenedor de blobs no configurado; la foto de comprobante de la transaccion {} no se almacenó.",
                    transactionId);
            return;
        }

        BlobClient blobClient = containerClient.getBlobClient(blobName(transactionId));
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            blobClient.upload(input, content.length, true);
            blobClient.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
        } catch (Exception e) {
            log.error("No se pudo almacenar la foto de comprobante de la transaccion {}: {}",
                    transactionId, e.getMessage());
            // El caso de uso necesita conocer el fallo para no responder que la foto se
            // subió cuando Azure la rechazó (por ejemplo, por falta de RBAC).
            throw new IllegalStateException("No se pudo almacenar la foto de comprobante", e);
        }
    }

    private String blobName(String transactionId) {
        return transactionId + "-receipt-photo";
    }
}
