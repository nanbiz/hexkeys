//! Word suggestions from one or more dictionaries.
//!
//! The typed word is matched against every dictionary word by a weighted
//! edit distance computed down the trie, one row per dictionary character,
//! so a branch is dropped as soon as no word below it can come close
//! enough. Case and accents cost nothing: the keyboard types neither
//! umlauts nor accents directly, so "uber" has to find "über" and "strasse"
//! has to find "Straße". Words that start with what was typed are offered
//! as completions. Candidates are ranked by frequency against cost, with the
//! frequency taken from the previous word's next-word list where it has one.

use crate::dictionary::{Dictionary, Error, Node};
use crate::swipe::Index;
use std::collections::HashMap;
use std::sync::OnceLock;
use unicode_normalization::UnicodeNormalization;

/// A letter the dictionary has but the typed word lacks, or the other way round.
const GAP: f32 = 1.0;
/// Two neighbouring letters swapped.
const SWAP: f32 = 0.7;
/// Finishing a word that starts with what was typed.
const COMPLETION: f32 = 0.5;
/// Frequency points one unit of cost is worth. Frequencies run 0 to 255 on
/// a logarithmic scale.
const COST_WEIGHT: f32 = 60.0;

/// How many letters a completion may add to what was typed. Without a limit
/// one typed letter would walk every word starting with it.
const COMPLETION_LETTERS: usize = 5;

/// How much a typed character differs from a dictionary character, both
/// already folded to lower case without accents: 0 for the same letter.
pub type Substitution<'a> = &'a dyn Fn(char, char) -> f32;

#[derive(Clone, Debug, PartialEq)]
pub struct Suggestion {
    pub word: String,
    pub score: f32,
    /// Spelled exactly as typed, up to case.
    pub exact: bool,
    /// Longer than what was typed rather than a correction of it.
    pub completion: bool,
}

/// The best match of one word found so far.
#[derive(Clone, Copy)]
struct Found {
    score: f32,
    exact: bool,
    completion: bool,
}

impl Found {
    /// Keeps the better scoring of two matches of the same word; it is
    /// exact if either was.
    fn merge(&mut self, other: Found) {
        if other.score > self.score {
            self.score = other.score;
            self.completion = other.completion;
        }
        self.exact |= other.exact;
    }
}

/// What a dictionary character may stand for in typed text, folded: lower
/// case, accents dropped, ß as "ss" or "s", an umlaut also as its letter
/// plus e, the usual spelling without umlauts, and apostrophes and hyphens
/// also as nothing, since they sit behind the symbol layer.
fn spellings(c: char) -> Vec<Vec<char>> {
    let lower: Vec<char> = c.to_lowercase().collect();
    if lower.len() != 1 {
        return vec![lower.iter().flat_map(|&c| fold(c)).collect()];
    }
    let c = lower[0];
    match c {
        'ß' => vec![vec!['s', 's'], vec!['s']],
        '\'' | '’' | '-' => vec![vec![], vec![c]],
        'ä' | 'ö' | 'ü' => vec![fold(c), vec![fold(c)[0], 'e']],
        _ => vec![fold(c)],
    }
}

/// A lower-case character as the keys type it: itself where a key has it,
/// else the plain letters it is written with (src/letters.rs), "é" as "e",
/// "æ" as "ae", "ə" as "e"; a combining mark as nothing.
pub(crate) fn fold(c: char) -> Vec<char> {
    if c.is_ascii() {
        return vec![c];
    }
    if is_combining(c) {
        return Vec::new();
    }
    if let Ok(at) = crate::letters::LETTERS.binary_search_by_key(&c, |&(letter, _)| letter) {
        return crate::letters::LETTERS[at].1.chars().collect();
    }
    let base: Vec<char> = c.to_string().nfd().filter(|c| !is_combining(*c)).collect();
    if base.is_empty() { vec![c] } else { base }
}

fn is_combining(c: char) -> bool {
    matches!(c as u32, 0x0300..=0x036F | 0x1AB0..=0x1AFF | 0x1DC0..=0x1DFF | 0x20D0..=0x20FF | 0xFE20..=0xFE2F)
}

/// The typed word folded the same way as dictionary characters.
fn fold_typed(typed: &str) -> Vec<char> {
    typed.chars().flat_map(|c| c.to_lowercase()).flat_map(fold).collect()
}

/// The largest cost a candidate may have, growing with the typed length:
/// one slip in a short word, two in a long one.
fn budget(length: usize) -> f32 {
    match length {
        0..=2 => 0.7,
        3..=4 => 1.2,
        5..=7 => 1.7,
        _ => 2.2,
    }
}

/// The typed word and what a slip costs: the edit distance side of a search.
struct Matcher<'a> {
    typed: Vec<char>,
    substitution: Substitution<'a>,
    budget: f32,
}

struct Search<'a> {
    dictionary: &'a Dictionary,
    matcher: &'a Matcher<'a>,
    found: HashMap<String, Found>,
}

/// The edit distance rows for the dictionary text so far.
#[derive(Clone)]
struct Rows {
    row: Vec<f32>,
    /// The row and character before the last one, for swaps.
    previous: Option<(Vec<f32>, char)>,
    last: Option<char>,
    /// The lowest cost of having matched all of the typed word against a
    /// prefix of the path, which is what a completion costs.
    prefix: f32,
}

