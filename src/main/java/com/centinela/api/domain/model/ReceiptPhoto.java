package com.centinela.api.domain.model;

public record ReceiptPhoto(byte[] content, String contentType) {

    public ReceiptPhoto {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
