# Image Search Indexing
An Hadoop image indexer for Web archiving - supports ARC/WARC files.


## Algorithm 
### Phase 1 - ImageIndexerWithDups
- Iterate through all ARC/WARC records to find all HTML records (i.e. records with mimetype that starts with text/html) and image records
  - For page records
    - Find all image tags in that html page i.e. (`<img>`, `<a>` having href with image file extensions, `css` backgrounds).
    - For each image tag 
      - Extract metadata and create `PageImage` record
      - Add to HDFS entry matching its `SURT`
  - For image records
    - Extract image metadata and create `ImageData` record 
    - Add to HDFS entry matching its `SURT`
- For each SURT
  - Combine `PageImage` and `ImageData` into `FullImageMetadata` according to their capture timestamp
  - Write them into the corresponding image digest
    
    

### Phase 2 - DupDigestMerger
- For each digest
  - Merge all `FullImageMetadata` in that digest into a single `FullImageMetadata`
  - Output the`FullImageMetadata` as JSON

## Compile

```mvn clean install``` 

The compiled jar with dependencies will be placed in target/image-search-indexing.jar

## Run


Create a .txt file where each line contains the path to a downloadable ARC/WARC file (WARC list file) and store it in Hadoop HDFS

```
hadoop jar image-search-indexing.jar pt.arquivo.imagesearch.indexing.FullImageIndexerJob <WARC list location in HDFS> <collection name> <WARCs per map> <number of reduces> <WARCs in HDFS: true or false> <output format: COMPACT or FULL>
```

**WARC list location in HDFS**: Location of the (W)ARC file list in HDFS

**collection name**: Name of the collection to process

**WARCs per map**: total number of (W)ARCs to process per Map process. Larger is faster, but can lead to Map timeouts in some collections (recommended: 1-5)

**number of reduces**: total number of reduces (recommended: 150)

**WARCs in HDFS**: true or false, whether the (W)ARCs are in HDFS or in external HTTP server

**output format**: COMPACT or FULL> use COMPACT for the current Solr schema





## Requirements
- Hadoop 3 cluster
  - Can be setup using `ansible-playbook -i infrastructure-prod/hosts.ini playbooks/hadoop3_cluster_provision.yml`
  - Generate a file with all arcs of a collection using [[Create_arc_list]].
  - Copy them to `p43.arquivo.pt:/opt/searcher/scripts/arcsList/`
  - Insert into HDFS a file with all (W)ARCs from every collection.
    - `ssh root@p43.arquivo.pt`
    - `/opt/hadoop-3.2.1/bin/hadoop dfs -mkdir -p /user/root`
    - `/opt/hadoop-3.2.1/bin/hadoop dfs -copyFromLocal /opt/searcher/scripts/arcsList/*.txt /user/root`
    
After placing the collection file lists in HDFS you can runt he following script will run the script for a list of collections defined in a text file (e.g. Collection.txt)
`./indexImagesNew.sh Collections.txt`
