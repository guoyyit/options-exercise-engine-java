package com.github.guoyyit.optionsexerciseenginejava.intervention;

public interface PermissionService {

    boolean hasPermission(String operator, Permission permission);

    enum Permission {
        EXERCISE_OVERRIDE,
        REVOKE_EXERCISE,
        ADJUST_ASSIGNMENT
    }
}
