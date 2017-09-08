package com.burak.core.wiremock.exception;

/**
 * Generic Simulator Exception Class
 */
public class SimulatorException extends RuntimeException {

    public SimulatorException(String message) {
        super(message);
    }

    public SimulatorException(String message, Throwable cause) {
        super(message, cause);
    }

}
