# Verify MySQL Korean Seed Encoding

`init.sql` is UTF-8 and forces the seed session to use `utf8mb4`.
The Docker MySQL service also starts with `utf8mb4` server defaults.

After a fresh Docker startup, verify Korean seed text with:

```bash
docker exec -i jobe-mysql mysql -uroot -proot --default-character-set=utf8mb4 -D jobe < scripts/verify-mysql-encoding.sql
```

Expected:

- The first query returns readable Korean rows such as `심리학과`, `경영학과`, and `컴퓨터공학과`.
- The second query returns no rows.

If an existing local Docker volume was created before the encoding fix, it may still contain mojibake data. Reset only when local data can be discarded:

```bash
docker compose down -v
docker compose up -d --build
```

