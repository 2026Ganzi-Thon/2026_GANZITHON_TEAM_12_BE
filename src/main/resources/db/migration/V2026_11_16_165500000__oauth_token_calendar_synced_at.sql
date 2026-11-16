-- 마지막 일정 동기화 시각(account 소유). 일정이 0건인 동기화도 남기려고 일정 행이 아니라 캘린더 연결 행에 둔다.
-- 연결 해제(철회 요청) 때 비우고, 연결 행이 지워지면 함께 사라진다.
ALTER TABLE oauth_tokens ADD COLUMN calendar_synced_at DATETIME(6) NULL AFTER revoke_requested_at;

-- 이미 동기화한 사용자는 저장된 일정의 마지막 동기화 시각으로 채운다.
UPDATE oauth_tokens t
  JOIN (SELECT user_id, MAX(synced_at) AS last_synced_at FROM calendar_events GROUP BY user_id) e
    ON e.user_id = t.user_id
   SET t.calendar_synced_at = e.last_synced_at
 WHERE t.revoke_requested_at IS NULL;