impl Matcher<'_> {
    fn start(&self) -> Rows {
        Rows { row: (0..=self.typed.len()).map(|j| j as f32 * GAP).collect(), previous: None, last: None, prefix: f32::MAX }
    }

    /// The rows after one more folded dictionary character.
    fn step(&self, rows: &Rows, c: char) -> Rows {
        let n = self.typed.len();
        let mut row = vec![0.0; n + 1];
        row[0] = rows.row[0] + GAP;
        for j in 1..=n {
            let typed = self.typed[j - 1];
            let replace = rows.row[j - 1] + (self.substitution)(typed, c);
            let mut cost = replace.min(rows.row[j] + GAP).min(row[j - 1] + GAP);
            if let (Some((before, _)), Some(last)) = (&rows.previous, rows.last)
                && j >= 2
                && typed == last
                && self.typed[j - 2] == c
            {
                cost = cost.min(before[j - 2] + SWAP);
            }
            row[j] = cost;
        }
        let prefix = rows.prefix.min(row[n]);
        Rows { previous: Some((rows.row.clone(), c)), last: Some(c), prefix, row }
    }

    /// The rows after a dictionary character, taking whichever of its
    /// spellings matches best.
    fn advance(&self, rows: &Rows, c: char) -> Rows {
        let mut spellings =
            spellings(c).into_iter().map(|spelling| spelling.iter().fold(rows.clone(), |rows, &c| self.step(&rows, c)));
        let mut best = spellings.next().expect("every character has a spelling");
        // Cell by cell: each prefix of the typed word takes the spelling
        // that suits it, so "uber" and "ueber" both match "über" exactly.
        for other in spellings {
            for (cell, value) in best.row.iter_mut().zip(other.row) {
                *cell = cell.min(value);
            }
            best.prefix = best.prefix.min(other.prefix);
        }
        best
    }

    /// How a word whose rows end in `rows` matches, if within the budget.
    fn judge(&self, rows: &Rows, frequency: f32) -> Option<Found> {
        let exact = rows.row[self.typed.len()];
        let completion = rows.prefix + COMPLETION;
        let cost = exact.min(completion);
        (cost <= self.budget).then_some(Found {
            score: frequency - COST_WEIGHT * cost,
            exact: exact == 0.0,
            completion: completion < exact,
        })
    }

    /// How a whole word matches, for words outside a dictionary.
    fn word(&self, word: &str, frequency: f32) -> Option<Found> {
        let rows = word.chars().fold(self.start(), |rows, c| self.advance(&rows, c));
        self.judge(&rows, frequency)
    }
}

impl Search<'_> {
    fn visit(&mut self, nodes: Vec<Node>, rows: &Rows, word: &mut String) -> Result<(), Error> {
        let matcher = self.matcher;
        let n = matcher.typed.len();
        for node in nodes {
            let mut node_rows = rows.clone();
            for &c in &node.chars {
                node_rows = matcher.advance(&node_rows, c);
            }
            let length = word.len();
            word.extend(&node.chars);
            if let Some(frequency) = node.frequency
                && !node.hidden
                && let Some(found) = matcher.judge(&node_rows, frequency as f32)
            {
                match self.found.get_mut(word.as_str()) {
                    Some(entry) => entry.merge(found),
                    None => {
                        self.found.insert(word.clone(), found);
                    }
                }
            }
            let reachable = node_rows.row.iter().copied().fold(f32::MAX, f32::min);
            let completing = node_rows.prefix + COMPLETION <= matcher.budget
                && word.chars().count() < n + COMPLETION_LETTERS;
            if let Some(children) = node.children
                && (reachable <= matcher.budget || completing)
            {
                let children = self.dictionary.array(children)?;
                self.visit(children, &node_rows, word)?;
            }
            word.truncate(length);
        }
        Ok(())
    }
}

/// The most accented letters one key offers: as many as fit a popup row.
const MAX_ACCENTS: usize = 7;

/// The key a letter the keys do not have is held on: the first plain letter
/// it is written with, "e" for "é" and "ə", "t" for "þ".
fn plain(c: char) -> Option<char> {
    if c.is_ascii() {
        return None;
    }
    fold(c).first().copied().filter(char::is_ascii_lowercase)
}

/// How often a word has to be kept before it counts as learned.
const LEARNED: u32 = 2;

/// The frequency a learned word ranks with: that of a common word, since
/// it is one of the user's.
const LEARNED_FREQUENCY: f32 = 180.0;

/// The dictionaries suggestions come from, plus the words learned from what
/// the user keeps typing that no dictionary has.
#[derive(Default)]
pub struct Lexicon {
    pub(crate) dictionaries: Vec<Dictionary>,
    learned: HashMap<String, u32>,
    /// The dictionaries' words as swipe typing looks them up, built at the
    /// first swipe and dropped whenever the dictionaries change.
    swipe: OnceLock<Index>,
    /// Accented letters each dictionary uses and how much, counted once.
    accent_uses: OnceLock<Vec<HashMap<char, f64>>>,
    /// Frequency points each dictionary's words gain from the language of
    /// the text being written, by [`Lexicon::set_context`].
    boosts: Vec<f32>,
    /// The dictionary whose language the text seems to be in.
    guess: Option<usize>,
    /// The dictionary the user picked for the language, over the guess.
    pub pinned: Option<usize>,
    /// The dictionary of the language the text is surely in, if it surely is in one.
    sure: Option<usize>,
    /// The counts telling apart spellings of one word, each with the
    /// dictionary of its language.
    pub(crate) contexts: Vec<(usize, crate::context::Contexts)>,
}

/// The most frequency points the language of the last words written gives
/// its words, about half the frequency range: enough that English "I'm"
/// (116) beats German "im" (229) in an English sentence, as the user
/// expects, and replays of the phone's swipes kept their words otherwise.
const LANGUAGE_BOOST: f32 = 120.0;

/// How many of the last words only one language has make it sure.
const SURE_WORDS: f32 = 3.0;

/// The least share of a language's text an accented letter is in for the
/// language to put it first while it is written: German ä is in 3% of
/// German, Dutch ë in 0.5% of Dutch, and English's most used, the é of
/// loanwords, in 0.03% of English.
const OWN_ACCENT: f64 = 0.001;

/// How many of the last words written tell the language.
const CONTEXT_WORDS: usize = 6;

