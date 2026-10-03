package com.github.guoyyit.optionsexerciseenginejava.settlement;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ContractLockManager {

    private static final int STRIPES = 1024;

    private final ConcurrentMap<String, ReentrantLock> namedLocks = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripeLocks;

    public ContractLockManager() {
        stripeLocks = new ReentrantLock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            stripeLocks[i] = new ReentrantLock();
        }
    }

    public ReentrantLock contractLock(String contractCode) {
        return namedLocks.computeIfAbsent(contractCode,
                code -> stripeLocks[Math.floorMod(code.hashCode(), STRIPES)]);
    }
}
