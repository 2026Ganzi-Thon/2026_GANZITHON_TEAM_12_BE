package com.comedown.calendar.application;

import com.comedown.calendar.contract.CalendarCategoryCommands;
import com.comedown.calendar.contract.CategoryChange;
import com.comedown.calendar.contract.CategoryChangeResult;
import com.comedown.calendar.domain.CategoryOverride;
import com.comedown.calendar.domain.CategoryScope;
import com.comedown.calendar.domain.CategorySource;
import com.comedown.calendar.domain.Classification;
import com.comedown.calendar.domain.ClassificationResolver;
import com.comedown.calendar.domain.EventCategory;
import com.comedown.calendar.domain.RuleClassifier;
import com.comedown.calendar.infrastructure.persistence.CalendarEventStore;
import com.comedown.calendar.infrastructure.persistence.CategoryOverrideStore;
import com.comedown.calendar.infrastructure.persistence.StoredEvent;
import com.comedown.shared.error.ApiException;
import com.comedown.shared.error.ErrorCode;
import com.comedown.shared.time.ServiceTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일정 분류 직접 수정. 규칙을 저장하고(같은 범위·키는 덮어씀) 범위에 드는 저장된 일정에 우선순위대로 다시 적용한다.
 * 원본 Google 일정은 바꾸지 않는다.
 */
@Service
public class CategoryChangeService implements CalendarCategoryCommands {

    static final int CUSTOM_CATEGORY_MAX = 20;

    private final CalendarEventStore store;
    private final CategoryOverrideStore overrides;
    private final ServiceTime time;

    public CategoryChangeService(CalendarEventStore store, CategoryOverrideStore overrides, ServiceTime time) {
        this.store = store;
        this.overrides = overrides;
        this.time = time;
    }

    @Override
    @Transactional
    public CategoryChangeResult change(long userId, long eventId, CategoryChange change) {
        EventCategory category = parse(EventCategory.class, change.category(), "알 수 없는 카테고리예요.");
        CategoryScope scope = parse(CategoryScope.class, change.scope(), "알 수 없는 적용 범위예요.");
        if (change.burden() != null && (change.burden() < 1 || change.burden() > 3)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "부담 정도는 1~3이어야 해요.");
        }
        String customCategory = customCategory(category, change.customCategory());
        // 카테고리 변경 화면에는 부담 정도·미리 시작 가능 여부 입력이 없다. 안 보내면 부담 정도는 비워 두고,
        // 미리 시작 가능 여부는 제목 규칙 분류와 같은 카테고리 기본값(과제·발표만 true)을 쓴다.
        boolean adjustable = change.adjustable() != null
                ? change.adjustable() : RuleClassifier.adjustableByDefault(category);
        StoredEvent target = store.findById(userId, eventId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        String matchKey = switch (scope) {
            case THIS -> target.googleEventId();
            case SERIES -> required(target.recurringEventId(), "반복 일정이 아니에요.");
            case TITLE -> required(target.titleHash(), "같은 제목으로 묶을 수 없는 일정이에요.");
        };
        Instant now = time.now();
        overrides.upsert(userId, new CategoryOverride(scope, matchKey, category, change.burden(),
                adjustable, change.deadline(), customCategory), now);

        List<StoredEvent> affected = switch (scope) {
            case THIS -> List.of(target);
            case SERIES -> store.findByRecurringId(userId, matchKey);
            case TITLE -> store.findByTitleHash(userId, matchKey);
        };
        List<CategoryOverride> rules = overrides.findAll(userId);
        int updated = 0;
        TreeSet<LocalDate> weeks = new TreeSet<>();
        for (StoredEvent event : affected) {
            Classification stored = event.classification();
            Classification automatic = stored != null && stored.source() != CategorySource.USER ? stored : null;
            Classification resolved = ClassificationResolver.resolve(event.keys(), rules, stored, automatic);
            if (resolved != null && !resolved.equals(stored)) {
                store.updateClassification(event.id(), resolved, now);
                updated++;
                weeks.addAll(weekStartsOf(event));
            }
        }
        return new CategoryChangeResult(updated, List.copyOf(weeks));
    }

    /** 일정이 걸친 모든 주의 월요일. 종일·여러 날 일정은 여러 주에 걸칠 수 있다. */
    private List<LocalDate> weekStartsOf(StoredEvent event) {
        LocalDate first = time.weekStartOf(time.toServiceDate(event.startAt()));
        LocalDate last = time.weekStartOf(time.toServiceDate(event.endAt().minusNanos(1)));
        List<LocalDate> weeks = new java.util.ArrayList<>();
        for (LocalDate week = first; !week.isAfter(last); week = week.plusWeeks(1)) {
            weeks.add(week);
        }
        return weeks;
    }

    /** 직접 입력한 카테고리 이름: 앞뒤 공백을 떼고 비어 있으면 없음. "기타"에서만 쓸 수 있고 20자까지. */
    private static String customCategory(EventCategory category, String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return null;
        }
        if (category != EventCategory.OTHER) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "직접 입력한 카테고리는 기타에서만 쓸 수 있어요.");
        }
        if (name.codePointCount(0, name.length()) > CUSTOM_CATEGORY_MAX) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "카테고리 이름은 " + CUSTOM_CATEGORY_MAX + "자까지 쓸 수 있어요.");
        }
        return name;
    }

    private static String required(String value, String message) {
        if (value == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
        return value;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
    }
}
