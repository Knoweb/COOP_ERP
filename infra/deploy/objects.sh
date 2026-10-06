#!/bin/sh
# The uploaded files in and out of a backup (wave 2, D-2). Runs INSIDE the minio-client container
# (backup.sh and restore.sh mount it with the backup folder at /backup); never on its own.
#
#   objects.sh backup     every object of attachments, images and packages that the backup does
#                         not hold yet, to /backup/<bucket>/<sha256 of its key>
#   objects.sh restore    every object the backup holds, back to its bucket and key
#
# Why not `mc mirror` to a folder: a key may also be the prefix of another key (an M2 image is
# <id>, its thumbnail <id>/thumb.jpg), and a file system cannot hold a file and a folder of one
# name: the mirror skipped the thumbnail without an error (found by the restore drill). So each
# object is a file named by the hash of its key, and /backup/<bucket>/INDEX lists, one per line,
# "<hash> <content type> <key>". Objects are written once under unique keys, so an object already
# in the backup is not copied again, and nothing is ever removed from it.
set -eu
MC="mc --config-dir /tmp/mc"
$MC alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null

case "${1:-}" in
  backup)
    for b in attachments images packages; do
      d="/backup/$b"
      mkdir -p "$d"
      touch "$d/INDEX"
      n=0
      # Every key. MinIO's listing does not show <key>/... while <key> itself is an object (the
      # thumbnail of an M2 image), so each key's own prefix is listed too, until nothing new.
      : > "$d/KEYS"
      $MC ls --recursive --json "local/$b" | sed -n 's/.*"key":"\([^"]*\)".*/\1/p' > "$d/NEXT"
      while [ -s "$d/NEXT" ]; do
        mv "$d/NEXT" "$d/NOW"
        : > "$d/NEXT"
        while IFS= read -r k; do
          echo "$k" >> "$d/KEYS"
          $MC ls --recursive --json "local/$b/$k/" 2>/dev/null \
            | sed -n 's/.*"key":"\([^"]*\)".*/\1/p' | sed "s#^#$k/#" >> "$d/NEXT"
        done < "$d/NOW"
      done
      rm -f "$d/NOW" "$d/NEXT"
      while IFS= read -r k; do
        h="$(printf '%s' "$k" | sha256sum | cut -c1-64)"
        if [ -s "$d/$h" ] && grep -q "^$h " "$d/INDEX"; then continue; fi
        ct="$($MC stat --json "local/$b/$k" | sed -n 's/.*"Content-Type":"\([^"]*\)".*/\1/p')"
        $MC cat "local/$b/$k" > "$d/$h.part"
        mv "$d/$h.part" "$d/$h"
        printf '%s %s %s\n' "$h" "${ct:-application/octet-stream}" "$k" >> "$d/INDEX"
        n=$((n + 1))
      done < "$d/KEYS"
      rm -f "$d/KEYS"
      echo "  $b: $n new object(s), $(wc -l < "$d/INDEX") in the backup"
    done
    ;;
  restore)
    for b in attachments images packages; do
      d="/backup/$b"
      [ -f "$d/INDEX" ] || continue
      n=0
      while read -r h ct k; do
        # put, not cp: cp copies INTO <key>/ when <key>/... exists; put writes the key itself, with
        # its content type (pipe ignores it, and its 528 MiB buffer got the container killed).
        $MC put --quiet -H "Content-Type:$ct" "$d/$h" "local/$b/$k" >/dev/null
        n=$((n + 1))
      done < "$d/INDEX"
      echo "  $b: $n object(s) put back"
    done
    ;;
  *)
    echo "usage: objects.sh backup|restore" >&2
    exit 2
    ;;
esac