/// How much more common a word several dictionaries have must be in one of
/// them, in frequency steps, to tell that language: Spanish lists German
/// das, diese and ja at 109, 90 and 88 where German has them at 231, 193
/// and 172, while words two languages really share sit close together.
const SHARED_LEAD: u8 = 40;

impl Lexicon {
    pub fn new(dictionaries: Vec<Dictionary>) -> Self {
        Self { dictionaries, ..Self::default() }
    }

    /// Works out the language of the text before the cursor from its last
    /// whole words that only one dictionary has, or one far more often than
    /// the others, and boosts that dictionary's words by their share of them.
    pub fn set_context(&mut self, before: &str) {
        let complete = before.trim_end_matches(in_word);
        let words: Vec<&str> =
            complete.rsplit(|c: char| !in_word(c)).filter(|word| !word.is_empty()).take(CONTEXT_WORDS).collect();
        let mut counts = vec![0f32; self.dictionaries.len()];
        for word in words {
            let lower = word.to_lowercase();
            let knowing: Vec<(usize, u8)> = (0..self.dictionaries.len())
                .filter_map(|d| {
                    [word, lower.as_str()]
                        .iter()
                        .filter_map(|form| self.dictionaries[d].find(form).ok().flatten().filter(|node| !node.hidden))
                        .filter_map(|node| node.frequency)
                        .max()
                        .map(|frequency| (d, frequency))
                })
                .collect();
            // The one dictionary that has the word, or has it far more often than the rest.
            let most = knowing.iter().map(|&(_, f)| f).max().unwrap_or(0);
            let leading: Vec<usize> = knowing.iter().filter(|&&(_, f)| f.saturating_add(SHARED_LEAD) > most).map(|&(d, _)| d).collect();
            if let [only] = leading[..] {
                counts[only] += 1.0;
            }
        }
        if let Some(pinned) = self.pinned.filter(|&d| d < self.dictionaries.len()) {
            self.boosts = (0..self.dictionaries.len()).map(|d| if d == pinned { LANGUAGE_BOOST } else { 0.0 }).collect();
            self.guess = Some(pinned);
            self.sure = Some(pinned);
            return;
        }
        let total: f32 = counts.iter().sum();
        self.boosts = counts.iter().map(|count| if total > 0.0 { LANGUAGE_BOOST * count / total } else { 0.0 }).collect();
        self.sure = (0..counts.len()).find(|&d| counts[d] >= SURE_WORDS && counts[d] >= 3.0 * (total - counts[d]));
        // A new guess needs words for it; without any the last one stands.
        if total > 0.0 {
            self.guess = (0..counts.len()).max_by(|&a, &b| counts[a].total_cmp(&counts[b]));
        }
    }

    /// The dictionary of the language the text is surely in: picked, or with
    /// at least [`SURE_WORDS`] of the last words only it has and three times
    /// as many as all others together.
    pub fn sure_language(&self) -> Option<usize> {
        self.sure
    }

    /// The dictionary the language is taken from: picked, or else guessed.
    pub fn language(&self) -> Option<usize> {
        self.pinned.or(self.guess)
    }

    /// The dictionaries from the language the text is most likely in to the
    /// least, the one in use first; ties keep their order.
    pub fn ranking(&self) -> Vec<usize> {
        let mut order: Vec<usize> = (0..self.dictionaries.len()).collect();
        order.sort_by(|&a, &b| {
            let first = |d: usize| Some(d) == self.language();
            first(b).cmp(&first(a)).then(self.boost(b).total_cmp(&self.boost(a)))
        });
        order
    }

    /// What the context gives a word of dictionary `index`.
    pub(crate) fn boost(&self, index: usize) -> f32 {
        self.boosts.get(index).copied().unwrap_or(0.0)
    }

    pub fn add(&mut self, dictionary: Dictionary) {
        self.dictionaries.push(dictionary);
        self.swipe = OnceLock::new();
        self.accent_uses = OnceLock::new();
    }

    /// Adds the context counts for the language of dictionary `index`.
    pub fn add_contexts(&mut self, index: usize, contexts: crate::context::Contexts) {
        self.contexts.push((index, contexts));
    }

    pub fn clear(&mut self) {
        self.dictionaries.clear();
        self.contexts.clear();
        self.swipe = OnceLock::new();
        self.accent_uses = OnceLock::new();
    }

    /// Builds what swipe typing looks words up in now, so the first swipe
    /// does not wait for it.
    pub fn prepare(&self) -> Result<(), Error> {
        self.swipe_index().map(|_| ())
    }

    pub(crate) fn swipe_index(&self) -> Result<&Index, Error> {
        if let Some(index) = self.swipe.get() {
            return Ok(index);
        }
        let index = Index::new(&self.dictionaries)?;
        Ok(self.swipe.get_or_init(|| index))
    }

    /// The accented letters the dictionaries use, by the plain letter they
    /// are typed from, most used first: what holding a letter offers, so
    /// every word of the languages in use can be spelled. A letter's use
    /// counts each word it is in by how common the word is.
    pub fn accents(&self) -> Result<HashMap<char, Vec<char>>, Error> {
        self.accents_for(None)
    }

    /// [`Lexicon::accents`] with the accents dictionary `first` uses ranked
    /// ahead of the others.
    pub fn accents_for(&self, first: Option<usize>) -> Result<HashMap<char, Vec<char>>, Error> {
        self.accents_preferring(first.as_slice())
    }

    /// [`Lexicon::accents`] for the text written: the accents of the language
    /// in use first, then those of every other language the last words are
    /// in, by how many of them it has. A German sentence with a few English
    /// words in it, which English may lead, still offers ä before Spanish á.
    pub fn accents_in_context(&self) -> Result<HashMap<char, Vec<char>>, Error> {
        let preferred: Vec<usize> =
            self.ranking().into_iter().filter(|&d| Some(d) == self.language() || self.boost(d) > 0.0).collect();
        self.accents_preferring(&preferred)
    }

