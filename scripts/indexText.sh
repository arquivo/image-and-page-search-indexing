#!/bin/bash
#
# Usage:
#   ./indexImages.sh Collections.txt
#
# Collections.txt has in each line the name of the collection to index
#
# Run inside a screen, this should be synchronous because we can only IndexImages after creating the database
#
set -x

timestamp=$(date +%s)
ssh p82.arquivo.pt "mkdir /data/text-search/data/$timestamp"

mkdir -p counter
FILE=$1
WORKING_PATH=/data/indexing_tmp
for line in $(cat $FILE); do
  TIMESTAMP=$(date +%s)
  COLLECTION="$line"
  if (("$#" > 1))
  then
     COLLECTION="$2"
  fi
  echo "/opt/hadoop-3.4.1/bin/hadoop jar text-search-indexing.jar pt.arquivo.imagesearch.indexing.FullDocumentIndexerJob /user/root/"$line"_ARCS.txt "$COLLECTION" 1 300 "$WORKING_PATH"_"$COLLECTION"_dups "$WORKING_PATH"_"$COLLECTION" "$WORKING_PATH" &> logs/"$line"_$TIMESTAMP.log"
  /opt/hadoop-3.4.1/bin/hadoop jar text-search-indexing.jar pt.arquivo.imagesearch.indexing.FullDocumentIndexerJob /user/root/"$line"_ARCS.txt "$COLLECTION" 1 300 "$WORKING_PATH"_"$COLLECTION"_dups "$WORKING_PATH"_"$COLLECTION" "$WORKING_PATH" &> logs/"$line"_$TIMESTAMP.log
  # /opt/hadoop-3.4.1/bin/yarn application -appStates FINISHED -list | grep application | cut -f 1 | cut -d "_" -f 2,3 | sort | tail -n 3 | head -n 2 | while read ln; do curl --compressed -H "Accept: application/json" -X GET http://p43.arquivo.pt:19888/ws/v1/history/mapreduce/jobs/job_$ln/counters | python -m json.tool >  counter/counters_$ln.json; done
  /opt/hadoop-3.4.1/bin/yarn application -appStates FINISHED -list </dev/null | grep application | cut -f 1 | cut -d "_" -f 2,3 | sort | tail -n 3 | head -n 2 | while read ln; do curl --compressed -H "Accept: application/json" -X GET http://p43.arquivo.pt:19888/ws/v1/history/mapreduce/jobs/job_$ln/counters | python -m json.tool >  counter/counters_$ln.json; done
  curl --compressed -H "Accept: application/json" -X GET http://p43.arquivo.pt:19888/ws/v1/history/mapreduce/jobs/ > counter/times_$TIMESTAMP.json
  ssh p82.arquivo.pt "/opt/hadoop-3.4.1/bin/hdfs dfs -copyToLocal /data/indexing_tmp_$COLLECTION /data/text-search/data/$timestamp"
  ssh p82.arquivo.pt "/opt/hadoop-3.4.1/bin/hdfs dfs -rm -r -f /data/indexing_tmp_$COLLECTION"
done
