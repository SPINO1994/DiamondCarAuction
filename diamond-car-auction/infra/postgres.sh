#!/bin/sh
set -eu
for service in users auctions orders notifications; do
  eval 'password=${'"${service}_PASSWORD"':-diamond-'"$service"'-local}'
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v role="$service" -v password="$password" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'password';
CREATE DATABASE :"role" OWNER :"role";
REVOKE CONNECT ON DATABASE :"role" FROM PUBLIC;
SQL
done
