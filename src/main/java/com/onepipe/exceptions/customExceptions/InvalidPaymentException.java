package com.onepipe.exceptions.customExceptions;

/** Messages describe validation rules, never submitted banking details or credentials. */
public class InvalidPaymentException extends IllegalArgumentException {
    public InvalidPaymentException(String message) { super(message); }
}
