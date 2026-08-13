package io.sentinel.platform.common.error;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "resource_not_found", "%s %s was not found".formatted(resource, id));
    }
}
