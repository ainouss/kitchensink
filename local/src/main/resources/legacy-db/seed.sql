-- Representative legacy data: ROWS members after member 0, always the same rows.
-- About 0.5 % of them break today's rules, like old production data:
--   every 997th   a name with a digit
--   every 1009th  a name of 30 characters
--   every 1013th  a phone with dashes
--   every 1019th  a phone of 13 digits
--   every 1021st  an email with two @
--   every 1031st  an email equal to the previous one but for its case (allowed: the unique index is case-sensitive)
-- Names mix accents and case, so the sort order depends on the collation.
-- ROWS is replaced by the tool before this runs.

CREATE TEMPORARY TABLE first_names (n int PRIMARY KEY, v text NOT NULL);
INSERT INTO first_names VALUES
  (0,'Ada'),(1,'émile'),(2,'Zoë'),(3,'zoe'),(4,'Ørsted'),(5,'Ñúñez'),(6,'Grace'),(7,'Mário'),(8,'Łukasz'),(9,'Chloé'),
  (10,'Søren'),(11,'Ana'),(12,'ANNA'),(13,'Jürgen'),(14,'Dorothy'),(15,'Kateřina'),(16,'Björn'),(17,'Aoife'),
  (18,'Émilie'),(19,'margaret'),(20,'Yasmine'),(21,'Hedy'),(22,'Çağrı'),(23,'Ingrid');
CREATE TEMPORARY TABLE last_names (n int PRIMARY KEY, v text NOT NULL);
INSERT INTO last_names VALUES
  (0,'Lovelace'),(1,'Hopper'),(2,'Durand'),(3,'de la Fontaine'),(4,'Østergaard'),(5,'García'),(6,'Müller'),
  (7,'O''Brien'),(8,'Nguyen'),(9,'smith'),(10,'Smith'),(11,'Okafor'),(12,'Kowalska'),(13,'Lamarr'),(14,'Ünal'),
  (15,'Rossi'),(16,'Haddad'),(17,'van Dijk'),(18,'Svensson'),(19,'Ngô');

INSERT INTO member (id, name, email, phone_number)
SELECT i,
       CASE
           WHEN i % 997 = 0  THEN f.v || ' ' || l.v || ' ' || (i % 10)
           WHEN i % 1009 = 0 THEN rpad(f.v || ' ' || l.v, 30, 'x')
           ELSE left(f.v || ' ' || l.v, 25)
       END,
       CASE
           WHEN i % 1021 = 0 THEN 'member' || i || '@@mailinator.com'
           WHEN i % 1031 = 0 THEN upper('member' || (i - 1)) || '@mailinator.com'
           ELSE 'member' || i || '@mailinator.com'
       END,
       CASE
           WHEN i % 1013 = 0 THEN '212-555-' || lpad((i % 10000)::text, 4, '0')
           WHEN i % 1019 = 0 THEN lpad((i % 9999999999999)::text, 13, '0')
           ELSE lpad((2120000000 + i % 999999999)::text, 10, '0')
       END
FROM generate_series(1, ROWS) AS i
JOIN first_names f ON f.n = i % 24
JOIN last_names  l ON l.n = (i / 24) % 20;

-- The sequence above every id, with room for Hibernate's block of 50.
SELECT setval('member_seq', (SELECT max(id) FROM member) + 100);

ANALYZE member;
