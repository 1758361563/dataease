#!/usr/bin/env bash
# Show what is installed, what is actually running, and whether the two agree. Read-only; no secrets.
#
#   status.sh
set -uo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_env
need_cmds docker awk sed
docker info >/dev/null 2>&1 || die "docker daemon is not reachable"

hr
echo "Company BI status   project=$PROJECT   home=$COMPANY_BI_HOME   listen=$BIND:$PORT"
hr
REC="$(state_get CURRENT_VERSION)"
echo "recorded version      : ${REC:-<none>}   (since $(state_get CURRENT_SINCE))"
echo "recorded image id     : $(state_get CURRENT_IMAGE_ID)"
echo "previous good version : $(state_get PREVIOUS_GOOD_VERSION)   rollback mode: $(state_get ROLLBACK_MODE)"
echo "last operation        : $(state_get LAST_OP) -> $(state_get LAST_OP_RESULT)  at $(state_get LAST_OP_TIME)"
echo "last backup           : $(state_get LAST_BACKUP)"
echo "PENDING_MANUAL        : $(state_get PENDING_MANUAL)"
echo "version.env           : $(version_env_get)"
echo
for c in "$APP_C" "$DB_C"; do
  if container_exists "$c"; then
    printf '%-22s state=%s health=%s restarts=%s image=%s\n' "$c" \
      "$(docker inspect -f '{{.State.Status}}' "$c")" "$(container_health "$c")" "$(docker inspect -f '{{.RestartCount}}' "$c")" \
      "$(docker inspect -f '{{.Config.Image}}' "$c")"
  else
    printf '%-22s (does not exist)\n' "$c"
  fi
done
echo
if container_exists "$APP_C"; then
  RUNV="$(docker inspect -f '{{index .Config.Labels "com.companybi.version"}}' "$APP_C" 2>/dev/null)"
  RUNID="$(container_image_id "$APP_C")"
  echo "running version       : $RUNV"
  echo "running image id      : $RUNID"
  echo "git commit / dirty    : $(docker inspect -f '{{index .Config.Labels "com.companybi.git-sha"}} / dirty={{index .Config.Labels "com.companybi.git-dirty"}}' "$APP_C" 2>/dev/null)"
  echo "built                 : $(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.created"}}' "$APP_C" 2>/dev/null)"
  if [ -n "$REC" ] && [ "$RUNV" != "$REC" ]; then echo "!! DRIFT: recorded '$REC' but running '$RUNV'"; fi
  if [ -n "$REC" ] && [ -n "$(state_get CURRENT_IMAGE_ID)" ] && [ "$RUNID" != "$(state_get CURRENT_IMAGE_ID)" ]; then
    echo "!! DRIFT: running image id differs from the recorded one (image was rebuilt/re-loaded?)"
  fi
  echo "memory (app / mysql)  : $(docker stats --no-stream --format '{{.MemUsage}}' "$APP_C" 2>/dev/null) / $(docker stats --no-stream --format '{{.MemUsage}}' "$DB_C" 2>/dev/null)"
fi
echo
echo "company images present :"
docker image ls companybi --format '  {{.Repository}}:{{.Tag}}  {{.ID}}  {{.Size}}  created {{.CreatedSince}}' 2>/dev/null
echo
echo "recent history:"
if [ -f "$HISTORY_FILE" ]; then tail -n 8 "$HISTORY_FILE" | sed 's/^/  /'; else echo "  (none)"; fi
echo
echo "backups (newest 5)     :"
ls -1t "$BAK_DIR"/*.sql.gz 2>/dev/null | head -5 | sed 's/^/  /' || true
echo
"$LIB_DIR/health-check.sh" || true
