package com.onepipe.exceptions.customExceptions;
import lombok.Getter;

@Getter
public class UserNotFoundException extends RuntimeException {

    private final String code;
    private final int status;

    public UserNotFoundException(String message) {
        super(message);
        this.code = "USER_NOT_FOUND";
        this.status = 404;
    }

}

