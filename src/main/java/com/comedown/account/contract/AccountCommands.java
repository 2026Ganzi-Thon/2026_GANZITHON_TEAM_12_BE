package com.comedown.account.contract;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 계정 쓰기 계약(users·user_consents·user_settings·user_preferred_activities는 account만 쓴다).
 * 호출하는 쪽의 트랜잭션에 참여한다.
 */
public interface AccountCommands {

    /**
     * @param consents ConsentType 이름 → 동의 여부. 6개(TERMS·PRIVACY·AGE_14·SENSITIVE_HRV·CALENDAR·AI_NOTICE)
     *                 모두 true여야 한다.
     */
    record Profile(String nickname, Map<String, Boolean> consents, String consentVersion) {
    }

    /** @param preferredActivities ActivityCode 이름(1개 이상, 중복·미등록 코드 불가) */
    record Settings(LocalTime measureTime, LocalTime sleepStart, LocalTime sleepEnd, List<String> preferredActivities) {
    }

    /**
     * 닉네임·동의를 저장하고 PROFILE 단계를 마친다(이미 지난 단계는 되돌리지 않음). 400 검증 실패.
     *
     * @return 처리 후 현재 단계
     */
    String saveProfile(long userId, Profile profile);

    /**
     * 생활 정보를 통째로 바꾸고 LIFESTYLE 단계를 마친다. 프로필·CBI 단계 전이면 409 ONBOARDING_STEP_INVALID.
     * hrvEnabled는 건드리지 않는다(동의 API로만 바뀐다).
     *
     * @return 처리 후 현재 단계
     */
    String replaceSettings(long userId, Settings settings);

    /** 건강정보(SENSITIVE_HRV) 동의를 철회하고 측정을 끈다. 유효한 동의가 없어도 측정은 꺼 둔다(재시도 안전). */
    void withdrawHealthConsent(long userId);
}
