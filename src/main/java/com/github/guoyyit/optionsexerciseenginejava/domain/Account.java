package com.github.guoyyit.optionsexerciseenginejava.domain;

public class Account {
    private String accountId;
    private long cash;
    private long underlyingUnits;

    public Account() {
    }

    public Account(String accountId, long cash, long underlyingUnits) {
        this.accountId = accountId;
        this.cash = cash;
        this.underlyingUnits = underlyingUnits;
    }

    public Account copy() {
        return new Account(accountId, cash, underlyingUnits);
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public long getCash() {
        return cash;
    }

    public void setCash(long cash) {
        this.cash = cash;
    }

    public long getUnderlyingUnits() {
        return underlyingUnits;
    }

    public void setUnderlyingUnits(long underlyingUnits) {
        this.underlyingUnits = underlyingUnits;
    }
}
