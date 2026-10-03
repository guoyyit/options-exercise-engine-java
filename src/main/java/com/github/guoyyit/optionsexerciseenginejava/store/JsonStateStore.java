package com.github.guoyyit.optionsexerciseenginejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.optionsexerciseenginejava.config.ExerciseProperties;
import com.github.guoyyit.optionsexerciseenginejava.domain.StateSnapshot;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Repository
public class JsonStateStore implements StateStore {
    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public JsonStateStore(ObjectMapper objectMapper, ExerciseProperties properties) {
        this.objectMapper = objectMapper;
        this.stateFile = Path.of(properties.getStateFile());
    }

    @Override
    public StateSnapshot load() {
        if (!Files.exists(stateFile)) {
            return StateSnapshot.empty();
        }
        try {
            return objectMapper.readValue(stateFile.toFile(), StateSnapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load state file: " + stateFile, e);
        }
    }

    @Override
    public synchronized void save(StateSnapshot snapshot) {
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporaryFile = Files.createTempFile(parent, "state-", ".tmp");
            try {
                objectMapper.writeValue(temporaryFile.toFile(), snapshot);
                Files.move(temporaryFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                if (Files.exists(temporaryFile)) {
                    Files.deleteIfExists(temporaryFile);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save state file: " + stateFile, e);
        }
    }
}
