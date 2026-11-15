-- 캘린더 연결 해제·탈퇴 때 구글 토큰 철회가 끝나기 전까지 암호화 토큰을 보관하고 연결을 숨긴다(account 소유).
ALTER TABLE oauth_tokens ADD COLUMN revoke_requested_at DATETIME(6) NULL AFTER scopes;
