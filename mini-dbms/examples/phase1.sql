INSERT INTO users (name, email) VALUES ('소희', 'sohee@example.com');
INSERT INTO users (name, email) VALUES ('O''Brien', 'obrien@example.com');
SELECT * FROM users;
SELECT id, name FROM users WHERE email = 'sohee@example.com';
