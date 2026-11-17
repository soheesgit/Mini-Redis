-- 사용자가 채우는 열은 name과 email이다. id는 저장할 때 자동으로 부여된다.
INSERT INTO users (name, email) VALUES ('소희', 'sohee@example.com');
-- SQL 문자열의 작은따옴표 두 개는 값에서 작은따옴표 한 개가 된다.
INSERT INTO users (name, email) VALUES ('O''Brien', 'obrien@example.com');
SELECT * FROM users;
-- email로 행을 고르고 출력에는 id와 name만 포함한다.
SELECT id, name FROM users WHERE email = 'sohee@example.com';
