package pt.arquivo.imagesearch.indexing.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Restores accented letters of Portuguese words that came out as U+FFFD ("Par�quia" for "Paróquia").
 * <p>
 * Every U+FFFD inside a word is taken to be a letter of the Portuguese alphabet (áàâãçéêíóôõúü); one letter can be one
 * U+FFFD (a Latin-1 byte) or two (a UTF-8 character), so "��" may be one or two letters ("previs��es" is
 * "previsões", "interven��o" is "intervenção") and the vocabulary decides. The vocabulary and the word pairs
 * come from clean Portuguese text (see scripts/build-fffd-model.py). A word is restored when exactly one reading is left
 * after rare ones are dropped, or when the neighbouring words make one reading clearly more likely; otherwise it is left
 * as it was. Where Portugal and Brazil spell a word differently (mónica/mônica) the spelling of the page wins, Portugal's
 * when the page does not show a preference. Only letters are restored: quotes, dashes, ordinals and truncated words are not.
 */
public final class FffdRestorer {

    private static final char FFFD = '�';
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\uFFFD]+");
    private static final int MAX_LENGTH = 30;
    private static final int MAX_FFFD = 8;
    private static final int MAX_VARIANTS = 64;
    private static final int MIN_WORD = 3;
    private static final double MIN_RELATIVE = 0.02;
    private static final double MARGIN = 2.0;
    /** A lone letter needs a neighbour pair seen this often, and a clear margin, because a bare U+FFFD is often a dash or a bullet. */
    private static final int LONE_MIN_PAIR = 3;
    private static final double LONE_MARGIN = 2.0;
    private static final int MIN_PAIR_COUNT = 20;
    private static final int MIN_BRAZILIAN_VOTES = 3;
    private static final String RESOURCE = "/pt-fffd-model.tsv.gz";

    /** Ways to split a run of k U+FFFD into letters of one or two U+FFFD each: the number of letters of every split. */
    private static final List<int[]>[] SPLITS = buildSplits();

    private final Map<String, List<String>> bySkeleton = new HashMap<>();
    private final Map<String, Integer> unigrams = new HashMap<>();
    private final java.util.TreeMap<String, Integer> sortedWords = new java.util.TreeMap<>();
    private final Map<String, Integer> capitalised = new HashMap<>();
    private final Map<String, Integer> pairs = new HashMap<>();
    private final Map<String, String> brazilianToEuropean = new HashMap<>();
    private final Map<String, String> europeanToBrazilian = new HashMap<>();
    private double total = 1;

    public static final class Result {
        public final String text;
        public final int restored;

        Result(String text, int restored) {
            this.text = text;
            this.restored = restored;
        }
    }

    private static final class Holder {
        static final FffdRestorer INSTANCE = load();
    }

    /** The restorer built from the model in the jar, or null if the model is missing. */
    public static FffdRestorer getInstance() {
        return Holder.INSTANCE;
    }

    private static FffdRestorer load() {
        try (InputStream in = FffdRestorer.class.getResourceAsStream(RESOURCE)) {
            if (in == null)
                return null;
            return fromReader(new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return null;
        }
    }

    /** Builds a restorer from model lines: N total, W word count, U word count, B first second count (tab separated). */
    public static FffdRestorer fromLines(Iterable<String> lines) {
        FffdRestorer r = new FffdRestorer();
        for (String line : lines)
            r.addLine(line);
        r.finish();
        return r;
    }

    private static FffdRestorer fromReader(BufferedReader reader) throws IOException {
        FffdRestorer r = new FffdRestorer();
        String line;
        while ((line = reader.readLine()) != null)
            r.addLine(line);
        r.finish();
        return r;
    }

    private FffdRestorer() {
    }

    private void addLine(String line) {
        String[] f = line.split("\t");
        if (f.length < 2)
            return;
        switch (f[0]) {
            case "N":
                total = Math.max(1, Double.parseDouble(f[1]));
                break;
            case "W":
                unigrams.put(f[1], Integer.parseInt(f[2]));
                sortedWords.put(f[1], Integer.parseInt(f[2]));
                if (f.length > 3 && Integer.parseInt(f[3]) > 0)
                    capitalised.put(f[1], Integer.parseInt(f[3]));
                bySkeleton.computeIfAbsent(skeleton(f[1]), k -> new ArrayList<>()).add(f[1]);
                break;
            case "U":
                unigrams.put(f[1], Integer.parseInt(f[2]));
                break;
            case "B":
                pairs.put(f[1] + " " + f[2], Integer.parseInt(f[3]));
                break;
            default:
                break;
        }
    }

    /** Finds the words that Brazil and Portugal write with ê/ô/â and é/ó/á, to tell which spelling a page uses. */
    private void finish() {
        for (List<String> words : bySkeleton.values()) {
            for (String a : words) {
                for (String b : words) {
                    if (a.length() != b.length() || a.equals(b) || unigrams.get(a) < MIN_PAIR_COUNT || unigrams.get(b) < MIN_PAIR_COUNT)
                        continue;
                    if (isBrazilianVariant(a, b)) {
                        brazilianToEuropean.put(a, b);
                        europeanToBrazilian.put(b, a);
                    }
                }
            }
        }
    }

    private static boolean isBrazilianVariant(String brazilian, String european) {
        int differences = 0;
        for (int i = 0; i < brazilian.length(); i++) {
            char x = brazilian.charAt(i), y = european.charAt(i);
            if (x == y)
                continue;
            // the Brazilian spelling closes the vowel before m or n (econômico/económico, tênis/ténis, Amazônia/Amazónia);
            // other ê/é and ô/ó pairs are different words or other languages (avô/avó, inglês/inglés)
            if (!((x == 'ê' && y == 'é') || (x == 'ô' && y == 'ó')) || i + 2 >= brazilian.length() || "mn".indexOf(brazilian.charAt(i + 1)) < 0)
                return false;
            differences++;
        }
        return differences == 1;
    }

    private static String skeleton(String word) {
        StringBuilder sb = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++)
            sb.append("áàâãçéêíóôõúü".indexOf(word.charAt(i)) >= 0 ? FFFD : word.charAt(i));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<int[]>[] buildSplits() {
        List<int[]>[] splits = new List[MAX_FFFD + 1];
        for (int k = 0; k <= MAX_FFFD; k++)
            splits[k] = new ArrayList<>();
        splits[0].add(new int[0]);
        for (int k = 1; k <= MAX_FFFD; k++) {
            for (int piece = 1; piece <= 2 && piece <= k; piece++) {
                for (int[] rest : splits[k - piece]) {
                    int[] s = new int[rest.length + 1];
                    s[0] = piece;
                    System.arraycopy(rest, 0, s, 1, rest.length);
                    splits[k].add(s);
                }
            }
        }
        return splits;
    }

    /** True if the page's own words use the Brazilian spelling more than the European one. */
    public boolean isBrazilian(String text) {
        int brazilian = 0, european = 0;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String w = m.group();
            if (w.indexOf(FFFD) >= 0 || w.length() > MAX_LENGTH)
                continue;
            w = w.toLowerCase(Locale.ROOT);
            if (brazilianToEuropean.containsKey(w))
                brazilian++;
            else if (europeanToBrazilian.containsKey(w))
                european++;
        }
        return brazilian > european && brazilian - european >= MIN_BRAZILIAN_VOTES;
    }

    private static final class Candidate {
        final String word;
        final int count;
        final int[] pieces; // letters of the word per run of U+FFFD, in order of the runs: the pieces of every run, flattened by run

        Candidate(String word, int count, int[] pieces) {
            this.word = word;
            this.count = count;
            this.pieces = pieces;
        }
    }

    private static final class Token {
        final int start, end;
        final String text;
        List<Candidate> candidates;
        String restored;

        Token(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private static final int LATIN1 = 1, UTF8 = 2, MIXED = 0;

    /**
     * How the page was damaged. Latin-1 bytes turn into one U+FFFD per letter, so runs of odd length ("n�o") are common; UTF-8
     * letters turn into two, so runs are all even and every pair is one letter. With a style, "a��o" is "ação" in the first and
     * can only be one letter ("aço") in the second, instead of both being open.
     */
    private static int damageStyle(List<Token> tokens) {
        int odd = 0, even = 0;
        for (Token t : tokens) {
            String s = t.text;
            if (s.indexOf(FFFD) < 0 || s.length() > MAX_LENGTH || s.replace(String.valueOf(FFFD), "").isEmpty())
                continue;
            for (int i = 0; i < s.length(); ) {
                if (s.charAt(i) != FFFD) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < s.length() && s.charAt(j) == FFFD)
                    j++;
                if ((j - i) % 2 == 0)
                    even++;
                else
                    odd++;
                i = j;
            }
        }
        if (odd + even < 4)
            return MIXED;
        if (odd == 0)
            return UTF8;
        return odd >= 0.15 * (odd + even) ? LATIN1 : MIXED;
    }

    /** Restores what it can in the text; the same instance is returned in {@link Result#text} if nothing changed. */
    public Result restore(String text, boolean brazilian) {
        if (text == null || text.indexOf(FFFD) < 0)
            return new Result(text, 0);
        List<Token> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find())
            tokens.add(new Token(m.start(), m.end(), m.group()));

        int style = damageStyle(tokens);
        // pass 1: words with a single reading
        for (Token t : tokens) {
            if (t.text.indexOf(FFFD) < 0)
                continue;
            t.candidates = candidates(t.text, brazilian, style);
            if (t.candidates.size() == 1)
                t.restored = rebuild(text, t, t.candidates.get(0));
            else if (t.candidates.isEmpty())
                t.restored = completeTruncated(t.text);
        }
        // pass 2: ambiguous words, with the neighbours restored in pass 1 as context
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.restored != null || t.candidates == null || t.candidates.size() < 2)
                continue;
            String prev = i > 0 ? known(tokens.get(i - 1)) : null;
            String next = i + 1 < tokens.size() ? known(tokens.get(i + 1)) : null;
            Candidate best = null;
            double bestScore = Double.NEGATIVE_INFINITY, second = Double.NEGATIVE_INFINITY;
            for (Candidate c : t.candidates) {
                double s = score(c.word, prev, next);
                if (s > bestScore) {
                    second = bestScore;
                    bestScore = s;
                    best = c;
                } else if (s > second) {
                    second = s;
                }
            }
            if (best != null && bestScore - second >= MARGIN)
                t.restored = rebuild(text, t, best);
        }
        StringBuilder out = null;
        int last = 0, restored = 0;
        for (Token t : tokens) {
            if (t.restored == null)
                continue;
            if (out == null)
                out = new StringBuilder(text.length());
            out.append(text, last, t.start).append(t.restored);
            last = t.end;
            restored++;
        }
        String letters = text;
        if (out != null) {
            out.append(text, last, text.length());
            letters = out.toString();
        }
        int[] extra = new int[1];
        String finalText = punctuation(letters, extra);
        if (restored + extra[0] == 0)
            return new Result(text, 0);
        return new Result(finalText, restored + extra[0]);
    }

    private static final Pattern ORDINAL = Pattern.compile("(?<=\\p{N})\uFFFD(?![\\p{L}\\p{N}\uFFFD])");
    private static final Pattern NUMBER_SIGN = Pattern.compile("(?<![\\p{L}\uFFFD])([nN])\uFFFD(?=\\s*\\p{N})");
    private static final Pattern LONE_WORD = Pattern.compile("(?<![^\\s])\uFFFD{1,2}(?![^\\s])");
    private static final Pattern APOSTROPHE = Pattern.compile("(?<=[A-Za-z])\uFFFD(?=(?:s|t|re|ve|ll|m|d)(?![\\p{L}\uFFFD]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTE_OPEN = Pattern.compile("(?<![\\p{L}\\p{N}\uFFFD])\uFFFD(?=\\p{L})");
    private static final Pattern QUOTE_CLOSE = Pattern.compile("(?<=[\\p{L}\\p{N}.,!?;:)])\uFFFD(?![\\p{L}\\p{N}\uFFFD])");
    private static final Pattern WORD_AFTER = Pattern.compile("^\\s*(\\p{L}+)");
    private static final Pattern WORD_BEFORE = Pattern.compile("(\\p{L}+)\\s*$");
    private static final int MAX_QUOTE_SPAN = 300;
    private static final String[] FEMININE_ENDINGS = {"a", "as", "\u00e7\u00e3o", "\u00e7\u00f5es", "dade", "dades", "gem", "gens", "\u00e3", "ez"};

    /**
     * What is left of the U+FFFD that is not a letter inside a word: ordinal indicators after digits (1º, 2ª, nº), the
     * one-letter words é and à, apostrophes inside English words (Lawson’s) and quotation marks that open and close a phrase.
     */
    private String punctuation(String s, int[] count) {
        if (s.indexOf(FFFD) < 0)
            return s;
        s = replaceAll(s, NUMBER_SIGN, count, (m, in) -> m.group(1) + "\u00ba");
        s = replaceAll(s, ORDINAL, count, (m, in) -> {
            if (crowded(in, m.start(), m.end()))
                return null;
            Matcher w = WORD_AFTER.matcher(in.substring(m.end()));
            String next = w.find() ? w.group(1).toLowerCase(Locale.ROOT) : "";
            for (String ending : FEMININE_ENDINGS)
                if (next.length() > ending.length() + 1 && next.endsWith(ending) && !next.equals("para"))
                    return "\u00aa";
            return "\u00ba";
        });
        List<String> oneLetter = bySkeleton.get(String.valueOf(FFFD));
        if (oneLetter != null) {
            s = replaceAll(s, LONE_WORD, count, (m, in) -> {
                // the word touching the U+FFFD on each side (across spaces only); a comma, quote, digit or bar on a side leaves
                // that side without a word and the other one has to carry the decision
                String prevToken = adjacentToken(in, m.start() - 1, -1);
                String nextToken = adjacentToken(in, m.end(), 1);
                if ((prevToken != null && prevToken.indexOf(FFFD) >= 0) || (nextToken != null && nextToken.indexOf(FFFD) >= 0)
                        || crowded(in, m.start(), m.end()))
                    return null;
                String prev = prevToken == null ? null : prevToken.toLowerCase(Locale.ROOT);
                String next = nextToken == null ? null : nextToken.toLowerCase(Locale.ROOT);
                if (prev == null && next == null)
                    return null;
                boolean sentenceStart = startsSentenceAt(in, m.start());
                String best = null;
                double bestScore = Double.NEGATIVE_INFINITY, second = Double.NEGATIVE_INFINITY;
                int top = 0;
                for (String w : oneLetter)
                    top = Math.max(top, unigrams.get(w));
                for (String w : oneLetter) {
                    if (unigrams.get(w) < Math.max(MIN_WORD, MIN_RELATIVE * top))
                        continue;
                    double sc = score(w, prev, next);
                    if (sc > bestScore) {
                        second = bestScore;
                        bestScore = sc;
                        best = w;
                    } else if (sc > second) {
                        second = sc;
                    }
                }
                // a bare U+FFFD between spaces is often a dash or a bullet: it needs the neighbours to vouch for the word
                boolean supported = best != null && ((prev != null && pairs.getOrDefault(prev + " " + best, 0) >= LONE_MIN_PAIR)
                        || (next != null && pairs.getOrDefault(best + " " + next, 0) >= LONE_MIN_PAIR));
                if (!supported || bestScore - second < LONE_MARGIN)
                    return null;
                return sentenceStart ? best.toUpperCase(Locale.ROOT) : best;
            });
        }
        s = replaceAll(s, APOSTROPHE, count, (m, in) -> {
            // only where Portuguese has no reading of the damaged word ("p�s" is pés, pós or pás, not "p's")
            int from = m.start(), to = m.end();
            while (from > 0 && (Character.isLetter(in.charAt(from - 1)) || in.charAt(from - 1) == FFFD))
                from--;
            while (to < in.length() && (Character.isLetter(in.charAt(to)) || in.charAt(to) == FFFD))
                to++;
            return candidates(in.substring(from, to), false, MIXED).isEmpty() ? "\u2019" : null;
        });
        return quotes(s, count);
    }

    /** True if other U+FFFD sit within a few characters: binary garbage or a damaged phrase, not a lone symbol to guess. */
    private static boolean crowded(String s, int from, int to) {
        for (int i = Math.max(0, from - 8); i < Math.min(s.length(), to + 8); i++)
            if ((i < from || i >= to) && s.charAt(i) == FFFD)
                return true;
        return false;
    }

    /** The word (letters and U+FFFD) that touches position at, skipping spaces in the given direction; null if a symbol comes first. */
    private static String adjacentToken(String s, int at, int direction) {
        int i = at;
        while (i >= 0 && i < s.length() && Character.isWhitespace(s.charAt(i)))
            i += direction;
        if (i < 0 || i >= s.length() || !(Character.isLetter(s.charAt(i)) || s.charAt(i) == FFFD))
            return null;
        int j = i;
        while (j + direction >= 0 && j + direction < s.length() && (Character.isLetter(s.charAt(j + direction)) || s.charAt(j + direction) == FFFD))
            j += direction;
        return direction < 0 ? s.substring(j, i + 1) : s.substring(i, j + 1);
    }

    private static boolean startsSentenceAt(String s, int at) {
        int p = at - 1;
        while (p >= 0 && Character.isWhitespace(s.charAt(p)))
            p--;
        return p < 0 || ".!?:".indexOf(s.charAt(p)) >= 0;
    }

    private interface Replacement {
        String apply(Matcher m, String input);
    }

    private static String replaceAll(String s, Pattern p, int[] count, Replacement r) {
        Matcher m = p.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String rep = r.apply(m, s);
            if (rep == null)
                continue;
            if (out == null)
                out = new StringBuilder(s.length());
            out.append(s, last, m.start()).append(rep);
            last = m.end();
            count[0]++;
        }
        if (out == null)
            return s;
        return out.append(s, last, s.length()).toString();
    }

    /** Pairs an opening U+FFFD (before a word) with the next closing one (after a word) and turns both into curly quotes. */
    private static String quotes(String s, int[] count) {
        List<int[]> marks = new ArrayList<>(); // position, 0 = opening, 1 = closing
        Matcher open = QUOTE_OPEN.matcher(s);
        while (open.find())
            marks.add(new int[] {open.start(), 0});
        Matcher close = QUOTE_CLOSE.matcher(s);
        while (close.find())
            marks.add(new int[] {close.start(), 1});
        if (marks.isEmpty())
            return s;
        marks.sort((x, y) -> Integer.compare(x[0], y[0]));
        char[] chars = s.toCharArray();
        int opening = -1;
        for (int[] mark : marks) {
            if (mark[1] == 0) {
                opening = mark[0];
            } else if (opening >= 0 && mark[0] - opening <= MAX_QUOTE_SPAN) {
                chars[opening] = '\u201c';
                chars[mark[0]] = '\u201d';
                count[0]++;
                opening = -1;
            }
        }
        return new String(chars);
    }

    /** A word cut by the site in the middle of its first byte ("igniç�" for "ignição"): completed when one word clearly fits. */
    private String completeTruncated(String token) {
        int n = token.length();
        if (n < 5 || token.charAt(n - 1) != FFFD || token.charAt(n - 2) == FFFD || token.indexOf(FFFD) != n - 1)
            return null;
        String prefix = token.substring(0, n - 1).toLowerCase(Locale.ROOT);
        String best = null, secondBest = null;
        int bestCount = 0, secondCount = 0;
        for (Map.Entry<String, Integer> e : sortedWords.subMap(prefix, prefix + '\uffff').entrySet()) {
            String w = e.getKey();
            if (w.length() <= prefix.length() || w.length() > prefix.length() + 3 || "\u00e1\u00e0\u00e2\u00e3\u00e7\u00e9\u00ea\u00ed\u00f3\u00f4\u00f5\u00fa\u00fc".indexOf(w.charAt(prefix.length())) < 0)
                continue;
            if (e.getValue() > bestCount) {
                secondBest = best;
                secondCount = bestCount;
                best = w;
                bestCount = e.getValue();
            } else if (e.getValue() > secondCount) {
                secondBest = w;
                secondCount = e.getValue();
            }
        }
        if (best == null || bestCount < 10 || (secondBest != null && bestCount < 10 * secondCount))
            return null;
        String suffix = best.substring(prefix.length());
        boolean upper = token.length() > 2 && token.substring(0, n - 1).equals(token.substring(0, n - 1).toUpperCase(Locale.ROOT));
        return token.substring(0, n - 1) + (upper ? suffix.toUpperCase(Locale.ROOT) : suffix);
    }

    /** The lowercase word of a neighbour if it is intact or was restored, else null. */
    private static String known(Token t) {
        if (t.restored != null)
            return t.restored.toLowerCase(Locale.ROOT);
        return t.text.indexOf(FFFD) < 0 ? t.text.toLowerCase(Locale.ROOT) : null;
    }

    private double score(String word, String prev, String next) {
        double u = unigrams.getOrDefault(word, 1);
        double score = Math.log(u);
        if (prev != null) {
            double p = (pairs.getOrDefault(prev + " " + word, 0) + u / total) / (unigrams.getOrDefault(prev, 0) + 1);
            score += Math.log(p);
        }
        if (next != null) {
            double p = (pairs.getOrDefault(word + " " + next, 0) + (unigrams.getOrDefault(next, 0) + 0.01) / total) / (u + 1);
            score += Math.log(p);
        }
        return score;
    }

    /** The readings of a damaged word that the vocabulary knows, rare ones and the spelling the page does not use removed. */
    private List<Candidate> candidates(String token, boolean brazilian, int style) {
        List<Candidate> found = new ArrayList<>();
        if (token.length() > MAX_LENGTH)
            return found;
        // runs of U+FFFD: start and length
        List<int[]> runs = new ArrayList<>();
        int fffd = 0;
        for (int i = 0; i < token.length(); ) {
            if (token.charAt(i) == FFFD) {
                int j = i;
                while (j < token.length() && token.charAt(j) == FFFD)
                    j++;
                runs.add(new int[] {i, j - i});
                fffd += j - i;
                i = j;
            } else {
                i++;
            }
        }
        if (runs.isEmpty() || fffd == token.length() || fffd > MAX_FFFD)
            return found;
        long variants = 1;
        for (int[] run : runs)
            variants *= splits(run[1], style).size();
        if (variants > MAX_VARIANTS)
            return found;
        String lower = token.toLowerCase(Locale.ROOT);
        int[] choice = new int[runs.size()];
        for (long v = 0; v < variants; v++) {
            long rest = v;
            StringBuilder skeleton = new StringBuilder();
            int[][] pieces = new int[runs.size()][];
            int at = 0;
            for (int r = 0; r < runs.size(); r++) {
                int[] run = runs.get(r);
                List<int[]> splits = splits(run[1], style);
                choice[r] = (int) (rest % splits.size());
                rest /= splits.size();
                pieces[r] = splits.get(choice[r]);
                skeleton.append(lower, at, run[0]);
                for (int p = 0; p < pieces[r].length; p++)
                    skeleton.append(FFFD);
                at = run[0] + run[1];
            }
            skeleton.append(lower, at, lower.length());
            List<String> words = bySkeleton.get(skeleton.toString());
            if (words == null)
                continue;
            int[] flat = flatten(pieces);
            for (String w : words)
                found.add(new Candidate(w, unigrams.get(w), flat));
        }
        if (found.size() < 2)
            return found;
        int best = 0;
        for (Candidate c : found)
            best = Math.max(best, c.count);
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : found)
            if (c.count >= Math.max(MIN_WORD, MIN_RELATIVE * best))
                kept.add(c);
        // Portugal and Brazil write some words differently: keep the spelling of the page, Portugal's by default
        Set<String> drop = new HashSet<>();
        for (Candidate c : kept) {
            String european = brazilianToEuropean.get(c.word);
            if (european == null)
                continue;
            for (Candidate other : kept) {
                if (other.word.equals(european)) {
                    drop.add(brazilian ? european : c.word);
                    break;
                }
            }
        }
        List<Candidate> result = new ArrayList<>();
        for (Candidate c : kept)
            if (!drop.contains(c.word))
                result.add(c);
        return result;
    }

    private static List<int[]> splits(int run, int style) {
        if (style == MIXED)
            return SPLITS[run];
        List<int[]> only = new ArrayList<>();
        for (int[] s : SPLITS[run]) {
            boolean ones = true, twos = true;
            for (int x : s) {
                ones &= x == 1;
                twos &= x == 2;
            }
            if ((style == LATIN1 && ones) || (style == UTF8 && twos))
                only.add(s);
        }
        return only;
    }

    private static int[] flatten(int[][] pieces) {
        int n = 0;
        for (int[] p : pieces)
            n += p.length;
        int[] flat = new int[n];
        int k = 0;
        for (int[] p : pieces)
            for (int x : p)
                flat[k++] = x;
        return flat;
    }

    /** The token with its U+FFFD replaced by the letters of the candidate (upper case where the known letters are). */
    private String rebuild(String text, Token t, Candidate c) {
        String token = t.text;
        boolean upper;
        int known = 0, knownUpper = 0;
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch != FFFD) {
                known++;
                if (Character.isUpperCase(ch))
                    knownUpper++;
            }
        }
        upper = known >= 2 && knownUpper == known;
        boolean startsSentence = false;
        if (token.charAt(0) == FFFD && !upper) {
            int p = t.start - 1;
            while (p >= 0 && Character.isWhitespace(text.charAt(p)))
                p--;
            startsSentence = p < 0 || ".!?:".indexOf(text.charAt(p)) >= 0;
            // \u00c1frica, \u00c9vora, \u00d3bidos: words that are almost always written with a capital
            if (!startsSentence && capitalised.getOrDefault(c.word, 0) >= 0.6 * unigrams.getOrDefault(c.word, 1))
                startsSentence = true;
        }
        StringBuilder out = new StringBuilder(token.length());
        int letter = 0, piece = 0;
        for (int i = 0; i < token.length(); ) {
            char ch = token.charAt(i);
            if (ch != FFFD) {
                out.append(ch);
                letter++;
                i++;
                continue;
            }
            int j = i;
            while (j < token.length() && token.charAt(j) == FFFD)
                j++;
            int pending = j - i;
            while (pending > 0) {
                int size = c.pieces[piece++];
                char restored = c.word.charAt(letter++);
                if (upper || (startsSentence && i == 0 && pending == j - i))
                    restored = Character.toUpperCase(restored);
                out.append(restored);
                pending -= size;
            }
            i = j;
        }
        return out.toString();
    }
}
