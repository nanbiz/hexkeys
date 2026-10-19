//! JNI entry points for `io.github.tieo.hexkeys.Core`.
//!
//! The Kotlin side owns one `Keyboard` through an opaque handle: it asks for
//! outlines and labels to draw, passes touches in, and gets back what a press
//! means. Every decision about keys lives in the core; Kotlin only draws and
//! talks to the app being typed into.


use hexkeys::dictionary::Dictionary;
use hexkeys::suggest::Lexicon;
use hexkeys::{Action, Keyboard, Point, suggest, swipe, space_middle_with, space_bottom, with_number_scale, with_shift_and_backspace, without_numbers};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JFloatArray, JString};
use jni::sys::{jboolean, jfloat, jfloatArray, jint, jlong, jobjectArray, jstring};

fn keyboard<'a>(handle: jlong) -> &'a mut Keyboard {
    // SAFETY: the handle comes from `create` and lives until `destroy`; the
    // input method calls in from its main thread only.
    unsafe { &mut *(handle as *mut Keyboard) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_create(
    mut env: JNIEnv,
    _class: JClass,
    width: jfloat,
    stretch: jfloat,
    number_scale: jfloat,
    long_press_ms: jlong,
    space_in_middle: jboolean,
    left_key: JString,
    edit_keys: jboolean,
    without_number_row: jboolean,
) -> jlong {
    let left_key: String = env.get_string(&left_key).map(Into::into).unwrap_or_default();
    let mut letters = if space_in_middle != 0 { space_middle_with(&left_key) } else { space_bottom() };
    if without_number_row != 0 {
        letters = without_numbers(letters);
    } else if edit_keys != 0 {
        letters = with_shift_and_backspace(letters);
    }
    let letters = with_number_scale(letters, number_scale);
    Box::into_raw(Box::new(Keyboard::new(letters, width, stretch, long_press_ms as u64))) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    // SAFETY: see `keyboard`; the Kotlin side drops the handle after this.
    drop(unsafe { Box::from_raw(handle as *mut Keyboard) });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_height(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    keyboard(handle).layout.height
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_keyCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    keyboard(handle).layout.keys.len() as jint
}

/// The key's outline as x0, y0, x1, y1, ...
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_outline(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloatArray {
    let layout = &keyboard(handle).layout;
    let flat: Vec<f32> =
        layout.outline(&layout.keys[index as usize]).iter().flat_map(|p| [p.x, p.y]).collect();
    let array = env.new_float_array(flat.len() as i32).expect("float array");
    env.set_float_array_region(&array, 0, &flat).expect("fill float array");
    array.into_raw()
}

/// Where the key's label goes, as x, y.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_labelPosition(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloatArray {
    let center = keyboard(handle).layout.keys[index as usize].center();
    let array = env.new_float_array(2).expect("float array");
    env.set_float_array_region(&array, 0, &[center.x, center.y]).expect("fill float array");
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_label(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jstring {
    env.new_string(keyboard(handle).label(index as usize)).expect("label").into_raw()
}

/// The QWERTZ row of the key's letter, 0 to 2, which themes colour it by;
/// -1 for keys that are not letters.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_keyRow(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jint {
    keyboard(handle).key_row(index as usize).map_or(-1, |row| row as jint)
}

/// Whether the key is one of the number row's.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_inNumberRow(_env: JNIEnv, _class: JClass, handle: jlong, index: jint) -> jboolean {
    keyboard(handle).in_number_row(index as usize) as jboolean
}

/// The key the finger went down on, or -1 when no finger is down.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_pressed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    keyboard(handle).pressed().map_or(-1, |index| index as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_touchDown(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    time: jlong,
) {
    keyboard(handle).touch_down(Point { x, y }, time as u64);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_touchMove(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    time: jlong,
) -> jobjectArray {
    let result = keyboard(handle).touch_move(Point { x, y }, time as u64);
    action(&mut env, result)
}

/// What lifting the finger means, as `[kind, text]` with kind one of
/// `commit`, `backspace`, `enter`, `enter_action`, `emoji`, `language`
/// (text 1 or -1), `cursor` (text "dx dy"), `redraw`, `nothing`, `swipe`, or
/// `delete` with text "pieces words started", the last two 0 or 1.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_touchUp(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    time: jlong,
) -> jobjectArray {
    let result = keyboard(handle).touch_up(Point { x, y }, time as u64);
    action(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_touchCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    keyboard(handle).touch_cancel();
}

/// When `tick` is next due, or -1 when nothing is waiting.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_deadline(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    keyboard(handle).deadline().map_or(-1, |time| time as jlong)
}

/// What a hold that has lasted long enough does, in the same form as `touchUp`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_tick(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time: jlong,
) -> jobjectArray {
    let result = keyboard(handle).tick(time as u64);
    action(&mut env, result)
}

fn floats(env: &JNIEnv, values: &[f32]) -> jfloatArray {
    let array = env.new_float_array(values.len() as i32).expect("float array");
    env.set_float_array_region(&array, 0, values).expect("fill float array");
    array.into_raw()
}

fn action(env: &mut JNIEnv, action: Action) -> jobjectArray {
    let (kind, text) = match action {
        Action::Commit(text) => ("commit", text),
        Action::Backspace => ("backspace", String::new()),
        Action::Enter => ("enter", String::new()),
        Action::EnterAction => ("enter_action", String::new()),
        Action::Emoji => ("emoji", String::new()),
        Action::Clipboard => ("clipboard", String::new()),
        Action::Voice => ("voice", String::new()),
        Action::Hide => ("hide", String::new()),
        Action::Cursor { dx, dy } => ("cursor", format!("{dx} {dy}")),
        Action::Language { forward } => ("language", if forward { "1" } else { "-1" }.to_owned()),
        Action::Redraw => ("redraw", String::new()),
        Action::Nothing => ("nothing", String::new()),
        Action::Swipe => ("swipe", String::new()),
        Action::Delete { letters, words, endless, started } => ("delete", format!("{letters} {words} {endless} {}", started as u8)),
    };
    let array = env
        .new_object_array(2, "java/lang/String", jni::objects::JObject::null())
        .expect("string array");
    for (i, value) in [kind, text.as_str()].into_iter().enumerate() {
        let string = env.new_string(value).expect("string");
        env.set_object_array_element(&array, i as i32, string).expect("fill string array");
    }
    array.into_raw()
}

/// Room above the keys a popup may reach into.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_headroom(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    keyboard(handle).headroom()
}

