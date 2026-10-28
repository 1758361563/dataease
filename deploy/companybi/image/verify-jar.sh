#!/bin/sh
# Hard gate for the built jar + schema manifest generation.
#
# usage: verify-jar.sh <CoreApplication.jar> <frontend-dist-dir> <out-dir>
# cwd must be the repository root inside the build stage (/build).
#
# Writes:
#   <out>/JAR.SHA256   sha256 of the jar
#   <out>/SCHEMA       key=value schema manifest + schema.fingerprint
#   <out>/CONTENT-CHECK  human readable counts of what was verified
set -eu

JAR="${1:?jar}"
DIST="${2:?dist}"
OUT="${3:?out}"
RES=core/core-backend/src/main/resources
INIT=core/core-backend/src/main/java/io/dataease/company/bi/auth/CompanyBiSchemaInitializer.java

fail() { echo "VERIFY FAILED: $*" >&2; exit 1; }

[ -s "$JAR" ] || fail "jar not found: $JAR"
[ -d "$DIST" ] || fail "frontend dist not found: $DIST"
mkdir -p "$OUT"

T=$(mktemp -d)
LIST="$T/jar.list"
jar tf "$JAR" > "$LIST"
need() { grep -qxF "$1" "$LIST" || fail "jar is missing $1"; }

# --- 1. frontend inside the jar ------------------------------------------
need BOOT-INF/classes/static/index.html
need BOOT-INF/classes/static/panel.html
( cd "$DIST" && find . -type f | sed 's|^\./|BOOT-INF/classes/static/|' | LC_ALL=C sort ) > "$T/want"
grep '^BOOT-INF/classes/static/.*[^/]$' "$LIST" | LC_ALL=C sort > "$T/have"
missing=$(LC_ALL=C comm -23 "$T/want" "$T/have" | head -5)
[ -z "$missing" ] || fail "frontend dist files absent from jar, e.g.: $missing"
dist_n=$(wc -l < "$T/want" | tr -d ' ')
[ "$dist_n" -ge 50 ] || fail "frontend dist looks too small ($dist_n files)"

# --- 2. company backend code ---------------------------------------------
for c in \
  io/dataease/company/bi/auth/CompanyLoginServer \
  io/dataease/company/bi/auth/CompanyBiSchemaInitializer \
  io/dataease/company/bi/auth/CompanyTokenSecret \
  io/dataease/company/bi/permission/CompanyCapabilityPathFilter \
  io/dataease/company/bi/admin/user/CompanyUserAdminController \
  io/dataease/company/bi/feishu/CompanyFeishuService \
  io/dataease/company/bi/feishu/CompanyFeishuWorker \
  io/dataease/company/bi/integration/IntegrationConnectionStore \
  io/dataease/company/bi/etl/EtlController \
  io/dataease/company/bi/etl/EtlChainRunner \
  io/dataease/company/bi/etl/EtlQuartzJob \
  io/dataease/chart/utils/SingleCategoryRank \
  io/dataease/chart/charts/impl/DefaultChartHandler \
  io/dataease/CoreApplication ; do
  need "BOOT-INF/classes/$c.class"
done
company_classes=$(grep -c '^BOOT-INF/classes/io/dataease/company/bi/.*\.class$' "$LIST" || true)
src_company=$(find core/core-backend/src/main/java/io/dataease/company/bi -name '*.java' | wc -l | tr -d ' ')
[ "$company_classes" -ge "$src_company" ] || fail "company classes in jar ($company_classes) < sources ($src_company)"

# --- 3. database initialisation / migration resources --------------------
for f in "$RES"/db/company/*.sql; do need "BOOT-INF/classes/db/company/$(basename "$f")"; done
for f in "$RES"/db/migration/*.sql; do need "BOOT-INF/classes/db/migration/$(basename "$f")"; done
need BOOT-INF/classes/application.yml
need BOOT-INF/classes/application-standalone.yml
grep -q '^BOOT-INF/lib/mysql-connector-j-' "$LIST" || fail "mysql-connector-j missing from BOOT-INF/lib"
grep -q '^BOOT-INF/lib/flyway-core-' "$LIST" || fail "flyway-core missing from BOOT-INF/lib"

# --- 4. no datasource secret compiled in ---------------------------------
( cd "$T" && jar xf "$OLDPWD/$JAR" BOOT-INF/classes )
if grep -rIlE '39\.101\.192\.183|123\.57\.152\.118|Password123@mysql' "$T/BOOT-INF/classes" >"$T/leak" 2>/dev/null && [ -s "$T/leak" ]; then
  cat "$T/leak" >&2
  fail "datasource host/credential literal found inside jar classes dir (see files above)"
fi
grep -q '\${COMPANY_BI_DB_URL}' "$T/BOOT-INF/classes/application-standalone.yml" || fail "standalone yml was not sanitized"
grep -q 'COMPANY_BI_TOKEN_SECRET' "$T/BOOT-INF/classes/application.yml" || fail "application.yml lost the token-secret placeholder"

# --- 5. outputs -----------------------------------------------------------
sha256sum "$JAR" | sed 's|  .*|  CoreApplication.jar|' > "$OUT/JAR.SHA256"

flyway_n=$(ls "$RES"/db/migration/*.sql | wc -l | tr -d ' ')
company_sql_n=$(ls "$RES"/db/company/*.sql | wc -l | tr -d ' ')
{
  echo "frontend_dist_files=$dist_n"
  echo "company_classes=$company_classes"
  echo "company_sources=$src_company"
  echo "flyway_migrations=$flyway_n"
  echo "company_schema_sql=$company_sql_n"
  echo "jar_entries=$(wc -l < "$LIST" | tr -d ' ')"
} > "$OUT/CONTENT-CHECK"

risk_re='(^|[^A-Za-z_])(DROP[[:space:]]+(TABLE|COLUMN|INDEX|KEY|DATABASE|FOREIGN|PRIMARY)|TRUNCATE|DELETE[[:space:]]+FROM|RENAME[[:space:]]+(TABLE|COLUMN|TO)|MODIFY[[:space:]]+(COLUMN)?|CHANGE[[:space:]]+COLUMN)'
{
  for f in "$RES"/db/migration/*.sql; do
    printf 'flyway.file.%s=%s\n' "$(basename "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
    grep -qiE "$risk_re" "$f" && printf 'risk.flyway.%s=destructive-keyword\n' "$(basename "$f")" || true
  done
  for f in "$RES"/db/company/*.sql; do
    printf 'company.sql.%s=%s\n' "$(basename "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
    grep -qiE "$risk_re" "$f" && printf 'risk.company.%s=destructive-keyword\n' "$(basename "$f")" || true
  done
  printf 'company.initializer=%s\n' "$(sha256sum "$INIT" | cut -d' ' -f1)"
  grep -qiE "$risk_re" "$INIT" && printf 'risk.company.initializer=destructive-keyword\n' || true
} | LC_ALL=C sort > "$T/schema.body"
fp=$(sha256sum "$T/schema.body" | cut -d' ' -f1)
{ cat "$T/schema.body"; echo "schema.fingerprint=$fp"; } > "$OUT/SCHEMA"

rm -rf "$T"
echo "verify-jar: OK  (frontend=$dist_n files, company classes=$company_classes, flyway=$flyway_n, company sql=$company_sql_n)"
echo "verify-jar: schema.fingerprint=$fp"
