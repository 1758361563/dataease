#!/bin/sh
# Company BI container entrypoint.
#  - makes the persistent data layout exist,
#  - syncs image-owned static content into the (possibly bind-mounted) data dir,
#  - fails fast with a readable message when required settings are missing,
#  - then execs Java as PID 1; Java owns the private integration executor.
set -eu

DATA=/opt/dataease2.0/data
SEED=/opt/dataease2.0/seed

for d in static-resource map-origin exportData font i18n excel report geo map appearance plugin; do
  mkdir -p "$DATA/$d"
done
mkdir -p /opt/dataease2.0/conf /opt/dataease2.0/cache /opt/dataease2.0/logs/dataease

# Image-owned map files: refreshed from the image on every start.
cp -a "$SEED/map-origin/." "$DATA/map-origin/"

# Default static resources: only fill in what is missing, never overwrite uploads.
( cd "$SEED/static-resource" && find . -type f ) | while IFS= read -r f; do
  if [ ! -e "$DATA/static-resource/$f" ]; then
    mkdir -p "$DATA/static-resource/$(dirname "$f")"
    cp -p "$SEED/static-resource/$f" "$DATA/static-resource/$f"
  fi
done

missing=""
for v in COMPANY_BI_DB_URL COMPANY_BI_DB_USER COMPANY_BI_DB_PASSWORD COMPANY_BI_TOKEN_SECRET; do
  eval "val=\${$v:-}"
  [ -n "$val" ] || missing="$missing $v"
done
if [ -n "$missing" ]; then
  echo "FATAL: required environment variable(s) not set:$missing" >&2
  echo "       (a missing datasource must never fall back to a built-in database)" >&2
  exit 64
fi

echo "companybi release: $(tr '\n' ' ' < /opt/apps/release/RELEASE)"
# JAVA_OPTIONS is the deployment-owned JVM option list, not user input from BI.
# shellcheck disable=SC2086
exec java ${JAVA_OPTIONS:-} -jar "${JAVA_APP_JAR:-/opt/apps/app.jar}" --server.port="${RUNNING_PORT:-8100}"