/// Back to the letters with shift off, for a new field.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_reset(_env: JNIEnv, _class: JClass, handle: jlong) {
    keyboard(handle).reset();
}

/// Sets how tall the strip between the keys and the headroom is.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_setStrip(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    height: jfloat,
) {
    keyboard(handle).strip = height;
}

/// Number of choices in the open popup, 0 when none is open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    keyboard(handle).popup.as_ref().map_or(0, |popup| popup.choices.len() as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupSelected(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    keyboard(handle).popup.as_ref().and_then(|popup| popup.selected).map_or(-1, |selected| selected as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupOutline(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloatArray {
    let flat: Vec<f32> = keyboard(handle).popup_outline(index as usize).iter().flat_map(|p| [p.x, p.y]).collect();
    floats(&env, &flat)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupLabelPosition(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jfloatArray {
    let popup = keyboard(handle).popup.as_ref().expect("no popup open");
    let cell = popup.cells[index as usize];
    floats(&env, &[cell.x, cell.y])
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_popupLabel(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
) -> jstring {
    let popup = keyboard(handle).popup.as_ref().expect("no popup open");
    env.new_string(&popup.choices[index as usize]).expect("label").into_raw()
}

/// Whether space is held and the keyboard moves the cursor.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_movingCursor(_env: JNIEnv, _class: JClass, handle: jlong) -> jboolean {
    keyboard(handle).moving_cursor() as jboolean
}

/// While a delete slide is under way, where it started and where the
/// finger is, as two x positions, then the zone it is in: 0 letters, 1
/// words, 2 endless; empty otherwise.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_deleting(env: JNIEnv, _class: JClass, handle: jlong) -> jfloatArray {
    let flat: Vec<f32> =
        keyboard(handle).deleting().map_or(Vec::new(), |(from, to, zone)| vec![from, to, zone as f32]);
    floats(&env, &flat)
}

