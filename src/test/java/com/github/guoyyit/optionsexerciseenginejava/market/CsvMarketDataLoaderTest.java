package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.config.ExerciseProperties;
import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvMarketDataLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsContractsPositionsAndPricesFromCsv() throws Exception {
        ExerciseProperties properties = new ExerciseProperties();
        properties.setDataDirectory(tempDir.toString());
        InMemoryContractRepository contracts = new InMemoryContractRepository();
        InMemoryPositionRepository positions = new InMemoryPositionRepository();
        InMemoryMarketDataProvider prices = new InMemoryMarketDataProvider();
        CsvMarketDataLoader loader = new CsvMarketDataLoader(properties, contracts, positions, prices);

        Files.writeString(tempDir.resolve("contracts.csv"), """
                id,underlying,optionType,exerciseStyle,strikePrice,expiryDate
                CALL-1,ASSET,CALL,EUROPEAN,100.00,2026-10-03
                """);
        Files.writeString(tempDir.resolve("positions.csv"), """
                accountId,instrumentId,quantity
                LONG,CALL-1,10
                WRITER,CALL-1,-10
                """);
        Files.writeString(tempDir.resolve("prices.csv"), """
                contractId,price,valuationDate
                CALL-1,110.00,2026-10-03
                """);

        loader.run(null);

        Contract contract = contracts.findById("CALL-1").orElseThrow();
        assertEquals(OptionType.CALL, contract.optionType());
        assertEquals(ExerciseStyle.EUROPEAN, contract.exerciseStyle());
        assertEquals(new BigDecimal("100.00"), contract.strikePrice());
        assertEquals(LocalDate.of(2026, 10, 3), contract.expiryDate());
        assertEquals(10, positions.find("LONG", "CALL-1").orElseThrow().quantity());
        assertEquals(-10, positions.find("WRITER", "CALL-1").orElseThrow().quantity());
        SettlementPrice price = prices.getSettlementPrice("CALL-1", LocalDate.of(2026, 10, 3)).orElseThrow();
        assertEquals(new BigDecimal("110.00"), price.price());
    }
}
