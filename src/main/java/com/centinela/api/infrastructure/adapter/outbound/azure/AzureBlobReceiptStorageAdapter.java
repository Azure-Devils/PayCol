package com.centinela.api.infrastructure.adapter.outbound.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.centinela.api.domain.port.outbound.TransactionReceiptStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

@Component
public class AzureBlobReceiptStorageAdapter implements TransactionReceiptStoragePort {

    private static final Logger log = LoggerFactory.getLogger(AzureBlobReceiptStorageAdapter.class);

    private final BlobContainerClient containerClient;

    public AzureBlobReceiptStorageAdapter(
            @Value("${azure.blob.endpoint:}") String endpoint,
            @Value("${azure.blob.receipts-container-name:identify-documents}") String containerName) {

        if (endpoint == null || endpoint.isBlank()) {
            log.warn("azure.blob.endpoint (env var AZURE_STORAGE_BLOB_ENDPOINT) no configurado; "
                    + "el almacenamiento de comprobantes PDF queda deshabilitado (modo degradado). "
                    + "La ingesta de transacciones sigue funcionando; simplemente no se generan ni "
                    + "sirven comprobantes hasta que el equipo de DevOps/Infra provisione la cuenta real.");
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
                    + "Almacenamiento de comprobantes deshabilitado.", containerName, endpoint, e.getMessage());
            return null;
        }
    }

    @Override
    public void store(String transactionId, byte[] pdfBytes) {
        if (containerClient == null) {
            log.warn("Contenedor de blobs no configurado; el comprobante de la transaccion {} no se almacenó.",
                    transactionId);
            return;
        }

        BlobClient blobClient = containerClient.getBlobClient(blobName(transactionId));
        try (ByteArrayInputStream input = new ByteArrayInputStream(pdfBytes)) {
            blobClient.upload(input, pdfBytes.length, true);
        } catch (Exception e) {
            log.error("No se pudo almacenar el comprobante de la transaccion {}: {}",
                    transactionId, e.getMessage());
        }
    }

    @Override
    public Optional<byte[]> retrieve(String transactionId) {
        if (containerClient == null) {
            return Optional.empty();
        }

        BlobClient blobClient = containerClient.getBlobClient(blobName(transactionId));
        try {
            if (!blobClient.exists()) {
                return Optional.empty();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            blobClient.downloadStream(output);
            return Optional.of(output.toByteArray());
        } catch (Exception e) {
            log.error("No se pudo recuperar el comprobante de la transaccion {}: {}",
                    transactionId, e.getMessage());
            return Optional.empty();
        }
    }

    private String blobName(String transactionId) {
        return transactionId + ".pdf";
    }
}
