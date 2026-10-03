package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Primary
public class InMemoryMarketDataProvider implements MarketDataProvider {

    private final ConcurrentMap<String, SettlementPrice> prices = new ConcurrentHashMap<>();

    public InMemoryMarketDataProvider() {
    }

    @Override
    public Optional<SettlementPrice> settlementPrice(String contractCode) {
        return Optional.ofNullable(prices.get(contractCode));
    }

    public void put(SettlementPrice price) {
        prices.put(price.getContractCode(), price);
    }

    public Map<String, SettlementPrice> prices() {
        return Map.copyOf(prices);
    }
}
