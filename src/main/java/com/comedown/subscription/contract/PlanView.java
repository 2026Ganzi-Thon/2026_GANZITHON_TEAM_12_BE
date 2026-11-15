package com.comedown.subscription.contract;

import java.time.Instant;

/**
 * 이용 자격 읽기 모델.
 *
 * @param plan TRIAL / FREE / PREMIUM (체험 기간이 끝났으면 FREE로 본다)
 * @param trialEndsAt 체험 종료 시각(UTC)
 * @param daysLeft 체험 남은 일수(서울 날짜 차이, 0 이상). 체험이 아니면 0
 */
public record PlanView(String plan, Instant trialEndsAt, int daysLeft) {
}