    /// [`Lexicon::accents`] with the accents of the `preferred` dictionaries
    /// ranked ahead, in their order, each by how much that language uses
    /// them: with German being written ä ö ü come first, with French é è à.
    /// Only accents a language really writes count as its own, [`OWN_ACCENT`]
    /// of its text or more: English's loanword á stays behind German's ä.
    fn accents_preferring(&self, preferred: &[usize]) -> Result<HashMap<char, Vec<char>>, Error> {
        let uses = self.accent_uses()?;
        let mut total: HashMap<char, f64> = HashMap::new();
        for shares in uses {
            for (&c, &share) in shares {
                *total.entry(c).or_insert(0.0) += share;
            }
        }
        // Which preferred language owns an accent first, and how much it uses it.
        let owner = |c: char| {
            preferred.iter().enumerate().find_map(|(rank, &d)| {
                uses.get(d).and_then(|shares| shares.get(&c)).copied().filter(|&share| share >= OWN_ACCENT).map(|share| (rank, share))
            })
        };
        let mut accents: HashMap<char, Vec<(char, usize, f64, f64)>> = HashMap::new();
        for (&c, &used) in &total {
            if let Some(base) = plain(c) {
                let (rank, own) = owner(c).unwrap_or((preferred.len(), 0.0));
                accents.entry(base).or_default().push((c, rank, own, used));
            }
        }
        Ok(accents
            .into_iter()
            .map(|(base, mut letters)| {
                letters.sort_by(|a, b| a.1.cmp(&b.1).then(b.2.total_cmp(&a.2)).then(b.3.total_cmp(&a.3)).then(a.0.cmp(&b.0)));
                (base, letters.into_iter().take(MAX_ACCENTS).map(|(c, ..)| c).collect())
            })
            .collect())
    }

    /// The share of each dictionary's text each accented letter is in,
    /// counted over all its words once and kept: a language's letters are
    /// weighed against its own text, so a large dictionary does not drown a
    /// small one's.
    fn accent_uses(&self) -> Result<&Vec<HashMap<char, f64>>, Error> {
        if let Some(uses) = self.accent_uses.get() {
            return Ok(uses);
        }
        let mut all = Vec::new();
        for dictionary in &self.dictionaries {
            let mut uses: HashMap<char, f64> = HashMap::new();
            let mut text = 0.0;
            dictionary.for_each_word(|word: &str, frequency: u8| {
                // Frequencies are logarithmic; 12 steps for a doubling fits how often
                // short common words such as French à occur in text.
                let weight = 2f64.powf(frequency as f64 / 12.0);
                text += weight;
                for c in word.chars().flat_map(char::to_lowercase) {
                    if c.is_alphabetic() && !c.is_ascii() && plain(c).is_some() {
                        *uses.entry(c).or_insert(0.0) += weight;
                    }
                }
            })?;
            if text > 0.0 {
                uses.values_mut().for_each(|used| *used /= text);
            }
            all.push(uses);
        }
        Ok(self.accent_uses.get_or_init(|| all))
    }

    /// Learned words with the frequency they rank with.
    pub(crate) fn learned_words(&self) -> impl Iterator<Item = (&str, f32)> {
        self.learned.iter().filter(|&(_, &times)| times >= LEARNED).map(|(word, _)| (word.as_str(), LEARNED_FREQUENCY))
    }

    /// Counts `word` as kept `times` more times; returns how often it now has been.
    pub fn learn(&mut self, word: &str, times: u32) -> u32 {
        let count = self.learned.entry(word.to_owned()).or_insert(0);
        *count = count.saturating_add(times);
        *count
    }

    pub fn forget(&mut self) {
        self.learned.clear();
    }

    fn knows(&self, word: &str) -> bool {
        self.learned.get(word).is_some_and(|&times| times >= LEARNED)
    }
}

/// Suggestions for `typed` after the word `previous`, best first.
pub fn suggest(
    lexicon: &Lexicon,
    typed: &str,
    previous: Option<&str>,
    substitution: Substitution,
    limit: usize,
) -> Result<Vec<Suggestion>, Error> {
    let folded = fold_typed(typed);
    if folded.is_empty() {
        return Ok(Vec::new());
    }
    let matcher = Matcher { budget: budget(folded.len()), typed: folded, substitution };
    let mut found: HashMap<String, Found> = HashMap::new();
    for (word, &times) in &lexicon.learned {
        if times >= LEARNED
            && let Some(match_) = matcher.word(word, LEARNED_FREQUENCY)
        {
            found.insert(word.clone(), match_);
        }
    }
    for (index, dictionary) in lexicon.dictionaries.iter().enumerate() {
        let mut search = Search { dictionary, matcher: &matcher, found: HashMap::new() };
        search.visit(dictionary.root()?, &matcher.start(), &mut String::new())?;
        for entry in search.found.values_mut() {
            entry.score += lexicon.boost(index);
        }
        // A word that often follows the previous one ranks by how often it does.
        if let Some(previous) = previous {
            for (word, frequency) in next_words(dictionary, previous)? {
                if let Some(entry) = search.found.get_mut(&word) {
                    let unigram = dictionary.find(&word)?.and_then(|node| node.frequency).unwrap_or(0);
                    entry.score += frequency.saturating_sub(unigram) as f32;
                }
            }
        }
        for (word, other) in search.found {
            match found.get_mut(&word) {
                Some(entry) => entry.merge(other),
                None => {
                    found.insert(word, other);
                }
            }
        }
    }
    let mut suggestions: Vec<Suggestion> = found
        .into_iter()
        .map(|(word, found)| Suggestion {
            word: match_case(typed, &word),
            score: found.score,
            exact: found.exact,
            completion: found.completion,
        })
        .collect();
    suggestions.sort_by(|a, b| b.score.total_cmp(&a.score).then_with(|| a.word.cmp(&b.word)));
    // Case variants of one word ("Haus", "haus") collapse to the best one.
    let mut seen = Vec::new();
    suggestions.retain(|s| {
        let key = s.word.to_lowercase();
        if seen.contains(&key) {
            false
        } else {
            seen.push(key);
            true
        }
    });
    suggestions.truncate(limit);
    Ok(suggestions)
}

