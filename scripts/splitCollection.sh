#!/bin/bash

help()
{
  echo "Usage: "
  echo "./splitCollection.sh COLLECTION NUMBER_OF_OUTPUTS"
  echo ""
  echo "Description: "
  echo "Splits an ARCs file named <COLLECTION>_ARCS.txt into <NUMBER_OF_OUTPUTS> different files named <COLLECTION>_<NUMBER>_ARCS.txt"
  echo "Should be run as root user."


  exit 0
}

error()
{
  echo "Error: $1"
  exit 1
}

############## Verify input ################
if (("$#" < 2)); then
  help
fi

if ! [ "$2" -eq "$2" ] 2> /dev/null; then
  error "NUMBER_OF_OUTPUTS must be an integer"
fi

if (("$2" < 1)); then
  error "NUMBER_OF_OUTPUTS must be greater than 0"
fi

COLLECTION=$1
NUMBER_OF_OUTPUTS=$2

FILE_DIR="/opt/searcher/scripts/arcsList"
FILE_NAME="${COLLECTION}_ARCS.txt"

#Searching for the file
FILE_PATH=$(find $FILE_DIR -name "$FILE_NAME")

if [ -z "$FILE_PATH" ]; then
  error "Could not find file '$FILE_NAME' in folder '$FILE_DIR'. Make sure the collection name is correct and that the ARCS file has been generated."
fi

TOTAL_LINES=$(wc -l "$FILE_PATH" | cut -d' ' -f1)

echo "Found file $FILE_PATH with $TOTAL_LINES lines..."

LINES_PER_FILE=$(echo "$TOTAL_LINES / $NUMBER_OF_OUTPUTS" | bc)

CURRENT_FILE=0
CURRENT_LINE=1

while (("$CURRENT_FILE" < "$NUMBER_OF_OUTPUTS")); do
  CURRENT_FILE=$((CURRENT_FILE+1))
  CURRENT_FILE_NAME="${COLLECTION}_${CURRENT_FILE}_ARCS.txt"
  CURRENT_FILE_PATH="$FILE_DIR/$CURRENT_FILE_NAME"
  tail -n "+$CURRENT_LINE" "$FILE_PATH" | head -n "$LINES_PER_FILE" > "$CURRENT_FILE_PATH"
  CURRENT_LINE=$((CURRENT_LINE+LINES_PER_FILE))
done

tail -n "+$CURRENT_LINE" "$FILE_PATH" >> "$CURRENT_FILE_PATH"

find "$FILE_DIR" -name "${COLLECTION}_*_ARCS.txt" | xargs wc -l

TOTAL_OUTPUT_LINES=$(find "$FILE_DIR" -name "${COLLECTION}_*_ARCS.txt" | xargs wc -l | grep total | rev | cut -d' ' -f2 | rev)

if [ "$TOTAL_OUTPUT_LINES" != "$TOTAL_LINES" ]; then
  error "Something went wrong, output number of lines mismatch from input. Input: '$TOTAL_LINES', Output: '$TOTAL_OUTPUT_LINES' "
fi

echo "Files created successfully!"

echo "Copy files to HDFS? [y/n]"

read COPY_TO_HDFS

if [ "$COPY_TO_HDFS" != "y" ]; then
  echo "Files were NOT copied to HDFS"
  exit 0
fi

find "$FILE_DIR" -name "${COLLECTION}_*_ARCS.txt" | xargs -t -I {} /opt/hadoop-3.3.6/bin/hdfs dfs -copyFromLocal {} /user/root

echo ""
echo "Finished copying to HDFS!"
echo "Create file for the indexing script? [y/n]"

read CREATE_FILE

if [ "$CREATE_FILE" != "y" ]; then
  echo "File was NOT created"
  exit 0
fi

find "$FILE_DIR" -name "${COLLECTION}_*_ARCS.txt" | rev | cut -d / -f1 | cut -d _ -f2- | rev > "${COLLECTION}.txt"

echo "File ${COLLECTION}.txt created!"

echo "To run the indexing script, switch to a non-root user and on a screen use the following command:"
echo ""
echo "./indexImagesNew.sh ${COLLECTION}.txt $COLLECTION"
