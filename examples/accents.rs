//! What holding each letter offers for the given `.dict` files:
//! `cargo run --release --example accents -- FILE[,FILE...] [TEXT]`, with
//! TEXT as the text written before the cursor, whose languages lead.

use hexkeys::dictionary::Dictionary;
use hexkeys::suggest::Lexicon;

fn main() {
    let paths = std::env::args().nth(1).expect("usage: accents FILE[,FILE...]");
    let mut lexicon = Lexicon::new(
        paths.split(',').map(|path| Dictionary::new(std::fs::read(path).expect("read")).expect("parse")).collect(),
    );
    let started = std::time::Instant::now();
    let accents = match std::env::args().nth(2) {
        Some(text) => {
            lexicon.set_context(&text);
            println!("language {:?}, ranking {:?}", lexicon.language(), lexicon.ranking());
            lexicon.accents_in_context()
        }
        None => lexicon.accents(),
    }
    .expect("read");
    let mut letters: Vec<_> = accents.into_iter().collect();
    letters.sort();
    for (letter, accented) in letters {
        println!("{letter}: {}", accented.iter().collect::<String>());
    }
    println!("in {:.0?}", started.elapsed());
}
