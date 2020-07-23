package com.github.sandin.artinjector;

public class ArtInjectException extends Exception {

    public ArtInjectException(String message) {
        super(message);
    }

    public ArtInjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
