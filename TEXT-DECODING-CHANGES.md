# Text indexer: decoding changes and their motivation

Working notes (not tracked by git). They describe what was changed in the Arquivo.pt text indexer
(`DocumentIndexerWithDupsJob` / `DocumentInformationExtractor`) so that documents are decoded correctly, and why.

Goals that drove every decision:

- **Global rules only.** No per-URL or per-site exceptions. The rules are tuned for the vast majority of a much larger
  dataset, validated on a 5.4 GB sample (54 WARCs in `data/`).
- **Portuguese first.** When a heuristic has to choose between readings, the one that gives correct Portuguese text wins
  (for example a stray Latin-1 byte inside a UTF-8 page becomes the windows-1252 character instead of U+FFFD).
- **Do not stress the Hadoop cluster.** Experiments run locally, in memory-bounded batches.
- **The cluster is Java 1.8, Hadoop 3.4.1, `file.encoding=UTF-8`.** Everything must work there.

## 1. The problem in the original indexer

The original `parseTextRecord` handed the raw WARC record stream straight to Tika's `AutoDetectParser`:

- Tika picked the charset by itself (its own detectors, ICU-like statistics) and ignored the charset the server declared
  in the HTTP `Content-Type` header.
- Only Brotli `Content-Encoding` was undone (`WARCRecordResponseEncapsulated.getInputStream`). gzip, deflate and chunked
  transfer encoding were not, so some bodies reached Tika still compressed.
- Text was hashed with `getBytes()` (platform default charset), so digests depended on the JVM's `file.encoding`.
- The result was visible garbage in the indexed text: U+FFFD (`�`), mojibake such as `Ã§` / `Ã£` (UTF-8 read as
  Latin-1), binary files (fonts, images served as text) indexed as text, and Latin-1 pages decoded as UTF-8 and vice versa.

## 2. What was changed

### 2.1 Payload decoding: `HttpBodyDecoder` (new)

`utils/HttpBodyDecoder.java`. Undoes `Transfer-Encoding: chunked` and `Content-Encoding` (gzip, deflate raw or zlib,
brotli, or a comma separated list). It also:

- parses the charset parameter out of `Content-Type` (`charsetFromContentType`);
- tolerates truncated compressed payloads (returns what was decoded so far instead of failing the record);
- copes with servers that lie: a `chunked` header with an already de-chunked body, a gzip header with a plain body, and
  gzip data after leading whitespace.

Why: a body that is still compressed or chunked can never be decoded correctly whatever the charset logic does.

`WARCRecordResponseEncapsulated.getDecodedInputStream()` / `getContentTypeCharset()` expose this for WARC records.
`getInputStream()` is unchanged on purpose, because the image pipeline relies on its Brotli-only behaviour.
ARC records use the same decoder (`DocumentInformationExtractor.parseRecord`).

### 2.2 Charset choice: `TextEncodingDetector` (new)

`utils/TextEncodingDetector.java`, plugged into Tika as an `EncodingDetector` through `ParseContext`. Text payloads are
buffered in memory (up to 64 MB) so the detector sees the whole document, not just the first few KB. The charset is
chosen by the first rule that matches:

| # | Rule | Reason counter |
|---|------|----------------|
| 1 | BOM (UTF-8, UTF-16 LE/BE) | `CHARSET_BOM` |
| 2 | Plain ASCII | `CHARSET_ASCII` |
| 3 | Bytes are valid UTF-8 with at least one multi-byte sequence | `CHARSET_UTF8_VALID` |
| 4 | Mostly valid UTF-8 (few stray bytes), or the page declares UTF-8 and is mostly valid | `CHARSET_UTF8_MOSTLY` |
| 5 | Control-byte ratio above 2%: binary served as text (fonts, images). Not indexed | `CHARSET_BINARY` |
| 6 | HTTP `Content-Type` charset, if it decodes every byte strictly | `CHARSET_DECLARED_HTTP` |
| 7 | HTML `<meta>` charset, if it decodes strictly | `CHARSET_DECLARED_META` |
| 8 | Same declarations, accepted if at most 2% of the non-ASCII bytes fail (mixed-encoding pages, like browsers do) | `CHARSET_DECLARED_LENIENT` |
| 9 | ICU4J, then juniversalchardet guesses, accepted only if they decode strictly | `CHARSET_STATISTICAL` |
| 10 | windows-1252 (ISO-8859-1 if 1252 has undefined bytes) | `CHARSET_FALLBACK` |

