#!/bin/sh
# Backs up both PostgreSQL databases once at start-up and then every BACKUP_INTERVAL_SECONDS (default: daily).
# Each run writes one compressed custom-format dump per database to /backups and deletes dumps older than
# BACKUP_KEEP_DAYS. Restore instructions: DEPLOY.md, "Backups".
set -eu

BACKUP_DIR=/backups
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-86400}"

dump() { # name host user password database
    name=$1 host=$2 user=$3 password=$4 database=$5
    stamp=$(date -u +%Y-%m-%dT%H%M%SZ)
    file="$BACKUP_DIR/$name-$stamp.dump"
    # Write to a temporary name first, so a half-written file never looks like a good backup.
    if PGPASSWORD="$password" pg_dump --host "$host" --username "$user" --dbname "$database" \
        --format custom --compress 9 --file "$file.partial"; then
        mv "$file.partial" "$file"
        echo "$(date -u) backup ok: $file ($(du -h "$file" | cut -f1))"
    else
        rm -f "$file.partial"
        echo "$(date -u) BACKUP FAILED: $name" >&2
        return 1
    fi
}

mkdir -p "$BACKUP_DIR"
while true; do
    failed=0
    dump payflash "$PAYFLASH_DB_HOST" "$PAYFLASH_DB_USER" "$PAYFLASH_DB_PASSWORD" "$PAYFLASH_DB_NAME" || failed=1
    dump notifications "$NOTIFICATIONS_DB_HOST" "$NOTIFICATIONS_DB_USER" "$NOTIFICATIONS_DB_PASSWORD" \
        "$NOTIFICATIONS_DB_NAME" || failed=1
    if [ "$failed" -eq 0 ]; then
        # Only prune after a successful run, so a broken database can never cause the last good backups to be deleted.
        find "$BACKUP_DIR" -name '*.dump' -mtime "+$KEEP_DAYS" -print -delete
    fi
    sleep "$INTERVAL"
done
