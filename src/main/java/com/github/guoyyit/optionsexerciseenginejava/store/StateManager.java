package com.github.guoyyit.optionsexerciseenginejava.store;

import com.github.guoyyit.optionsexerciseenginejava.domain.StateSnapshot;
import com.github.guoyyit.optionsexerciseenginejava.market.ContractRepository;
import com.github.guoyyit.optionsexerciseenginejava.market.MarketDataProvider;
import com.github.guoyyit.optionsexerciseenginejava.market.PositionRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.AssignmentRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.AuditLogRepository;
import com.github.guoyyit.optionsexerciseenginejava.repository.ExerciseInstructionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

@Service
public class StateManager {
    private final ContractRepository contractRepository;
    private final PositionRepository positionRepository;
    private final MarketDataProvider marketDataProvider;
    private final ExerciseInstructionRepository exerciseInstructionRepository;
    private final AssignmentRepository assignmentRepository;
    private final AuditLogRepository auditLogRepository;
    private final StateStore stateStore;
    private final ReentrantLock lock = new ReentrantLock();

    public StateManager(
            ContractRepository contractRepository,
            PositionRepository positionRepository,
            MarketDataProvider marketDataProvider,
            ExerciseInstructionRepository exerciseInstructionRepository,
            AssignmentRepository assignmentRepository,
            AuditLogRepository auditLogRepository,
            StateStore stateStore
    ) {
        this.contractRepository = contractRepository;
        this.positionRepository = positionRepository;
        this.marketDataProvider = marketDataProvider;
        this.exerciseInstructionRepository = exerciseInstructionRepository;
        this.assignmentRepository = assignmentRepository;
        this.auditLogRepository = auditLogRepository;
        this.stateStore = stateStore;
    }

    @PostConstruct
    public void init() {
        restore(stateStore.load());
    }

    public StateSnapshot snapshot() {
        return new StateSnapshot(
                contractRepository.findAll(),
                positionRepository.findAll(),
                marketDataProvider.findAllPrices(),
                exerciseInstructionRepository.findAll(),
                assignmentRepository.findAll(),
                auditLogRepository.findAll()
        );
    }

    public void restore(StateSnapshot snapshot) {
        lock.lock();
        try {
            contractRepository.replaceAll(snapshot.contracts());
            positionRepository.replaceAll(snapshot.positions());
            marketDataProvider.replaceAll(snapshot.prices());
            exerciseInstructionRepository.replaceAll(snapshot.exercises());
            assignmentRepository.replaceAll(snapshot.assignments());
            auditLogRepository.replaceAll(snapshot.auditLogs());
        } finally {
            lock.unlock();
        }
    }

    public void commit(UnaryOperator<StateSnapshot> mutation) {
        lock.lock();
        try {
            StateSnapshot previous = snapshot();
            StateSnapshot next = mutation.apply(previous);
            try {
                restore(next);
                stateStore.save(next);
            } catch (RuntimeException e) {
                restore(previous);
                stateStore.save(previous);
                throw e;
            }
        } catch (RuntimeException e) {
            throw e;
        } finally {
            lock.unlock();
        }
    }

    public void commit(StateSnapshot newState) {
        commit(current -> newState);
    }
}
