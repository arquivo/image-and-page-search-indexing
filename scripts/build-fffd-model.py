#!/usr/bin/env python3
"""
Builds the model that restores U+FFFD inside Portuguese words (src/main/resources/pt-fffd-model.tsv.gz).

  scripts/build-fffd-model.py OUT.tsv.gz NAME=GLOB [NAME=GLOB ...]

  scripts/build-fffd-model.py src/main/resources/pt-fffd-model.tsv.gz \\
      AWP10='data/AWP10/jsonls/*' FAWP22='data/FAWP22/jsonls/*' Weblog='data/Weblog/jsonls/*'

Every NAME=GLOB is one source: JSONL as written by the indexer (one document per line, from LocalDocumentIndexer or from the
cluster). A source contributes at most --max-tokens tokens and --max-docs-per-host documents of one host, so that a big
collection or a chatty site does not outweigh the others. A document is used only if it is Portuguese, has no U+FFFD, no
double-encoded text ("Ã£") and is not a JSON document, and only once per source.

Two passes over the sources: the first counts words (and in how many documents each accented word occurs), the second counts
the word pairs around the words that stay ambiguous after pruning, so memory stays small. Tokens are runs of letters (Unicode
letters, no digits or underscore), lowercased, exactly as in FffdRestorer.
"""
import argparse
import collections
import glob
import gzip
import hashlib
import json
import multiprocessing as mp
import re
import sys
import time

PT = set('abcdefghijklmnopqrstuvwxyzáàâãçéêíóôõúü')
ACCENTED = set('áàâãçéêíóôõúü')
CAPITAL_ACCENTS = 'ÁÀÂÃÇÉÊÍÓÔÕÚÜ'
WORD = re.compile(r'[^\W\d_]+', re.U)
MOJIBAKE = re.compile('[ÃÂâ][\u0080-¿€‚-„‘-…‰‹™ŒœŠšŸŽžƒˆ˜]')
FFFD = '�'


def is_candidate(w):
    return all(ch in PT for ch in w) and any(ch in ACCENTED for ch in w)


def skeleton(w):
    return ''.join(FFFD if ch in ACCENTED else ch for ch in w)


def host_of(j):
    s = j.get('surtOldest') or ''
    m = re.match(r'\(([^)]*)\)', s)
    return m.group(1) if m else ''


def documents(files, opts):
    """Yields the lowercased words and the original words of every usable document of a source, within its caps."""
    seen = set()
    per_host = collections.Counter()
    tokens = 0
    for f in files:
        with open(f, encoding='utf-8') as fh:
            for line in fh:
                try:
                    j = json.loads(line)
                except ValueError:
                    continue
                if j.get('language') != 'pt':
                    continue
                c = j.get('content')
                c = ' '.join(c) if isinstance(c, list) else (c or '')
                if len(c) < 200 or FFFD in c or MOJIBAKE.search(c):
                    continue
                s = c.strip()
                if s[:1] in '{[' and s[-1:] in '}]':
                    continue
                digest = hashlib.md5(c.encode('utf-8')).digest()
                if digest in seen:
                    continue
                host = host_of(j)
                if per_host[host] >= opts.max_docs_per_host:
                    continue
                seen.add(digest)
                per_host[host] += 1
                raw = WORD.findall(c[:200000])
                tokens += len(raw)
                yield raw, host
                if tokens >= opts.max_tokens:
                    return


def pass_one(job):
    name, files, opts = job
    uni = collections.Counter()
    df = collections.Counter()
    capitalised = collections.Counter()
    hosts = set()
    docs = 0
    for raw, host in documents(files, opts):
        docs += 1
        hosts.add(host)
        ws = [w.lower() for w in raw]
        uni.update(ws)
        for w in set(ws):
            if not w.isascii():
                df[w] += 1
        for w in raw:
            if w[0] in CAPITAL_ACCENTS and not w.isupper():
                capitalised[w.lower()] += 1
    return name, docs, len(hosts), uni, df, capitalised


AMBIGUOUS = frozenset()


