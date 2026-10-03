package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryMarketDataProvider implements MarketDataProvider {
    private final Map<String, SettlementPrice> prices = new ConcurrentHashMap<>();

    @Override
    public Optional<SettlementPrice> getSettlementPrice(String contractId, LocalDate valuationDate) {
        return Optional.ofNullable(prices.get(key(contractId, valuationDate)));
    }

    @Override
    public java.util.List<SettlementPrice> findAllPrices() {
        return java.util.List.copyOf(prices.values());
    }

    @Override
    public void replaceAll(Collection<SettlementPrice> prices) {
        Map<String, SettlementPrice> replacement = new ConcurrentHashMap<>();
        prices.forEach(price -> replacement.put(key(price.contractId(), price.valuationDate()), price));
        this.prices.clear();
        this.prices.putAll(replacement);
    }

    public SettlementPrice save(SettlementPrice price) {
        prices.put(key(price.contractId(), price.valuationDate()), price);
        return price;
    }

    private static String key(String contractId, LocalDate valuationDate) {
        return contractId + "|" + valuationDate;
    }
}
