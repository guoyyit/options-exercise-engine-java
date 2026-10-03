package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.config.ExerciseProperties;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

@Component
public class CsvMarketDataLoader implements ApplicationRunner {
    private final ExerciseProperties properties;
    private final ContractRepository contractRepository;
    private final PositionRepository positionRepository;
    private final MarketDataProvider marketDataProvider;

    public CsvMarketDataLoader(
            ExerciseProperties properties,
            ContractRepository contractRepository,
            PositionRepository positionRepository,
            MarketDataProvider marketDataProvider
    ) {
        this.properties = properties;
        this.contractRepository = contractRepository;
        this.positionRepository = positionRepository;
        this.marketDataProvider = marketDataProvider;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path dataDirectory = Path.of(properties.getDataDirectory());
        if (!Files.isDirectory(dataDirectory) || !contractRepository.findAll().isEmpty()) {
            return;
        }

        Path contractsFile = dataDirectory.resolve("contracts.csv");
        if (Files.exists(contractsFile)) {
            try (BufferedReader reader = Files.newBufferedReader(contractsFile)) {
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] fields = line.split(",");
                    contractRepository.save(new Contract(
                            fields[0].trim(),
                            fields[1].trim(),
                            OptionType.valueOf(fields[2].trim()),
                            ExerciseStyle.valueOf(fields[3].trim()),
                            new BigDecimal(fields[4].trim()),
                            LocalDate.parse(fields[5].trim())
                    ));
                }
            }
        }

        Path positionsFile = dataDirectory.resolve("positions.csv");
        if (Files.exists(positionsFile)) {
            try (BufferedReader reader = Files.newBufferedReader(positionsFile)) {
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] fields = line.split(",");
                    positionRepository.save(new Position(
                            fields[0].trim(),
                            fields[1].trim(),
                            Long.parseLong(fields[2].trim())
                    ));
                }
            }
        }

        if (marketDataProvider instanceof InMemoryMarketDataProvider inMemoryMarketDataProvider) {
            Path pricesFile = dataDirectory.resolve("prices.csv");
            if (Files.exists(pricesFile)) {
                try (BufferedReader reader = Files.newBufferedReader(pricesFile)) {
                    reader.readLine();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        String[] fields = line.split(",");
                        inMemoryMarketDataProvider.save(new SettlementPrice(
                                fields[0].trim(),
                                new BigDecimal(fields[1].trim()),
                                LocalDate.parse(fields[2].trim())
                        ));
                    }
                }
            }
        }
    }
}
