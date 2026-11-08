//! Replays the taps in a debug build's gesture log against ways of reading
//! them: `cargo run --example taps -- gestures.jsonl`. A tap is a touch that
//! typed one letter or digit and moved less than a third of a key. Each way
//! is scored on the taps kept (how many it would have turned into another
//! key, which is wrong but for the few typos nobody fixed) and on the taps
//! deleted at once and typed again as another key (how many it would have
//! typed right the first time). With `--leaning` after the file, it lists
//! instead every tap the number row's edge leaning by what the cursor
//! follows reads as another key.

use hexkeys::{Follows, Keyboard, Point, space_bottom, space_middle, with_shift_and_backspace};
use serde_json::Value;

struct Tap {
    keyboard: Keyboard,
    at: Point,
    typed: String,
    /// The key typed right after deleting this one, when it was deleted at once.
    retyped: Option<String>,
}

fn single(record: &Value) -> Option<String> {
    let actions = record["actions"].as_array()?;
    let [action] = actions.as_slice() else { return None };
    let typed = action["text"].as_str()?;
    (action["kind"] == "commit" && typed.chars().count() == 1 && typed.chars().all(char::is_alphanumeric)).then(|| typed.to_lowercase())
}

fn deletes(record: &Value) -> bool {
    record["actions"].as_array().is_some_and(|actions| actions.iter().any(|a| a["kind"] == "delete" || a["kind"] == "backspace"))
}

/// Where the learned offsets are kept apart: the letter row, and the left,
/// middle or right third of the keyboard.
fn zone(keyboard: &Keyboard, at: Point) -> usize {
    let layout = &keyboard.layout;
    let row = if at.y < layout.numbers_bottom { 0 } else { 1 + ((at.y - layout.numbers_bottom) / (layout.cell_height * 0.75)) as usize };
    row.min(5) * 3 + ((at.x / layout.width * 3.0) as usize).min(2)
}

/// One way of reading taps, learning as it goes.
trait Reader {
    fn read(&self, tap: &Tap) -> String;
    fn learn(&mut self, tap: &Tap);
}

fn label(keyboard: &Keyboard, at: Point) -> String {
    keyboard.layout.key_at(at).map(|key| key.label.to_lowercase()).unwrap_or_default()
}

/// The offset of a tap from the nearest cell of the key it typed, in key widths.
fn offset(tap: &Tap) -> Option<(f32, f32)> {
    let layout = &tap.keyboard.layout;
    let key = layout.keys.iter().find(|key| key.label.to_lowercase() == tap.typed)?;
    let cell = key.cells.iter().min_by(|a, b| (a.x - tap.at.x).hypot(a.y - tap.at.y).total_cmp(&(b.x - tap.at.x).hypot(b.y - tap.at.y)))?;
    let (dx, dy) = ((tap.at.x - cell.x) / layout.cell_width, (tap.at.y - cell.y) / layout.cell_width);
    (dx.hypot(dy) <= 0.6).then_some((dx, dy))
}

struct Raw;
impl Reader for Raw {
    fn read(&self, tap: &Tap) -> String {
        label(&tap.keyboard, tap.at)
    }
    fn learn(&mut self, _: &Tap) {}
}

/// Offsets learned as a moving average, one per zone or one for all.
struct Learned {
    offsets: Vec<(f32, f32)>,
    zoned: bool,
    /// Whether the number row's edge leans by what the cursor follows.
    leaning: bool,
}

impl Learned {
    fn new(zoned: bool, leaning: bool) -> Self {
        Self { offsets: vec![(0.0, 0.0); 18], zoned, leaning }
    }
    fn slot(&self, tap: &Tap) -> usize {
        if self.zoned { zone(&tap.keyboard, tap.at) } else { 0 }
    }
}

impl Reader for Learned {
    fn read(&self, tap: &Tap) -> String {
        let w = tap.keyboard.layout.cell_width;
        let (dx, dy) = self.offsets[self.slot(tap)];
        let at = Point { x: tap.at.x - dx * w, y: tap.at.y - dy * w };
        if !self.leaning {
            return label(&tap.keyboard, at);
        }
        tap.keyboard.key_down(at).map(|key| tap.keyboard.layout.keys[key].label.to_lowercase()).unwrap_or_default()
    }
    fn learn(&mut self, tap: &Tap) {
        let Some((dx, dy)) = offset(tap) else { return };
        let slot = self.slot(tap);
        let o = &mut self.offsets[slot];
        o.0 = (o.0 + (dx - o.0) * 0.02).clamp(-0.3, 0.3);
        o.1 = (o.1 + (dy - o.1) * 0.02).clamp(-0.3, 0.3);
    }
}