Why this order:

- Valid UTF-8 beats a declared Latin-1: single-byte charsets accept any input, so they can never disprove UTF-8, while
  real Latin-1 text almost never forms valid multi-byte sequences by chance. This fixes the most common failure
  (UTF-8 pages declared as ISO-8859-1).
- A declared charset is only trusted if it decodes every byte, otherwise statistics take over.
- Labels are normalised the way browsers do (WHATWG): `iso-8859-1`, `ascii`, `latin1` mean windows-1252; `gb2312`/`gbk`
  mean GB18030; `iso-8859-9` means windows-1254; etc. UTF-16/32 without a BOM is only believed if the data is full of NULs.
- Binary is checked before the declarations, which would happily "decode" a font.

The decision is recorded in the metadata (`X-Charset-Resolved`, `X-Charset-Reason`), which feeds the `CHARSET_*` counters.

### 2.3 Repairs of mixed-encoding pages

Real pages mix encodings. Both repairs convert the bytes to clean UTF-8 *before* Tika parses them.

- **`repairStrayBytes`** (`UTF8_REPAIRED`): the page is UTF-8 but contains a few Latin-1/windows-1252 bytes (typically
  `Portugu\xEAs` with a lone `0xEA`). Each stray byte becomes its windows-1252 character instead of U+FFFD. A lone
  `0xC2`/`0xC3` before punctuation, whitespace or the end is the first half of a cut-off character (`looksCutOff`) and
  is dropped (see section 8). Portuguese-first: a lone `0xEA` is far more likely `ê` than garbage.
- **`repairEmbeddedUtf8`** (`LATIN_REPAIRED`): the page is Latin-1 but user content inside it is UTF-8 (`Gonçalves` next
  to `Comentário`). Only sequences that Latin text never produces by chance are recognised: `C2/C3 xx`, `E2 80 xx`,
  `E2 82 AC` (euro), `E2 84 A2` (trademark). Everything else is decoded with the chosen Latin charset. In the 54 WARC sample
  696 pages were repaired and `MOJIBAKE_UTF8_AS_LATIN` in Latin charsets fell from 485 to 9.

### 2.4 Other changes in `DocumentInformationExtractor`

- When Tika's content type is not `text/html` but the WARC mimetype is text-like, the WARC mimetype is passed as a hint.
  Tika's magic detection calls legacy 8-bit text `application/octet-stream`.
- Binary documents (`BINARY` reason) are not indexed.
- Empty bodies are counted (`RECORDS_TIKA_EMPTY`), decode failures are counted (`RECORDS_TIKA_FAILED_DECODING`).
- Digests are computed on UTF-8 bytes, so the same content gets the same digest on any JVM and whatever its source
  encoding (a test checks it).
- Charset decisions can be recorded per document (`trackCharsetDecisions`) for local analysis.

### 2.5 WARC reading fixes

- **WARC/1.1 records were silently skipped.** In WARC/1.0 a response record has `Content-Type: application/http;
  msgtype=response`. In WARC/1.1 some writers put the payload mimetype there instead (the body still starts with
  `HTTP/1.1 200 OK`). `isWARCResponseRecord()` now also accepts a `response` record whose `WARC-Target-URI` starts with
  `http`. All 54 WARCs are now indexed.
- `readWarcRecords` now increments its record counter (the log used to say `WARCS RECORDS READ: 0`).
- `resource` records (the `Local` collection, files with no HTTP headers) were already handled.

