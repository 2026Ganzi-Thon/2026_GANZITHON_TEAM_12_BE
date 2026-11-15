package com.comedown.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 제목 규칙 분류(classify-rule-v1). 규칙표는 docs/계산_정책.md 1절과 같다. */
class RuleClassifierTest {

    private static Classification classify(String title) {
        return RuleClassifier.classify(title, false);
    }

    @ParameterizedTest
    @CsvSource({
            "자료구조 중간고사, EXAM",
            "토익 시험, EXAM",
            "Midterm, EXAM",
            "캡스톤 발표, PRESENTATION",
            "Final Presentation, PRESENTATION",
            "운영체제 과제 제출, ASSIGNMENT",
            "레포트 마감, ASSIGNMENT",
            "HW3 due, ASSIGNMENT",
            "캡스톤 회의, WORK_MEETING",
            "팀플, WORK_MEETING",
            "카페 알바, PART_TIME",
            "동아리 정기 세션, CLUB",
            "알고리즘 수업, CLASS",
            "3교시, CLASS",
            "도서관 자습, STUDY",
            "스터디, STUDY",
            "헬스, EXERCISE",
            "저녁 약속, APPOINTMENT",
            "치과, APPOINTMENT",
            "가족 여행, PERSONAL",
            "산책, PERSONAL",
            "면접, OTHER",
            "ㅇㅇ, OTHER",
    })
    void 제목_키워드로_카테고리를_정한다(String title, EventCategory expected) {
        assertThat(classify(title).category()).isEqualTo(expected);
    }

    @Test
    void 규칙_분류의_출처는_RULE이고_부담_정도는_정하지_않는다() {
        Classification c = classify("운영체제 과제 제출");

        assertThat(c.source()).isEqualTo(CategorySource.RULE);
        assertThat(c.burden()).isNull();
    }

    @Test
    void 마감은_과제_시험_발표만이고_미리_시작할_수_있는_일은_과제_발표만이다() {
        assertThat(classify("과제 제출")).extracting(Classification::deadline, Classification::adjustable)
                .containsExactly(true, true);
        assertThat(classify("캡스톤 발표")).extracting(Classification::deadline, Classification::adjustable)
                .containsExactly(true, true);
        assertThat(classify("기말고사")).extracting(Classification::deadline, Classification::adjustable)
                .containsExactly(true, false);
        assertThat(classify("캡스톤 회의")).extracting(Classification::deadline, Classification::adjustable)
                .containsExactly(false, false);
        assertThat(classify("헬스")).extracting(Classification::deadline, Classification::adjustable)
                .containsExactly(false, false);
    }

    @ParameterizedTest
    @CsvSource({
            "시험 공부",
            "발표 준비",
            "발표 리허설",
            "과제 스터디",
            "midterm study",
    })
    void 시험_발표_과제의_준비는_공부로_보고_마감으로_세지_않는다(String title) {
        Classification c = classify(title);

        assertThat(c.category()).isEqualTo(EventCategory.STUDY);
        assertThat(c.deadline()).isFalse();
    }

    @Test
    void 시험_발표_과제_키워드가_없으면_준비라는_말만으로_공부가_되지는_않는다() {
        assertThat(classify("여행 준비").category()).isEqualTo(EventCategory.PERSONAL);
        assertThat(classify("회의 준비").category()).isEqualTo(EventCategory.WORK_MEETING);
    }

    @Test
    void 앞선_규칙이_먼저_적용된다() {
        assertThat(classify("팀플 발표").category()).isEqualTo(EventCategory.PRESENTATION); // 발표 > 회의
        assertThat(classify("과제 회의").category()).isEqualTo(EventCategory.ASSIGNMENT); // 과제 > 회의
        assertThat(classify("동아리 회의").category()).isEqualTo(EventCategory.WORK_MEETING); // 회의 > 동아리
        assertThat(classify("조모임").category()).isEqualTo(EventCategory.WORK_MEETING); // "모임"(약속)보다 먼저
        assertThat(classify("마감 알바").category()).isEqualTo(EventCategory.PART_TIME); // 근무 > 과제 마감
        assertThat(classify("카페 알바").category()).isEqualTo(EventCategory.PART_TIME); // 근무 > 약속(카페)
    }

    @Test
    void 영문_키워드는_단어_단위로만_맞춘다() {
        assertThat(classify("classic concert").category()).isEqualTo(EventCategory.OTHER); // class가 아니다
        assertThat(classify("overdue books").category()).isEqualTo(EventCategory.OTHER); // due가 아니다
        assertThat(classify("Lab 3").category()).isEqualTo(EventCategory.CLASS);
        assertThat(classify("Team   MEETING").category()).isEqualTo(EventCategory.WORK_MEETING); // 대소문자·공백 정규화
    }

    @Test
    void 제목이_없으면_OTHER() {
        assertThat(classify(null).category()).isEqualTo(EventCategory.OTHER);
        assertThat(classify("   ").category()).isEqualTo(EventCategory.OTHER);
    }

    @Test
    void 앱이_만든_일정은_제목과_무관하게_RECOVERY이고_마감이_아니다() {
        Classification c = RuleClassifier.classify("과제 미리 시작하기", true);

        assertThat(c.category()).isEqualTo(EventCategory.RECOVERY);
        assertThat(c.deadline()).isFalse();
        assertThat(c.source()).isEqualTo(CategorySource.RULE);
    }
}
