package com.github.guoyyit.optionsexerciseenginejava.tests;

import com.github.guoyyit.optionsexerciseenginejava.assignment.ProportionalAssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.assignment.RandomAssignmentStrategy;
import com.github.guoyyit.optionsexerciseenginejava.domain.Assignment;
import com.github.guoyyit.optionsexerciseenginejava.domain.ExerciseStyle;
import com.github.guoyyit.optionsexerciseenginejava.domain.OptionType;
import com.github.guoyyit.optionsexerciseenginejava.domain.Position;
import com.github.guoyyit.optionsexerciseenginejava.engine.EngineProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AssignmentStrategyTest {

    private Position shortPosition(String id, String accountId, long quantity) {
        return new Position(id, accountId, "OPT", OptionType.CALL, ExerciseStyle.EUROPEAN,
                100L, -quantity, 20260101L);
    }

    private Map<String, Long> quantities(List<Assignment> assignments) {
        return assignments.stream().collect(Collectors.toMap(
                Assignment::shortPositionId, Assignment::quantity, Long::sum));
    }

    @Test
    void proportionalAllocationRespectsOpenInterestAndTotals() {
        ProportionalAssignmentStrategy strategy = new ProportionalAssignmentStrategy();
        List<Position> shorts = List.of(
                shortPosition("s1", "a", 10L),
                shortPosition("s2", "b", 30L),
                shortPosition("s3", "c", 60L));

        List<Assignment> result = strategy.allocate("OPT", 40L, shorts, 1L);

        assertEquals(40L, result.stream().mapToLong(Assignment::quantity).sum());
        Map<String, Long> qty = quantities(result);
        assertEquals(4L, qty.get("s1"));
        assertEquals(12L, qty.get("s2"));
        assertEquals(24L, qty.get("s3"));
    }

    @Test
    void proportionalAllocationIsDeterministic() {
        ProportionalAssignmentStrategy strategy = new ProportionalAssignmentStrategy();
        List<Position> shorts = List.of(shortPosition("s1", "a", 33L), shortPosition("s2", "b", 67L));

        List<Assignment> first = strategy.allocate("OPT", 10L, shorts, 7L);
        List<Assignment> second = strategy.allocate("OPT", 10L, shorts, 99L);

        assertEquals(first, second);
    }

    @Test
    void randomAllocationIsReproducibleWithSameSeedAndInput() {
        EngineProperties properties = new EngineProperties();
        properties.setRandomSeed(42L);
        RandomAssignmentStrategy first = new RandomAssignmentStrategy(properties);
        RandomAssignmentStrategy second = new RandomAssignmentStrategy(properties);
        List<Position> shorts = List.of(
                shortPosition("s1", "a", 25L),
                shortPosition("s2", "b", 25L),
                shortPosition("s3", "c", 25L),
                shortPosition("s4", "d", 25L));

        List<Assignment> run1 = first.allocate("OPT", 30L, shorts, 9L);
        List<Assignment> run2 = second.allocate("OPT", 30L, shorts, 9L);

        assertEquals(30L, run1.stream().mapToLong(Assignment::quantity).sum());
        assertEquals(run1, run2);
        assertEquals(30L, quantities(run1).values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void randomAllocationSpreadsAcrossSellers() {
        EngineProperties properties = new EngineProperties();
        properties.setRandomSeed(7L);
        RandomAssignmentStrategy strategy = new RandomAssignmentStrategy(properties);
        List<Position> shorts = List.of(
                shortPosition("s1", "a", 50L), shortPosition("s2", "b", 50L));

        List<Assignment> result = strategy.allocate("OPT", 100L, shorts, 1L);

        Map<String, Long> qty = quantities(result);
        assertEquals(100L, qty.values().stream().mapToLong(Long::longValue).sum());
        assertNotEquals(100L, qty.getOrDefault("s1", 0L));
    }
}
