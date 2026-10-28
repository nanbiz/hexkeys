//! Where a debug build's gesture log says taps landed against the key they
//! typed: for every letter tapped, the touch's offset from the nearest cell
//! centre of that key, in key widths, summed up per letter row, to show a
//! finger landing above or below the keys: `cargo run --example taps --
//! gestures.jsonl`. Taps are touches that typed one letter and moved less than
//! a third of a key.

use hexkeys::{Keyboard, Point, space_bottom, space_middle, with_shift_and_backspace};
use serde_json::Value;

fn main() {
    let file = std::env::args().nth(1).expect("usage: taps gestures.jsonl");
    let text = std::fs::read_to_string(file).expect("read");
    // Offsets by the key's row: sum of dx, sum of dy, count.
    let mut rows: std::collections::BTreeMap<usize, (f32, f32, u32)> = Default::default();
    for line in text.lines() {
        let Ok(record) = serde_json::from_str::<Value>(line) else { continue };
        let Some(points) = record["points"].as_array() else { continue };
        let actions = record["actions"].as_array().cloned().unwrap_or_default();
        let [action] = actions.as_slice() else { continue };
        let typed = action["text"].as_str().unwrap_or("");
        if action["kind"] != "commit" || typed.chars().count() != 1 || !typed.chars().all(char::is_alphabetic) {
            continue;
        }
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
        let lower = typed.to_lowercase();
        let Some(key) = keyboard.layout.keys.iter().find(|key| key.label == lower) else { continue };
        let Some(cell) = key.cells.iter().min_by(|a, b| (a.x - first.x).hypot(a.y - first.y).total_cmp(&(b.x - first.x).hypot(b.y - first.y))) else { continue };
        let entry = rows.entry(key.row).or_default();
        entry.0 += (first.x - cell.x) / w;
        entry.1 += (first.y - cell.y) / w;
        entry.2 += 1;
    }
    println!("row  taps  mean dx  mean dy (key widths; dy below 0 is above the centre)");
    for (row, (dx, dy, n)) in rows {
        println!("{row:>3} {n:>5} {:>8.3} {:>8.3}", dx / n as f32, dy / n as f32);
    }
}
