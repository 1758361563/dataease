#!/bin/sh
# Replace the hard-coded datasource in application-standalone.yml with required
# placeholders so that no host / account / password is compiled into the jar.
#
# Runs inside the Maven build stage on the *copied* source only; the working
# tree and Git history are not touched.
#
# Fails (build stops) when the file layout is not exactly what we expect, so an
# upstream change to this file is noticed instead of silently masked.
set -eu

f="${1:?usage: sanitize-standalone-yml.sh <application-standalone.yml>}"
[ -f "$f" ] || { echo "sanitize: $f not found" >&2; exit 1; }

n_url=$(grep -cE '^[[:space:]]+url:[[:space:]]*jdbc:mysql://' "$f" || true)
n_user=$(grep -cE '^[[:space:]]+username:' "$f" || true)
n_pwd=$(grep -cE '^[[:space:]]+password:' "$f" || true)
if [ "$n_url" != 1 ] || [ "$n_user" != 1 ] || [ "$n_pwd" != 1 ]; then
  echo "sanitize: unexpected datasource layout (url=$n_url username=$n_user password=$n_pwd)." >&2
  echo "sanitize: review $f and deploy/companybi/image/sanitize-standalone-yml.sh" >&2
  exit 1
fi

sed -i -E \
  -e '/^[[:space:]]*#.*jdbc:/d' \
  -e 's|^([[:space:]]+url:).*$|\1 ${COMPANY_BI_DB_URL}|' \
  -e 's|^([[:space:]]+username:).*$|\1 ${COMPANY_BI_DB_USER}|' \
  -e 's|^([[:space:]]+password:).*$|\1 ${COMPANY_BI_DB_PASSWORD}|' \
  "$f"

for key in COMPANY_BI_DB_URL COMPANY_BI_DB_USER COMPANY_BI_DB_PASSWORD; do
  grep -qF "\${$key}" "$f" || { echo "sanitize: placeholder $key missing after rewrite" >&2; exit 1; }
done
if grep -nE '([0-9]{1,3}\.){3}[0-9]{1,3}|Password123|jdbc:mysql://' "$f"; then
  echo "sanitize: a host/credential literal is still present in $f" >&2
  exit 1
fi
echo "sanitize: $f now reads the datasource only from COMPANY_BI_DB_URL/USER/PASSWORD"
