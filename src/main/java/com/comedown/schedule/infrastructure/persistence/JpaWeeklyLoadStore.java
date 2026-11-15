package com.comedown.schedule.infrastructure.persistence;

import com.comedown.schedule.application.WeeklyLoadStore;
import com.comedown.schedule.domain.WeekLoad;
import com.comedown.schedule.domain.WeekLoad.RiskLevel;
import com.comedown.schedule.domain.WeekLoad.RiskReason;
import com.comedown.schedule.domain.WeekLoad.UnavailableReason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class JpaWeeklyLoadStore implements WeeklyLoadStore {

    private final WeeklyLoadJpaRepository repository;

    JpaWeeklyLoadStore(WeeklyLoadJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<WeekLoad> find(long userId, LocalDate weekStart) {
        return repository.findById(new WeeklyLoadId(userId, weekStart)).map(JpaWeeklyLoadStore::toDomain);
    }

    @Override
    public List<WeekLoad> findBetween(long userId, LocalDate from, LocalDate toInclusive) {
        return repository.findBetween(userId, from, toInclusive).stream().map(JpaWeeklyLoadStore::toDomain).toList();
    }

    @Override
    public void save(long userId, WeekLoad load, Instant computedAt) {
        WeeklyLoadId id = new WeeklyLoadId(userId, load.weekStart());
        WeeklyLoadEntity entity = repository.findById(id).orElseGet(() -> new WeeklyLoadEntity(id));
        entity.update(load.totalHours(), load.loadScore(), load.vsUsualPct(),
                load.vsUsualUnavailableReason() == null ? null : load.vsUsualUnavailableReason().name(),
                load.deadlineCount(), load.lateNightCount(), load.weekendHours(), load.restDays(),
                load.riskLevel().name(), load.riskReasons().stream().map(Enum::name).toList(),
                load.calculationVersion(), computedAt);
        repository.save(entity);
    }

    private static WeekLoad toDomain(WeeklyLoadEntity e) {
        return new WeekLoad(e.id().weekStart(), e.totalHours(), e.loadScore(), e.vsUsualPct(),
                e.vsUsualUnavailableReason() == null ? null : UnavailableReason.valueOf(e.vsUsualUnavailableReason()),
                e.deadlineCount(), e.lateNightCount(), e.weekendHours(), e.restDays(),
                RiskLevel.valueOf(e.riskLevel()), e.riskReasons().stream().map(RiskReason::valueOf).toList(),
                e.calculationVersion());
    }
}
