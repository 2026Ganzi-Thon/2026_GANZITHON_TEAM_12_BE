package com.comedown.calendar.contract;

import java.time.LocalDate;
import java.util.List;

/**
 * @param updatedCount 분류가 바뀐 일정 수
 * @param affectedWeekStarts 부하를 다시 계산해야 하는 주(월요일, 오름차순)
 */
public record CategoryChangeResult(int updatedCount, List<LocalDate> affectedWeekStarts) {
}