/// Shift as it stands: 0 off, 1 for the next letter, 2 locked.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_shiftState(_env: JNIEnv, _class: JClass, handle: jlong) -> jint {
    keyboard(handle).shift_state() as jint
}

/// Picks the dictionary whose language the text is in, over the guess;
/// -1 goes back to guessing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconPin(_env: JNIEnv, _class: JClass, handle: jlong, index: jint) {
    lexicon(handle).pinned = usize::try_from(index).ok();
}

/// Where the number row ends, which delete slides start above.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_numbersBottom(_env: JNIEnv, _class: JClass, handle: jlong) -> jfloat {
    keyboard(handle).layout.numbers_bottom
}

/// Width of one cell, which labels are sized from.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_cellWidth(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloat {
    keyboard(handle).layout.cell_width
}

/// The dictionaries and learned words suggestions come from, kept apart from
/// the keyboard, which is rebuilt whenever its size changes.
fn lexicon<'a>(handle: jlong) -> &'a mut Lexicon {
    // SAFETY: the handle comes from `lexiconCreate` and lives until
    // `lexiconDestroy`; every call with it comes from the suggestion thread.
    unsafe { &mut *(handle as *mut Lexicon) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconCreate(_env: JNIEnv, _class: JClass) -> jlong {
    Box::into_raw(Box::new(Lexicon::default())) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconDestroy(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: see `lexicon`; the Kotlin side drops the handle after this.
    drop(unsafe { Box::from_raw(handle as *mut Lexicon) });
}

/// Adds a `.dict` file's contents; false when they are not a dictionary
/// this reader understands.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconAdd(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bytes: JByteArray,
) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&bytes) else { return 0 };
    match Dictionary::new(bytes) {
        Ok(dictionary) => {
            lexicon(handle).add(dictionary);
            1
        }
        Err(_) => 0,
    }
}

/// Adds the context counts (assets/contexts) of the language of the
/// dictionary added `index`th.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconContexts(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    index: jint,
    text: JString,
) {
    let text: String = env.get_string(&text).map(Into::into).unwrap_or_default();
    if let Ok(index) = usize::try_from(index) {
        lexicon(handle).add_contexts(index, hexkeys::context::Contexts::parse(&text));
    }
}

/// How the end of `before` reads better with a word respelled for the words
/// that followed it: the number of UTF-16 units to replace from the end and
/// the text to put there, or an empty array. `kept` is where a word put back
/// by hand ends, in UTF-16 units from the end, -1 for none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_amend(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    before: JString,
    kept: jint,
) -> jobjectArray {
    let before: String = env.get_string(&before).map(Into::into).unwrap_or_default();
    let amended = hexkeys::context::amend(lexicon(handle), &before, usize::try_from(kept).ok());
    let values = match &amended {
        Some((replaced, text)) => vec![replaced.to_string(), text.clone()],
        None => Vec::new(),
    };
    let array = env
        .new_object_array(values.len() as i32, "java/lang/String", jni::objects::JObject::null())
        .expect("string array");
    for (i, value) in values.iter().enumerate() {
        let string = env.new_string(value).expect("string");
        env.set_object_array_element(&array, i as i32, string).expect("fill string array");
    }
    array.into_raw()
}

/// Builds the swipe index ahead of the first swipe.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconPrepare(_env: JNIEnv, _class: JClass, handle: jlong) {
    let _ = lexicon(handle).prepare();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconClear(_env: JNIEnv, _class: JClass, handle: jlong) {
    lexicon(handle).clear();
}

