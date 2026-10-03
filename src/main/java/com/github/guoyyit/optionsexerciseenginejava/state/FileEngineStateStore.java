package com.github.guoyyit.optionsexerciseenginejava.state;

import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementEvent;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class FileEngineStateStore implements EngineStateStore {

    static final String EVENTS_FILE = "events.log";
    static final String COMMIT_FILE = "committed";
    static final String REF_FILE = "ref";

    private final Path stateDir;
    private final Path txDir;
    private final ObjectMapper objectMapper;

    public FileEngineStateStore(EngineProperties properties, ObjectMapper objectMapper) {
        this.stateDir = Path.of(properties.getStateDir());
        this.txDir = stateDir.resolve("transactions");
        this.objectMapper = objectMapper;
        ensureDirs();
    }

    private void ensureDirs() {
        try {
            Files.createDirectories(txDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create state dir: " + stateDir, e);
        }
    }

    Path stateDir() {
        return stateDir;
    }

    Path txDir() {
        return txDir;
    }

    private Path dirOf(String transactionId) {
        return txDir.resolve(safeName(transactionId));
    }

    private String safeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @Override
    public synchronized void beginTransaction(String transactionId, String settlementRef) {
        Path dir = dirOf(transactionId);
        if (Files.exists(dir.resolve(COMMIT_FILE))) {
            throw new IllegalStateException("transaction already committed: " + transactionId);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(REF_FILE), settlementRef, StandardCharsets.UTF_8);
            if (Files.exists(dir.resolve(EVENTS_FILE))) {
                Files.delete(dir.resolve(EVENTS_FILE));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot begin transaction: " + transactionId, e);
        }
    }

    @Override
    public synchronized void appendEvent(String transactionId, SettlementEvent event) {
        Path file = dirOf(transactionId).resolve(EVENTS_FILE);
        try {
            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append event for transaction: " + transactionId, e);
        }
    }

    @Override
    public synchronized void commitTransaction(String transactionId) {
        try {
            Files.writeString(dirOf(transactionId).resolve(COMMIT_FILE),
                    String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot commit transaction: " + transactionId, e);
        }
    }

    @Override
    public synchronized List<SettlementEvent> committedEvents(String settlementRef) {
        List<SettlementEvent> events = new ArrayList<>();
        for (RecoverableTransaction tx : pendingOrCommitted(true)) {
            if (tx.settlementRef().equals(settlementRef)) {
                events.addAll(tx.events());
            }
        }
        return events;
    }

    @Override
    public synchronized List<RecoverableTransaction> pendingTransactions() {
        return pendingOrCommitted(false);
    }

    @Override
    public synchronized List<RecoverableTransaction> committedTransactions() {
        return pendingOrCommitted(true);
    }

    private List<RecoverableTransaction> pendingOrCommitted(boolean committed) {
        ensureDirs();
        List<RecoverableTransaction> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(txDir)) {
            List<Path> txDirs = dirs.filter(Files::isDirectory).sorted().toList();
            for (Path dir : txDirs) {
                boolean isCommitted = Files.exists(dir.resolve(COMMIT_FILE));
                if (isCommitted != committed) {
                    continue;
                }
                String txId = dir.getFileName().toString();
                String ref = Files.exists(dir.resolve(REF_FILE))
                        ? Files.readString(dir.resolve(REF_FILE), StandardCharsets.UTF_8).trim()
                        : txId;
                List<SettlementEvent> events = readEvents(dir.resolve(EVENTS_FILE));
                result.add(new RecoverableTransaction(txId, ref, events));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot scan transactions", e);
        }
        return result;
    }

    private List<SettlementEvent> readEvents(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<SettlementEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(objectMapper.readValue(line, SettlementEvent.class));
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read events: " + file, e);
        }
    }

    @Override
    public synchronized void truncateTransaction(String transactionId) {
        Path dir = dirOf(transactionId);
        deleteRecursively(dir);
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete: " + path, e);
        }
    }
}
