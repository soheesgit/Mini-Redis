-- 빈 데이터 디렉터리에서 실행한다.
INSERT INTO users (name, email) VALUES ('소희', 'sohee@example.com');
INSERT INTO users (name, email) VALUES ('지호', 'jiho@example.com');
UPDATE users SET name = '문소희' WHERE id = 1;
DELETE FROM users WHERE id = 2;
SELECT id, name FROM users WHERE id >= 1 AND name != '';
