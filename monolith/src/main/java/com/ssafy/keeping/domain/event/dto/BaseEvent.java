package com.ssafy.keeping.domain.event.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
public abstract class BaseEvent {
    private String eventId;
    private LocalDateTime eventTimestamp;
    private String eventType;

    public BaseEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.eventTimestamp = LocalDateTime.now();
    }
}