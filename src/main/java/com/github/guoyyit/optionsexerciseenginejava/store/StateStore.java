package com.github.guoyyit.optionsexerciseenginejava.store;

import com.github.guoyyit.optionsexerciseenginejava.domain.StateSnapshot;

public interface StateStore {
    StateSnapshot load();

    void save(StateSnapshot snapshot);
}
