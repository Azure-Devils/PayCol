package com.centinela.api.domain.port.outbound;

public interface ReceiptPhotoStoragePort {

    void store(String transactionId, byte[] content, String contentType);
}
