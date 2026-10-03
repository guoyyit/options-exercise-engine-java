package com.github.guoyyit.optionsexerciseenginejava.intervention;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class FileBackedAuditLog implements AuditLog {

    private final Path file;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    public FileBackedAuditLog(EngineProperties properties, ObjectMapper objectMapper) {
        this.file = Path.of(properties.getStateDir()).resolve("audit.log");
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create audit dir", e);
        }
    }

    @Override
    public AuditEntry record(String operator, String action, String target, String detail,
                             boolean allowed, String reason) {
        AuditEntry entry = new AuditEntry(UUID.randomUUID().toString(), Instant.now(),
                operator, action, target, detail, allowed, reason);
        synchronized (writeLock) {
            try {
                String line = objectMapper.writeValueAsString(entry) + System.lineSeparator();
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write audit log", e);
            }
        }
        return entry;
    }

    @Override
    public List<AuditEntry> findByTarget(String target) {
        return readAll().stream().filter(e -> target.equals(e.getTarget())).toList();
    }

    @Override
    public List<AuditEntry> findAll() {
        return readAll();
    }

    private List<AuditEntry> readAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<AuditEntry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    entries.add(objectMapper.readValue(line, AuditEntry.class));
                }
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read audit log", e);
        }
    }
}
