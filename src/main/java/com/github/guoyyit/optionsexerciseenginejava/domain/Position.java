package com.github.guoyyit.optionsexerciseenginejava.domain;

public class Position {
    private String id;
    private String accountId;
    private String contractCode;
    private OptionType optionType;
    private ExerciseStyle exerciseStyle;
    private long strike;
    private long quantity;
    private long expiryDate;

    public Position() {
    }

    public Position(String id, String accountId, String contractCode, OptionType optionType,
                    ExerciseStyle exerciseStyle, long strike, long quantity, long expiryDate) {
        this.id = id;
        this.accountId = accountId;
        this.contractCode = contractCode;
        this.optionType = optionType;
        this.exerciseStyle = exerciseStyle;
        this.strike = strike;
        this.quantity = quantity;
        this.expiryDate = expiryDate;
    }

    public boolean isLong() {
        return quantity > 0;
    }

    public boolean isShort() {
        return quantity < 0;
    }

    public long shortQuantity() {
        return -quantity;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getContractCode() {
        return contractCode;
    }

    public void setContractCode(String contractCode) {
        this.contractCode = contractCode;
    }

    public OptionType getOptionType() {
        return optionType;
    }

    public void setOptionType(OptionType optionType) {
        this.optionType = optionType;
    }

    public ExerciseStyle getExerciseStyle() {
        return exerciseStyle;
    }

    public void setExerciseStyle(ExerciseStyle exerciseStyle) {
        this.exerciseStyle = exerciseStyle;
    }

    public long getStrike() {
        return strike;
    }

    public void setStrike(long strike) {
        this.strike = strike;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    public long getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(long expiryDate) {
        this.expiryDate = expiryDate;
    }
}
