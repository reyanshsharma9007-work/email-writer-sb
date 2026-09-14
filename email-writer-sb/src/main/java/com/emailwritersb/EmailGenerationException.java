package com.emailwritersb;

import org.springframework.http.HttpStatusCode;

public class EmailGenerationException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String clientMessage;

    public EmailGenerationException(HttpStatusCode statusCode, String clientMessage) {
        super(clientMessage);
        this.statusCode = statusCode;
        this.clientMessage = clientMessage;
    }

    public EmailGenerationException(HttpStatusCode statusCode, String clientMessage, Throwable cause) {
        super(clientMessage, cause);
        this.statusCode = statusCode;
        this.clientMessage = clientMessage;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public String getClientMessage() {
        return clientMessage;
    }
}
