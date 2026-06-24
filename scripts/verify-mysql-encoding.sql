SET NAMES utf8mb4;

SELECT id, name, category
FROM majors
WHERE name IN ('심리학과', '경영학과', '컴퓨터공학과')
ORDER BY id;

SELECT id, name, category
FROM majors
WHERE HEX(name) REGEXP 'C3AC|C3AB|C3AA|C3AD|C383|C382'
ORDER BY id;
