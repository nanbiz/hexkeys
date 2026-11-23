//! Words that differ only in apostrophes, accents and capitals, told apart
//! by the words around them: "its" becomes "it's" in "its good", "weg"
//! becomes "Weg" in "der weg ist". The counts come from a million sentences
//! of web text per language (tools/contexts.py): how often each spelling
//! was written, and how often each word came right before and after it.
//!
//! Only a word written plainly, in lower case without apostrophes or
//! accents, is changed, since the other spellings take an extra gesture to
//! type and so are meant. It is judged once the word after it is there,
//! which is what tells most of them apart: the word before alone put back
//! 10 of 2504 held-back uses of "it's" written as "its", both words 1633.

use std::collections::HashMap;

use crate::suggest::{Lexicon, capitalise};

/// Kept equal to tools/contexts.py: the count added to every context, the
/// number of different contexts assumed for that, and how much more likely,
/// as a natural logarithm, another spelling has to be than the plain one.
/// The margin changed 0.34 % of plain English uses in held-back text and
/// put back 48 % of the other spellings written plainly.
const SMOOTHING: f64 = 0.1;
const CONTEXTS: f64 = 1000.0;
const MARGIN: f64 = 4.0;

/// The context word for a sentence's start, as the counts write it.
const START: &str = "<s>";

/// One spelling of a confusion set with its counts, the context words by
/// their number in [`Contexts::words`].
struct Spelling {
    word: String,
    count: u32,
    before: HashMap<u32, u32>,
    after: HashMap<u32, u32>,
}

impl Spelling {
    fn score(&self, before: Option<u32>, after: Option<u32>) -> f64 {
        let count = self.count as f64;
        let side = count + SMOOTHING * CONTEXTS;
        let before = before.and_then(|id| self.before.get(&id)).copied().unwrap_or(0) as f64;
        let after = after.and_then(|id| self.after.get(&id)).copied().unwrap_or(0) as f64;
        count.ln() + ((before + SMOOTHING) / side).ln() + ((after + SMOOTHING) / side).ln()
    }
}

/// The confusion sets of one language, by their plain word, and the
/// context words, each stored once and numbered.
#[derive(Default)]
pub struct Contexts {
    sets: HashMap<String, Vec<Spelling>>,
    words: HashMap<String, u32>,
}

impl Contexts {
    /// Reads the file tools/contexts.py writes.
    pub fn parse(text: &str) -> Self {
        let mut sets: HashMap<String, Vec<Spelling>> = HashMap::new();
        let mut words: HashMap<String, u32> = HashMap::new();
        for line in text.lines() {
            let mut fields = line.split('\t');
            let (Some(plain), Some(word), Some(count)) = (fields.next(), fields.next(), fields.next()) else { continue };
            let Ok(count) = count.parse() else { continue };
            let mut pairs = |field: Option<&str>| -> HashMap<u32, u32> {
                field
                    .unwrap_or("")
                    .split(' ')
                    .filter_map(|pair| {
                        let (context, n) = pair.rsplit_once(':')?;
                        let next = words.len() as u32;
                        let id = *words.entry(context.to_owned()).or_insert(next);
                        Some((id, n.parse().ok()?))
                    })
                    .collect()
            };
            let before = pairs(fields.next());
            let after = pairs(fields.next());
            sets.entry(plain.to_owned()).or_default().push(Spelling { word: word.to_owned(), count, before, after });
        }
        Self { sets, words }
    }

    /// The most written spelling of `plain`'s set that `known` accepts, other
    /// than `plain` itself.
    fn likeliest(&self, plain: &str, known: impl Fn(&str) -> bool) -> Option<&str> {
        self.sets
            .get(plain)?
            .iter()
            .filter(|s| s.word != plain && known(&s.word))
            .max_by_key(|s| s.count)
            .map(|s| s.word.as_str())
    }

