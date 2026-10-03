package com.github.guoyyit.optionsexerciseenginejava.repository;

import com.github.guoyyit.optionsexerciseenginejava.domain.Account;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import java.util.Optional;

@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public InMemoryAccountRepository() {
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return Optional.ofNullable(accounts.get(accountId)).map(Account::copy);
    }

    @Override
    public Account getOrCreate(String accountId) {
        return accounts.computeIfAbsent(accountId, id -> new Account(id, 0L, 0L)).copy();
    }

    @Override
    public void save(Account account) {
        accounts.put(account.getAccountId(), account.copy());
    }

    @Override
    public long adjustCash(String accountId, long delta) {
        return adjust(accountId, delta, 0L).getCash();
    }

    @Override
    public long adjustUnderlying(String accountId, long delta) {
        return adjust(accountId, 0L, delta).getUnderlyingUnits();
    }

    private synchronized Account adjust(String accountId, long cashDelta, long underlyingDelta) {
        Account account = accounts.computeIfAbsent(accountId, id -> new Account(id, 0L, 0L));
        account.setCash(account.getCash() + cashDelta);
        account.setUnderlyingUnits(account.getUnderlyingUnits() + underlyingDelta);
        return account.copy();
    }
}
