package com.ssafy.keeping.qr.domain.idempotency.dto;

import com.ssafy.keeping.qr.domain.idempotency.model.IdempotencyKey;

public class IdemBegin {
    private final IdempotencyKey row;
    private final boolean created;

    public IdemBegin(IdempotencyKey row, boolean created) {
        this.row = row;
        this.created = created;
    }

    public IdempotencyKey getRow() {
        return row;
    }

    public boolean isCreated() {
        return created;
    }
}
