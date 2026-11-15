package com.comedown.schedule.application;

import com.comedown.schedule.contract.WeekLoadView;
import com.comedown.schedule.contract.WeekLoads;
import com.comedown.schedule.domain.WeekLoad;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** {@link WeekLoads} 계약 구현. 계산·저장은 {@link WeeklyLoadService}에 맡기고 읽기 모델로만 바꾼다. */
@Service
class WeekLoadsAdapter implements WeekLoads {

    private final WeeklyLoadService service;

    WeekLoadsAdapter(WeeklyLoadService service) {
        this.service = service;
    }

    @Override
    public List<WeekLoadView> recalculate(long userId, Collection<LocalDate> weekStarts) {
        return service.recalculate(userId, weekStarts).stream().map(WeekLoadsAdapter::view).toList();
    }

    @Override
    public Optional<WeekLoadView> find(long userId, LocalDate weekStart) {
        return service.weeks(userId, weekStart, 1).stream().findFirst().map(WeekLoadsAdapter::view);
    }

    @Override
    public List<WeekLoadView> range(long userId, LocalDate from, int count) {
        return service.weeks(userId, from, count).stream().map(WeekLoadsAdapter::view).toList();
    }

    static WeekLoadView view(WeekLoad load) {
        return new WeekLoadView(load.weekStart(), load.weekStart().plusDays(6), load.totalHours(),
                load.loadScore(), load.vsUsualPct(), load.deadlineCount(), load.lateNightCount(), load.restDays(),
                load.riskLevel().name(), load.riskReasons().stream().map(Enum::name).toList(),
                load.vsUsualUnavailableReason() == null ? null : load.vsUsualUnavailableReason().name());
    }
}
