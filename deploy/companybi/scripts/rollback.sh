#!/usr/bin/env bash
# Roll Company BI back to an earlier, already-loaded image version.
#
#   rollback.sh [--to <version>] [--yes]                         image-only rollback (needs proven schema compatibility)
#   rollback.sh --to <version> --restore-db <backup.sql.gz> [--yes]   image rollback + restore the metadata DB dump
#   rollback.sh --clear-pending [--yes]                          acknowledge a manual recovery (clears PENDING_MANUAL)
#
# Safety rules (the point of this script):
#   * An image-only rollback is performed ONLY if the schema is unchanged between the version being
#     left and the target, or the version being left declares the target's schema compatible (COMPAT)
#     and its SQL contains no destructive keyword. Otherwise the script refuses and tells you why.
#   * The DB is restored ONLY with an explicit --restore-db FILE and a typed confirmation. Before any
#     restore a fresh "pre-restore" dump of the CURRENT database is taken, so the data written since
#     the old backup is not silently lost.
#   * Only <project>-app is switched; MySQL is restarted by nobody. One attempt only: if the target
#     is not healthy afterwards the app is stopped (no flip-flopping between versions).
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TARGET=""; RESTORE=""; YES=no; CLEAR=no
while [ $# -gt 0 ]; do
  case "$1" in
    --to) TARGET="${2:?}"; shift 2 ;;
    --restore-db) RESTORE="${2:?}"; shift 2 ;;
    --yes) YES=yes; shift ;;
    --clear-pending) CLEAR=yes; shift ;;
    -h|--help) sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

load_env; check_docker; need_cmds gzip stat mktemp
acquire_lock

if [ "$CLEAR" = yes ]; then
  [ "$YES" = yes ] || confirm "Clear PENDING_MANUAL? Only do this after you verified the system state yourself" || die "aborted"
  state_set PENDING_MANUAL no
  history_add clear-pending "-" "-" OK "-" "-" "manual acknowledgement"
  log "PENDING_MANUAL cleared."; exit 0
fi

CUR="$(state_get CURRENT_VERSION)"
[ -n "$CUR" ] || die "no installed version recorded; nothing to roll back"
# the version we are leaving = what the app container was last run with (falls back to the recorded one)
FROM="$CUR"
if container_exists "$APP_C"; then
  assert_ours "$APP_C"
  lv="$(docker inspect -f '{{index .Config.Labels "com.companybi.version"}}' "$APP_C" 2>/dev/null || true)"
  [ -z "$lv" ] || FROM="$lv"
fi
# --restore-db alone means "restore the data, keep running the same version"
[ -n "$TARGET" ] || { [ -z "$RESTORE" ] || TARGET="$FROM"; }
[ -n "$TARGET" ] || TARGET="$(state_get PREVIOUS_GOOD_VERSION)"
[ -n "$TARGET" ] || die "no --to given and no PREVIOUS_GOOD_VERSION recorded"
if [ "$TARGET" = "$FROM" ] && [ -z "$RESTORE" ]; then die "target $TARGET is the version that is already running"; fi
case "$TARGET" in latest|'') die "refusing version '$TARGET'" ;; esac

log "== Company BI rollback: project=$PROJECT  $FROM -> $TARGET =="
verify_image "$TARGET"
[ -f "$REL_DIR/$TARGET/SCHEMA" ] || store_release_files "$TARGET" || true
[ -f "$REL_DIR/$FROM/SCHEMA" ]   || store_release_files "$FROM"   2>/dev/null || true
container_running "$DB_C" || die "MySQL container $DB_C is not running"

# ---- schema decision ----------------------------------------------------------------------
if [ -f "$REL_DIR/$TARGET/SCHEMA" ] && [ -f "$REL_DIR/$FROM/SCHEMA" ]; then
  schema_assess "$REL_DIR/$TARGET/SCHEMA" "$REL_DIR/$FROM/SCHEMA" "$REL_DIR/$FROM/COMPAT"
else
  SCHEMA_CLASS=UNCONFIRMED; SCHEMA_DESTRUCTIVE=unknown; SCHEMA_DELTA="(schema manifest missing for $FROM or $TARGET)"
fi
hr
log "schema between $TARGET (old) and $FROM (leaving): class=$SCHEMA_CLASS destructive_keywords=$SCHEMA_DESTRUCTIVE"
[ -z "${SCHEMA_DELTA:-}" ] || printf '%s\n' "$SCHEMA_DELTA" | sed 's/^/    delta: /'
hr
SAFE=no
case "$SCHEMA_CLASS" in
  NONE) SAFE=yes ;;
  DECLARED_COMPATIBLE) [ "$SCHEMA_DESTRUCTIVE" = no ] && SAFE=yes ;;
esac