    /// The spelling the plain word `plain` stands for between `before` and
    /// `after` (lower case, punctuation as itself), when another is enough
    /// more likely there; `known` says which spellings may be used.
    fn judge(&self, plain: &str, before: &str, after: &str, known: impl Fn(&str) -> bool) -> Option<&str> {
        let spellings = self.sets.get(plain)?;
        let (before, after) = (self.words.get(before).copied(), self.words.get(after).copied());
        let own = spellings.iter().find(|s| s.word == plain)?.score(before, after);
        spellings
            .iter()
            .filter(|s| s.word != plain && known(&s.word))
            .map(|s| (s.score(before, after), s))
            .filter(|(score, _)| *score - own > MARGIN)
            .max_by(|a, b| a.0.total_cmp(&b.0))
            .map(|(_, s)| s.word.as_str())
    }
}

/// A word or punctuation mark near the end of the text, by its byte range.
struct Token {
    start: usize,
    end: usize,
    word: bool,
}

fn in_word(c: char) -> bool {
    c.is_alphanumeric() || matches!(c, '\'' | '’' | '-')
}

/// The last `count` words and punctuation marks of `text`, last last.
fn last_tokens(text: &str, count: usize) -> Vec<Token> {
    let mut tokens = Vec::new();
    let mut end = text.len();
    for (at, c) in text.char_indices().rev() {
        if in_word(c) {
            continue;
        }
        let after = at + c.len_utf8();
        if after < end {
            tokens.push(Token { start: after, end, word: true });
        }
        if matches!(c, '.' | ',' | '!' | '?' | ';' | ':') {
            tokens.push(Token { start: at, end: after, word: false });
        }
        end = at;
        if tokens.len() >= count {
            break;
        }
    }
    if end > 0 && tokens.len() < count && text[..end].chars().all(in_word) {
        tokens.push(Token { start: 0, end, word: true });
    }
    tokens.truncate(count);
    tokens.reverse();
    tokens
}

/// The new end of `before` where a word in it now reads better spelled
/// otherwise, given the words that followed: how many UTF-16 units from
/// the end to replace, and with what. The last word and the one before are
/// judged where a word or punctuation mark follows them; the word before is
/// judged again because punctuation can follow a word with no space typed
/// after it. A word ending `kept` UTF-16 units before the end is left as
/// it is, having been put back by hand.
pub fn amend(lexicon: &Lexicon, before: &str, kept: Option<usize>) -> Option<(usize, String)> {
    let tokens = last_tokens(before, LOOK_BACK);
    let at_start = |token: &Token| before[..token.start].trim().is_empty();
    let is_kept = |token: &Token| kept == Some(before[token.end..].encode_utf16().count());
    // The word as typed and its plain form, unless it is spelled on purpose:
    // with a capital, apart from one starting a sentence.
    let plain = |i: usize| -> Option<(String, bool)> {
        let token = &tokens[i];
        if !token.word || is_kept(token) {
            return None;
        }
        let typed = &before[token.start..token.end];
        let lower = typed.to_lowercase();
        let starts = i.checked_sub(1).map_or(at_start(token), |j| {
            matches!(&before[tokens[j].start..tokens[j].end], "." | "!" | "?")
        });
        if typed == lower || (typed == capitalise(&lower) && starts) { Some((lower, typed != typed.to_lowercase())) } else { None }
    };
    let mut changes: Vec<(usize, String)> = Vec::new();
    let context = |token: &Token| {
        let word = before[token.start..token.end].to_lowercase();
        if matches!(word.as_str(), "." | "!" | "?") { START.to_owned() } else { word.replace('’', "'") }
    };
    for i in (0..tokens.len().saturating_sub(1)).rev().take(2) {
        let Some((lower, capital)) = plain(i) else { continue };
        let left = match i.checked_sub(1) {
            Some(j) => context(&tokens[j]),
            None if at_start(&tokens[i]) => START.to_owned(),
            None => continue,
        };
        let right = context(&tokens[i + 1]);
        if let Some(word) = lexicon.respell(&lower, &left, &right).and_then(|word| in_plain_case(&lower, &word, capital)) {
            changes.push((i, word));
        }
    }
    // Once the text is surely in one language, the words further back that
    // its dictionary does not have but has another spelling of are slips for
    // it: an "im" typed before it was clear the text is English.
    if let Some(language) = lexicon.sure_language() {
        for i in 0..tokens.len() {
            if changes.iter().any(|(j, _)| *j == i) {
                continue;
            }
            let Some((lower, capital)) = plain(i) else { continue };
            if let Some(word) = lexicon.respell_in(language, &lower).and_then(|word| in_plain_case(&lower, &word, capital)) {
                changes.push((i, word));
            }
        }
    }
    let from = changes.iter().map(|(i, _)| tokens[*i].start).min()?;
    changes.sort_by_key(|(i, _)| std::cmp::Reverse(*i));
    let mut text = before.to_owned();
    for (i, word) in &changes {
        text.replace_range(tokens[*i].start..tokens[*i].end, word);
    }
    let replaced = before[from..].encode_utf16().count();
    Some((replaced, text[from..].to_owned()))
}

