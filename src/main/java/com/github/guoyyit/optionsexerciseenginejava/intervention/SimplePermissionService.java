package com.github.guoyyit.optionsexerciseenginejava.intervention;

import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SimplePermissionService implements PermissionService {

    private final Map<String, Set<Permission>> grants = new ConcurrentHashMap<>();

    public SimplePermissionService() {
    }

    @Override
    public boolean hasPermission(String operator, Permission permission) {
        return grants.getOrDefault(operator, Set.of()).contains(permission);
    }

    public void grant(String operator, Permission permission) {
        grants.computeIfAbsent(operator, key -> EnumSet.noneOf(Permission.class)).add(permission);
    }

    public Set<Permission> permissions(String operator) {
        return Set.copyOf(grants.getOrDefault(operator, Set.of()));
    }
}