/// What usually follows `previous`, best first, before anything is typed;
/// the words of the language the text is in gain as they do while typing.
pub fn predict(lexicon: &Lexicon, previous: &str, limit: usize) -> Result<Vec<String>, Error> {
    let mut found: HashMap<String, f32> = HashMap::new();
    for (index, dictionary) in lexicon.dictionaries.iter().enumerate() {
        for (word, frequency) in next_words(dictionary, previous)? {
            let score = frequency as f32 + lexicon.boost(index);
            let entry = found.entry(word).or_insert(0.0);
            *entry = entry.max(score);
        }
    }
    let mut words: Vec<(String, f32)> = found.into_iter().collect();
    words.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    Ok(words.into_iter().take(limit).map(|(word, _)| word).collect())
}

/// Next words for `previous` as written, or, where the dictionary lacks it
/// or holds it at frequency 0 (English "i", a slip for "I"), for its most
/// frequent other form in lower case or capitalised: a sentence start
/// capitalises "Die" for "die", a lazy "i" stands for "I".
pub(crate) fn next_words(dictionary: &Dictionary, previous: &str) -> Result<Vec<(String, u8)>, Error> {
    let written = dictionary.find(previous)?.filter(|node| node.frequency.unwrap_or(0) > 0);
    let node = match written {
        Some(node) => Some(node),
        None => {
            let lower = previous.to_lowercase();
            let mut best: Option<Node> = None;
            for form in [lower.clone(), capitalise(&lower)] {
                if let Some(node) = dictionary.find(&form)?
                    && node.frequency > best.as_ref().and_then(|b| b.frequency)
                {
                    best = Some(node);
                }
            }
            best
        }
    };
    match node {
        Some(node) => dictionary.next_words(&node),
        None => Ok(Vec::new()),
    }
}

/// What the suggestion strip shows: three slots left to right, empty where
/// there is nothing, and how many characters before the cursor a choice
/// replaces.
#[derive(Clone, Debug, PartialEq)]
pub struct Strip {
    pub slots: [String; 3],
    pub replaces: usize,
    /// What autocorrect puts in place of the typed word when a space or
    /// punctuation follows it: the best candidate, when the typed word is
    /// not a word and the candidate corrects rather than completes it.
    pub correction: Option<String>,
    /// The word being typed, when it is not a word yet; what learning counts.
    pub unknown: Option<String>,
    /// The obvious fix of a typed word that is no word: the word it is
    /// apart from capitals, apostrophes and accents, such as "Haus" for
    /// "haus", "I'm" for "im" or "für" for "fur", where nothing else is.
    pub fix: Option<String>,
}

/// Letters and the marks inside words; what the word at the cursor is made of.
fn in_word(c: char) -> bool {
    c.is_alphanumeric() || matches!(c, '\'' | '’' | '-')
}

/// The strip for the text before the cursor. The best candidate takes the
/// middle as on other keyboards. While a word is being typed that no
/// dictionary spells that way, the word as typed stays on the left so it
/// can be kept; otherwise the left and right slots hold the runners-up.
/// After a word and a space, the slots offer what usually comes next.
pub fn strip(lexicon: &Lexicon, before: &str, substitution: Substitution) -> Result<Strip, Error> {
    let start = before.char_indices().rev().take_while(|&(_, c)| in_word(c)).last().map_or(before.len(), |(i, _)| i);
    let (rest, typed) = before.split_at(start);
    let previous = rest.trim_end().rsplit(|c: char| !in_word(c)).next().filter(|word| !word.is_empty());
    let mut slots = [String::new(), String::new(), String::new()];
    if typed.is_empty() {
        if rest.ends_with(' ')
            && let Some(previous) = previous
        {
            let words = predict(lexicon, previous, 3)?;
            for (slot, word) in [1, 0, 2].into_iter().zip(words) {
                slots[slot] = word;
            }
        }
        return Ok(Strip { slots, replaces: 0, correction: None, unknown: None, fix: None });
    }
    let found = suggest(lexicon, typed, previous, substitution, 3)?;
    let known = spelled(lexicon, typed)?;
    let correction =
        found.first().filter(|best| !known && !best.completion && !drops_accents(typed, &best.word)).map(|best| best.word.clone());
    // A word some language has is fixed only when the text is surely in another
    // that has a spelling of it, as "im" is "I'm" in a surely English text.
    let fix = if known {
        let lower = typed.to_lowercase();
        let plainly = typed == lower || typed == capitalise(&lower);
        lexicon
            .sure_language()
            .filter(|_| plainly)
            .and_then(|language| lexicon.respell_in(language, &lower))
            .map(|word| if typed != lower { capitalise(&word) } else { word })
    } else {
        obvious_fix(lexicon, typed, found.iter().map(|s| s.word.as_str()))?
    };
    let mut words: Vec<String> = found.into_iter().map(|s| s.word).filter(|word| word != typed).collect();
    if known {
        words.insert(0, typed.to_owned());
        for (slot, word) in [1, 0, 2].into_iter().zip(words) {
            slots[slot] = word;
        }
    } else {
        slots[0] = typed.to_owned();
        for (slot, word) in [1, 2].into_iter().zip(words) {
            slots[slot] = word;
        }
    }
    let unknown = (!known).then(|| typed.to_owned());
    Ok(Strip { slots, replaces: typed.chars().count(), correction, unknown, fix })
}

/// A word reduced to what an obvious slip leaves alone: lower case, no
/// apostrophes, every letter as the keys type it.
fn folded(word: &str) -> String {
    word.chars().flat_map(char::to_lowercase).filter(|c| !matches!(c, '\'' | '’')).flat_map(fold).collect()
}

