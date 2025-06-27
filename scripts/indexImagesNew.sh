#!/bin/bash
#
# Usage:
#   ./indexImages.sh Collections.txt [collection_name]
#
# Collections.txt has in each line the name of the collection to index. 
# Alternatively, if [collection_name] is given, will assume that the collection was split into multiple files instead.
#
# Run inside a screen, this should be synchronous because we can only IndexImages after creating the database
#

mkdir -p counter
FILE=$1
WORKING_PATH=/data/indexing_tmp
while read line; do
  TIMESTAMP=$(date +%s)
  COLLECTION="$line"
  if (("$#" > 1))
  then
     COLLECTION="$2"
  fi
  /opt/hadoop-3.3.6/bin/hadoop jar image-search-indexing.jar pt.arquivo.imagesearch.indexing.FullImageIndexerJob /user/root/"$line"_ARCS.txt "$COLLECTION" 1 150 false COMPACT "$WORKING_PATH" &> logs/$line_$TIMESTAMP.log && python3.5 send_nsfw.py "$COLLECTION"
  /opt/hadoop-3.3.6/bin/yarn application -appStates FINISHED -list | grep application | cut -f 1 | cut -d "_" -f 2,3 | sort | tail -n 3 | head -n 2 | while read ln; do curl --compressed -H "Accept: application/json" -X GET http://p43.arquivo.pt:19888/ws/v1/history/mapreduce/jobs/job_$ln/counters | python -m json.tool >  counter/counters_$ln.json; done
  curl --compressed -H "Accept: application/json" -X GET http://p43.arquivo.pt:19888/ws/v1/history/mapreduce/jobs/ > counter/times_$TIMESTAMP.json
done < $FILE
