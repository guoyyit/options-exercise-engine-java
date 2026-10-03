package com.github.guoyyit.optionsexerciseenginejava.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ContractLockRegistry {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void lock(String contractId) {
        ReentrantLock lock = locks.computeIfAbsent(contractId, key -> new ReentrantLock());
        lock.lock();
    }

    public void unlock(String contractId) {
        ReentrantLock lock = locks.get(contractId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
