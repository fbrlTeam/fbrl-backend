package com.fbrl.adapter.in.web.dto;

import com.fbrl.domain.model.OutboxEvent;
import java.time.Instant;

public record OutboxEventResponse(
    Long id,
    String aggregateType,
    String aggregateId,
    String eventType,
    Instant createdAt,
    String previousHash,
    String entryHash,
    String traceId,
    String spanId) {
  public static OutboxEventResponse from(OutboxEvent event) {
    return new OutboxEventResponse(
        event.getId(),
        event.getAggregateType(),
        event.getAggregateId(),
        event.getEventType(),
        event.getCreatedAt(),
        event.getPreviousHash(),
        event.getEntryHash(),
        event.getTraceId(),
        event.getSpanId());
  }
}
