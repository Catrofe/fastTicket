#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 \
  -v admin_user="$DB_ADMIN_USER" -v admin_password="$DB_ADMIN_PASSWORD" \
  -v app_user="$DB_USER" -v app_password="$DB_PASSWORD" \
  -v dbname="$POSTGRES_DB" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -f /sql/init-roles.sql
