package com.comedown.calendar.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 제목 키워드로 일정을 분류하는 규칙 분류기({@code classify-rule-v1}). AI 분류가 없거나 실패했을 때 쓰는 대체 분류이며,
 * 규칙표는 docs/계산_정책.md 1절 "일정 자동 분류(규칙)"와 같다. 순수 계산이라 DB·외부 호출에 의존하지 않고,
 * 제목은 분류에만 쓰고 저장하지 않는다.
 *
 * <p>순서대로 처음 맞는 규칙을 쓴다. 한글 키워드는 포함 여부로, 영문 키워드는 단어 단위로 비교한다
 * ("contest"가 "test"에 걸리지 않게).
 */
public final class RuleClassifier {

    public static final String VERSION = "classify-rule-v1";

    private record Rule(EventCategory category, List<Pattern> keywords) {

        boolean matches(String title) {
            for (Pattern keyword : keywords) {
                if (keyword.matcher(title).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Rule EXAM = rule(EventCategory.EXAM,
            "시험", "중간고사", "기말고사", "모의고사", "퀴즈", "exam", "midterm", "finals", "quiz");
    private static final Rule PRESENTATION = rule(EventCategory.PRESENTATION,
            "발표", "프레젠테이션", "피칭", "presentation", "pitch");
    private static final Rule ASSIGNMENT = rule(EventCategory.ASSIGNMENT,
            "과제", "레포트", "리포트", "보고서", "숙제", "제출", "마감",
            "assignment", "homework", "report", "deadline", "due");

    /** 시험·발표·과제 자체가 아니라 그 준비인 일정을 가르는 표시. */
    private static final Rule PREPARATION = rule(EventCategory.STUDY,
            "준비", "공부", "연습", "리허설", "복습", "스터디", "prep", "practice", "rehearsal", "study");

    /** 위에서부터 처음 맞는 규칙을 쓴다. */
    private static final List<Rule> ORDERED = List.of(
            EXAM,
            PRESENTATION,
            // "마감 알바"처럼 근무를 뜻하는 제목이 과제 마감으로 잡히지 않게 과제보다 먼저 본다.
            rule(EventCategory.PART_TIME, "알바", "아르바이트", "근무", "출근", "part-time", "parttime", "shift"),
            ASSIGNMENT,
            rule(EventCategory.WORK_MEETING,
                    "회의", "미팅", "팀플", "조모임", "조별", "회고", "스크럼", "meeting", "scrum", "standup"),
            rule(EventCategory.CLUB, "동아리", "대외활동", "학회", "해커톤", "서포터즈", "봉사", "club", "hackathon"),
            rule(EventCategory.CLASS,
                    "수업", "강의", "실습", "실험", "세미나", "특강", "교시", "lecture", "class", "seminar", "lab"),
            rule(EventCategory.STUDY, "공부", "스터디", "복습", "예습", "자습", "인강", "도서관", "독서실", "study"),
            rule(EventCategory.EXERCISE,
                    "운동", "헬스", "러닝", "조깅", "요가", "필라테스", "수영", "축구", "농구", "풋살", "테니스",
                    "등산", "클라이밍", "gym", "workout"),
            rule(EventCategory.APPOINTMENT,
                    "약속", "점심", "저녁", "식사", "회식", "생일", "데이트", "모임", "술자리", "카페", "병원", "치과",
                    "미용실", "lunch", "dinner", "brunch"),
            rule(EventCategory.PERSONAL,
                    "여행", "휴가", "가족", "청소", "빨래", "이사", "은행", "쇼핑", "장보기", "영화", "공연", "콘서트",
                    "휴식", "산책", "명상", "낮잠", "trip", "vacation", "movie"));

    private RuleClassifier() {
    }

    /**
     * @param title 일정 제목. 없거나 비어 있으면 {@code OTHER}
     * @param createdByApp Come-down이 넣은 일정이면 제목과 무관하게 {@code RECOVERY}(부하·마감·제안 대상이 아니다)
     * @return 규칙 분류(출처 {@code RULE}). 어느 규칙에도 맞지 않으면 {@code OTHER}
     */
    public static Classification classify(String title, boolean createdByApp) {
        if (createdByApp) {
            return of(EventCategory.RECOVERY);
        }
        if (title == null || title.isBlank()) {
            return of(EventCategory.OTHER);
        }
        String normalized = title.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        // "시험 공부", "발표 준비", "과제 스터디"는 시험·발표·마감 자체가 아니므로 마감으로 세지 않는다.
        if (PREPARATION.matches(normalized)
                && (EXAM.matches(normalized) || PRESENTATION.matches(normalized) || ASSIGNMENT.matches(normalized))) {
            return of(EventCategory.STUDY);
        }
        for (Rule rule : ORDERED) {
            if (rule.matches(normalized)) {
                return of(rule.category());
            }
        }
        return of(EventCategory.OTHER);
    }

    /**
     * 카테고리에서 나머지 값을 정한다. 마감 여부는 과제·시험·발표만 true, 미리 시작할 수 있는 일은 과제·발표만 true다.
     * 부담 정도(burden)는 규칙으로 정하지 않는다(null).
     */
    private static Classification of(EventCategory category) {
        boolean deadline = category == EventCategory.ASSIGNMENT || category == EventCategory.EXAM
                || category == EventCategory.PRESENTATION;
        boolean adjustable = category == EventCategory.ASSIGNMENT || category == EventCategory.PRESENTATION;
        return new Classification(category, null, adjustable, deadline, CategorySource.RULE);
    }

    private static Rule rule(EventCategory category, String... keywords) {
        return new Rule(category, java.util.Arrays.stream(keywords).map(RuleClassifier::pattern).toList());
    }

    /** 영문·숫자로만 된 키워드는 단어 단위로, 그 외(한글)는 포함 여부로 비교한다. */
    private static Pattern pattern(String keyword) {
        String quoted = Pattern.quote(keyword);
        return keyword.matches("[a-z0-9-]+")
                ? Pattern.compile("(?<![a-z0-9])" + quoted + "(?![a-z0-9])")
                : Pattern.compile(quoted);
    }
}
