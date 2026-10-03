package com.github.guoyyit.optionsexerciseenginejava.service;

import com.github.guoyyit.optionsexerciseenginejava.config.ExerciseProperties;
import com.github.guoyyit.optionsexerciseenginejava.domain.AssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.market.ContractRepository;
import com.github.guoyyit.optionsexerciseenginejava.service.ExerciseService.ContractResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ExpirationProcessor {
    private final ExerciseService exerciseService;
    private final ContractRepository contractRepository;
    private final ExerciseProperties properties;

    public ExpirationProcessor(ExerciseService exerciseService, ContractRepository contractRepository, ExerciseProperties properties) {
        this.exerciseService = exerciseService;
        this.contractRepository = contractRepository;
        this.properties = properties;
    }

    public ProcessingResult process(LocalDate date) {
        return process(date, properties.getAssignmentStrategy());
    }

    public ProcessingResult process(LocalDate date, AssignmentStrategy strategy) {
        List<Contract> contracts = contractRepository.findAll().stream()
                .filter(contract -> contract.expiryDate().equals(date))
                .sorted(Comparator.comparing(Contract::id))
                .toList();
        List<List<Contract>> batches = partition(contracts, properties.getBatchSize());
        ExecutorService executor = Executors.newFixedThreadPool(properties.getConcurrency(), new NamedThreadFactory());

        List<Callable<BatchResult>> tasks = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            int batchNumber = i;
            List<Contract> batch = batches.get(i);
            tasks.add(() -> processBatch(batchNumber, batch, date, strategy));
        }

        List<ContractResult> results = new ArrayList<>();
        List<ProcessingFailure> failures = new ArrayList<>();
        boolean timedOut;
        boolean cancelled = false;
        try {
            List<Future<BatchResult>> futures = executor.invokeAll(
                    tasks,
                    properties.getProcessingTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            timedOut = futures.stream().anyMatch(Future::isCancelled);
            for (Future<BatchResult> future : futures) {
                if (future.isCancelled()) {
                    failures.add(new ProcessingFailure("BATCH", "Batch was cancelled before completion"));
                    continue;
                }
                try {
                    BatchResult batchResult = future.get();
                    results.addAll(batchResult.results());
                    failures.addAll(batchResult.failures());
                } catch (ExecutionException e) {
                    failures.add(new ProcessingFailure("BATCH", "Batch failed: " + e.getCause().getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    failures.add(new ProcessingFailure("BATCH", "Batch was interrupted"));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
            timedOut = false;
            failures.add(new ProcessingFailure("BATCH", "Processing was interrupted"));
        } finally {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(properties.getProcessingTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    failures.add(new ProcessingFailure("BATCH", "Executor did not terminate after shutdown"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
            }
        }

        return new ProcessingResult(date, properties.getBatchSize(), properties.getConcurrency(), results, failures, timedOut, cancelled);
    }

    private BatchResult processBatch(int batchNumber, List<Contract> contracts, LocalDate date, AssignmentStrategy strategy) {
        List<ContractResult> results = new ArrayList<>();
        List<ProcessingFailure> failures = new ArrayList<>();
        for (Contract contract : contracts) {
            try {
                results.add(exerciseService.processContract(contract, date, strategy));
            } catch (RuntimeException e) {
                failures.add(new ProcessingFailure(contract.id(), e.getMessage()));
            }
        }
        return new BatchResult(batchNumber, results, failures);
    }

    private List<List<Contract>> partition(List<Contract> contracts, int batchSize) {
        List<List<Contract>> batches = new ArrayList<>();
        for (int i = 0; i < contracts.size(); i += batchSize) {
            batches.add(contracts.subList(i, Math.min(i + batchSize, contracts.size())));
        }
        return batches;
    }

    private record BatchResult(int batchNumber, List<ContractResult> results, List<ProcessingFailure> failures) {
    }

    public record ProcessingFailure(String contractId, String message) {
    }

    public record ProcessingResult(
            LocalDate date,
            int batchSize,
            int concurrency,
            List<ContractResult> results,
            List<ProcessingFailure> failures,
            boolean timedOut,
            boolean cancelled
    ) {
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicLong counter = new AtomicLong();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "expiration-processor-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
