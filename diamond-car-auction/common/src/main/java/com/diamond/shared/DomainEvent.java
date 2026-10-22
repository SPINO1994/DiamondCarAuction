package com.diamond.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DomainEvent(UUID eventId, String eventType, int schemaVersion, UUID aggregateId,
                          long aggregateVersion, OffsetDateTime timestamp, String correlationId, JsonNode data) {
    public UUID uuid(String field) { return UUID.fromString(data.required(field).asText()); }
    public String text(String field) { return data.required(field).asText(); }
}