### 2.6 Quality measurement: `TextQualityChecker` (new) and `LocalDocumentIndexer`

`TextQualityChecker.check(text, charset)` returns issues, so we can measure instead of guessing:

| Issue | Meaning | Better is |
|-------|---------|-----------|
| `REPLACEMENT_CHAR` | extracted text contains U+FFFD | lower |
| `REPLACEMENT_CHAR_IN_SOURCE` | same, but the original bytes already contained U+FFFD (`EF BF BD` or `&#65533;`): not our fault | n/a |
| `MOJIBAKE_UTF8_AS_LATIN` / `_CYRILLIC` / `_CJK` | UTF-8 read as Latin-1/cp1252 (`Ã§`, `Ð¿Ñ€`, `ä¸­`) | lower |
| `LATIN_SUPPLEMENT_OVERLOAD` | more than 25% of letters in U+00C0-U+00FF (Cyrillic/Greek read as Latin) | lower |
| `C1_CONTROLS` | U+0080-U+009F (cp1252 read as ISO-8859-1) | lower |
| `BINARY_GARBAGE` | control or private-use characters: a non-text file got indexed | lower |
| `SCRIPT_MISMATCH` | chosen charset belongs to a script the text barely uses | lower |

`LocalDocumentIndexer` (third argument = output path) writes the documents in the same JSONL format as
`DocumentDupDigestMergerJob`, runs the checker on every document and prints:

- `TEXT_DOCUMENTS`: unique text documents indexed (images and binary files excluded);
- `TEXT_DOCUMENTS_CLEAN`: of those, how many have neither U+FFFD nor mojibake;
- issue counts by type, by type and chosen charset, and a `declared -> chosen [reason]` table;
- `<out>.issues.tsv` with one line per issue (type, declared, chosen, reason, warc, offset, url, snippet).

### 2.7 Counters added (`DOCUMENT_COUNTERS`)

`RECORDS_TIKA_FAILED_DECODING`, `CHARSET_BOM`, `CHARSET_ASCII`, `CHARSET_UTF8_VALID`, `CHARSET_UTF8_MOSTLY`,
`CHARSET_UTF8_REPAIRED`, `CHARSET_LATIN_REPAIRED`, `CHARSET_DECLARED_HTTP`, `CHARSET_DECLARED_META`,
`CHARSET_DECLARED_LENIENT`, `CHARSET_STATISTICAL`, `CHARSET_FALLBACK`, `CHARSET_BINARY`. The mapping is
`"CHARSET_" + Reason`, so a new reason needs a new counter. `TEXT_DOCUMENTS` and `TEXT_DOCUMENTS_CLEAN` exist only in the
local indexer, because the quality check needs the final text.

## 3. Build, test and local run tooling

- **`pom.xml`**: `maven-compiler-plugin` 3.11.0 and a profile that sets `maven.compiler.release=8` on JDK 9+, because
  `source/target` alone still compile against the running JDK's API (`CharBuffer.clear()` returns `CharBuffer` since Java 9
  and fails with `NoSuchMethodError` on Java 8). `maven-assembly-plugin` pinned to 3.7.1 and a custom descriptor
  (`src/assembly/fat-jar.xml`) that merges `META-INF/services`, so Tika's parsers and detectors survive in the fat jar.
  `exec-maven-plugin` runs `scripts/test-fatjar.sh` in `integration-test`, which re-runs the unit tests with only the fat
  jar on the classpath (this is what runs on the cluster; packaging bugs show up only here). `mvn verify` = compile,
  unit tests, fat jar, fat jar tests.
- **`.github/workflows/build.yml`**: CI runs `mvn verify`.
- **`Dockerfile`, `docker-compose.yml`, `.dockerignore`**: Java 8 build image (`maven:3.9.9-eclipse-temurin-8`) with the
  cluster's `file.encoding=UTF-8`; `cpus`/`mem_limit` configurable (`INDEXER_CPUS`, `INDEXER_MEM`).
