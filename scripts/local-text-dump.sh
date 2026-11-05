#!/bin/bash
#
# Runs the text indexer locally (no Hadoop) over WARC/ARC files and writes the resulting JSONL.
# Intended to run inside the docker image (see docker-compose.yml):
#
#   docker compose run --rm indexer scripts/local-text-dump.sh /data/some.warc.gz [/data/other.warc.gz ...]
#
# Output: /out/documents.jsonl
# Afterwards: grep -c $'\xef\xbf\xbd' out/documents.jsonl
#
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <warc file> [<warc file> ...]" >&2
  exit 1
fi

OUT=${OUT:-/out/documents.jsonl}
COLLECTION=${COLLECTION:-Local}

LIST=$(mktemp)
for f in "$@"; do
  echo "file://$(readlink -f "$f")" >> "$LIST"
done

[ -n "${SKIP_BUILD:-}" ] || mvn -q package -DskipTests
java ${JAVA_OPTS:-} -cp target/image-search-indexing.jar pt.arquivo.imagesearch.indexing.LocalDocumentIndexer "$LIST" "$COLLECTION" "$OUT"
rm -f "$LIST"
