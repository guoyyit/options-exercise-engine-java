package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class FileMarketDataProvider implements MarketDataProvider {

    private final ConcurrentMap<String, SettlementPrice> prices = new ConcurrentHashMap<>();

    public FileMarketDataProvider() {
    }

    @Override
    public Optional<SettlementPrice> settlementPrice(String contractCode) {
        return Optional.ofNullable(prices.get(contractCode));
    }

    public final void load(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("market data file not found: " + file);
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(",");
                if (parts.length < 2) {
                    throw new IllegalArgumentException("invalid market data line: " + line);
                }
                String contractCode = parts[0].trim();
                long price = Long.parseLong(parts[1].trim());
                prices.put(contractCode, new SettlementPrice(contractCode, price));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read market data file: " + file, e);
        }
    }

    public Map<String, SettlementPrice> prices() {
        return Map.copyOf(prices);
    }
}
