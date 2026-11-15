package com.comedown.calendar.contract;

/** 동기화 저장 결과. {@code kept}는 저장 대상으로 남은(취소 아님) 일정 수. */
public record SyncResult(int kept, int created, int updated, int deleted, int classified) {
}