/// How common a word has to be (of 255) for an obvious fix that adds both a
/// capital and an apostrophe or accent: "I'm" for "im" (116) and "John's"
/// for "johns" (108) are, the possessives of rarer names such as "Lang's"
/// for "langs" (69) are not.
const COMMON: u8 = 100;

/// The word `typed` stands for when it differs only in capitals,
/// apostrophes and accents: the first such candidate, or else the typed
/// word capitalised or in capitals when a dictionary has that. A capital
/// the typed word starts with is kept.
fn obvious_fix<'a>(lexicon: &Lexicon, typed: &str, candidates: impl Iterator<Item = &'a str>) -> Result<Option<String>, Error> {
    let target = folded(typed);
    let lower = typed.to_lowercase();
    let typed_lower = lower == typed;
    let likely = |word: &str| {
        let capital = typed_lower && word.to_lowercase() != word;
        let marked = word.to_lowercase() != lower;
        let frequency = lexicon
            .dictionaries
            .iter()
            .filter_map(|d| d.find(word).ok().flatten().and_then(|node| node.frequency))
            .max()
            .unwrap_or(0);
        !(capital && marked) || frequency >= COMMON
    };
    let mut found = candidates
        .filter(|word| *word != typed)
        .find(|word| folded(word) == target && likely(word) && !drops_accents(typed, word))
        .map(str::to_owned);
    if found.is_none() {
        for form in [capitalise(&typed.to_lowercase()), typed.to_uppercase()] {
            if form != typed && lexicon.dictionaries.iter().any(|d| d.find(&form).ok().flatten().is_some_and(|node| !node.hidden)) {
                found = Some(form);
                break;
            }
        }
    }
    let starts_upper = typed.chars().next().is_some_and(char::is_uppercase);
    Ok(found.map(|word| if starts_upper { capitalise(&word) } else { word }))
}

/// Whether `word` lacks an accented letter `typed` has. An accent takes
/// holding its letter, so it is never a slip: "ä" typed alone stays rather
/// than becoming English "a", which matching, blind to accents, finds free.
fn drops_accents(typed: &str, word: &str) -> bool {
    let word: Vec<char> = word.chars().flat_map(char::to_lowercase).collect();
    typed.chars().flat_map(char::to_lowercase).filter(|&c| plain(c).is_some()).any(|c| !word.contains(&c))
}

/// The last word in `before`, which the next word follows.
pub fn last_word(before: &str) -> Option<&str> {
    before.rsplit(|c: char| !in_word(c)).find(|word| !word.is_empty())
}

/// `word` with its first letter in upper case.
pub fn capitalise(word: &str) -> String {
    let mut chars = word.chars();
    chars.next().into_iter().flat_map(char::to_uppercase).chain(chars).collect()
}

