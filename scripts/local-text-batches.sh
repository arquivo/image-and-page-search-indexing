#!/usr/bin/env bash
# Indexes many WARCs on this machine in small sequential batches, so memory stays bounded (the local indexer keeps every
# parsed document in memory until its run ends) and the machine stays usable. Run on the host, not inside the container:
#
#   scripts/local-text-batches.sh                 # every data/*.warc.gz
#   BATCH_SIZE=4 INDEXER_CPUS=1 scripts/local-text-batches.sh data/merged-000*.warc.gz
#
# Output: out/batches/batch-NN.jsonl (+ .issues.tsv, .log) and out/batches/totals.txt (counters summed over all batches).
# Batches that already have a finished .jsonl are skipped, so an interrupted run can simply be started again.
# Documents are only deduplicated within a batch.
set -euo pipefail
cd "$(dirname "$0")/.."

BATCH_SIZE=${BATCH_SIZE:-4}
export INDEXER_CPUS=${INDEXER_CPUS:-2}
export INDEXER_MEM=${INDEXER_MEM:-3g}
JAVA_OPTS_BATCH=${JAVA_OPTS:--Xmx2g}
OUTDIR=${OUTDIR:-out/batches}
mkdir -p "$OUTDIR"

if [ "$#" -gt 0 ]; then FILES=("$@"); else FILES=(data/*.warc.gz); fi
TOTAL=${#FILES[@]}
N=$(( (TOTAL + BATCH_SIZE - 1) / BATCH_SIZE ))

docker compose build -q indexer
docker compose run --rm indexer mvn -q package -DskipTests

for ((b = 0; b < N; b++)); do
  name=$(printf "batch-%02d" "$((b + 1))")
  if [ -s "$OUTDIR/$name.jsonl" ] && grep -q "FullPageIndexer\$PAGE_INDEXER_COUNTERS" "$OUTDIR/$name.log" 2>/dev/null; then
    echo "$name already done, skipping"
    continue
  fi
  batch=()
  for f in "${FILES[@]:$((b * BATCH_SIZE)):$BATCH_SIZE}"; do batch+=("/data/$(basename "$f")"); done
  echo "$(date +%T) $name/$N: ${#batch[@]} WARCs (${batch[0]} ...)"
  # CHARSET_DATASET=1 also writes out/.../batch-NN.charsets.tsv; COLLECTION names the collection (default Local)
  dataset_env=()
  if [ -n "${CHARSET_DATASET:-}" ]; then dataset_env=(-e CHARSET_DATASET="/${OUTDIR}/$name.charsets.tsv" -e CHARSET_DATASET_AGREE_PERCENT="${CHARSET_DATASET_AGREE_PERCENT:-1}"); fi
  docker compose run --rm -e SKIP_BUILD=1 -e JAVA_OPTS="$JAVA_OPTS_BATCH" -e OUT="/${OUTDIR}/$name.jsonl" -e COLLECTION="${COLLECTION:-Local}" "${dataset_env[@]}" \
    indexer scripts/local-text-dump.sh "${batch[@]}" > "$OUTDIR/$name.log" 2>&1 || echo "$name FAILED, see $OUTDIR/$name.log"
done

# Counters and decision tables are tab-indented "key;count" lines; add them up over all batches
cat "$OUTDIR"/batch-*.log | awk -F';' '/^\t.*;[0-9]+$/ { n = $NF; k = substr($0, 1, length($0) - length(n) - 1); t[k] += n }
  END { for (k in t) print k ";" t[k] }' | sort > "$OUTDIR/totals.txt"
echo "Done. Summed counters: $OUTDIR/totals.txt"
awk -F';' '{ v[$1] = $2 }
  function line(label, d, c, t) { if (v[d] > 0) printf "%-22s %6d documents, clean %6d (%.1f%%), clean tolerating a few U+FFFD %6d (%.1f%%)\n", label, v[d], v[c], 100 * v[c] / v[d], v[t], 100 * v[t] / v[d] }
  END {
    line("All text documents:", "\tTEXT_DOCUMENTS", "\tTEXT_DOCUMENTS_CLEAN", "\tTEXT_DOCUMENTS_CLEAN_TOLERANT")
    line("HTML and plain text:", "\tTEXT_DOCUMENTS_HTML_PLAIN", "\tTEXT_DOCUMENTS_HTML_PLAIN_CLEAN", "\tTEXT_DOCUMENTS_HTML_PLAIN_CLEAN_TOLERANT")
    line("PDF, Office, other:", "\tTEXT_DOCUMENTS_OTHER", "\tTEXT_DOCUMENTS_OTHER_CLEAN", "\tTEXT_DOCUMENTS_OTHER_CLEAN_TOLERANT")
  }' "$OUTDIR/totals.txt"
