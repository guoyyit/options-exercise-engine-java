package com.github.guoyyit.optionsexerciseenginejava.domain;

public record SettlementEvent(long seq,
                              String settlementRef,
                              SettlementEventType type,
                              String accountId,
                              String positionId,
                              String contractCode,
                              long positionDelta,
                              long cashDelta,
                              long underlyingDelta,
                              String operator,
                              String note) {
}