/// Whether a dictionary, or what was learned, spells `word` exactly so, or with a lower-case first
/// letter where the word was capitalised to start a sentence.
fn spelled(lexicon: &Lexicon, word: &str) -> Result<bool, Error> {
    let mut chars = word.chars();
    let lowered: String = chars.next().into_iter().flat_map(char::to_lowercase).chain(chars).collect();
    if lexicon.knows(word) || lexicon.knows(&lowered) {
        return Ok(true);
    }
    for dictionary in &lexicon.dictionaries {
        for form in [word, &lowered] {
            if dictionary.find(form)?.is_some_and(|node| !node.hidden) {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

/// Whether any dictionary has `word`, as typed or with its first letter
/// lowered or raised.
pub fn is_word(lexicon: &Lexicon, word: &str) -> Result<bool, Error> {
    let lower = word.to_lowercase();
    let mut capital = String::new();
    let mut chars = lower.chars();
    if let Some(first) = chars.next() {
        capital.extend(first.to_uppercase());
        capital.push_str(chars.as_str());
    }
    if [word, &lower, &capital].iter().any(|form| lexicon.knows(form)) {
        return Ok(true);
    }
    for dictionary in &lexicon.dictionaries {
        for form in [word, &lower, &capital] {
            if dictionary.find(form)?.is_some_and(|node| !node.hidden) {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

/// The dictionary word in the case the user typed: all capitals if they
/// typed more than one letter in capitals, a capital first letter if they
/// started with one, otherwise as the dictionary spells it.
fn match_case(typed: &str, word: &str) -> String {
    let letters: Vec<char> = typed.chars().filter(|c| c.is_alphabetic()).collect();
    if letters.len() > 1 && letters.iter().all(|c| c.is_uppercase()) {
        return word.to_uppercase();
    }
    if letters.first().is_some_and(|c| c.is_uppercase()) {
        let mut chars = word.chars();
        if let Some(first) = chars.next() {
            return first.to_uppercase().chain(chars).collect();
        }
    }
    word.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dictionary::tests::encode;

    fn plain(a: char, b: char) -> f32 {
        if a == b { 0.0 } else { 1.0 }
    }

    fn words(lexicon: &Lexicon, typed: &str, previous: Option<&str>) -> Vec<String> {
        suggest(lexicon, typed, previous, &plain, 3).unwrap().into_iter().map(|s| s.word).collect()
    }

    fn german() -> Lexicon {
        let words = [
            ("Haus", 200),
            ("Hause", 180),
            ("hat", 220),
            ("über", 230),
            ("Straße", 180),
            ("die", 250),
            ("das", 250),
            ("Polizei", 190),
            ("Stadt", 200),
            ("hallo", 150),
        ];
        Lexicon::new(vec![Dictionary::new(encode(&words, &[("die", "Polizei", 15), ("die", "Stadt", 3)])).unwrap()])
    }

    #[test]
    fn case_and_umlauts_cost_nothing() {
        let german = german();
        assert_eq!(words(&german, "haus", None)[0], "Haus");
        assert_eq!(words(&german, "uber", None)[0], "über");
        assert_eq!(words(&german, "ueber", None)[0], "über");
        assert_eq!(words(&german, "strasse", None)[0], "Straße");
        assert_eq!(words(&german, "strase", None)[0], "Straße");
    }

    #[test]
    fn obvious_slips_are_fixed_and_words_left_alone() {
        let german = german();
        let fix = |text: &str| strip(&german, text, &plain).unwrap().fix;
        assert_eq!(fix("das haus").as_deref(), Some("Haus"));
        assert_eq!(fix("uber").as_deref(), Some("über"));
        assert_eq!(fix("strasse").as_deref(), Some("Straße"));
        assert_eq!(fix("das Haus"), None, "a word as it is");
        assert_eq!(fix("die"), None);
        assert_eq!(fix("hallp"), None, "a slip of the finger is no obvious fix");
        let english = Lexicon::new(vec![Dictionary::new(encode(&[("I'm", 200), ("I", 250), ("don't", 200)], &[])).unwrap()]);
        let fix = |text: &str| strip(&english, text, &plain).unwrap().fix;
        assert_eq!(fix("im").as_deref(), Some("I'm"));
        assert_eq!(fix("i").as_deref(), Some("I"));
        assert_eq!(fix("Dont").as_deref(), Some("Don't"));
        let names = Lexicon::new(vec![Dictionary::new(encode(&[("Lang's", 69), ("John's", 108)], &[])).unwrap()]);
        let fix = |text: &str| strip(&names, text, &plain).unwrap().fix;
        assert_eq!(fix("langs"), None, "a rare name's possessive is no obvious fix");
        assert_eq!(fix("johns").as_deref(), Some("John's"));
    }

    #[test]
    fn the_language_being_written_wins_over_the_other() {
        let english = Dictionary::new(encode(&[("I'm", 200), ("and", 250), ("also", 230)], &[])).unwrap();
        let german = Dictionary::new(encode(&[("im", 215), ("und", 250), ("auch", 230)], &[])).unwrap();
        let mut lexicon = Lexicon::new(vec![english, german]);
        lexicon.set_context("und auch ");
        assert_eq!(words(&lexicon, "im", None)[0], "im");
        lexicon.set_context("and also ");
        assert_eq!(words(&lexicon, "im", None)[0], "I'm");
        assert_eq!(lexicon.language(), Some(0));
        lexicon.pinned = Some(1);
        lexicon.set_context("and also ");
        assert_eq!((words(&lexicon, "im", None)[0].as_str(), lexicon.language()), ("im", Some(1)), "a picked language wins");
    }

    #[test]
    fn a_language_writing_an_accent_only_in_loanwords_does_not_put_it_first() {
        let mut english_words: Vec<(String, u8)> = (0..2000).map(|i| (format!("w{i}"), 200)).collect();
        english_words.push(("señor".into(), 60));
        english_words.push(("pásame".into(), 60));
        let english_refs: Vec<(&str, u8)> = english_words.iter().map(|(w, f)| (w.as_str(), *f)).collect();
        let english = Dictionary::new(encode(&english_refs, &[])).unwrap();
        let german = Dictionary::new(encode(&[("und", 255), ("für", 230), ("März", 180)], &[])).unwrap();
        let lexicon = Lexicon::new(vec![english, german]);
        assert_eq!(lexicon.accents_for(Some(0)).unwrap()[&'a'][0], 'ä', "while English is written");
        assert_eq!(lexicon.accents_for(Some(1)).unwrap()[&'a'][0], 'ä');
    }

    #[test]
    fn a_typed_accent_is_never_corrected_away() {
        let english = Dictionary::new(encode(&[("a", 255), ("the", 255)], &[])).unwrap();
        let german = Dictionary::new(encode(&[("auf", 240), ("für", 230)], &[])).unwrap();
        let lexicon = Lexicon::new(vec![english, german]);
        let strip = strip(&lexicon, "ä", &|a, b| if a == b { 0.0 } else { 1.0 }).unwrap();
        assert_eq!(strip.correction, None);
        assert_eq!(strip.fix, None);
        let strip = super::strip(&lexicon, "fur", &|a, b| if a == b { 0.0 } else { 1.0 }).unwrap();
        assert_eq!(strip.fix.as_deref(), Some("für"), "an accent left out is still put in");
    }

    #[test]
    fn the_language_in_use_puts_its_accents_first() {
        let french = Dictionary::new(encode(&[("à", 250), ("là", 200)], &[])).unwrap();
        let portuguese = Dictionary::new(encode(&[("ânimo", 150)], &[])).unwrap();
        let lexicon = Lexicon::new(vec![french, portuguese]);
        assert_eq!(lexicon.accents_for(None).unwrap()[&'a'], ['à', 'â']);
        assert_eq!(lexicon.accents_for(Some(1)).unwrap()[&'a'], ['â', 'à']);
    }

    #[test]
    fn every_language_in_the_text_puts_its_accents_ahead_of_others() {
        let english = Dictionary::new(encode(&[("street", 200), ("view", 200), ("challenge", 150), ("the", 255)], &[])).unwrap();
        let german = Dictionary::new(encode(&[("das", 250), ("diese", 200), ("für", 230), ("März", 180)], &[])).unwrap();
        let spanish = Dictionary::new(encode(&[("está", 250), ("más", 240), ("allá", 200), ("qué", 200)], &[])).unwrap();
        let mut lexicon = Lexicon::new(vec![english, german, spanish]);
        // As on the phone: a German sentence whose last words are mostly English.
        lexicon.set_context("diese challenge das street view ");
        assert_eq!(lexicon.language(), Some(0));
        assert_eq!(lexicon.accents_for(None).unwrap()[&'a'][0], 'á', "by use alone Spanish leads");
        assert_eq!(lexicon.accents_in_context().unwrap()[&'a'][0], 'ä', "German is in the text, Spanish is not");
        // Spanish written, its accents come first.
        lexicon.set_context("qué más está ");
        assert_eq!(lexicon.accents_in_context().unwrap()[&'a'][0], 'á');
    }

    #[test]
    fn apostrophes_may_be_left_out() {
        let english = Lexicon::new(vec![Dictionary::new(encode(&[("don't", 200), ("done", 150)], &[])).unwrap()]);
        let found = suggest(&english, "dont", None, &plain, 3).unwrap();
        assert_eq!((found[0].word.as_str(), found[0].exact), ("don't", true));
    }

    #[test]
    fn slips_are_corrected() {
        let german = german();
        assert_eq!(words(&german, "hallp", None)[0], "hallo", "substitution");
        assert_eq!(words(&german, "hlalo", None)[0], "hallo", "swap");
        assert_eq!(words(&german, "halo", None)[0], "hallo", "missing letter");
    }

    #[test]
    fn completions_are_offered() {
        let german = german();
        assert!(words(&german, "Poli", None).contains(&"Polizei".to_owned()));
    }

    #[test]
    fn the_previous_word_ranks_what_follows_it() {
        let german = german();
        assert_eq!(predict(&german, "die", 2).unwrap(), ["Polizei", "Stadt"]);
        assert_eq!(predict(&german, "Die", 2).unwrap(), ["Polizei", "Stadt"], "sentence start");
    }

    #[test]
    fn a_lazy_lower_case_word_predicts_as_its_capitalised_form() {
        use crate::dictionary::tests::encode;
        let english = Dictionary::new(encode(&[("I", 230), ("i", 0), ("think", 200)], &[("I", "think", 9), ("i", "think", 1)])).unwrap();
        let german = Dictionary::new(encode(&[("i", 117), ("hab", 150)], &[("i", "hab", 4)])).unwrap();
        let lexicon = Lexicon::new(vec![english, german]);
        assert_eq!(predict(&lexicon, "i", 1).unwrap(), ["think"]);
    }

    #[test]
    fn typed_capitals_carry_over() {
        let german = german();
        assert_eq!(words(&german, "Uber", None)[0], "Über");
        assert_eq!(words(&german, "HAUS", None)[0], "HAUS");
    }

    #[test]
    fn strip_keeps_an_unknown_word_on_the_left() {
        let german = german();
        let strip = strip(&german, "Wir gehen nach haus", &plain).unwrap();
        assert_eq!(strip.slots[1], "Haus");
        assert_eq!(strip.replaces, 4);
        let strip = super::strip(&german, "ich sag hallp", &plain).unwrap();
        assert_eq!(strip.slots[0], "hallp");
        assert_eq!(strip.slots[1], "hallo");
        assert_eq!(strip.correction.as_deref(), Some("hallo"));
    }

    #[test]
    fn autocorrect_leaves_words_and_unfinished_words_alone() {
        let german = german();
        assert_eq!(strip(&german, "das Haus", &plain).unwrap().correction, None, "a word");
        assert_eq!(strip(&german, "die Poli", &plain).unwrap().correction, None, "only completions");
    }

    #[test]
    fn strip_predicts_after_a_space() {
        let german = german();
        let strip = strip(&german, "Heute die ", &plain).unwrap();
        assert_eq!(strip.slots, ["Stadt".to_owned(), "Polizei".to_owned(), String::new()]);
        assert_eq!(strip.replaces, 0);
        assert_eq!(strip.correction, None);
    }

    #[test]
    fn a_word_kept_twice_is_learned() {
        let mut german = german();
        assert_eq!(strip(&german, "beim Blorb", &plain).unwrap().unknown.as_deref(), Some("Blorb"));
        german.learn("Blorb", 1);
        assert!(!is_word(&german, "Blorb").unwrap(), "once is not enough");
        german.learn("Blorb", 1);
        assert!(is_word(&german, "Blorb").unwrap());
        let strip = strip(&german, "beim Blorb", &plain).unwrap();
        assert_eq!((strip.unknown, strip.correction), (None, None));
        assert_eq!(words(&german, "blirb", None)[0], "Blorb", "and suggested");
        german.forget();
        assert!(!is_word(&german, "Blorb").unwrap());
    }

    #[test]
    fn knows_its_words() {
        let german = german();
        assert!(is_word(&german, "haus").unwrap());
        assert!(is_word(&german, "Hallo").unwrap());
        assert!(!is_word(&german, "hauss").unwrap());
    }

    #[test]
    fn accents_come_from_the_dictionaries_most_used_first() {
        let words = [("über", 200), ("Straße", 180), ("schön", 170), ("Mädchen", 170), ("hätte", 160), ("café", 40), ("naïve", 10), ("à", 30)];
        let lexicon = Lexicon::new(vec![Dictionary::new(encode(&words, &[])).unwrap()]);
        let accents = lexicon.accents().unwrap();
        assert_eq!(accents[&'a'], vec!['ä', 'à'], "most used first");
        assert_eq!(accents[&'s'], vec!['ß']);
        assert_eq!(accents[&'e'], vec!['é'], "café can be spelled");
        assert_eq!(accents[&'i'], vec!['ï'], "and so can naïve, however rare");
    }

    #[test]
    fn letters_without_a_base_letter_are_typed_and_held_like_the_others() {
        let entries = [("gələcək", 200), ("þing", 150), ("aɣrum", 150)];
        let lexicon = Lexicon::new(vec![Dictionary::new(encode(&entries, &[])).unwrap()]);
        let accents = lexicon.accents().unwrap();
        assert_eq!(accents[&'e'], vec!['ə']);
        assert_eq!(accents[&'t'], vec!['þ']);
        assert_eq!(accents[&'g'], vec!['ɣ']);
        assert_eq!(words(&lexicon, "gelecek", None)[0], "gələcək");
        assert_eq!(words(&lexicon, "thing", None)[0], "þing");
        assert_eq!(words(&lexicon, "agrum", None)[0], "aɣrum");
    }
}
