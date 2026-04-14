package com.ssafy.keeping.domain.event.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@Setter
@SuperBuilder
public class PaymentEvent extends BaseEvent {
    private Long customerId;
    private String customerName;
    private Long storeId;
    private String storeName;
    private Long ownerId;
    private Long transactionId;
    private String transactionUniqueNo;
    private Long paymentAmount;
    private Long totalPoints;
    private Integer bonusPercentage;
    private Long bonusAmount;
    private LocalDateTime transactionTime;

    public PaymentEvent() {
        super();
        super.setEventType("PAYMENT");
    }
}