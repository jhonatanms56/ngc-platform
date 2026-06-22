package com.test.interactionapi.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class InteractionNotFoundException extends RuntimeException {

    public InteractionNotFoundException(String id) {
        super("Interaction not found: " + id);
    }
}
