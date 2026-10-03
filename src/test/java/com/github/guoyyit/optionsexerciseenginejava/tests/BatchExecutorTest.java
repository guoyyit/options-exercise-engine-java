package com.github.guoyyit.optionsexerciseenginejava.tests;

import com.github.guoyyit.optionsexerciseenginejava.engine.ConfigurableBatchExecutor;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchExecutorTest {

    @Test
    void partitionsIntoBatchesAndHonorsConfiguredSizeAndConcurrency() {
        EngineProperties properties = new EngineProperties();
        properties.setBatchSize(3);
        properties.setConcurrency(2);
        ConfigurableBatchExecutor executor = new ConfigurableBatchExecutor(properties);
        List<Integer> items = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7));
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        var result = executor.runBatches("t", items, processed::addAll);

        assertEquals(7, result.totalItems());
        assertEquals(7, result.completedItems());
        assertEquals(7, processed.size());
        assertTrue(processed.containsAll(items));
    }

    @Test
    void timeoutMarksRunTimedOutAndStopsFurtherBatches() {
        EngineProperties properties = new EngineProperties();
        properties.setBatchSize(1);
        properties.setConcurrency(1);
        properties.setOperationTimeoutMs(30L);
        ConfigurableBatchExecutor executor = new ConfigurableBatchExecutor(properties);
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(i);
        }
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        var result = executor.runBatches("t", items, batch -> {
            processed.addAll(batch);
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(result.timedOut());
        assertTrue(result.cancelled());
        assertTrue(processed.size() < items.size(), "remaining batches must not run after timeout");
    }
}
