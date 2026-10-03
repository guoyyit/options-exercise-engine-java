package com.github.guoyyit.optionsexerciseenginejava.data;

import com.github.guoyyit.optionsexerciseenginejava.domain.Account;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.repository.AccountRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.PositionRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class CsvDataLoader {

    private final PositionRepository positionRepository;
    private final AccountRepository accountRepository;

    public CsvDataLoader(PositionRepository positionRepository, AccountRepository accountRepository) {
        this.positionRepository = positionRepository;
        this.accountRepository = accountRepository;
    }

    public void loadPositions(Path file) {
        for (String line : readLines(file)) {
            String[] parts = line.split(",");
            if (parts.length < 8) {
                throw new IllegalArgumentException(
                        "position line must have id,accountId,contractCode,type,style,strike,quantity,expiryDate: "
                                + line);
            }
            Position position = new Position(
                    parts[0].trim(),
                    parts[1].trim(),
                    parts[2].trim(),
                    OptionType.valueOf(parts[3].trim().toUpperCase()),
                    ExerciseStyle.valueOf(parts[4].trim().toUpperCase()),
                    Long.parseLong(parts[5].trim()),
                    Long.parseLong(parts[6].trim()),
                    Long.parseLong(parts[7].trim()));
            positionRepository.save(position);
        }
    }

    public void loadAccounts(Path file) {
        for (String line : readLines(file)) {
            String[] parts = line.split(",");
            if (parts.length < 3) {
                throw new IllegalArgumentException(
                        "account line must have accountId,cash,underlyingUnits: " + line);
            }
            accountRepository.save(new Account(parts[0].trim(),
                    Long.parseLong(parts[1].trim()), Long.parseLong(parts[2].trim())));
        }
    }

    private Iterable<String> readLines(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("data file not found: " + file);
        }
        try {
            return Files.readAllLines(file).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read data file: " + file, e);
        }
    }
}