/// A respelling of the plain word `lower` in the capitals it was typed with,
/// a capital starting a sentence kept: none where only capitals would change,
/// as amazon to Amazon or grad to Grad.
fn in_plain_case(lower: &str, word: &str, capital: bool) -> Option<String> {
    let word = crate::suggest::in_typed_case(lower, word)?;
    Some(if capital { capitalise(&word) } else { word })
}

/// How many words and marks back from the cursor amending looks once the
/// language is sure.
const LOOK_BACK: usize = 12;

impl Lexicon {
    /// Whether `word` is in dictionary `index` and in no other, as it is
    /// written or capitalised.
    fn only_in(&self, index: usize, word: &str) -> bool {
        let has = |d: &crate::dictionary::Dictionary| {
            [word.to_owned(), capitalise(word)].iter().any(|form| d.find(form).ok().flatten().is_some_and(|node| !node.hidden))
        };
        self.dictionaries.iter().enumerate().all(|(i, d)| has(d) == (i == index))
    }

    /// The spelling of `plain` in dictionary `index`'s language when that
    /// dictionary does not have it as it is but has another spelling of it.
    pub(crate) fn respell_in(&self, index: usize, plain: &str) -> Option<String> {
        let dictionary = self.dictionaries.get(index)?;
        let known = |word: &str| dictionary.find(word).ok().flatten().is_some_and(|node| !node.hidden);
        if known(plain) {
            return None;
        }
        let (_, contexts) = self.contexts.iter().find(|(d, _)| *d == index)?;
        contexts.likeliest(plain, known).map(str::to_owned)
    }

