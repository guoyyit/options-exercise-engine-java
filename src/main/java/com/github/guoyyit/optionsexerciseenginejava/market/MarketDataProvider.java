package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

public interface MarketDataProvider {
    Optional<SettlementPrice> getSettlementPrice(String contractId, LocalDate valuationDate);

    java.util.List<SettlementPrice> findAllPrices();

    void replaceAll(Collection<SettlementPrice> prices);
}