if [ -z "$RESTORE" ] && [ "$SAFE" != yes ]; then
  log "REFUSED: an image-only rollback to $TARGET is NOT proven safe (the DB schema may contain changes $TARGET does not know)."
  log "Nothing was changed. Options:"
  log "  1. restore the dump taken before the upgrade (DATA SINCE THEN IS LOST, a fresh safety dump is taken first):"
  log "       scripts/rollback.sh --to $TARGET --restore-db <backup.sql.gz>     (last backup: $(state_get LAST_BACKUP))"
  log "  2. fix forward: ship a corrected release instead."
  log "  3. if you have verified by hand that $TARGET runs on the current schema, add its fingerprint to $FROM's"
  log "     deploy/companybi/schema-compat.txt, rebuild $FROM as a new release - do not bypass this script."
  history_add rollback "$FROM" "$TARGET" REFUSED "$SCHEMA_CLASS" "-" "schema compat unproven"
  exit 3
fi

if [ -n "$RESTORE" ]; then
  [ -f "$RESTORE" ] || die "backup file not found: $RESTORE"
  gzip -t "$RESTORE" || die "backup file is corrupt (gzip -t failed)"
  hdr="$(gzip -dc "$RESTORE" 2>/dev/null | head -c 8192 || true)"
  case "$hdr" in *"CREATE DATABASE"*"\`$DB_NAME\`"*) ;; *) die "backup does not look like a dump of database '$DB_NAME'" ;; esac
fi

log "plan: stop $APP_C -> take safety dump of the current DB${RESTORE:+ -> RESTORE $RESTORE} -> start $(image_ref "$TARGET") -> health check"
if [ "$YES" != yes ]; then
  if [ -n "$RESTORE" ]; then
    confirm "This REPLACES the metadata database with $RESTORE. Everything written after that backup is lost (a safety dump is kept). Continue?" || die "aborted by user"
  else
    confirm "Roll the app back to $TARGET (image only, schema proven compatible)?" || die "aborted by user"
  fi
fi

# ---- execute ---------------------------------------------------------------------------------
PHASE=stopping
trap 'rc=$?; if [ $rc -ne 0 ] && [ "${PHASE:-}" != done ]; then state_set PENDING_MANUAL yes LAST_OP rollback LAST_OP_RESULT INTERRUPTED LAST_OP_TIME "$(date "+%F %T")" || true; log "ERROR: rollback aborted in phase $PHASE; state UNKNOWN; run scripts/status.sh" >&2; fi' EXIT

compose stop app >/dev/null 2>&1 || true
if ! SAFETY="$(backup_db "pre-rollback-from-$FROM")"; then
  compose start app >/dev/null 2>&1 || true
  PHASE=done
  die "could not take the safety dump; nothing was changed (the app was started again on $FROM). Aborting."
fi
log "safety dump of the current DB: $SAFETY"

if [ -n "$RESTORE" ]; then
  PHASE=restoring
  log "restoring $RESTORE into '$DB_NAME' ..."
  # the dump (--databases) re-creates the database with its original charset/collation and issues USE
  mysql_exec "DROP DATABASE IF EXISTS \`$DB_NAME\`;" >/dev/null
  gzip -dc "$RESTORE" | docker exec -i "$DB_C" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' \
    || die "restore failed part-way. The pre-rollback dump is $SAFETY. See docs/rollback.md (manual recovery)."
  log "restore finished"
fi

PHASE=switching
switch_app "$TARGET"
PHASE=verifying
if "$LIB_DIR/health-check.sh" --wait "${COMPANY_BI_HEALTH_WAIT:-600}" --expect-version "$TARGET"; then
  PHASE=recording
  state_set CURRENT_VERSION "$TARGET" CURRENT_IMAGE_ID "$(image_id "$TARGET")" CURRENT_SINCE "$(date '+%F %T')" \
            CURRENT_SCHEMA_FP "$(kv "$REL_DIR/$TARGET/SCHEMA" schema.fingerprint 2>/dev/null || true)" \
            ROLLED_BACK_FROM "$FROM" PREVIOUS_GOOD_VERSION "" PREVIOUS_GOOD_IMAGE_ID "" ROLLBACK_MODE "" \
            LAST_BACKUP "$SAFETY" LAST_OP rollback LAST_OP_RESULT OK LAST_OP_TIME "$(date '+%F %T')" PENDING_MANUAL no
  NOTE="image only"; [ -z "$RESTORE" ] || NOTE="db restored from $RESTORE"
  history_add rollback "$FROM" "$TARGET" OK "$SCHEMA_CLASS" "$SAFETY" "$NOTE"
  PHASE=done
  hr
  log "ROLLBACK OK: now running $TARGET. Safety dump of the pre-rollback DB: $SAFETY"
  exit 0
fi

docker stop "$APP_C" >/dev/null 2>&1 || true
state_set LAST_OP rollback LAST_OP_RESULT FAILED LAST_OP_TIME "$(date '+%F %T')" LAST_BACKUP "$SAFETY" PENDING_MANUAL yes
history_add rollback "$FROM" "$TARGET" FAILED "$SCHEMA_CLASS" "$SAFETY" "target unhealthy after rollback; app stopped"
PHASE=done
log "ROLLBACK FAILED: $TARGET is not healthy. The app is stopped on purpose (no flip-flopping). MySQL is untouched."
log "next: docker logs $APP_C ; scripts/status.sh ; docs/rollback.md ; safety dump: $SAFETY"
exit 1