- **`scripts/local-text-dump.sh`**: runs `LocalDocumentIndexer` on some WARCs inside the container.
- **`scripts/local-text-batches.sh`**: runs all `data/*.warc.gz` in sequential batches of 4 (the local indexer keeps every
  document in memory), output in `out/batches/` (`batch-NN.jsonl`, `.issues.tsv`, `.log`, and `totals.txt` with summed
  counters). Finished batches are skipped, so an interrupted run can resume.
- **`scripts/test-fatjar.sh`**: see above.
- **`TextEncodingTest`** (47 tests): charset rules, HTTP body decoding, repairs, quality checker, WARC/1.1 records,
  digest stability. The whole suite (56 tests) passes under `mvn verify`.

Build gotchas on this host: the default `mvn` runs a Java 21 JRE without `ct.sym`, so `--release 8` fails; Java 8's default
encoding is ASCII. Build with `JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`, or use
Docker. Never leave Java-21-compiled classes in `target/` before a Docker run.

## 4. Results so far (54 WARCs, 5.4 GB)

Latest full run (`out/batches/`): **25,697 text documents, 17,448 clean (67.9%)**.

| Metric | Count |
|--------|-------|
| `REPLACEMENT_CHAR` (not in the source bytes) | 5,041 |
| `REPLACEMENT_CHAR_IN_SOURCE` (reported separately, not ours) | 2,974 |
| `MOJIBAKE_UTF8_AS_LATIN` | 891 (827 in UTF-8 pages, i.e. mojibake already in the source) |
| `BINARY_GARBAGE` | 846 |
| `CHARSET_BINARY` | 623 |
| `RECORDS_TIKA_FAILED` | 62 |
| `CHARSET_STATISTICAL` | about 742 |
| `CHARSET_FALLBACK` | 4 |

U+FFFD rate by document type: HTML 22% (4,950 of 22,219), PDF 99%, msword 100%, rss 99%, zip 92%, text/plain 47%.

What the remaining 32% is, by cause:

- **PDF / Word / zip (about 99% have U+FFFD).** Not a charset problem. PDFBox emits the raw glyph code as a control
  character for fonts without a ToUnicode map (for example `\u001C` for "fi": `Quali\u001Ckatio`), and Tika turns those
  into U+FFFD. POI does the same with Word field markers. Recovering the letters needs OCR or per-font glyph analysis.
- **HTML (22%).** Mostly U+FFFD already present in the source bytes, cut-off snippets and mojibake already in the source.
  In a sample of 250 flagged HTML documents: 211 had no control or astral characters in the source, 28 had control
  characters and 11 had astral characters.
- **text/plain (47%).** Includes Mac Roman files in `resource` records that are decoded as ISO-8859-1.

## 5. Baseline

The counters have not yet been measured on the original indexer (the code before any of these changes), so the
improvement cannot be quantified. Planned: run the original `parseTextRecord` over the same 54 WARCs and report the same
`TEXT_DOCUMENTS` / `TEXT_DOCUMENTS_CLEAN` / issue counts.

## 6. Follow-up: raising the clean rate

Reasoning: after section 4, most of the 32% of "unclean" documents are not decoding mistakes, so the work was split into
(a) measuring them honestly and (b) removing the artifacts that Tika itself introduces.

### 6.1 Clean counters by type and with a tolerance (measurement only)

`LocalDocumentIndexer` now prints, besides `TEXT_DOCUMENTS` / `TEXT_DOCUMENTS_CLEAN`:

- `TEXT_DOCUMENTS_CLEAN_TOLERANT`: no mojibake and U+FFFD in at most 0.5% of the characters (one `�` in a 50 KB document
  does not hurt search);
