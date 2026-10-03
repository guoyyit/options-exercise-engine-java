package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryContractRepository implements ContractRepository {
    private final Map<String, Contract> contracts = new ConcurrentHashMap<>();

    @Override
    public Optional<Contract> findById(String contractId) {
        return Optional.ofNullable(contracts.get(contractId));
    }

    @Override
    public List<Contract> findAll() {
        return List.copyOf(contracts.values());
    }

    @Override
    public void replaceAll(Collection<Contract> contracts) {
        Map<String, Contract> replacement = new ConcurrentHashMap<>();
        contracts.forEach(contract -> replacement.put(contract.id(), contract));
        this.contracts.clear();
        this.contracts.putAll(replacement);
    }

    @Override
    public Contract save(Contract contract) {
        contracts.put(contract.id(), contract);
        return contract;
    }
}
