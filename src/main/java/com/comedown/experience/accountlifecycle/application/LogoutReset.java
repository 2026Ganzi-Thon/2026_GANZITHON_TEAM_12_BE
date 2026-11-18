package com.comedown.experience.accountlifecycle.application;

import com.comedown.account.contract.CalendarCredential;
import com.comedown.account.contract.CalendarCredentialProvider;
import com.comedown.calendar.contract.CalendarEventWriter;
import com.comedown.calendar.contract.CalendarEventWriter.DeleteResult;
import com.comedown.recommendation.contract.SuggestionQuery;
import com.comedown.recommendation.contract.SuggestionView;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 시연 모드의 로그아웃 초기화(2026-11-19 결정). {@code comedown.demo.reset-on-logout=true}일 때만 로그아웃이 부른다.
 * <p>
 * 시연은 계정 하나로 여러 번 반복하므로, 로그아웃하면 그 사용자를 가입 전 상태로 되돌린다.
 * <ol>
 *   <li>Come & Down이 구글 캘린더에 넣은 승인 일정을 지운다(앱 표식이 있는 일정만, 실패해도 계속). 다음 시연에서
 *       같은 제안을 다시 승인해 캘린더에 새로 생기는 장면을 보여줄 수 있게 한다.</li>
 *   <li>탈퇴처럼 사용자와 모든 데이터(건강 기록·일정·제안·동의·설정·구독·세션·구글 토큰)를 지운다.</li>
 * </ol>
 * 구글 권한 철회는 하지 않는다(같은 계정으로 바로 다시 가입할 수 있게). 사용자가 직접 넣은 구글 일정은 건드리지 않는다.
 * 구글 호출은 DB 트랜잭션 밖에서 하고, DB 정리는 {@link LifecycleTransactions#resetForLogout} 한 트랜잭션이다.
 */
@Service
public class LogoutReset {

    private static final Logger log = LoggerFactory.getLogger(LogoutReset.class);

    private final SuggestionQuery suggestions;
    private final CalendarCredentialProvider credentials;
    private final CalendarEventWriter writer;
    private final LifecycleTransactions transactions;

    public LogoutReset(SuggestionQuery suggestions, CalendarCredentialProvider credentials,
            CalendarEventWriter writer, LifecycleTransactions transactions) {
        this.suggestions = suggestions;
        this.credentials = credentials;
        this.writer = writer;
        this.transactions = transactions;
    }

    public void reset(long userId) {
        int removed = removeAppEvents(userId);
        transactions.resetForLogout(userId);
        log.info("로그아웃 초기화: userId={}, 지운 앱 일정 {}개", userId, removed);
    }

    /** @return 구글에서 지운 앱 일정 수. 캘린더 자격이 없거나 구글 오류가 나면 그 일정은 건너뛴다. */
    int removeAppEvents(long userId) {
        List<String> eventIds = suggestions.search(userId, null, "APPROVED", null).stream()
                .map(SuggestionView::googleEventId)
                .filter(Objects::nonNull)
                .toList();
        if (eventIds.isEmpty()) {
            return 0;
        }
        CalendarCredential credential;
        try {
            credential = credentials.credentialFor(userId);
        } catch (RuntimeException e) {
            log.warn("로그아웃 초기화: 캘린더 자격이 없어 앱 일정을 지우지 못했습니다(userId={}, {})", userId,
                    e.getClass().getSimpleName());
            return 0;
        }
        int removed = 0;
        for (String eventId : eventIds) {
            try {
                if (writer.deleteAppEvent(credential, eventId) == DeleteResult.DELETED) {
                    removed++;
                }
            } catch (RuntimeException e) {
                log.warn("로그아웃 초기화: 앱 일정 삭제 실패(userId={}, {})", userId, e.getClass().getSimpleName());
            }
        }
        return removed;
    }
}
