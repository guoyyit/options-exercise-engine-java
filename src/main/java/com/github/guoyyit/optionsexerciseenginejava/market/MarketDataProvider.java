package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.SettlementPrice;

import java.util.Optional;

public interface MarketDataProvider {

    Optional<SettlementPrice> settlementPrice(String contractCode);
}
