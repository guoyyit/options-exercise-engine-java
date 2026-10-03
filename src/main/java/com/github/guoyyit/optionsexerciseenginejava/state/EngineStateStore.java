package com.github.guoyyit.optionsexerciseenginejava.state;

import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementEvent;

import java.util.List;

public interface EngineStateStore {

    void beginTransaction(String transactionId, String settlementRef);

    void appendEvent(String transactionId, SettlementEvent event);

    void commitTransaction(String transactionId);

    List<SettlementEvent> committedEvents(String settlementRef);

    List<RecoverableTransaction> pendingTransactions();

    List<RecoverableTransaction> committedTransactions();

    void truncateTransaction(String transactionId);

    record RecoverableTransaction(String transactionId, String settlementRef, List<SettlementEvent> events) {
    }
}
