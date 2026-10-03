package com.github.guoyyit.optionsexerciseenginejava.domain;

public class SettlementPrice {
    private String contractCode;
    private long price;

    public SettlementPrice() {
    }

    public SettlementPrice(String contractCode, long price) {
        this.contractCode = contractCode;
        this.price = price;
    }

    public String getContractCode() {
        return contractCode;
    }

    public void setContractCode(String contractCode) {
        this.contractCode = contractCode;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }
}
