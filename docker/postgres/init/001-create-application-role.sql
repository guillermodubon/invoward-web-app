-- Local development role only; never used by a remote or production environment.
CREATE ROLE invoward
    WITH LOGIN
    PASSWORD 'invoward_local'
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION;

REVOKE CONNECT, TEMPORARY ON DATABASE invoward FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE invoward TO invoward;
