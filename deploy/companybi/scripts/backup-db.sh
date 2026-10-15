#!/usr/bin/env bash
# Back up the Company BI METADATA database (not any business database).
#
#   backup-db.sh [--label NAME] [--keep N]
#
# Writes ${COMPANY_BI_HOME}/backups/<db>_<timestamp>_<label>.sql.gz (mode 0600): a consistent
# mysqldump (--single-transaction) incl. routines/triggers/events. Backups never go into Git.
# --keep N prunes old backups whose label is "manual" or "scheduled" (keeps the newest N);
# pre-upgrade / pre-rollback dumps are never pruned by this script.
#
# Example cron (host):  15 3 * * *  /path/to/deploy/companybi/scripts/backup-db.sh --label scheduled --keep 14
# NOTE: backups on the same disk do not protect against disk loss; copy them off the host as well.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

LABEL="manual"; KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="${2:?}"; shift 2 ;;
    --keep) KEEP="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ "$LABEL" =~ ^[A-Za-z0-9_-]{1,40}$ ]] || die "invalid --label"
[[ "$KEEP" =~ ^[0-9]+$ ]] || die "--keep must be a number"

load_env; check_docker; need_cmds gzip stat
acquire_lock
assert_ours "$DB_C"

log "backing up database '$DB_NAME' from $DB_C ..."
OUT="$(backup_db "$LABEL")" || die "backup failed"
log "OK: $OUT ($(stat -c %s "$OUT") bytes)"

if [ "$KEEP" -gt 0 ]; then
  # only our own scheduled/manual files in our own backup dir
  n=0
  for f in $(ls -1t "$BAK_DIR"/"${DB_NAME}"_*_manual.sql.gz "$BAK_DIR"/"${DB_NAME}"_*_scheduled.sql.gz 2>/dev/null || true); do
    n=$((n+1))
    if [ "$n" -gt "$KEEP" ]; then log "pruning old backup: $f"; rm -f -- "$f"; fi
  done
fi
