package com.github.guoyyit.optionsexerciseenginejava.domain;

public record ExerciseDecision(Position position,
                               long settlementPrice,
                               long intrinsicValue,
                               ExerciseAction action,
                               boolean manual,
                               String reason) {

    public static ExerciseDecision exercise(Position position, long settlementPrice, long intrinsicValue,
                                            boolean manual, String reason) {
        return new ExerciseDecision(position, settlementPrice, intrinsicValue,
                ExerciseAction.EXERCISE, manual, reason);
    }

    public static ExerciseDecision abandon(Position position, long settlementPrice, long intrinsicValue,
                                           boolean manual, String reason) {
        return new ExerciseDecision(position, settlementPrice, intrinsicValue,
                ExerciseAction.ABANDON, manual, reason);
    }
}
