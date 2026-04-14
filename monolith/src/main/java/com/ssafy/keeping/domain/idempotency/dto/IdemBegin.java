package com.ssafy.keeping.domain.idempotency.dto;

import com.ssafy.keeping.domain.idempotency.model.IdempotencyKey;

public class IdemBegin {
    private final IdempotencyKey row;
    private final boolean created;

    public IdemBegin(IdempotencyKey row, boolean created) {
        this.row = row;
        this.created = created;
    }

    public IdempotencyKey getRow() { return row; }
    public boolean isCreated() { return created; }
}
