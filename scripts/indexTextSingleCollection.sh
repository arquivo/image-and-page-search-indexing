#!/bin/bash
#
# Usage:
#   ./indexTextSingleCollection.sh [job_name] <collection_name> 
#
# Arguments:
#   <collection_name>  (Required) Name of the collection to index.
#   [job_name]         (Optional) Name of the job (used for logs and paths). 
#                      Only needed if the collection has been split into multiple jobs.
#
# Description:
#   This script indexes a single text collection using Hadoop. It performs the following steps:
#     1. Creates a timestamped directory on the output server.
#     2. Runs the Hadoop indexing job.
#     3. Extracts counters and job history from the Hadoop History Server.
#     4. Copies JSONL files to the output server and cleans up HDFS temporary data.
#
# Notes:
#   - An ARC list for the collection must be uploaded to HDFS before running this script.
#   - Run inside a 'screen' session to avoid interruptions.


set -euo pipefail

# =========================
# Functions
# =========================

print_usage() {
    echo "Usage:"
    echo "  $0 [job_name] <collection_name>"
    echo
    echo "Arguments:"
    echo "  <collection_name>  (Required) Name of the collection to index."
    echo "  [job_name]         (Optional) Name of the job (used for logs and paths)."
    echo "                     Only needed if the collection has been split into multiple jobs."
    echo
    echo "Examples:"
    echo "  $0 AWP1"
    echo "  $0 AWP1_part_1 AWP1"
    exit 1
}


# =========================
# Validate Arguments
# =========================
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Error: Invalid number of arguments."
    print_usage
fi


# =========================
# Configurable Variables
# =========================
OUTPUT_SERVER="p82.arquivo.pt"        # Server where the JSONLs will be stored
OUTPUT_DIR="/data/text-search/data"   # Directory where the JSONLs will be stored
HISTORY_SERVER="p43.arquivo.pt"       # Hadoop master
HISTORY_PORT="19888"                  # Hadoop history server port
HADOOP_BIN="/opt/hadoop-3.4.1/bin"    # Hadoop binaries location
WORKING_PATH="/data/indexing_tmp"     # Directory for temporary hadoop files (/tmp could get clogged if not for this)
HADOOP_JAR="text-search-indexing.jar" # Text indexer JAR file 
INDEXER_CLASS="pt.arquivo.imagesearch.indexing.FullDocumentIndexerJob"
                                      # Text indexer class for indexing + deduplication 
LOG_DIR="./logs"                      # Directory for indexer logs
COUNTER_DIR="./counter"               # Directory for indexer counters

# =========================
# Script Logic
# =========================
timestamp=$(date +%s)
ssh "$OUTPUT_SERVER" "mkdir -p $OUTPUT_DIR/$timestamp"

mkdir -p "$COUNTER_DIR"
JOB_NAME=$1
TIMESTAMP=$(date +%s)
COLLECTION="$JOB_NAME"
if (("$#" > 1)); then
   COLLECTION="$2"
fi

# Run the Hadoop application
"$HADOOP_BIN"/hadoop jar "$HADOOP_JAR" "$INDEXER_CLASS" \
  /user/root/"$JOB_NAME"_ARCS.txt "$COLLECTION" 1 300 \
  "${WORKING_PATH}_${JOB_NAME}_dups" "${WORKING_PATH}_${JOB_NAME}" "$WORKING_PATH" \
  &> "$LOG_DIR"/"$JOB_NAME"_$TIMESTAMP.log

# Extract the counters from the finished applications
"$HADOOP_BIN"/yarn application -appStates FINISHED -list < /dev/null \
  | grep "${COLLECTION}_Document" | cut -f 1 | sort | cut -d "_" -f 2,3 | tail -n2 \
  | while read ln; do
      curl --compressed -H "Accept: application/json" -X GET \
      "http://${HISTORY_SERVER}:${HISTORY_PORT}/ws/v1/history/mapreduce/jobs/job_$ln/counters" \
      | python -m json.tool > "$COUNTER_DIR"/counters_$ln.json;
    done

# Extract the times from the job history
curl --compressed -H "Accept: application/json" -X GET \
  "http://${HISTORY_SERVER}:${HISTORY_PORT}/ws/v1/history/mapreduce/jobs/" \
  > "$COUNTER_DIR"/times_"$TIMESTAMP"_"$JOB_NAME".json

# Copy JSONLs to output server
ssh "$OUTPUT_SERVER" "$HADOOP_BIN/hdfs dfs -copyToLocal ${WORKING_PATH}_${JOB_NAME} $OUTPUT_DIR/$timestamp"

# Delete JSONLs from HDFS
ssh "$OUTPUT_SERVER" "$HADOOP_BIN/hdfs dfs -rm -r -f ${WORKING_PATH}_${JOB_NAME}"