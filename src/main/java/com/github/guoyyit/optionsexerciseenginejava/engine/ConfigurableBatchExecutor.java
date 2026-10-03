package com.github.guoyyit.optionsexerciseenginejava.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class ConfigurableBatchExecutor implements BatchExecutor {

    private final EngineProperties properties;
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public ConfigurableBatchExecutor(EngineProperties properties) {
        this.properties = properties;
    }

    @Override
    public <T> BatchResult runBatches(String batchIdPrefix, List<T> items, Consumer<List<T>> batchConsumer) {
        cancelled.set(false);
        int size = Math.max(1, properties.getBatchSize());
        int concurrency = Math.max(1, properties.getConcurrency());
        List<List<T>> batches = partition(items, size);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "expiration-batch-" + batchIdPrefix);
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger completed = new AtomicInteger(0);
        boolean timedOut = false;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < batches.size(); i++) {
                final int index = i;
                final List<T> batch = batches.get(i);
                futures.add(pool.submit(() -> {
                    if (cancelled.get()) {
                        return;
                    }
                    batchConsumer.accept(batch);
                    completed.addAndGet(batch.size());
                }));
            }
            long timeoutMs = properties.getOperationTimeoutMs();
            for (Future<?> future : futures) {
                try {
                    if (timeoutMs > 0) {
                        future.get(timeoutMs, TimeUnit.MILLISECONDS);
                    } else {
                        future.get();
                    }
                } catch (TimeoutException e) {
                    timedOut = true;
                    cancel();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IllegalStateException(cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancel();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new BatchResult(items.size(), completed.get(), cancelled.get(), timedOut);
    }

    public void cancel() {
        cancelled.set(true);
    }

    private <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int from = 0; from < items.size(); from += size) {
            batches.add(List.copyOf(items.subList(from, Math.min(from + size, items.size()))));
        }
        return batches;
    }
}
