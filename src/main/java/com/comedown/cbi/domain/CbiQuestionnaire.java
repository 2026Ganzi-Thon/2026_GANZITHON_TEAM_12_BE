package com.comedown.cbi.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CBI 문항·채점 정책({@value #VERSION}, 계산 정책 2절). 문항 문구는 코드에 둔다.
 * <ul>
 *   <li>보기 option 0→4의 점수 100 / 75 / 50 / 25 / 0. 13번은 역채점(100 − 환산값)</li>
 *   <li>하위척도 점수 = 응답 점수 평균(0~100): PERSONAL 1~6번, WORK 7~13번</li>
 *   <li>어느 하위척도든 75 이상이면 high_risk</li>
 *   <li>level: LOW(50 미만) HIGH(50 이상 75 미만) VERY_HIGH(75 이상) — 프로젝트 표시 기준이며 진단이 아니다</li>
 * </ul>
 */
public final class CbiQuestionnaire {

    public static final String VERSION = "cbi-ko-2026.11.2";
    public static final int ITEM_COUNT = 13;
    public static final BigDecimal HIGH_RISK_THRESHOLD = BigDecimal.valueOf(75);
    static final BigDecimal HIGH_THRESHOLD = BigDecimal.valueOf(50);
    static final int[] OPTION_VALUES = {100, 75, 50, 25, 0};

    public enum Subscale { PERSONAL, WORK }

    public enum Scale { FREQUENCY, DEGREE }

    public enum Level { LOW, HIGH, VERY_HIGH }

    public record Item(int no, Subscale subscale, Scale scale, String text, boolean reverse) {
    }

    public static final Map<Scale, List<String>> SCALE_LABELS = Map.of(
            Scale.FREQUENCY, List.of("항상", "자주", "가끔", "드물게", "전혀 또는 거의 없음"),
            Scale.DEGREE, List.of("매우 많이", "많이", "어느 정도", "조금", "거의 없음"));

    public static final List<Item> ITEMS = List.of(
            new Item(1, Subscale.PERSONAL, Scale.FREQUENCY, "얼마나 자주 피곤함을 느끼나요?", false),
            new Item(2, Subscale.PERSONAL, Scale.FREQUENCY, "얼마나 자주 몸이 지쳐 있나요?", false),
            new Item(3, Subscale.PERSONAL, Scale.FREQUENCY, "얼마나 자주 마음이 지쳐 있나요?", false),
            new Item(4, Subscale.PERSONAL, Scale.FREQUENCY, "얼마나 자주 '더는 못 버티겠다'고 생각하나요?", false),
            new Item(5, Subscale.PERSONAL, Scale.FREQUENCY, "얼마나 자주 기진맥진하다고 느끼나요?", false),
            new Item(6, Subscale.PERSONAL, Scale.FREQUENCY, "얼마나 자주 몸이 약해져 병에 걸리기 쉽다고 느끼나요?", false),
            new Item(7, Subscale.WORK, Scale.DEGREE, "지금 하는 일·학업이 감정적으로 지치게 하나요?", false),
            new Item(8, Subscale.WORK, Scale.DEGREE, "일·학업 때문에 다 타버린 느낌이 드나요?", false),
            new Item(9, Subscale.WORK, Scale.DEGREE, "일·학업 때문에 좌절감을 느끼나요?", false),
            new Item(10, Subscale.WORK, Scale.FREQUENCY, "하루 일과가 끝나면 기진맥진한가요?", false),
            new Item(11, Subscale.WORK, Scale.FREQUENCY, "아침에 또 하루를 보낼 생각만 해도 지치나요?", false),
            new Item(12, Subscale.WORK, Scale.FREQUENCY, "일·학업을 하는 매 시간이 힘들게 느껴지나요?", false),
            new Item(13, Subscale.WORK, Scale.FREQUENCY, "여가 시간에 가족·친구와 보낼 에너지가 충분한가요?", true));

    private CbiQuestionnaire() {
    }

    public static Item item(int no) {
        if (no < 1 || no > ITEM_COUNT) {
            throw new IllegalArgumentException("문항 번호는 1~13이어야 합니다: " + no);
        }
        return ITEMS.get(no - 1);
    }

    /** 원래 선택(0~4)을 점수로 바꾼다. 역문항은 뒤집는다. */
    public static int value(int no, int option) {
        if (option < 0 || option > 4) {
            throw new IllegalArgumentException("보기는 0~4여야 합니다: " + option);
        }
        int value = OPTION_VALUES[option];
        return item(no).reverse() ? 100 - value : value;
    }

    /**
     * 정확히 13개 문항(1~13 각 1번)의 답으로 채점한다.
     *
     * @param options 문항 번호 → 원래 선택(0~4)
     */
    public static Score score(Map<Integer, Integer> options) {
        Objects.requireNonNull(options, "options");
        if (options.size() != ITEM_COUNT) {
            throw new IllegalArgumentException("13개 문항에 모두 답해야 합니다");
        }
        int personalSum = 0;
        int personalCount = 0;
        int workSum = 0;
        int workCount = 0;
        for (Item item : ITEMS) {
            Integer option = options.get(item.no());
            if (option == null) {
                throw new IllegalArgumentException("답이 없는 문항이 있습니다: " + item.no());
            }
            int value = value(item.no(), option);
            if (item.subscale() == Subscale.PERSONAL) {
                personalSum += value;
                personalCount++;
            } else {
                workSum += value;
                workCount++;
            }
        }
        BigDecimal personal = average(personalSum, personalCount);
        BigDecimal work = average(workSum, workCount);
        boolean highRisk = personal.compareTo(HIGH_RISK_THRESHOLD) >= 0 || work.compareTo(HIGH_RISK_THRESHOLD) >= 0;
        return new Score(personal, work, highRisk);
    }

    /** 표시 수준. */
    public static Level level(BigDecimal score) {
        if (score.compareTo(HIGH_RISK_THRESHOLD) >= 0) {
            return Level.VERY_HIGH;
        }
        return score.compareTo(HIGH_THRESHOLD) >= 0 ? Level.HIGH : Level.LOW;
    }

    /** DB DECIMAL(5,2)에 맞춰 소수 둘째 자리로 반올림한 평균. */
    private static BigDecimal average(int sum, int count) {
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /** 채점 결과(소수 둘째 자리). */
    public record Score(BigDecimal personal, BigDecimal work, boolean highRisk) {
    }
}