/// Counts `word` as kept `times` more times; returns how often it now has been.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconLearn(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    word: JString,
    times: jint,
) -> jint {
    let word: String = env.get_string(&word).map(Into::into).unwrap_or_default();
    lexicon(handle).learn(&word, times.max(0) as u32) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconForget(_env: JNIEnv, _class: JClass, handle: jlong) {
    lexicon(handle).forget();
}

/// The suggestion strip for the text before the cursor, as the left,
/// middle and right slot (empty when unused), the number of code points
/// a choice replaces, the autocorrection, the typed word when it is not a
/// word yet, and its obvious fix (each empty when none), then the index of
/// the dictionary whose language the text is taken to be in, -1 for none,
/// and all dictionaries' indices from the likeliest language to the least,
/// joined by commas.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_strip(
    mut env: JNIEnv,
    _class: JClass,
    lexicon_handle: jlong,
    letters_handle: jlong,
    before: JString,
) -> jobjectArray {
    let before: String = env.get_string(&before).map(Into::into).unwrap_or_default();
    let letters = letters(letters_handle);
    let substitution = |typed: char, meant: char| letters.layout.substitution(typed, meant);
    let lexicon = lexicon(lexicon_handle);
    lexicon.set_context(&before);
    let strip = suggest::strip(lexicon, &before, &substitution)
        .unwrap_or(suggest::Strip { slots: Default::default(), replaces: 0, correction: None, unknown: None, fix: None });
    let replaces = strip.replaces.to_string();
    let correction = strip.correction.unwrap_or_default();
    let unknown = strip.unknown.unwrap_or_default();
    let fix = strip.fix.unwrap_or_default();
    let language = lexicon.language().map_or(-1, |index| index as i64).to_string();
    let ranking = lexicon.ranking().iter().map(usize::to_string).collect::<Vec<_>>().join(",");
    let values = [
        strip.slots[0].as_str(),
        strip.slots[1].as_str(),
        strip.slots[2].as_str(),
        replaces.as_str(),
        correction.as_str(),
        unknown.as_str(),
        fix.as_str(),
        language.as_str(),
        ranking.as_str(),
    ];
    let array = env
        .new_object_array(values.len() as i32, "java/lang/String", jni::objects::JObject::null())
        .expect("string array");
    for (i, value) in values.into_iter().enumerate() {
        let string = env.new_string(value).expect("string");
        env.set_object_array_element(&array, i as i32, string).expect("fill string array");
    }
    array.into_raw()
}

/// The path being swiped, as x0, y0, x1, y1, ..., empty when none is.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_trail(env: JNIEnv, _class: JClass, handle: jlong) -> jfloatArray {
    let flat: Vec<f32> = keyboard(handle).trail().iter().flat_map(|p| [p.x, p.y]).collect();
    floats(&env, &flat)
}

/// The letter layer of a keyboard, copied for suggestions and swipe
/// decoding, which run on a thread of their own while the keyboard keeps
/// changing on the main thread.
pub struct Letters {
    layout: hexkeys::Layout,
    keys: swipe::Keys,
}

fn letters<'a>(handle: jlong) -> &'a Letters {
    // SAFETY: the handle comes from `letters` and lives until `lettersFree`,
    // which the suggestion thread calls after its last use.
    unsafe { &*(handle as *const Letters) }
}

/// A copy of the keyboard's letter layer for the suggestion thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_letters(_env: JNIEnv, _class: JClass, handle: jlong) -> jlong {
    let keyboard = keyboard(handle);
    let letters = Letters { layout: keyboard.letter_layout().clone(), keys: keyboard.swipe_keys() };
    Box::into_raw(Box::new(letters)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lettersFree(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: see `letters`.
    drop(unsafe { Box::from_raw(handle as *mut Letters) });
}

/// The last swipe's path as x0, y0, x1, y1, ..., with a last value of 1
/// when it starts with a capital and 0 otherwise; empty without one.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_swiped(env: JNIEnv, _class: JClass, handle: jlong) -> jfloatArray {
    let flat: Vec<f32> = match &keyboard(handle).swiped {
        Some(swiped) => swiped
            .path
            .iter()
            .flat_map(|p| [p.x, p.y])
            .chain(std::iter::once(if swiped.capital { 1.0 } else { 0.0 }))
            .collect(),
        None => Vec::new(),
    };
    floats(&env, &flat)
}