- the same three numbers split by detected type: `TEXT_DOCUMENTS_HTML_PLAIN*` (text/*, xml, html: this is where the
  decoder is responsible for the result) and `TEXT_DOCUMENTS_OTHER*` (PDF, Office, zip, ...: extraction quality).

`scripts/local-text-batches.sh` prints the three groups after summing the batches. `OUTDIR` can now be overridden
(`OUTDIR=out/batches-v4 scripts/local-text-batches.sh ...`), so runs do not overwrite each other.

### 6.2 Control bytes in text (`stripControlBytes`, counter `RECORDS_TIKA_CONTROL_CHARS_STRIPPED`)

Finding: the 328 plain-text documents with U+FFFD were mostly JavaScript and RFC `.txt` files. The RFCs are ASCII with
form feeds (`\f`, page breaks) in them. XML cannot represent `\f` or other C0 controls, and Tika's SafeContentHandler
replaces each one with U+FFFD.

Fix: before parsing, C0 control bytes other than tab, LF and CR become spaces. Skipped for UTF-16/32 (BOM or many NULs),
ISO-2022 (contains ESC), and binary data (over 2% control bytes), so `BINARY` detection is unchanged. Safe for every
other charset the detector can pick, because none of them uses bytes below 0x20 inside multi-byte sequences.

Checked: astral characters (emoji, mathematical letters) were suspected too, but Tika 2.9.1 keeps them intact, so
nothing was done about them.

### 6.3 Unmapped glyphs in PDF/Office (counter `RECORDS_TIKA_UNMAPPED_GLYPHS_REPLACED`)

When the *detected* type is not text-like (PDF, Word, ...) and the extracted body contains U+FFFD, runs of U+FFFD become
one space. Detection by the detected type, not the reported one, so a real decoding error in an HTML page served as
`application/octet-stream` is never hidden.

What this does and does not do: the letters are not recovered (`Quali\u001Ckatio` becomes `Quali katio`). It only stops
unrecoverable placeholders from polluting the text. **A PDF that was affected is now counted as "clean"**, so the
`TEXT_DOCUMENTS_OTHER_CLEAN` figure is not comparable with the earlier 99% U+FFFD rate; the number of affected documents
is the counter above. The loss happens inside the parser (PDFBox/POI output goes through SafeContentHandler before our
handler sees it), so a post-parse replacement was the only place to do it without forking Tika.

### 6.4 Mac Roman: investigated, nothing to fix

Hypothesis: Mac Roman plain-text files from `resource` records were decoded as ISO-8859-1. Evidence against: only 9
`C1_CONTROLS` issues in the whole run (Mac Roman read as Latin-1 puts accented letters in 0x80-0x9F) and the Mac-origin
RFC files are ASCII, their U+FFFD was the form feed of 6.2. No detection rule was added. If a real Mac Roman file turns
up, the rule would be: no declaration, not valid UTF-8, and cp1252 hits undefined or many C1 bytes.

### 6.5 Not done

- Upgrading Tika/PDFBox: uncertain gain, risky on the Java 8 cluster.
- OCR for glyph-mapped PDFs: not practical on the cluster.
- Records reported as non-text whose content is detected as text (HTML served as `application/octet-stream`) skip our
  own decoding step (`isTextLike` looks at the reported type); Tika's detector hook still applies. Not measured yet.

### 6.6 Results

See section 7.


## 7. Results after section 6 (full run, 54 WARCs, `out/batches-v4/`)

| | Before (`out/batches`) | After |
|---|---|---|
| Text documents | 25,697 | 25,695 |
| Strict clean (no U+FFFD, no mojibake) | 17,448 (67.9%) | 20,057 (78.1%) |
| Clean tolerating a few U+FFFD (at most 0.5%) | not measured | 24,583 (95.7%) |
| `REPLACEMENT_CHAR` (not in source) | 5,041 | 2,393 |
| `BINARY_GARBAGE` | 846 | 654 |
| `RECORDS_TIKA_CONTROL_CHARS_STRIPPED` | - | 362 |
| `RECORDS_TIKA_UNMAPPED_GLYPHS_REPLACED` | - | 2,782 |

By type: HTML and plain text 23,094 documents, 75.8% strict clean, 95.3% tolerant. PDF, Office, other 2,601 documents,
98.3% clean, but see 6.3: those are clean because the placeholders were replaced, not because the text was recovered.
2,782 documents had them. (The 2 missing documents are merged by the content digest, which changed after cleaning.)

What is left in `REPLACEMENT_CHAR` (2,393 documents): 1,000 in `UTF8_VALID` pages, 849 in `UTF8_REPAIRED`, 167 with no
charset reported, 120 BOM, 84 `LATIN_REPAIRED`, the rest Latin charsets. Findings:

- Many are excerpts cut by bytes in the source (`...Proteç�…`, `...S�...`): the first half of a multi-byte character
  before an ellipsis. Handled in section 8.1.
- RSS: 174 of 176 documents contain U+FFFD and no charset reason is recorded (167 `(unknown)`). Tika's XML parser
  decodes by itself (XML declaration, UTF-8 default) and never calls `TextEncodingDetector`, so feeds in Latin-1 with a
  wrong or missing declaration are not covered by our rules (`Troms�ø`, `Cr��dito`). The same applies to every
  XML-based type.


## 8. Second follow-up: cut-off characters, XML types, C1 controls (`out/batches-v6/`)

### 8.1 Cut-off characters are dropped

`repairStrayBytes` used to write U+FFFD for the lone `0xC2`/`0xC3` that starts a character cut off by an excerpt
(`Prote\xc3\xa7\xc3&hellip;`, `S\xc3...`). The dangling byte is now dropped, so the text reads `...Proteç...`.

### 8.2 XML types are decoded with our rules (`xmlToUtf8`)

Tika's XML parser (RSS and every other XML type) decodes by itself and never asks `TextEncodingDetector`. For reported
types containing `xml` (not `html`), the payload is now decoded before Tika sees it: charset = HTTP charset, else the XML
declaration, then the usual rules; the document goes to Tika as UTF-8 with the declaration's `encoding` rewritten to
`UTF-8`. UTF-16 is transcoded (BOM removed). Untouched when it is already UTF-8 with a UTF-8 (or no) declaration, or
binary. The reason feeds the normal `CHARSET_*` counters. RSS/XML documents with no charset reason: 167 -> 9.

### 8.3 C1 controls written as UTF-8 become windows-1252 characters (`repairC1InUtf8`)

Found while reading the v5 results: 1,086 of the remaining U+FFFD documents were valid UTF-8 pages. Their source has
`C2 80`-`C2 9F` (U+0080-U+009F), i.e. windows-1252 smart quotes, dashes and euro signs that went through a Latin-1 to
UTF-8 conversion (`Exame \xc2\x96 100 melhores`). XML cannot carry C1 controls, so Tika turned them into U+FFFD. In pages
that resolve to UTF-8 they are now replaced by the windows-1252 character (the five undefined ones by a space), the
reading a browser gives. Counter `RECORDS_TIKA_C1_CONTROLS_REMAPPED` (1,176 documents).

### 8.4 Results (54 WARCs)

| | v4 | v5 (8.1 + 8.2) | v6 (+ 8.3) |
|---|---|---|---|
| Strict clean, all text documents | 78.1% (20,057) | 81.4% (20,906) | 82.7% (21,247) |
| Strict clean, HTML and plain text | 75.8% | 79.5% | 80.9% (18,690 of 23,095) |
| Clean tolerating a few U+FFFD | 95.7% | 95.7% | 95.7% (24,601) |
| `REPLACEMENT_CHAR` | 2,393 | 1,522 | 777 |
| `REPLACEMENT_CHAR_IN_SOURCE` | 2,974 | 2,972 | 2,965 |

Mojibake counts (CJK 125, Cyrillic 80), `BINARY_GARBAGE` (655) and `RECORDS_TIKA_FAILED` (61) are unchanged: no regressions.
Remaining 777 `REPLACEMENT_CHAR`: 502 in valid UTF-8 pages, 90 BOM, the rest spread over declared/statistical charsets. Not
yet investigated; samples so far point at garbage already in the source (double-encoded text, literal U+FFFD in scripts).
