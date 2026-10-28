//! Where a debug build's gesture log says taps landed against the key they
//! typed, and what shifting taps by that average would have changed:
//! `cargo run --example taps -- gestures.jsonl`. Prints, per letter row, the
//! taps' mean offset from the nearest cell centre of their key in key widths,
//! then replays every tap shifted by the overall mean: how many letters that
//! were deleted at once and typed again would have been the one typed again,
//! and how many letters kept would have changed. Taps are touches that typed
//! one letter and moved less than a third of a key.

use hexkeys::{Keyboard, Point, space_bottom, space_middle, with_shift_and_backspace};
use serde_json::Value;

struct Tap {
    keyboard: Keyboard,
    at: Point,
    typed: String,
    /// The letter typed right after deleting this one, when it was deleted at once.
    retyped: Option<String>,
}

fn letter(record: &Value) -> Option<String> {
    let actions = record["actions"].as_array()?;
    let [action] = actions.as_slice() else { return None };
    let typed = action["text"].as_str()?;
    (action["kind"] == "commit" && typed.chars().count() == 1 && typed.chars().all(char::is_alphabetic)).then(|| typed.to_lowercase())
}

fn deletes(record: &Value) -> bool {
    record["actions"].as_array().is_some_and(|actions| actions.iter().any(|a| a["kind"] == "delete" || a["kind"] == "backspace"))
}

fn main() {
    let file = std::env::args().nth(1).expect("usage: taps gestures.jsonl");
    let text = std::fs::read_to_string(file).expect("read");
    let records: Vec<Value> = text.lines().filter_map(|line| serde_json::from_str(line).ok()).filter(|r: &Value| r["points"].is_array()).collect();
    let mut taps = Vec::new();
    for (index, record) in records.iter().enumerate() {
        let Some(typed) = letter(record) else { continue };
        let points = record["points"].as_array().expect("points");
        let mut letters = if record["layout"] == "space_middle" { space_middle() } else { space_bottom() };
        if record["editKeys"].as_bool().unwrap_or(false) {
            letters = with_shift_and_backspace(letters);
        }
        let keyboard = Keyboard::new(letters, record["width"].as_f64().unwrap_or(1008.0) as f32, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let at = |p: &Value| Point { x: p[0].as_f64().unwrap_or(0.0) as f32, y: p[1].as_f64().unwrap_or(0.0) as f32 };
        let (first, last) = (at(&points[0]), at(&points[points.len() - 1]));
        if (last.x - first.x).hypot(last.y - first.y) > w / 3.0 {
            continue;
        }
        let retyped = match (records.get(index + 1), records.get(index + 2)) {
            (Some(next), Some(after)) if deletes(next) => letter(after),
            _ => None,
        };
        taps.push(Tap { keyboard, at: first, typed, retyped });
    }
    // Offsets by the key's row: sum of dx, sum of dy, count.
    let mut rows: std::collections::BTreeMap<usize, (f32, f32, u32)> = Default::default();
    let (mut sum_x, mut sum_y) = (0.0, 0.0);
    for tap in &taps {
        let layout = &tap.keyboard.layout;
        let Some(key) = layout.keys.iter().find(|key| key.label == tap.typed) else { continue };
        let Some(cell) = key.cells.iter().min_by(|a, b| (a.x - tap.at.x).hypot(a.y - tap.at.y).total_cmp(&(b.x - tap.at.x).hypot(b.y - tap.at.y))) else { continue };
        let (dx, dy) = ((tap.at.x - cell.x) / layout.cell_width, (tap.at.y - cell.y) / layout.cell_width);
        let entry = rows.entry(key.row).or_default();
        entry.0 += dx;
        entry.1 += dy;
        entry.2 += 1;
        sum_x += dx;
        sum_y += dy;
    }
    println!("row  taps  mean dx  mean dy (key widths; dy below 0 is above the centre)");
    for (row, (dx, dy, n)) in &rows {
        println!("{row:>3} {n:>5} {:>8.3} {:>8.3}", dx / *n as f32, dy / *n as f32);
    }
    let count = taps.len().max(1) as f32;
    let (mean_x, mean_y) = (sum_x / count, sum_y / count);
    let (mut mended, mut fixed, mut broken, mut kept) = (0, 0, 0, 0);
    for tap in &taps {
        let w = tap.keyboard.layout.cell_width;
        let shifted = Point { x: tap.at.x - mean_x * w, y: tap.at.y - mean_y * w };
        let now = tap.keyboard.layout.key_at(shifted).map(|key| key.label.clone()).unwrap_or_default();
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
                }
            }
        }
    }
    println!("shifted by {mean_x:.3}, {mean_y:.3}: {mended} of {fixed} letters typed again would have been right, {broken} of {kept} kept letters would have changed");
}
