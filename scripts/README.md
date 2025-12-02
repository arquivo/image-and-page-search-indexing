
# Indexing Scripts for Arquivo.pt

This repository contains a set of scripts for indexing text and image collections into Hadoop-based search systems. These scripts are designed for large-scale processing and should be executed in controlled environments (e.g., inside `screen` sessions).

---

## Contents
- `indexText.sh` – Sequential text indexing for multiple collections.
- `indexTextParallel.sh` – Parallel text indexing for multiple collections.
- `indexTextSingleCollection.sh` – Index a single text collection.
- `indexImagesNew.sh` – Index multiple image collections sequentially.
- `send_nsfw.py` – Post-processing on image indexing for NSFW detection and RabbitMQ messaging.

---

## Usage

### 1. Index Text (Sequential)
```bash
./indexText.sh Collections.txt
```
- Indexes each collection listed in `Collections.txt` sequentially.
- Copies results to `/data/text-search/data/<timestamp>` on the output server.
- Cleans up temporary HDFS directories.

---

### 2. Index Text (Parallel)
```bash
./indexTextParallel.sh <collections_file>
```
- Reads collections from `<collections_file>` and schedules jobs in parallel.
- Uses `indexTextSingleCollection.sh` internally.
- Run inside `screen` to avoid interruptions.

---

### 3. Index Single Text Collection
```bash
./indexTextSingleCollection.sh [job_name] <collection_name>
```
- **collection_name**: Required.
- **job_name**: Optional (used for logs and paths if collection is split).
- Performs:
  - Hadoop indexing job
  - Extracts counters and job history
  - Copies JSONL files to output server
  - Cleans up HDFS temporary data

---

### 4. Index Images
```bash
./indexImagesNew.sh Collections.txt [collection_name]
```
- **Collections.txt**: Each line contains a collection name.
- If `collection_name` is provided, assumes the collection is split into multiple files.
- Logs are stored in `logs/`.
- After indexing, triggers `send_nsfw.py` for NSFW detection.

---

### 5. NSFW Detection
```bash
python3 send_nsfw.py <collection_name>
```
- Scans HDFS output for the collection.
- Publishes file paths to RabbitMQ queues (`log` and `nsfw`).

---

## Logging & Monitoring
- Logs are stored in `logs/<collection>_<timestamp>.log`.
- Job counters and times are saved in `counter/` as JSON files.
- Hadoop History Server is queried for job details.

---

## Best Practices
- Always run inside a `screen` session.
- Validate ARC lists are uploaded to HDFS before starting.
- Monitor disk usage and Hadoop job status.
- Adjust configurable parameters (e.g., JAR names, servers) in scripts if needed.