def init_pass_two(ambiguous):
    global AMBIGUOUS
    AMBIGUOUS = ambiguous


def pass_two(job):
    name, files, opts = job
    pairs = collections.Counter()
    amb = AMBIGUOUS
    for raw, _ in documents(files, opts):
        ws = [w.lower() for w in raw]
        for a, b in zip(ws, ws[1:]):
            if a in amb or b in amb:
                pairs[(a, b)] += 1
    return name, pairs


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('out')
    ap.add_argument('sources', nargs='+', help='NAME=GLOB')
    ap.add_argument('--max-tokens', type=float, default=40e6, help='tokens taken from one source (default 40 M)')
    ap.add_argument('--max-docs-per-host', type=int, default=1500)
    ap.add_argument('--min-word', type=int, default=5, help='occurrences of a candidate word')
    ap.add_argument('--min-docs', type=int, default=2, help='documents a candidate word occurs in')
    ap.add_argument('--min-relative', type=float, default=0.02, help='a candidate below this share of its best rival is ignored')
    ap.add_argument('--min-pair', type=int, default=2)
    ap.add_argument('--workers', type=int, default=3)
    opts = ap.parse_args()
    opts.max_tokens = int(opts.max_tokens)

    jobs = []
    for s in opts.sources:
        name, _, pattern = s.partition('=')
        files = sorted(glob.glob(pattern))
        if not files:
            sys.exit('no files for ' + s)
        jobs.append((name, files, opts))

    t0 = time.time()
    uni = collections.Counter()
    df = collections.Counter()
    capitalised = collections.Counter()
    with mp.Pool(opts.workers) as pool:
        for name, docs, hosts, u, d, c in pool.imap_unordered(pass_one, jobs):
            print('%-14s %7d documents, %5d hosts, %6.1f M tokens  (%.0fs)' % (name, docs, hosts, sum(u.values()) / 1e6, time.time() - t0), flush=True)
            uni.update(u)
            df.update(d)
            capitalised.update(c)
    total = sum(uni.values())

    groups = collections.defaultdict(dict)
    for w, n in uni.items():
        if n >= opts.min_word and df[w] >= opts.min_docs and is_candidate(w):
            groups[skeleton(w)][w] = n
    words = {}
    ambiguous = set()
    for sk, cs in groups.items():
        best = max(cs.values())
        kept = {w: n for w, n in cs.items() if n >= max(opts.min_word, opts.min_relative * best)}
        words.update(kept)
        if len(kept) > 1:
            ambiguous.update(kept)
    print('tokens %.1f M, vocabulary %d, candidate words %d, ambiguous %d' % (total / 1e6, len(uni), len(words), len(ambiguous)), flush=True)

    pairs = collections.Counter()
    with mp.Pool(opts.workers, initializer=init_pass_two, initargs=(frozenset(ambiguous),)) as pool:
        for name, p in pool.imap_unordered(pass_two, jobs):
            pairs.update(p)
            print('pairs after %-14s %8d  (%.0fs)' % (name, len(pairs), time.time() - t0), flush=True)
    kept_pairs = {k: n for k, n in pairs.items() if n >= opts.min_pair}
    context = set(ambiguous)
    for a, b in kept_pairs:
        context.add(a)
        context.add(b)

    with gzip.open(opts.out, 'wt', encoding='utf-8', compresslevel=9) as o:
        o.write('N\t%d\n' % total)
        for w, n in sorted(words.items()):
            o.write('W\t%s\t%d\t%d\n' % (w, n, capitalised.get(w, 0)))
        for w in sorted(context - set(words)):
            o.write('U\t%s\t%d\n' % (w, uni[w]))
        for (a, b), n in sorted(kept_pairs.items()):
            o.write('B\t%s\t%s\t%d\n' % (a, b, n))
    print('written %s: %d words, %d context words, %d pairs (%.0fs)' % (opts.out, len(words), len(context - set(words)), len(kept_pairs), time.time() - t0))


if __name__ == '__main__':
    main()
