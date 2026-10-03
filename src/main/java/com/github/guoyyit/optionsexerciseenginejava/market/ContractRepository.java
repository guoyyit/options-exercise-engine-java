package com.github.guoyyit.optionsexerciseenginejava.market;

import com.github.guoyyit.optionsexerciseenginejava.domain.Contract;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContractRepository {
    Optional<Contract> findById(String contractId);

    List<Contract> findAll();

    void replaceAll(Collection<Contract> contracts);

    Contract save(Contract contract);
}
