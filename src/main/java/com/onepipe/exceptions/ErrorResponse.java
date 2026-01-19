package com.onepipe.exceptions;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErrorResponse {
    private String code;
    private String message;
    private int status;
    private String timestamp;
    private String path;

    public ErrorResponse(String code, String message, int status, String path) {
        this.code = code;
        this.message = message;
        this.status = status;
        this.timestamp = java.time.ZonedDateTime.now().toString();
        this.path = path;
    }

}
