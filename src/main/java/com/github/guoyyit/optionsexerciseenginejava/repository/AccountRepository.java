package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.Account;

import java.util.Optional;

public interface AccountRepository {

    Optional<Account> findById(String accountId);

    Account getOrCreate(String accountId);

    void save(Account account);

    long adjustCash(String accountId, long delta);

    long adjustUnderlying(String accountId, long delta);
}
