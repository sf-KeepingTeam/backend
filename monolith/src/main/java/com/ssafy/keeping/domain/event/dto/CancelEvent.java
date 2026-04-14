package com.ssafy.keeping.domain.event.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@Setter
@SuperBuilder
public class CancelEvent extends BaseEvent {
    private Long customerId;
    private String customerName;
    private Long storeId;
    private String storeName;
    private Long ownerId;
    private Long cancelTransactionId;
    private String transactionUniqueNo;
    private Long cancelAmount;
    private LocalDateTime cancelTime;

    public CancelEvent() {
        super();
        super.setEventType("CANCEL");
    }
}