package com.github.guoyyit.optionsexerciseenginejava.engine;

import java.util.List;
import java.util.function.Consumer;

public interface BatchExecutor {

    <T> BatchResult runBatches(String batchIdPrefix,
                               List<T> items,
                               Consumer<List<T>> batchConsumer);

    record BatchResult(int totalItems, int completedItems, boolean cancelled, boolean timedOut) {
    }
}