    /// The spelling of `plain` its context calls for, from the counts of
    /// the language the text is in first, then any other's; only spellings
    /// that language's dictionary has are used.
    fn respell(&self, plain: &str, before: &str, after: &str) -> Option<String> {
        let mut order: Vec<&(usize, Contexts)> = self.contexts.iter().collect();
        order.sort_by_key(|(index, _)| Some(*index) != self.language());
        for (index, contexts) in order {
            let Some(dictionary) = self.dictionaries.get(*index) else { continue };
            let known = |word: &str| dictionary.find(word).ok().flatten().is_some_and(|node| !node.hidden);
            // A plain word this language's dictionary does not have, next to a word
            // only this language has, is a slip for the spelling it does have, as
            // "im" before "happy" is for "I'm", whatever German makes of "im".
            if !known(plain)
                && (self.only_in(*index, before) || self.only_in(*index, after))
                && let Some(word) = contexts.likeliest(plain, known)
            {
                return Some(word.to_owned());
            }
            if let Some(word) = contexts.judge(plain, before, after, known) {
                return Some(word.to_owned());
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dictionary::Dictionary;

    const COUNTS: &str = "its\tits\t1000\tthe:2 of:50 in:40\town:300 way:100\n\
                          its\tit's\t1000\tthink:80 and:60\tgood:120 a:200 not:150\n\
                          weg\tweg\t500\tes:10\tist:3\n\
                          weg\tWeg\t500\tder:200 den:100\tist:80\n";

    fn lexicon() -> Lexicon {
        let english = Dictionary::new(crate::dictionary::tests::encode(&[("its", 150), ("it's", 160), ("good", 160)], &[])).unwrap();
        let mut lexicon = Lexicon::new(vec![english]);
        lexicon.add_contexts(0, Contexts::parse(COUNTS));
        lexicon
    }

    #[test]
    fn the_word_after_tells_its_from_its() {
        let lexicon = lexicon();
        assert_eq!(amend(&lexicon, "I think its good ", None), Some((9, "it's good ".into())));
        assert_eq!(amend(&lexicon, "I like its own ", None), None);
    }

    #[test]
    fn punctuation_after_a_word_judges_it_and_the_one_before() {
        let lexicon = lexicon();
        assert_eq!(amend(&lexicon, "and its good.", None).map(|(_, text)| text), Some("it's good.".into()));
    }

    #[test]
    fn a_word_put_back_by_hand_stays() {
        let lexicon = lexicon();
        assert_eq!(amend(&lexicon, "I think its good ", Some(6)), None);
    }

    #[test]
    fn only_spellings_the_dictionary_has() {
        // "Weg" is in the counts but in no dictionary loaded.
        let lexicon = lexicon();
        assert_eq!(amend(&lexicon, "der weg ist ", None), None);
    }

    #[test]
    fn a_word_only_another_language_has_takes_the_spelling_of_the_one_in_use() {
        let english = Dictionary::new(crate::dictionary::tests::encode(&[("I'm", 150), ("happy", 160), ("so", 200)], &[])).unwrap();
        let german = Dictionary::new(crate::dictionary::tests::encode(&[("im", 200), ("Haus", 160)], &[])).unwrap();
        let mut lexicon = Lexicon::new(vec![english, german]);
        lexicon.add_contexts(0, Contexts::parse("im\tim\t100\t\thappy:30\nim\tI'm\t1000\t\thappy:100\n"));
        assert_eq!(amend(&lexicon, "ok im happy ", None).map(|(_, text)| text), Some("I'm happy ".into()));
        assert_eq!(amend(&lexicon, "im Haus ", None), None, "German stays German");
    }

    #[test]
    fn once_the_language_is_sure_words_further_back_are_respelled() {
        let english = Dictionary::new(crate::dictionary::tests::encode(
            &[("I'm", 150), ("happy", 160), ("now", 200), ("also", 200), ("why", 200), ("ok", 150)],
            &[],
        ))
        .unwrap();
        let german = Dictionary::new(crate::dictionary::tests::encode(&[("im", 200), ("ok", 150)], &[])).unwrap();
        let mut lexicon = Lexicon::new(vec![english, german]);
        lexicon.add_contexts(0, Contexts::parse("im\tim\t100\t\t\nim\tI'm\t1000\t\t\n"));
        let text = "ok im glad now also why ";
        lexicon.set_context(text);
        assert_eq!(lexicon.sure_language(), Some(0));
        assert_eq!(amend(&lexicon, text, None).map(|(_, text)| text), Some("I'm glad now also why ".into()));
    }

    #[test]
    fn a_capital_at_the_sentence_start_stays() {
        let lexicon = lexicon();
        assert_eq!(amend(&lexicon, "Its good ", None), Some((9, "It's good ".into())));
        assert_eq!(amend(&lexicon, "Fine. Its good ", None).map(|(_, text)| text), Some("It's good ".into()));
    }
}