fn main() {
    let file = std::env::args().nth(1).expect("usage: taps gestures.jsonl");
    let text = std::fs::read_to_string(file).expect("read");
    let records: Vec<Value> = text.lines().filter_map(|line| serde_json::from_str(line).ok()).filter(|r: &Value| r["points"].is_array()).collect();
    let mut taps = Vec::new();
    for (index, record) in records.iter().enumerate() {
        let Some(typed) = single(record) else { continue };
        let points = record["points"].as_array().expect("points");
        let mut letters = if record["layout"] == "space_middle" { space_middle() } else { space_bottom() };
        if record["editKeys"].as_bool().unwrap_or(false) {
            letters = with_shift_and_backspace(letters);
        }
        let mut keyboard = Keyboard::new(letters, record["width"].as_f64().unwrap_or(1008.0) as f32, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let at = |p: &Value| Point { x: p[0].as_f64().unwrap_or(0.0) as f32, y: p[1].as_f64().unwrap_or(0.0) as f32 };
        let (first, last) = (at(&points[0]), at(&points[points.len() - 1]));
        if (last.x - first.x).hypot(last.y - first.y) > w / 3.0 {
            continue;
        }
        let retyped = match (records.get(index + 1), records.get(index + 2)) {
            (Some(next), Some(after)) if deletes(next) => single(after),
            _ => None,
        };
        keyboard.follows = match record["before"].as_str().and_then(|text| text.chars().last()) {
            Some(c) if c.is_alphabetic() => Follows::Letter,
            Some(c) if c.is_ascii_digit() => Follows::Digit,
            _ => Follows::Other,
        };
        taps.push(Tap { keyboard, at: first, typed, retyped });
    }
    if std::env::args().nth(2).as_deref() == Some("--leaning") {
        let (mut plain, mut leaning) = (Learned::new(false, false), Learned::new(false, true));
        for (index, tap) in taps.iter().enumerate() {
            let (was, now) = (plain.read(tap), leaning.read(tap));
            if was != now {
                println!("{index:>6} typed {} plain {was} leaning {now} at {:.0},{:.0} after {:?} then {:?}", tap.typed, tap.at.x, tap.at.y, tap.keyboard.follows, tap.retyped);
            }
            if tap.retyped.is_none() {
                plain.learn(tap);
                leaning.learn(tap);
            }
        }
        return;
    }
    let readers: Vec<(&str, Box<dyn Reader>)> = vec![
        ("as touched", Box::new(Raw)),
        ("one offset", Box::new(Learned::new(false, false))),
        ("offset per zone", Box::new(Learned::new(true, false))),
        ("one offset, edge leaning", Box::new(Learned::new(false, true))),
    ];
    println!("{} taps", taps.len());
    for (name, mut reader) in readers {
        let (mut mended, mut fixed, mut broken, mut kept, mut digits_broken) = (0, 0, 0, 0, 0);
        let mut changes: Vec<String> = Vec::new();
        for tap in &taps {
            let now = reader.read(tap);
            match &tap.retyped {
                Some(wanted) if *wanted != tap.typed => {
                    fixed += 1;
                    if now == *wanted {
                        mended += 1;
                    }
                }
                _ => {
                    kept += 1;
                    if now != tap.typed {
                        broken += 1;
                        if tap.typed.chars().all(|c| c.is_ascii_digit()) {
                            digits_broken += 1;
                        }
                        if changes.len() < 40 {
                            changes.push(format!("{}→{}", tap.typed, now));
                        }
                    }
                }
            }
            if tap.retyped.is_none() {
                reader.learn(tap);
            }
        }
        println!("{name:<30} right after all {mended:>3} of {fixed}, changed {broken:>3} of {kept} kept ({digits_broken} digits) {}", changes.join(" "));
    }
}