/// The words a swiped path, as `swiped` gives it, spells after `before`,
/// best first.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_decodeSwipe(
    mut env: JNIEnv,
    _class: JClass,
    lexicon_handle: jlong,
    letters_handle: jlong,
    path: JFloatArray,
    before: JString,
) -> jobjectArray {
    let before: String = env.get_string(&before).map(Into::into).unwrap_or_default();
    let length = env.get_array_length(&path).unwrap_or(0) as usize;
    let mut flat = vec![0f32; length];
    let _ = env.get_float_array_region(&path, 0, &mut flat);
    let words = match flat.split_last() {
        Some((&capital, points)) if points.len() >= 2 => {
            let path: Vec<Point> = points.as_chunks::<2>().0.iter().map(|p| Point { x: p[0], y: p[1] }).collect();
            let previous = suggest::last_word(&before);
            let lexicon = lexicon(lexicon_handle);
            // A swipe follows a whole word, space or not: it puts in its own.
            lexicon.set_context(&format!("{before} "));
            let mut words = swipe::decode(lexicon, &letters(letters_handle).keys, &path, previous, 3).unwrap_or_default();
            if capital == 1.0 {
                words = words.iter().map(|word| suggest::capitalise(word)).collect();
            }
            words
        }
        _ => Vec::new(),
    };
    let array = env
        .new_object_array(words.len() as i32, "java/lang/String", jni::objects::JObject::null())
        .expect("string array");
    for (i, word) in words.iter().enumerate() {
        let string = env.new_string(word).expect("string");
        env.set_object_array_element(&array, i as i32, string).expect("fill string array");
    }
    array.into_raw()
}

/// Sets up the gestures: delete slides from the left edge instead of the
/// right, and where their words and endless zones start, in percent of the
/// width from that edge.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_configure(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    delete_from_left: jboolean,
    delete_words_percent: jint,
    delete_endless_percent: jint,
) {
    let gestures = &mut keyboard(handle).gestures;
    gestures.delete_from_left = delete_from_left != 0;
    gestures.delete_words_percent = u32::try_from(delete_words_percent).unwrap_or(hexkeys::DELETE_WORDS_PERCENT);
    gestures.delete_endless_percent = u32::try_from(delete_endless_percent).unwrap_or(hexkeys::DELETE_ENDLESS_PERCENT);
}

/// Where a delete slide's words zone and its endless zone start, in pixels
/// from the edge it comes in from; the second is the width when there is no
/// endless zone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_deleteBorders(env: JNIEnv, _class: JClass, handle: jlong) -> jfloatArray {
    let (words, endless) = keyboard(handle).delete_borders();
    floats(&env, &[words, endless])
}

/// The accented letters of the lexicon's languages by plain letter, as
/// "a:ä;o:ö;s:ß", most used first, those of dictionary `first` ahead when
/// it is not -1. The first call counts through every word, which takes a
/// while on large dictionaries: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_lexiconAccents(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    first: jint,
) -> jstring {
    let accents = lexicon(handle).accents_for(usize::try_from(first).ok()).unwrap_or_default();
    let text: Vec<String> =
        accents.iter().map(|(letter, accented)| format!("{letter}:{}", accented.iter().collect::<String>())).collect();
    env.new_string(text.join(";")).expect("string").into_raw()
}

/// Sets what holding a letter offers, in the form `lexiconAccents` returns.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_setAccents(mut env: JNIEnv, _class: JClass, handle: jlong, accents: JString) {
    let accents: String = env.get_string(&accents).map(Into::into).unwrap_or_default();
    keyboard(handle).accents = accents
        .split(';')
        .filter_map(|entry| {
            let (letter, accented) = entry.split_once(':')?;
            Some((letter.chars().next()?, accented.chars().collect()))
        })
        .collect();
}

/// What the capital gesture types on a punctuation key, to show small on
/// it; empty for other keys.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_keyHint(env: JNIEnv, _class: JClass, handle: jlong, index: jint) -> jstring {
    env.new_string(keyboard(handle).hint(index as usize)).expect("string").into_raw()
}

/// The word before the cursor with `key` typed after it as Telex makes it,
/// or null when the key is just a letter after the word.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_hexkeys_Core_telex(
    mut env: JNIEnv,
    _class: JClass,
    word: JString,
    key: JString,
) -> jstring {
    let word: String = env.get_string(&word).map(Into::into).unwrap_or_default();
    let key: String = env.get_string(&key).map(Into::into).unwrap_or_default();
    let mut keys = key.chars();
    match (keys.next(), keys.next()) {
        (Some(key), None) => match hexkeys::telex::telex(&word, key) {
            Some(typed) => env.new_string(typed).expect("string").into_raw(),
            None => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}
