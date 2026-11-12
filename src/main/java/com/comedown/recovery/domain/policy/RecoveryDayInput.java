package com.comedown.recovery.domain.policy;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 회복 계산에 들어가는 하루치 입력(측정 또는 컨디션을 저장한 날).
 *
 * @param date               서울 날짜
 * @param dailyLn            하루 대표값 ln(RMSSD). 적격 측정이 없으면 null
 * @param qualityLevel       대표 측정의 품질(GOOD·FAIR). 대표값이 없으면 null
 * @param measurementCount   그날 적격(GOOD·FAIR) 측정 수
 * @param conditionCompleted 컨디션·교란 요인 입력 완료 여부
 * @param confounded         음주·아픔·카페인 중 하나라도 있음. 입력 전이면 false
 */
public record RecoveryDayInput(
        LocalDate date,
        Double dailyLn,
        String qualityLevel,
        int measurementCount,
        boolean conditionCompleted,
        boolean confounded) {

    /** 하루 대표값이 될 수 있는 품질. RETRY·UNVERIFIED(수동값)는 제외한다. */
    static final Set<String> REPRESENTATIVE_QUALITIES = Set.of("GOOD", "FAIR");

    public RecoveryDayInput {
        Objects.requireNonNull(date, "date");
        if ((dailyLn == null) != (qualityLevel == null)) {
            throw new IllegalArgumentException("대표값과 대표 품질은 함께 있어야 합니다");
        }
        if (dailyLn != null && !Double.isFinite(dailyLn)) {
            throw new IllegalArgumentException("dailyLn은 유한수여야 합니다");
        }
        if (qualityLevel != null && !REPRESENTATIVE_QUALITIES.contains(qualityLevel)) {
            throw new IllegalArgumentException("대표 품질은 GOOD·FAIR만 가능합니다: " + qualityLevel);
        }
        if (measurementCount < 0 || (dailyLn != null && measurementCount == 0)) {
            throw new IllegalArgumentException("적격 측정 수가 올바르지 않습니다: " + measurementCount);
        }
    }

    /**
     * 그날의 측정 목록에서 하루 대표값을 골라 입력을 만든다.
     * 대표값 = 측정 시작 시각 순으로 첫 번째 적격(GOOD·FAIR) 측정. 첫 측정이 RETRY면 다음 적격 재측정이 대표값이 되고,
     * 그 뒤의 측정(운동 후, 저녁 등)은 섞지 않는다. 시간대 제한은 두지 않는다(검토결과 Q2 임시 해석).
     */
    public static RecoveryDayInput of(
            LocalDate date, List<MeasurementSample> measurements, boolean conditionCompleted, boolean confounded) {
        List<MeasurementSample> qualified = qualified(measurements);
        if (qualified.isEmpty()) {
            return new RecoveryDayInput(date, null, null, 0, conditionCompleted, confounded);
        }
        MeasurementSample first = qualified.getFirst();
        return new RecoveryDayInput(
                date, first.lnRmssd(), first.qualityLevel(), qualified.size(), conditionCompleted, confounded);
    }

    /** {@link #of}와 같은 규칙으로 고른 하루 대표 측정. 적격 측정이 없으면 비어 있다. */
    public static Optional<MeasurementSample> representative(List<MeasurementSample> measurements) {
        return qualified(measurements).stream().findFirst();
    }

    private static List<MeasurementSample> qualified(List<MeasurementSample> measurements) {
        return measurements.stream()
                .filter(m -> REPRESENTATIVE_QUALITIES.contains(m.qualityLevel()) && m.lnRmssd() != null)
                .sorted(Comparator.comparing(MeasurementSample::measuredAt))
                .toList();
    }

    /** 적격일: 대표값이 있고, 컨디션·교란 요인 입력이 끝났고, 교란 요인이 없는 날. */
    public boolean eligible() {
        return dailyLn != null && conditionCompleted && !confounded;
    }
}
