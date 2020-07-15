DROP TABLE IF EXISTS AM_KEY_MANAGER;
CREATE TABLE  IF NOT EXISTS AM_KEY_MANAGER (
  UUID VARCHAR(50) NOT NULL,
  NAME VARCHAR(100) NULL,
  DISPLAY_NAME VARCHAR(100) NULL,
  DESCRIPTION VARCHAR(256) NULL,
  TYPE VARCHAR(45) NULL,
  CONFIGURATION BYTEA NULL,
  ENABLED BOOLEAN DEFAULT '1',
  TENANT_DOMAIN VARCHAR(100) NULL,
  PRIMARY KEY (UUID),
  UNIQUE (NAME,TENANT_DOMAIN)
);

DROP TABLE IF EXISTS AM_GW_PUBLISHED_API_DETAILS;
CREATE TABLE IF NOT EXISTS AM_GW_PUBLISHED_API_DETAILS (
  API_ID varchar(255) NOT NULL,
  TENANT_DOMAIN varchar(255),
  API_PROVIDER varchar(255),
  API_NAME varchar(255),
  API_VERSION varchar(255),
  PRIMARY KEY (API_ID)
);

DROP TABLE IF EXISTS AM_GW_API_ARTIFACTS;
CREATE TABLE IF NOT EXISTS AM_GW_API_ARTIFACTS (
  API_ID varchar(255) NOT NULL,
  ARTIFACT BYTEA,
  GATEWAY_INSTRUCTION varchar(20),
  GATEWAY_LABEL varchar(255),
  TIME_STAMP TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (GATEWAY_LABEL, API_ID),
  FOREIGN KEY (API_ID) REFERENCES AM_GW_PUBLISHED_API_DETAILS(API_ID) ON UPDATE CASCADE ON DELETE NO ACTION
);

CREATE OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.TIME_STAMP= now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER TIME_STAMP AFTER UPDATE ON AM_GW_API_ARTIFACTS FOR EACH ROW EXECUTE PROCEDURE  update_modified_column();

DO $$ DECLARE con_name varchar(200);
BEGIN
SELECT 'ALTER TABLE AM_APPLICATION_REGISTRATION DROP CONSTRAINT ' || tc .constraint_name || ';' INTO con_name
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu ON tc.constraint_name = kcu.constraint_name
WHERE constraint_type = 'UNIQUE' AND tc.table_name = 'am_application_registration' AND kcu.column_name = 'token_type';

EXECUTE con_name;
END $$;

ALTER TABLE AM_APPLICATION_REGISTRATION
    ADD KEY_MANAGER VARCHAR(255) DEFAULT 'Default',
    ADD UNIQUE (SUBSCRIBER_ID,APP_ID,TOKEN_TYPE,KEY_MANAGER);

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
ALTER TABLE AM_APPLICATION_KEY_MAPPING
    ADD UUID VARCHAR(50) NOT NULL DEFAULT uuid_generate_v1(),
    ADD KEY_MANAGER VARCHAR(50) NOT NULL DEFAULT 'Default',
    ADD APP_INFO BYTEA NULL,
    ADD CONSTRAINT application_key_unique UNIQUE(APPLICATION_ID,KEY_TYPE,KEY_MANAGER);

DO $$ DECLARE con_name varchar(200);
BEGIN
SELECT 'ALTER TABLE AM_APPLICATION_KEY_MAPPING DROP CONSTRAINT ' || tc .constraint_name || ';' INTO con_name
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu ON tc.constraint_name = kcu.constraint_name
WHERE constraint_type = 'PRIMARY KEY' AND tc.table_name = 'am_application_key_mapping';
EXECUTE con_name;
END $$;

ALTER TABLE AM_WORKFLOWS
    ADD WF_METADATA BYTEA NULL,
    ADD WF_PROPERTIES BYTEA NULL;

ALTER TABLE AM_SUBSCRIPTION ADD TIER_ID_PENDING VARCHAR(50);

ALTER TABLE AM_POLICY_SUBSCRIPTION
    ADD MAX_COMPLEXITY INTEGER NOT NULL DEFAULT 0,
    ADD MAX_DEPTH INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS AM_API_RESOURCE_SCOPE_MAPPING (
    SCOPE_NAME VARCHAR(255) NOT NULL,
    URL_MAPPING_ID INTEGER NOT NULL,
    TENANT_ID INTEGER NOT NULL,
    FOREIGN KEY (URL_MAPPING_ID) REFERENCES   AM_API_URL_MAPPING(URL_MAPPING_ID) ON DELETE CASCADE,
    PRIMARY KEY(SCOPE_NAME, URL_MAPPING_ID)
);

CREATE TABLE IF NOT EXISTS AM_SHARED_SCOPE (
     NAME VARCHAR(255),
     UUID VARCHAR (256),
     TENANT_ID INTEGER,
     PRIMARY KEY (UUID)
);

DO $$ DECLARE con_name varchar(200);
BEGIN SELECT 'ALTER TABLE IDN_OAUTH2_RESOURCE_SCOPE DROP CONSTRAINT ' || tc .constraint_name || ';' INTO con_name
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage AS ccu ON ccu.constraint_name = tc.constraint_name
WHERE constraint_type = 'PRIMARY KEY' AND tc.table_name = 'IDN_OAUTH2_RESOURCE_SCOPE';
EXECUTE con_name;
END $$;

DROP TABLE IF EXISTS AM_TENANT_THEMES;
CREATE TABLE IF NOT EXISTS AM_TENANT_THEMES (
  TENANT_ID INTEGER NOT NULL,
  THEME BYTEA NOT NULL,
  PRIMARY KEY (TENANT_ID)
);

CREATE TABLE IF NOT EXISTS AM_GRAPHQL_COMPLEXITY (
    UUID VARCHAR(256),
    API_ID INTEGER NOT NULL,
    TYPE VARCHAR(256),
    FIELD VARCHAR(256),
    COMPLEXITY_VALUE INTEGER,
    FOREIGN KEY (API_ID) REFERENCES AM_API(API_ID) ON UPDATE CASCADE ON DELETE CASCADE,
    PRIMARY KEY(UUID),
    UNIQUE (API_ID,TYPE,FIELD)
);

UPDATE IDN_OAUTH_CONSUMER_APPS SET CALLBACK_URL="" WHERE CALLBACK_URL IS NULL;
