-- The legacy schema, as a DBA created it in production (Hibernate never creates it).
-- Column widths are wider than the entity's @Size rules: the table holds old rows that today's rules refuse.

CREATE SEQUENCE member_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE member (
    id           bigint       NOT NULL,
    email        varchar(255) NOT NULL,
    name         varchar(255) NOT NULL,
    phone_number varchar(255) NOT NULL,
    CONSTRAINT member_pkey PRIMARY KEY (id),
    CONSTRAINT uk_member_email UNIQUE (email)
);

-- The quickstart's own sample member (its import.sql).
INSERT INTO member (id, name, email, phone_number) VALUES (0, 'John Smith', 'john.smith@mailinator.com', '2125551212');

-- The legacy application's account.
CREATE ROLE kitchensink WITH LOGIN PASSWORD 'kitchensink';
GRANT SELECT, INSERT, UPDATE, DELETE ON member TO kitchensink;
GRANT USAGE, SELECT, UPDATE ON SEQUENCE member_seq TO kitchensink;

-- The migration's account: logical replication (CDC), reads, and writes for the reverse sync.
CREATE ROLE kitchensink_sync WITH LOGIN REPLICATION PASSWORD 'kitchensink_sync';
GRANT SELECT, INSERT, UPDATE, DELETE ON member TO kitchensink_sync;
GRANT USAGE, SELECT, UPDATE ON SEQUENCE member_seq TO kitchensink_sync;
GRANT CREATE ON DATABASE kitchensink TO kitchensink_sync;

-- What Debezium streams. The signal table lets the sync ask for a partial re-snapshot.
CREATE PUBLICATION kitchensink FOR TABLE member;
CREATE TABLE debezium_signal (id varchar(64) PRIMARY KEY, type varchar(32) NOT NULL, data varchar(2048));
GRANT SELECT, INSERT, UPDATE, DELETE ON debezium_signal TO kitchensink_sync;
ALTER PUBLICATION kitchensink ADD TABLE debezium_signal;
