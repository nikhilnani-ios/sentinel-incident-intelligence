package io.sentinel.platform.common.error;

import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String from, String to) {
        super(HttpStatus.CONFLICT, "invalid_state_transition", "Cannot move from %s to %s".formatted(from, to));
    }
}
