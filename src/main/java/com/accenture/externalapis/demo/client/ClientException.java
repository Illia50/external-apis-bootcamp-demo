package com.accenture.externalapis.demo.client;

public class ClientException extends RuntimeException {

    private final Integer statusCode;

    public ClientException(String message) {
        this(message, null, null);
    }

    public ClientException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public ClientException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean hasStatusCode() {
        return statusCode != null;
    }

    public boolean isServerError() {
        return statusCode != null && statusCode >= 500;
    }
}
