package com.comedown.cbi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 문항별 응답(cbi_answers). answer_option은 원래 선택(0~4), answer_value는 역채점까지 반영한 점수.
 */
@Entity
@Table(name = "cbi_answers")
@IdClass(CbiAnswer.Key.class)
public class CbiAnswer implements Persistable<CbiAnswer.Key> {

    @Id
    @Column(name = "assessment_id", nullable = false)
    private long assessmentId;

    @Id
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "item_no", nullable = false)
    private int itemNo;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "answer_option", nullable = false)
    private int answerOption;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "answer_value", nullable = false)
    private int answerValue;

    @Transient
    private boolean isNew;

    protected CbiAnswer() {
    }

    public static CbiAnswer of(long assessmentId, int itemNo, int option) {
        CbiAnswer a = new CbiAnswer();
        a.assessmentId = assessmentId;
        a.itemNo = itemNo;
        a.answerOption = option;
        a.answerValue = CbiQuestionnaire.value(itemNo, option);
        a.isNew = true;
        return a;
    }

    @Override
    public Key getId() {
        return new Key(assessmentId, itemNo);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public static class Key implements Serializable {

        private long assessmentId;
        private int itemNo;

        protected Key() {
        }

        public Key(long assessmentId, int itemNo) {
            this.assessmentId = assessmentId;
            this.itemNo = itemNo;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && assessmentId == other.assessmentId && itemNo == other.itemNo;
        }

        @Override
        public int hashCode() {
            return Objects.hash(assessmentId, itemNo);
        }
    }
}
