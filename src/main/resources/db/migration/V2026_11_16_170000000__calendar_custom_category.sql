-- 카테고리 변경 화면(B2-1)에서 "기타"를 고르고 사용자가 직접 입력한 카테고리 이름(calendar 소유).
-- category = 'OTHER'이고 직접 수정한 분류일 때만 값이 있다.
ALTER TABLE calendar_events ADD COLUMN custom_category VARCHAR(20) NULL AFTER category;
ALTER TABLE event_category_overrides ADD COLUMN custom_category VARCHAR(20) NULL AFTER category;
