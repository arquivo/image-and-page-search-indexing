
#!/bin/bash
#
# Usage:
#   ./indexTextParallel.sh <collections_file>
#
# Description:
#   Reads a list of collections from the specified file and schedules indexing jobs
#   in parallel on Hadoop.
#
# Arguments:
#   <collections_file>  Path to a text file containing one collection per line.
#                       If a collection has been split into multiple jobs, each line
#                       should use the format: job_name collection_name
#
# Notes:
#   - This script relies on indexTextSingleCollection.sh for the actual indexing process.
#   - Run inside a 'screen' session to avoid interruptions.
#   - Ensure sufficient disk space: /tmp may require up to 500MB per line in <collections_file>.
#   - Configurable parameters (e.g., output server, JAR file name, history server URL)
#     can be changed in indexTextSingleCollection.sh. Review that file if you need to adjust settings.
#


set -euo pipefail
set -x

print_usage() {
    echo "Usage:"
    echo "  $0 <collections_file>"
    echo
    echo "Description:"
    echo "  Reads a list of collections from the specified file and schedules indexing jobs"
    echo "  in parallel on Hadoop."
    echo
    echo "Arguments:"
    echo "  <collections_file>  Path to a text file containing one collection per line."
    echo "                      If a collection has been split into multiple jobs, each line"
    echo "                      should use the format: job_name collection_name"
    echo
    echo "Example:"
    echo "  $0 collections.txt"
    exit 1
}

# =========================
# Validate Arguments
# =========================
if [ "$#" -ne 1 ]; then
    echo "Error: Missing or too many arguments."
    print_usage
fi

FILE="$1"

if [ ! -f "$FILE" ]; then
    echo "Error: File '$FILE' does not exist."
    print_usage
fi

if [ ! -s "$FILE" ]; then
    echo "Error: File '$FILE' is empty."
    print_usage
fi

# =========================
# Main Logic
# =========================
# Schedule all jobs in parallel using xargs
cat "$FILE" | xargs -P "$(wc -l < "$FILE")" -I {} bash -c "./indexTextSingleCollection.sh {}"
