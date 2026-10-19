//! Hexagonal keyboard geometry.
//!
//! Keys sit on a honeycomb of pointy-top hexagon cells. Every other row is
//! shifted by half a cell, so each cell touches six neighbours. A key covers
//! one cell or a run of adjacent cells in the same row (space, shift, enter),
//! drawn as one elongated hexagon.
//!
//! Hit-testing picks the cell whose centre is nearest to the touch. On a
//! regular hexagonal lattice the region nearest to a centre is exactly that
//! hexagon, so the touch area matches what is drawn. Cells may be stretched
//! vertically to give taller keys; distances are then measured with y divided
//! by the stretch, which keeps the nearest-centre regions equal to the
//! stretched hexagons.

pub mod context;
pub mod dictionary;
mod letters;
pub mod suggest;
pub mod swipe;
pub mod telex;

/// One key as authored in a layout: what it is and how many cells it spans.
#[derive(Clone, Debug, PartialEq)]
pub struct KeySpec {
    pub label: String,
    pub span: usize,
    /// A second key sharing the cell: the cell is split down the middle,
    /// `label` on the left half and this on the right.
    pub right_half: Option<String>,
}

impl KeySpec {
    pub fn new(label: &str, span: usize) -> Self {
        assert!(span >= 1, "a key spans at least one cell");
        Self { label: label.to_owned(), span, right_half: None }
    }

    /// Two keys splitting one cell, left and right.
    pub fn halves(left: &str, right: &str) -> Self {
        Self { label: left.to_owned(), span: 1, right_half: Some(right.to_owned()) }
    }
}

/// One row: its keys from left to right, starting at a cell column.
///
/// Columns count in whole cells. Odd rows are shifted right by half a cell
/// on top of that, which is what interlocks the rows.
#[derive(Clone, Debug, PartialEq)]
pub struct RowSpec {
    pub start_column: usize,
    pub keys: Vec<KeySpec>,
    /// Keys filling the half cell a shifted row leaves free at its left and
    /// right end, instead of the row's outer keys running on into it.
    pub left_edge: Option<String>,
    pub right_edge: Option<String>,
    /// How far the row is shifted right, in cells, in place of the half
    /// every other row is: a row of whole cells centred in a wider one.
    pub offset: Option<f32>,
}

/// A layout before it is fitted to a screen.
#[derive(Clone, Debug, PartialEq)]
pub struct LayoutSpec {
    pub rows: Vec<RowSpec>,
    /// Whether the first row is the one shifted by half a cell, which makes
    /// the even rows the short ones instead of the odd.
    pub first_row_shifted: bool,
    /// Digits in a row of their own above the honeycomb, narrower and lower
    /// than the cells, as the number row of a phone keyboard.
    pub numbers: Option<String>,
    /// How tall the number row is against its usual height, 1.0 for that.
    pub number_scale: f32,
}

impl LayoutSpec {
    /// Width in cells, counting the half-cell shift of odd rows.
    pub fn width_in_cells(&self) -> f32 {
        self.rows
            .iter()
            .enumerate()
            .map(|(row, spec)| {
                let cells: usize = spec.keys.iter().map(|key| key.span).sum();
                (spec.start_column + cells) as f32 + self.shift(row)
            })
            .fold(0.0, f32::max)
    }

    /// How far a row is shifted right, in cells: every other row by half.
    fn shift(&self, row: usize) -> f32 {
        if let Some(offset) = self.rows.get(row).and_then(|spec| spec.offset) {
            return offset;
        }
        if (row % 2 == 1) != self.first_row_shifted { 0.5 } else { 0.0 }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

/// A key placed on screen.
#[derive(Clone, Debug, PartialEq)]
pub struct Key {
    pub label: String,
    pub row: usize,
    /// Centres of the cells the key covers, left to right.
    pub cells: Vec<Point>,
    /// Where the label goes: the middle of the key's visible area.
    pub label_at: Point,
    /// `cells` plus, for a key at the end of a row that stops half a cell
    /// short of the keyboard's edge, one more centre beyond it. The key's
    /// area then runs on to the edge, which keeps the keyboard's sides
    /// straight instead of leaving the honeycomb's half-cell notches.
    pub regions: Vec<Point>,
    /// Which half of its cell the key has, when it shares the cell.
    pub half: Option<Half>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Half {
    Left,
    Right,
}

impl Key {
    /// Centre of the whole key, where its label goes.
    pub fn center(&self) -> Point {
        self.label_at
    }
}

/// A layout fitted to a width in pixels.
#[derive(Clone, Debug, PartialEq)]
pub struct Layout {
    pub keys: Vec<Key>,
    /// Distance between neighbouring cell centres in a row.
    pub cell_width: f32,
    /// Height of a cell from top vertex to bottom vertex.
    pub cell_height: f32,
    pub width: f32,
    pub height: f32,
    /// Where the number row ends and the honeycomb starts; 0 without one.
    pub numbers_bottom: f32,
    /// A bottom row of whole cells straight under the row above, which keeps
    /// its hexagons' lower points: the bottom row's keys take what is left
    /// below them, notched where the points reach down.
    flat_row: Option<usize>,
}

/// The row index of number row keys, which stand outside the honeycomb.
const NUMBER_ROW: usize = usize::MAX;

/// How much of the top letter row's height, in cell heights, lies under
/// the number row; the bottom row is cut as much, so the two stay as tall
/// as each other. None: thumbs aiming at the top row landed on the digits.
const TOP_CUT: f32 = 0.0;

/// Height of the number row in cell heights.
const NUMBER_BAND: f32 = 1.25;

/// The share of the number row's height over which its two rows interlock.
const NUMBER_INTERLOCK: f32 = 0.4;

impl Layout {
    /// Fits `spec` to `width` pixels. `stretch` scales cell height relative to
    /// a regular hexagon (1.0 = regular).
    pub fn fit(spec: &LayoutSpec, width: f32, stretch: f32) -> Self {
        let cell_width = width / spec.width_in_cells();
        let cell_height = cell_width * 2.0 / 3f32.sqrt() * stretch;
        let row_pitch = cell_height * 0.75;
        let numbers_bottom = if spec.numbers.is_some() { cell_height * NUMBER_BAND * spec.number_scale } else { 0.0 };
        // Under a number row the honeycomb starts at the top row's points, so
        // those keys reach up to the digits with all of their height.
        let top = if spec.numbers.is_some() { numbers_bottom - TOP_CUT * cell_height } else { 0.0 };

        let mut keys = Vec::new();
        for (row, row_spec) in spec.rows.iter().enumerate() {
            let y = top + cell_height / 2.0 + row as f32 * row_pitch;
            let mut column = row_spec.start_column as f32 + spec.shift(row);
            for key_spec in &row_spec.keys {
                let cells: Vec<Point> = (0..key_spec.span)
                    .map(|i| Point { x: (column + i as f32 + 0.5) * cell_width, y })
                    .collect();
                let label_at = Point { x: (column + key_spec.span as f32 / 2.0) * cell_width, y };
                match &key_spec.right_half {
                    None => keys.push(Key {
                        label: key_spec.label.clone(),
                        row,
                        label_at,
                        regions: cells.clone(),
                        cells,
                        half: None,
                    }),
                    Some(right) => {
                        for (label, half, dx) in
                            [(&key_spec.label, Half::Left, -0.25), (right, Half::Right, 0.25)]
                        {
                            keys.push(Key {
                                label: label.clone(),
                                row,
                                label_at: Point { x: label_at.x + dx * cell_width, y },
                                regions: cells.clone(),
                                cells: cells.clone(),
                                half: Some(half),
                            });
                        }
                    }
                }
                column += key_spec.span as f32;
            }
        }

        for (row, row_spec) in spec.rows.iter().enumerate() {
            let y = top + cell_height / 2.0 + row as f32 * row_pitch;
            let left = keys.iter().position(|key| key.row == row);
            let right = keys.iter().rposition(|key| key.row == row);
            let (Some(left), Some(right)) = (left, right) else { continue };
            let left_x = keys[left].cells[0].x;
            let right_x = keys[right].cells[keys[right].cells.len() - 1].x;
            let left_gap = left_x - cell_width / 2.0 > 0.01;
            let right_gap = right_x + cell_width / 2.0 < width - 0.01;
            // A gap at a row's end goes to an edge key when the row has one,
            // otherwise the outer key runs on into it. Either way the
            // keyboard's sides stay straight.
            if left_gap {
                let centre = Point { x: left_x - cell_width, y };
                match &row_spec.left_edge {
                    Some(label) => keys.push(Key {
                        label: label.clone(),
                        row,
                        label_at: Point { x: (left_x - cell_width / 2.0) / 2.0, y },
                        cells: vec![centre],
                        regions: vec![centre],
                        half: None,
                    }),
                    None => {
                        // The label moves to the middle of what the key now covers.
                        // The label stays on the key's hexagon, as on every other key.
                        keys[left].regions.insert(0, centre);
                    }
                }
            }
            if right_gap {
                let centre = Point { x: right_x + cell_width, y };
                match &row_spec.right_edge {
                    Some(label) => keys.push(Key {
                        label: label.clone(),
                        row,
                        label_at: Point { x: (right_x + cell_width / 2.0 + width) / 2.0, y },
                        cells: vec![centre],
                        regions: vec![centre],
                        half: None,
                    }),
                    None => {
                        keys[right].regions.push(centre);
                    }
                }
            }
        }

        if let Some(digits) = &spec.numbers {
            // Digits alternate between an upper and a lower row, each key
            // two steps wide: the upper ones point down between the lower
            // ones, which point up between them, over the middle of the band.
            let step = width / digits.chars().count() as f32;
            for (i, digit) in digits.chars().enumerate() {
                let y = numbers_bottom * if i % 2 == 0 { 0.25 } else { 0.75 };
                let centre = Point { x: (i as f32 + 0.5) * step, y };
                keys.push(Key {
                    label: digit.to_string(),
                    row: NUMBER_ROW,
                    label_at: centre,
                    cells: vec![centre],
                    regions: vec![centre],
                    half: None,
                });
            }
        }

        // A top row cut at its shoulders shows only its lower part, and the
        // bottom row is cut as far from its lower point, so the two are as
        // tall as each other.
        let cut = if spec.numbers.is_some() { TOP_CUT * cell_height } else { 0.0 };
        let last = spec.rows.len().saturating_sub(1);
        for key in &mut keys {
            if key.row == 0 {
                key.label_at.y += cut / 2.0;
            } else if key.row == last {
                key.label_at.y -= cut / 2.0;
            }
        }

        // A bottom row of whole cells straight under the row above: the row above
        // keeps its points, which reach down to a quarter cell above the bottom
        // row's centres, and the bottom row's labels sit in the middle of what is
        // left below them.
        let flat_row = (last > 0 && spec.shift(last) == spec.shift(last - 1)).then_some(last);
        if flat_row.is_some() {
            for key in keys.iter_mut().filter(|key| key.row == last) {
                key.label_at.y += cell_height / 8.0;
            }
        }
        let height = top + cell_height - cut + last as f32 * row_pitch;
        Self { keys, cell_width, cell_height, width, height, numbers_bottom, flat_row }
    }

    /// Grows the layout to `height` by moving its keys down, so layers with
    /// fewer rows keep their bottom row where the others have it. The top
    /// row's keys run up into the space above, which keeps the tiling whole.
    fn lower_to(&mut self, height: f32) {
        let by = height - self.height;
        for key in &mut self.keys {
            for cell in key.cells.iter_mut().chain(key.regions.iter_mut()) {
                cell.y += by;
            }
            // The top row's labels sit in the middle of what those keys now cover.
            key.label_at.y += if key.row == 0 { by / 2.0 } else { by };
        }
        self.height = height;
        if self.numbers_bottom > 0.0 {
            self.numbers_bottom += by;
        }
    }

    /// How much heights count less than widths when the number row is split
    /// between its digits. With rows at a quarter and three quarters of the
    /// band, the keys of one row then reach between those of the other over
    /// `NUMBER_INTERLOCK` of the band's height.
    fn number_squash(&self) -> f32 {
        let digits = self.keys.iter().filter(|key| key.row == NUMBER_ROW).count().max(1);
        let step = self.width / digits as f32;
        (NUMBER_INTERLOCK / 2.0).sqrt() * self.numbers_bottom / step
    }

    /// The key under a touch: the one owning the nearest cell centre.
    /// Where the row above a flat bottom row ends at `x`, over the bottom
    /// row's `cell`: its hexagon's lower edges, from the shoulders at the
    /// cell's sides down to the point over its middle.
    fn points_above(&self, cell: Point, x: f32) -> f32 {
        let above = cell.y - self.cell_height * 0.75;
        let side = ((x - cell.x).abs() / (self.cell_width / 2.0)).min(1.0);
        above + self.cell_height / 4.0 + self.cell_height / 4.0 * (1.0 - side)
    }

    /// A flat bottom row key's outline, clockwise on screen: along the lower
    /// edges of the hexagons above it, then down its sides to the bottom.
    fn flat_outline(&self, key: &Key) -> Vec<Point> {
        let mut cells = key.cells.clone();
        cells.sort_by(|a, b| a.x.total_cmp(&b.x));
        let half = self.cell_width / 2.0;
        let mut outline = Vec::new();
        for cell in &cells {
            outline.push(Point { x: cell.x - half, y: self.points_above(*cell, cell.x - half) });
            outline.push(Point { x: cell.x, y: self.points_above(*cell, cell.x) });
        }
        let last = cells.last().expect("a key has at least one cell");
        let first = cells[0];
        outline.push(Point { x: last.x + half, y: self.points_above(*last, last.x + half) });
        outline.push(Point { x: last.x + half, y: self.height });
        outline.push(Point { x: first.x - half, y: self.height });
        outline
    }

    /// A free-standing cell outline around `center`, clockwise on screen, for
    /// things drawn over the keys such as a long-press popup.
    pub fn hexagon(&self, center: Point) -> Vec<Point> {
        let (w, h) = (self.cell_width / 2.0, self.cell_height / 2.0);
        let q = self.cell_height / 4.0;
        [(0.0, -h), (w, -q), (w, q), (0.0, h), (-w, q), (-w, -q)]
            .iter()
            .map(|&(dx, dy)| Point { x: center.x + dx, y: center.y + dy })
            .collect()
    }

    /// What typing `typed` where the word has `meant` costs: nothing for the
    /// same letter, less for a key touching it than for one farther away,
    /// since a finger slips onto a neighbour far more often.
    pub fn substitution(&self, typed: char, meant: char) -> f32 {
        if typed == meant {
            return 0.0;
        }
        let centre = |c: char| {
            self.keys.iter().find(|key| key.label.chars().eq(std::iter::once(c))).map(|key| key.cells[0])
        };
        match (centre(typed), centre(meant)) {
            (Some(a), Some(b)) => {
                let stretch = self.cell_height / (self.cell_width * 2.0 / 3f32.sqrt());
                let apart = (a.x - b.x).hypot((a.y - b.y) / stretch) / self.cell_width;
                if apart < 1.1 { 0.6 } else { 1.0 }
            }
            _ => 1.0,
        }
    }

    pub fn find(&self, label: &str) -> &Key {
        self.keys.iter().find(|key| key.label == label).expect("no such key")
    }

    pub fn key_at(&self, touch: Point) -> Option<&Key> {
        let stretch = self.cell_height / (self.cell_width * 2.0 / 3f32.sqrt());
        let distance = |cell: &Point| {
            let dx = touch.x - cell.x;
            let dy = (touch.y - cell.y) / stretch;
            dx * dx + dy * dy
        };
        // Number keys own the band above the honeycomb, split by x alone.
        let in_band = touch.y < self.numbers_bottom;
        let squash = self.number_squash();
        let distance = |cell: &Point| {
            if in_band {
                let (dx, dy) = (touch.x - cell.x, (touch.y - cell.y) / squash);
                dx * dx + dy * dy
            } else {
                distance(cell)
            }
        };
        // Below the points of the row above, a flat bottom row's keys go by column.
        if let Some(flat) = self.flat_row.filter(|_| !in_band) {
            let cells = self.keys.iter().filter(|key| key.row == flat).flat_map(|key| key.cells.iter().map(move |cell| (key, *cell)));
            if let Some((key, cell)) = cells.min_by(|a, b| (a.1.x - touch.x).abs().total_cmp(&(b.1.x - touch.x).abs()))
                && touch.y > self.points_above(cell, touch.x)
            {
                return Some(key);
            }
        }
        let flat = self.flat_row;
        let (key, cell) = self
            .keys
            .iter()
            .filter(|key| Some(key.row) != flat || in_band)
            .filter(|key| (key.row == NUMBER_ROW) == in_band)
            .flat_map(|key| key.regions.iter().map(move |cell| (key, *cell)))
            .min_by(|a, b| distance(&a.1).total_cmp(&distance(&b.1)))?;
        if key.half.is_none() {
            return Some(key);
        }
        // A split cell: the half the touch is on.
        let side = if touch.x < cell.x { Half::Left } else { Half::Right };
        self.keys.iter().find(|other| other.half == Some(side) && other.regions.iter().any(|c| same(*c, cell)))
    }

    /// Outline of a key: every point of the keyboard whose nearest cell
    /// centre belongs to this key, as a polygon clockwise on screen.
    ///
    /// Inside the keyboard that is the key's hexagon, or the hexagons of its
    /// run of cells joined side by side, which keeps the honeycomb's zigzag
    /// along its top and bottom. At the keyboard's edges the region runs on to
    /// the border, so the keys tile the whole rectangle with no gaps and what
    /// is drawn is exactly what `key_at` answers to.
    pub fn outline(&self, key: &Key) -> Vec<Point> {
        if key.row == NUMBER_ROW {
            // The band split between the digits by nearness, measured where
            // their honeycomb is not squashed.
            let squash = self.number_squash();
            let unsquash = |p: Point| Point { x: p.x, y: p.y / squash };
            let own = unsquash(key.cells[0]);
            let bottom = self.numbers_bottom / squash;
            let mut polygon = vec![
                Point { x: 0.0, y: 0.0 },
                Point { x: self.width, y: 0.0 },
                Point { x: self.width, y: bottom },
                Point { x: 0.0, y: bottom },
            ];
            for other in self.keys.iter().filter(|other| other.row == NUMBER_ROW) {
                let other = unsquash(other.cells[0]);
                if !same(other, own) {
                    polygon = clip_nearer(&polygon, own, other);
                }
            }
            return drop_collinear(polygon).into_iter().map(|p| Point { x: p.x, y: p.y * squash }).collect();
        }
        if Some(key.row) == self.flat_row {
            return self.flat_outline(key);
        }
        let stretch = self.cell_height / (self.cell_width * 2.0 / 3f32.sqrt());
        // Work in coordinates where the hexagons are regular, so that
        // perpendicular bisectors are the true cell borders.
        let to_regular = |p: &Point| Point { x: p.x, y: p.y / stretch };
        let mut centres: Vec<Point> =
            self.keys
                .iter()
                .filter(|other| other.row != NUMBER_ROW && Some(other.row) != self.flat_row)
                .flat_map(|other| other.regions.iter().map(to_regular))
                .collect();
        // Above a flat bottom row, the cells border it as if it were a row of
        // hexagons interlocking with theirs, which gives them their points.
        if let Some(flat) = self.flat_row {
            let pitch = self.cell_height * 0.75;
            for cell in self.keys.iter().filter(|other| other.row == flat - 1).flat_map(|other| other.regions.iter()) {
                for dx in [-0.5, 0.5] {
                    centres.push(to_regular(&Point { x: cell.x + dx * self.cell_width, y: cell.y + pitch }));
                }
            }
        }

        let mut outline: Option<Vec<Point>> = None;
        for cell in key.regions.iter().map(to_regular) {
            let mut polygon = vec![
                Point { x: 0.0, y: self.numbers_bottom / stretch },
                Point { x: self.width, y: self.numbers_bottom / stretch },
                Point { x: self.width, y: self.height / stretch },
                Point { x: 0.0, y: self.height / stretch },
            ];
            // Against every other cell, the key's own included, so that the
            // key's cells meet along shared edges instead of overlapping.
            for other in centres.iter().filter(|other| !same(**other, cell)) {
                polygon = clip_nearer(&polygon, cell, *other);
            }
            // Clipping can leave corners in the middle of a straight edge; the
            // shared edge must be one edge on both sides for the merge to find it.
            let mut polygon = drop_collinear(polygon);
            // A shared cell is cut down the middle.
            if let Some(half) = key.half {
                let (left, right) = (Point { x: cell.x - 1.0, y: cell.y }, Point { x: cell.x + 1.0, y: cell.y });
                polygon = match half {
                    Half::Left => clip_nearer(&polygon, left, right),
                    Half::Right => clip_nearer(&polygon, right, left),
                };
                polygon = drop_collinear(polygon);
            }
            outline = Some(match outline {
                None => polygon,
                Some(left) => merge_along_shared_edge(&left, &polygon),
            });
        }
        drop_collinear(outline.expect("a key has at least one cell"))
            .into_iter()
            .map(|p| Point { x: p.x, y: p.y * stretch })
            .collect()
    }
}

fn same(a: Point, b: Point) -> bool {
    (a.x - b.x).abs() < 1e-3 && (a.y - b.y).abs() < 1e-3
}

/// Joins two clockwise polygons that share one edge into their union. Cells
/// of one key sit side by side in a row, so each new cell shares exactly its
/// left edge with the key built so far.
fn merge_along_shared_edge(left: &[Point], right: &[Point]) -> Vec<Point> {
    let (n, m) = (left.len(), right.len());
    // The shared edge runs a -> b in `left` and b -> a in `right`.
    for i in 0..n {
        let (a, b) = (left[i], left[(i + 1) % n]);
        if let Some(j) = (0..m).find(|&j| same(right[j], b) && same(right[(j + 1) % m], a)) {
            let mut union = Vec::with_capacity(n + m - 2);
            // Around `left` from b back to a, then around `right` from a to b.
            union.extend((0..n).map(|k| left[(i + 1 + k) % n]));
            union.extend((1..m - 1).map(|k| right[(j + 1 + k) % m]));
            return union;
        }
    }
    panic!("cells of one key do not touch");
}

/// Removes repeated corners, then corners in the middle of a straight edge.
fn drop_collinear(mut polygon: Vec<Point>) -> Vec<Point> {
    polygon.dedup_by(|a, b| same(*a, *b));
    while polygon.len() > 1 && same(polygon[0], polygon[polygon.len() - 1]) {
        polygon.pop();
    }
    let n = polygon.len();
    (0..n)
        .filter(|&i| {
            let (o, a, b) = (polygon[(i + n - 1) % n], polygon[i], polygon[(i + 1) % n]);
            let turn = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
            turn.abs() > 1e-2
        })
        .map(|i| polygon[i])
        .collect()
}

/// Keeps the part of a convex polygon that is at least as near to `own` as to
/// `other` (Sutherland-Hodgman against their perpendicular bisector).
fn clip_nearer(polygon: &[Point], own: Point, other: Point) -> Vec<Point> {
    // p is kept when (other - own) . p <= (|other|^2 - |own|^2) / 2.
    let normal = Point { x: other.x - own.x, y: other.y - own.y };
    let limit = (other.x * other.x + other.y * other.y - own.x * own.x - own.y * own.y) / 2.0;
    let side = |p: &Point| normal.x * p.x + normal.y * p.y - limit;
    let mut clipped = Vec::with_capacity(polygon.len() + 1);
    for i in 0..polygon.len() {
        let a = polygon[i];
        let b = polygon[(i + 1) % polygon.len()];
        let (sa, sb) = (side(&a), side(&b));
        if sa <= 0.0 {
            clipped.push(a);
        }
        if (sa < 0.0) != (sb < 0.0) && sa != sb {
            let t = sa / (sa - sb);
            clipped.push(Point { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
        }
    }
    clipped
}

fn letters(s: &str) -> impl Iterator<Item = KeySpec> + '_ {
    s.chars().map(|c| KeySpec::new(&c.to_string(), 1))
}

fn row(start_column: usize, keys: impl IntoIterator<Item = KeySpec>) -> RowSpec {
    RowSpec { start_column, keys: keys.into_iter().collect(), left_edge: None, right_edge: None, offset: None }
}

fn keys(labels: &[(&str, usize)]) -> Vec<KeySpec> {
    labels.iter().map(|&(label, span)| KeySpec::new(label, span)).collect()
}

/// The keyboard: the largest keys that fit the letters in four rows, seven
/// and six to a row, with nothing but letters among them.
///
/// QWERTZ has three letter rows and keys this size need four, so letters
/// cannot all keep their row. Each sits in the cell nearest to where it is on
/// a QWERTZ phone keyboard (an optimal assignment over squared distance, with
/// vertical distance weighted double), which keeps left and right and each
/// letter's neighbourhood. Shift and backspace fill the half cells at the ends
/// of the last letter row, beside y and m as on QWERTZ. There are no umlauts;
/// ß is a long-press of s.
pub fn honeycomb() -> LayoutSpec {
    let mut last_letters = row(0, letters("yxcvnm"));
    last_letters.left_edge = Some("⇧".into());
    last_letters.right_edge = Some("⌫".into());
    LayoutSpec {
        rows: vec![
            row(0, letters("qerzuip")),
            row(0, letters("wdthjo")),
            row(0, letters("asfgbkl")),
            last_letters,
            bottom_row(SYMBOLS_KEY),
        ],
        first_row_shifted: false,
        numbers: Some("1234567890".into()),
        number_scale: 1.0,
    }
}

/// [`space_middle`] with space and the marks at the bottom. Its top two
/// letter rows are the same; the 13 letters left are exactly QWERTZ's home
/// row s d f h j k and bottom row y x c v b n m, which fill the next two
/// rows in QWERTZ order, f h and y in the cells they have in the other
/// layout. Keeping its y s d n m j k instead leaves no room for b left of
/// n. The bottom row is seven whole cells, space three of them in the middle between
/// . and ?, with the symbols key and enter at the ends.
pub fn space_bottom() -> LayoutSpec {
    LayoutSpec {
        rows: vec![
            row(0, letters("wetzio")),
            row(0, letters("qargulp")),
            row(0, letters("sdfhjk")),
            row(0, letters("yxcvbnm")),
            // Seven whole cells across, unshifted: space three wide in the middle.
            RowSpec {
                offset: Some(0.0),
                ..row(0, keys(&[(SYMBOLS_KEY, 1), (".", 1), ("space", 3), ("?", 1), ("⏎", 1)]))
            },
        ],
        first_row_shifted: true,
        numbers: Some("1234567890".into()),
        number_scale: 1.0,
    }
}

/// `spec` with shift and backspace in the half cells at the ends of its
/// third row, where QWERTZ has them beside its last letter row. Both
/// layouts have capitals and deleting as gestures; the keys are for those
/// who want them as well.
pub fn with_shift_and_backspace(mut spec: LayoutSpec) -> LayoutSpec {
    let row = &mut spec.rows[2];
    row.left_edge = Some("⇧".into());
    row.right_edge = Some("⌫".into());
    spec
}

/// The least and the most the number row may be scaled to, against its usual height.
pub const NUMBER_SCALE: std::ops::RangeInclusive<f32> = 0.5..=1.5;

/// `spec` with a number row `scale` times as tall as usual, kept to [`NUMBER_SCALE`].
pub fn with_number_scale(spec: LayoutSpec, scale: f32) -> LayoutSpec {
    LayoutSpec { number_scale: scale.clamp(*NUMBER_SCALE.start(), *NUMBER_SCALE.end()), ..spec }
}

/// `spec` for landscape: no number row, so the letters keep more of the
/// little height there is, and backspace and shift as keys, since deleting
/// slides in along the number row.
pub fn without_numbers(spec: LayoutSpec) -> LayoutSpec {
    LayoutSpec { numbers: None, ..with_shift_and_backspace(spec) }
}

/// What the key left of the middle layout's space can be, by the name
/// the settings store: the comma, backspace, the emoji, the clipboard,
/// dictation, hiding the keyboard, or a second space.
pub const LEFT_KEYS: &[&str] = &["comma", "backspace", "emoji", "clipboard", "voice", "hide", "space"];

/// The label of the left key `id` names; the comma for an id no key has.
fn left_key_label(id: &str) -> &'static str {
    match id {
        "backspace" => "⌫",
        "emoji" => "emoji",
        "clipboard" => "clipboard",
        "voice" => "voice",
        "hide" => "hide",
        "space" => "space",
        _ => ",",
    }
}

/// The letters with space and the marks between them, plus a number row
/// along the top, and the comma left of space. A gesture types capitals and
/// a slide in from the edge deletes.
pub fn space_middle() -> LayoutSpec {
    space_middle_with("comma")
}

/// [`space_middle`] with the key left of space one of [`LEFT_KEYS`].
pub fn space_middle_with(left: &str) -> LayoutSpec {
    LayoutSpec {
        rows: vec![
            row(0, letters("wetzio")),
            row(0, letters("qargulp")),
            row(0, keys(&[(".", 1), (left_key_label(left), 1), ("f", 1), ("h", 1), ("space", 1), ("?", 1)])),
            row(0, letters("ysdnmjk")),
            row(0, keys(&[("x", 1), ("c", 1), ("v", 1), ("b", 1), (SYMBOLS_KEY, 1), ("⏎", 1)])),
        ],
        first_row_shifted: true,
        numbers: Some("1234567890".into()),
        number_scale: 1.0,
    }
}

/// The bottom row every layer shares, with the key back to letters or on to
/// symbols at its left.
fn bottom_row(switch: &str) -> RowSpec {
    row(0, keys(&[(switch, 1), (",", 1), ("space", 3), (".", 1), ("⏎", 1)]))
}

/// The key to the symbol page, named after the symbols on it: the digits
/// and the question mark it used to name are on the letter page already.
const SYMBOLS_KEY: &str = "@#€";

/// The key that turns to the second symbol page, and on it back to the first.
const MORE: &str = "=\\<";

/// What each row of letters holds on the two symbol pages, by the row's
/// letters, a symbol per letter left to right: kinds of symbol together,
/// the brackets in one row, money in another, arithmetic in a third. The
/// rows both layouts have hold the same in both.
const SYMBOL_ROWS: &[(&str, [&str; 2])] = &[
    ("wetzio", ["@#€$%&", "£¥¢₹§°"]),
    // The round brackets on u and l, side by side.
    ("qargulp", ["[]{}()_", "±×÷≈≠≤≥"]),
    // Space at the bottom.
    ("sdfhjk", ["\"':;!~", "<>\\|√π"]),
    ("yxcvbnm", ["+-*/=^", "‰©®™✓•"]),
    // Space in the middle.
    ("fh", ["\"'", "^~"]),
    ("ysdnmjk", ["+-*/=<>", "\\|√π‰©®"]),
    ("xcvb", [":;!", "™✓•"]),
];

/// The symbol pages of a layout without a number row, which landscape
/// leaves out for the letters' height: the digits take the first page's top
/// two rows, the round brackets stay on u and l, and what the digits moved
/// goes to the second page, whose rarest marks are held instead (see
/// [`alternates`]).
const SYMBOL_ROWS_WITHOUT_NUMBERS: &[(&str, [&str; 2])] = &[
    ("wetzio", ["123456", "@#€$%&"]),
    ("qargulp", ["7890()_", "[]{}<>\\"]),
    // Space at the bottom.
    ("sdfhjk", ["\"':;!~", "|°§√π©"]),
    ("yxcvbnm", ["+-*/=^", "®™✓•‰÷×"]),
    // Space in the middle.
    ("fh", ["\"'", "^~"]),
    ("ysdnmjk", ["+-*/=<>", "|°§√π‰©"]),
    ("xcvb", [":;!", "®™✓•"]),
];

/// For a letter cell no row names a symbol for, as the last letter is where
/// shift takes over turning the page: the marks otherwise only held.
const SPARE_SYMBOLS: &str = "«»„“”–—`";

/// A symbol page shaped exactly like `letters`: the number row, space,
/// enter, backspace and the punctuation keys stay where they are, the key
/// to the symbols turns into the key back to the letters in the same
/// place, and the letter cells hold the symbols [`SYMBOL_ROWS`] gives
/// their row. Shift's place, or the last letter cell where there is no
/// shift, turns to the other page. `page` 0 is the first page, 1 the rarer.
pub fn symbol_layer(letters: &LayoutSpec, page: usize) -> LayoutSpec {
    let mut spec = letters.clone();
    let mut spare = SPARE_SYMBOLS.chars().skip(page * 4).map(String::from);
    let has_shift = spec.rows.iter().any(|row| row.left_edge.as_deref() == Some("⇧"));
    let turn = if page == 0 { MORE } else { SYMBOLS_KEY };
    let last_letter = spec
        .rows
        .iter()
        .enumerate()
        .flat_map(|(r, row)| row.keys.iter().enumerate().filter(|(_, key)| is_letter(&key.label)).map(move |(k, _)| (r, k)))
        .last();
    for (r, row) in spec.rows.iter_mut().enumerate() {
        let letters: String = row.keys.iter().filter(|key| is_letter(&key.label)).map(|key| key.label.as_str()).collect();
        let table = if spec.numbers.is_some() { SYMBOL_ROWS } else { SYMBOL_ROWS_WITHOUT_NUMBERS };
        let mut symbols = table
            .iter()
            .find(|(name, _)| *name == letters)
            .map_or("", |(_, pages)| pages[page])
            .chars()
            .map(String::from);
        for (k, key) in row.keys.iter_mut().enumerate() {
            if key.label == SYMBOLS_KEY {
                key.label = "ABC".into();
            } else if is_letter(&key.label) {
                key.label = if !has_shift && Some((r, k)) == last_letter {
                    turn.into()
                } else {
                    symbols.next().or_else(|| spare.next()).unwrap_or_default()
                };
            }
        }
        if row.left_edge.as_deref() == Some("⇧") {
            row.left_edge = Some(turn.into());
        }
    }
    spec
}

/// What a key press asks the app to do.
#[derive(Clone, Debug, PartialEq)]
pub enum Action {
    /// Insert this text at the cursor.
    Commit(String),
    Backspace,
    Enter,
    /// Enter held: the field's own action, such as send or search.
    EnterAction,
    /// Open the emoji, by a flick up on the key to the symbols, which are
    /// what emoji are typed among.
    Emoji,
    /// Open the clipboard, from the key left of space.
    Clipboard,
    /// Start or stop dictation, from the key left of space.
    Voice,
    /// Hide the keyboard, from the key left of space.
    Hide,
    /// A sideways flick on space: the next language, or the previous one
    /// when `forward` is false.
    Language { forward: bool },
    /// Space held and the finger moved: the cursor moves `dx` characters
    /// right (left when negative) and `dy` lines down.
    Cursor { dx: i32, dy: i32 },
    /// Nothing for the app; the keyboard itself changed (shift, layer).
    Redraw,
    /// Nothing changed.
    Nothing,
    /// A path was drawn across the letters; [`Keyboard::swiped`] has it for
    /// the decoder.
    Swipe,
    /// A delete swipe reached far enough in: everything before the cursor
    /// goes. Moving back out puts it back with a [`Action::Delete`].
    /// `started` marks the first action of a delete swipe, as it does there:
    /// a swipe that reaches this far in its first move starts with this.
    DeleteAll { started: bool },
    /// A delete swipe now has `pieces` deleted before the cursor, counted
    /// from where it started: more delete, fewer put back what was deleted
    /// last. Pieces added since the last action are whole words when
    /// `words` is set, otherwise characters. `started` marks the first
    /// action of a delete swipe.
    Delete { pieces: u32, words: bool, started: bool },
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Shift {
    Off,
    /// The next letter is upper case, then shift turns off.
    Once,
    /// Every letter is upper case until shift is tapped again: shift held.
    Locked,
}

/// The alternatives a long press offers, shown as a row of cells above the
/// key; the finger slides along it and lifting commits the one under it.
#[derive(Clone, Debug, PartialEq)]
pub struct Popup {
    pub choices: Vec<String>,
    /// Centres of the choice cells, in keyboard coordinates. The row sits one
    /// row above the key, so over the top row it is above the keyboard.
    pub cells: Vec<Point>,
    /// Width of a choice's hexagon, smaller than a key's.
    pub cell_width: f32,
    /// The choice lifting the finger takes, if any.
    pub selected: Option<usize>,
    /// Where the finger was when the popup opened; the selection follows
    /// it only once it has moved away from there.
    pub origin: Point,
    /// The finger has moved away from `origin`.
    pub moved: bool,
}

/// How big a popup's hexagons are next to a key's, as on other keyboards
/// whose long-press choices are smaller than the keys.
const POPUP_SCALE: f32 = 0.72;

/// How far a finger moves up from where it went down, in key widths, for a
/// flick up. Within one key width every other movement types the key: a
/// finger drifts as it taps, down and sideways, and only a flick up and
/// space's sideways flick mean something else. Anything longer is a path over
/// several keys, which belongs to swipe typing.
const FLICK: f32 = 0.35;

/// How far a finger may drift and still count as holding still, in key widths.
const STILL: f32 = 0.25;


/// How near the far side of the keyboard a delete swipe has to reach to
/// delete whole words, in key widths from that edge: holding anywhere else
/// is choosing how many characters to take.
const FAR_SIDE: f32 = 1.0;

/// How long a delete swipe rests on the far side before its first whole
/// word goes, in ms; until then it takes characters one after another.
const FAR_HOLD_MS: u64 = 1000;

/// How long a delete swipe rests on the far side before it takes its first
/// character there, and the pause between the ones after, in ms.
const FAR_LETTER_START_MS: u64 = 150;
const FAR_LETTER_REPEAT_MS: u64 = 100;

/// Pause between characters while backspace is held, in ms.
const BACKSPACE_REPEAT_MS: u64 = 60;

/// How far a delete swipe moves inwards for its first character, in key
/// widths. Further characters come faster (see [`DELETE_GROWTH`]), so a
/// short slide deletes a letter or two with care and a long one clears more.
const DELETE_FIRST: f32 = 0.7;

/// How much farther back a delete swipe has to move before it puts a
/// character back, in key widths, so a finger easing off does not undo
/// more than it means to.
const RESTORE_SLACK: f32 = 0.25;

/// How much faster characters go the farther a delete swipe reaches: n
/// characters take `DELETE_FIRST * n^(1/DELETE_GROWTH)`. At 1.5 slides from
/// the phone's log took 5 characters over 2.1 key widths, more than meant;
/// now 3, and whole words are for the far side.
const DELETE_GROWTH: f32 = 1.2;

/// How long a delete swipe has to have lasted for its distance to count in
/// full, in ms. A quicker stroke counts for less, so a flick takes a
/// letter where a slow slide of the same length takes several.
const DELETE_RAMP_MS: f32 = 350.0;

/// How far a delete swipe has to reach for everything before the cursor to
/// go, as a percent of the keyboard's width from the edge it came in from,
/// when the settings say nothing, and the percent at which the characters it
/// takes on the way keep the pace [`DELETE_FIRST`] and [`DELETE_GROWTH`] set.
pub const DELETE_ALL_PERCENT: u32 = 80;

/// The least a delete swipe may be set to reach: the start zone is the
/// outer third, and the reach has to lie beyond it.
pub const DELETE_ALL_LEAST: u32 = 50;

/// A reach of this much, the whole width, is no reach: nothing is deleted
/// all at once and the swipe keeps the pace it had before the setting.
pub const DELETE_ALL_OFF: u32 = 100;

/// Characters a delete swipe deletes that has moved `inwards` key widths
/// in `elapsed` ms.
fn delete_count(inwards: f32, elapsed: u64) -> u32 {
    let counted = inwards * (elapsed as f32 / DELETE_RAMP_MS).min(1.0);
    if counted <= 0.0 { 0 } else { (counted / DELETE_FIRST).powf(DELETE_GROWTH) as u32 }
}

/// Strokes quicker than this, in ms, that run straight are gestures even
/// when longer than a key: a flick of the thumb. Swipe typing is slower;
/// in FUTO's swipe dataset 7% of two-letter words and almost no longer
/// ones are swiped this fast.
const FAST_MS: u64 = 150;

/// How far a quick stroke may bend and still count as straight, in key widths.
const FAST_BEND: f32 = 0.35;

/// The longest a quick stroke can be, in key widths.
const FAST_LONGEST: f32 = 3.5;

/// Pause between whole words deleted while a delete swipe rests on the far side, in ms.
const WORD_REPEAT_MS: u64 = 300;

/// The shortest pause between whole words, which the pause shrinks to.
const WORD_REPEAT_FASTEST: u64 = 60;

/// How gestures are set up, from the settings.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Gestures {
    /// Delete swipes come in from the left edge instead of the right.
    pub delete_from_left: bool,
    /// How far in a delete swipe deletes everything before the cursor, as a
    /// percent of the width from the edge it came in from, between
    /// [`DELETE_ALL_LEAST`] and [`DELETE_ALL_OFF`]. A shorter reach also
    /// takes more characters over the same distance, a longer one fewer, so
    /// the pace keeps up with the distance left to the end.
    pub delete_all_percent: u32,
}

impl Default for Gestures {
    fn default() -> Self {
        Self { delete_from_left: false, delete_all_percent: DELETE_ALL_PERCENT }
    }
}

impl Gestures {
    /// The percent, kept to what it may be.
    fn reach(&self) -> u32 {
        self.delete_all_percent.clamp(DELETE_ALL_LEAST, DELETE_ALL_OFF)
    }

    /// How many times faster than at the standard reach a delete swipe takes
    /// characters; the same pace as ever when there is no reach.
    fn pace(&self) -> f32 {
        if self.reach() == DELETE_ALL_OFF { 1.0 } else { DELETE_ALL_PERCENT as f32 / self.reach() as f32 }
    }
}

/// What a touch amounts to so far.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Gesture {
    Tap,
    /// A short flick up.
    Up,
    /// A quick straight stroke down, longer than a key: nothing.
    Down,
    /// A sideways flick on space.
    Sideways,
    /// Movement longer than a key: a swipe-typing path.
    Path,
    /// A slide in from the delete edge: deleting as it goes.
    Delete,
    /// A quick straight stroke towards the delete edge's opposite side,
    /// starting in the third of the keyboard nearest the delete edge:
    /// deletes when the finger lifts.
    QuickDelete,
}

/// A delete swipe under way.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Deleting {
    /// Characters deleted by how far the finger moved in.
    characters: u32,
    /// Characters deleted one after another while resting on the far side.
    held: u32,
    /// Whole words deleted while holding still.
    words: u32,
    /// Reached far enough in for everything before the cursor to be gone.
    all: bool,
}

impl Deleting {
    fn pieces(&self) -> u32 {
        self.characters + self.held + self.words
    }
}

/// One finger on the keyboard. Times are in milliseconds on any clock that
/// only moves forward, as long as every call uses the same one.
#[derive(Clone, Debug)]
struct Touch {
    key: usize,
    start: Point,
    at: Point,
    /// The farthest the finger has been from `start`, and where that was.
    extent: f32,
    farthest: Point,
    /// Where and since when the finger has held still.
    anchor: Point,
    anchor_time: u64,
    /// When the next word is due while a delete swipe holds still.
    repeat_at: Option<u64>,
    /// When the finger went down and when it last moved.
    start_time: u64,
    last_time: u64,
    /// Every point the finger has been, for swipe typing.
    path: Vec<Point>,
    /// Went down where delete slides start, so sliding inwards deletes.
    at_edge: bool,
    deleting: Option<Deleting>,
    /// Held long enough to have acted already, as enter's action; lifting
    /// then adds nothing.
    acted: bool,
    /// Space held: the keyboard is a touchpad for the cursor.
    cursor: Option<Cursor>,
}

/// The cursor being moved by a finger after holding space: where the finger
/// was when it started, and how far the cursor has moved since.
#[derive(Clone, Copy, Debug)]
struct Cursor {
    origin: Point,
    dx: i32,
    dy: i32,
    /// Characters stepped while resting at a side, which speed the next up.
    edge_steps: u32,
}

/// How far the finger moves for the cursor to move one character, in key
/// widths.
const CURSOR_STEP: f32 = 0.3;

/// How far the finger moves up or down for the cursor to move one line, in
/// cell heights: far enough that a thumb drifting while it slides sideways
/// does not send the cursor to another line, which in a short text is the
/// start or the end.
const CURSOR_LINE: f32 = 1.5;

/// How near a side of the keyboard the finger moving the cursor has to rest,
/// in key widths, for the cursor to step on by itself towards that side,
/// where the finger can go no further: the outer key column, since on the
/// phone a thumb at the side rested 70 to 77 px from it.
const CURSOR_EDGE: f32 = 1.0;

/// How long the finger rests at a side before the first step, the pause
/// after it, and the shortest pause the steps speed up to, in ms.
const CURSOR_EDGE_START_MS: u64 = 150;
const CURSOR_EDGE_REPEAT_MS: f32 = 120.0;
const CURSOR_EDGE_FASTEST_MS: f32 = 25.0;

/// A finished swipe across the letters.
#[derive(Clone, Debug, PartialEq)]
pub struct Swiped {
    pub path: Vec<Point>,
    /// Shift was on, so the word starts with a capital.
    pub capital: bool,
}

fn distance(a: Point, b: Point) -> f32 {
    (a.x - b.x).hypot(a.y - b.y)
}

/// How far a path strays from the straight line between its ends at most.
fn bend(path: &[Point]) -> f32 {
    let (Some(&a), Some(&b)) = (path.first(), path.last()) else { return 0.0 };
    let length = distance(a, b);
    if length == 0.0 {
        return path.iter().map(|p| distance(*p, a)).fold(0.0, f32::max);
    }
    path.iter()
        .map(|p| ((b.x - a.x) * (a.y - p.y) - (a.x - p.x) * (b.y - a.y)).abs() / length)
        .fold(0.0, f32::max)
}

/// A fitted layout plus the state that decides what its keys mean.
pub struct Keyboard {
    /// The layer on screen now.
    pub layout: Layout,
    /// Letters, symbols and more symbols, fitted once.
    layers: [Layout; 3],
    shift: Shift,
    pub popup: Option<Popup>,
    touch: Option<Touch>,
    /// How long a finger holds still before a long press, in ms.
    long_press_ms: u64,
    /// Which of `layers` is on screen.
    layer: usize,
    /// The last swipe, until the next one.
    pub swiped: Option<Swiped>,
    pub gestures: Gestures,
    /// Accented letters by the plain letter whose key offers them when held,
    /// from the languages in use.
    pub accents: std::collections::HashMap<char, Vec<char>>,
    /// How tall what lies between the keys and the headroom is, the
    /// suggestion strip, which a popup can reach over.
    pub strip: f32,
}

impl Keyboard {
    /// A keyboard with `letters` as its letter layer.
    pub fn new(letters: LayoutSpec, width: f32, stretch: f32, long_press_ms: u64) -> Self {
        let pages = [symbol_layer(&letters, 0), symbol_layer(&letters, 1)];
        let mut layers = [letters, pages[0].clone(), pages[1].clone()].map(|spec| Layout::fit(&spec, width, stretch));
        let height = layers.iter().map(|layer| layer.height).fold(0.0, f32::max);
        for layer in &mut layers {
            layer.lower_to(height);
        }
        Self {
            layout: layers[0].clone(),
            layers,
            shift: Shift::Off,
            popup: None,
            touch: None,
            long_press_ms,
            layer: 0,
            swiped: None,
            gestures: Gestures::default(),
            accents: Default::default(),
            strip: 0.0,
        }
    }

    /// How far inwards from the delete edge a point is, relative to where
    /// the touch started.
    fn inwards(&self, touch: &Touch, at: Point) -> f32 {
        let dx = at.x - touch.start.x;
        if self.gestures.delete_from_left { dx } else { -dx }
    }

    fn gesture(&self, touch: &Touch) -> Gesture {
        if touch.deleting.is_some() {
            return Gesture::Delete;
        }
        let w = self.layout.cell_width;
        let (dx, dy) = (touch.at.x - touch.start.x, touch.at.y - touch.start.y);
        // Space starts no swiped word, so a flick sideways on it of any length
        // picks the language: one key width is short for a thumb flicking.
        if dx.hypot(dy) >= FLICK * w && dx.abs() >= dy.abs() && self.layout.keys[touch.key].label == "space" {
            return Gesture::Sideways;
        }
        if touch.extent >= w {
            return self.quick(touch).unwrap_or(Gesture::Path);
        }
        if dx.hypot(dy) < FLICK * w {
            Gesture::Tap
        } else if dy < 0.0 && dy.abs() > dx.abs() {
            Gesture::Up
        } else {
            Gesture::Tap
        }
    }

    /// A quick straight stroke longer than a flick, read as the flick or
    /// delete it is; None for anything else, which is swipe typing.
    fn quick(&self, touch: &Touch) -> Option<Gesture> {
        let w = self.layout.cell_width;
        if touch.last_time - touch.start_time > FAST_MS || touch.extent > FAST_LONGEST * w || bend(&touch.path) > FAST_BEND * w {
            return None;
        }
        let (dx, dy) = (touch.at.x - touch.start.x, touch.at.y - touch.start.y);
        if dy.abs() > 1.4 * dx.abs() {
            Some(if dy < 0.0 { Gesture::Up } else { Gesture::Down })
        } else if dx.abs() > 1.4 * dy.abs() && touch.at_edge && self.inwards(touch, touch.at) > 0.0 {
            Some(Gesture::QuickDelete)
        } else {
            None
        }
    }

    /// Whether a touch starts where delete slides do: the number row's third
    /// on the delete side. Swipe typing never starts there, so a slide
    /// cannot be mistaken for a swiped word; anywhere on the letters a
    /// word's path from the edge column looked like one.
    fn in_delete_zone(&self, at: Point) -> bool {
        let third = self.layout.width / 3.0;
        at.y < self.layout.numbers_bottom && if self.gestures.delete_from_left { at.x < third } else { at.x > 2.0 * third }
    }

    /// Which way the cursor steps on by itself while the finger moving it
    /// rests at a side of the keyboard: -1 left, 1 right, none elsewhere.
    fn cursor_edge(&self, touch: &Touch) -> Option<i32> {
        touch.cursor.as_ref()?;
        let reach = CURSOR_EDGE * self.layout.cell_width;
        if touch.at.x < reach {
            Some(-1)
        } else if touch.at.x > self.layout.width - reach {
            Some(1)
        } else {
            None
        }
    }

    /// Whether a touch starts where a sideways slide moves the cursor: the
    /// number row's middle third.
    fn in_cursor_zone(&self, at: Point) -> bool {
        let third = self.layout.width / 3.0;
        at.y < self.layout.numbers_bottom && at.x >= third && at.x <= 2.0 * third
    }

    /// Whether a delete swipe has reached the far side of the keyboard,
    /// where resting deletes whole words.
    fn on_far_side(&self, touch: &Touch) -> bool {
        let reach = FAR_SIDE * self.layout.cell_width;
        if self.gestures.delete_from_left { touch.at.x > self.layout.width - reach } else { touch.at.x < reach }
    }

    /// Shift as it stands: 0 off, 1 for the next letter, 2 locked.
    pub fn shift_state(&self) -> u8 {
        match self.shift {
            Shift::Off => 0,
            Shift::Once => 1,
            Shift::Locked => 2,
        }
    }

    /// Shift goes off after a letter unless it is locked.
    fn letter_typed(&mut self) {
        if self.shift == Shift::Once {
            self.shift = Shift::Off;
        }
    }

    /// Whether space is held and the keyboard moves the cursor.
    pub fn moving_cursor(&self) -> bool {
        self.touch.as_ref().is_some_and(|touch| touch.cursor.is_some())
    }

    /// While a delete slide is under way, where it started and where the
    /// finger is now, along the keyboard's width, and whether it has reached
    /// the far side, where it deletes whole words.
    pub fn deleting(&self) -> Option<(f32, f32, bool)> {
        self.touch
            .as_ref()
            .filter(|touch| touch.deleting.is_some())
            .map(|touch| {
                let everything = touch.deleting.is_some_and(|deleting| deleting.all);
                (touch.start.x, touch.at.x, everything || self.on_far_side(touch))
            })
    }

    /// The key the finger went down on, which every gesture belongs to;
    /// none while swiping or deleting, which do not belong to a key.
    pub fn pressed(&self) -> Option<usize> {
        self.touch
            .as_ref()
            .filter(|touch| self.trail().is_empty() && touch.deleting.is_none())
            .map(|touch| touch.key)
    }

    pub fn touch_down(&mut self, at: Point, time: u64) {
        self.popup = None;
        let at_edge = self.in_delete_zone(at);
        self.touch = self.key_at(at).map(|key| Touch {
            key,
            start: at,
            at,
            extent: 0.0,
            farthest: at,
            anchor: at,
            anchor_time: time,
            repeat_at: None,
            start_time: time,
            last_time: time,
            path: vec![at],
            at_edge,
            deleting: None,
            acted: false,
            cursor: None,
        });
    }

    /// Follows the finger. Only a delete swipe acts while the finger moves;
    /// everything else waits for it to lift.
    pub fn touch_move(&mut self, at: Point, time: u64) -> Action {
        if self.popup.is_some() {
            self.popup_move(at);
            return Action::Nothing;
        }
        let w = self.layout.cell_width;
        let Some(mut touch) = self.touch.clone() else { return Action::Nothing };
        touch.at = at;
        touch.last_time = time;
        if let Some(cursor) = &mut touch.cursor {
            if distance(at, touch.anchor) > STILL * w {
                touch.anchor = at;
                touch.anchor_time = time;
                touch.repeat_at = None;
                cursor.edge_steps = 0;
            }
            touch.at = at;
            let dx = ((at.x - cursor.origin.x) / (CURSOR_STEP * w)).trunc() as i32;
            let dy = ((at.y - cursor.origin.y) / (self.layout.cell_height * CURSOR_LINE)).trunc() as i32;
            let moved = Action::Cursor { dx: dx - cursor.dx, dy: dy - cursor.dy };
            let changed = dx != cursor.dx || dy != cursor.dy;
            (cursor.dx, cursor.dy) = (dx, dy);
            self.touch = Some(touch);
            return if changed { moved } else { Action::Nothing };
        }
        // A sideways slide from the number row's middle moves the cursor at once.
        if self.in_cursor_zone(touch.start)
            && (at.x - touch.start.x).abs() >= FLICK * w
            && (at.x - touch.start.x).abs() >= (at.y - touch.start.y).abs()
        {
            touch.acted = true;
            touch.cursor = Some(Cursor { origin: touch.start, dx: 0, dy: 0, edge_steps: 0 });
            self.touch = Some(touch);
            return self.touch_move(at, time);
        }
        touch.path.push(at);
        let from_start = distance(at, touch.start);
        if from_start > touch.extent {
            touch.extent = from_start;
            touch.farthest = at;
        }
        if distance(at, touch.anchor) > STILL * w {
            touch.anchor = at;
            touch.anchor_time = time;
            touch.repeat_at = None;
        }
        let inwards = self.inwards(&touch, at);
        let mut action = Action::Nothing;
        let mut started = false;
        if touch.deleting.is_none()
            && touch.at_edge
            && inwards >= FLICK * w
            && (at.x - touch.start.x).abs() >= (at.y - touch.start.y).abs()
        {
            touch.deleting = Some(Deleting { characters: 0, held: 0, words: 0, all: false });
            started = true;
        }
        let pace = self.gestures.pace();
        let beyond = self.deleted_all(&touch);
        if let Some(deleting) = &mut touch.deleting {
            let before = deleting.pieces();
            let elapsed = time - touch.start_time;
            let forwards = delete_count(inwards / w * pace, elapsed);
            let back = delete_count((inwards / w + RESTORE_SLACK) * pace, elapsed);
            if forwards > deleting.characters {
                deleting.characters = forwards;
            } else if back < deleting.characters {
                // Coming back puts everything back in proportion, the letters taken
                // while resting and the words too, all of it at the start.
                let scaled = deleting.pieces() * back / deleting.characters;
                deleting.characters = back;
                let mut over = deleting.pieces().saturating_sub(scaled);
                let words = over.min(deleting.words);
                deleting.words -= words;
                over -= words;
                deleting.held -= over.min(deleting.held);
            }
            // The reach has a little slack too, so a finger easing off does not
            // put everything back; while it is crossed, nothing else is asked.
            let crossed = match (deleting.all, beyond) {
                (false, Some(true)) => Some(true),
                (true, Some(false)) => Some(false),
                _ => None,
            };
            if let Some(all) = crossed {
                deleting.all = all;
            }
            if deleting.all && crossed.is_some() {
                action = Action::DeleteAll { started };
            } else if !deleting.all && (started || crossed.is_some() || deleting.pieces() != before) {
                action = Action::Delete { pieces: deleting.pieces(), words: false, started };
            }
        }
        self.touch = Some(touch);
        action
    }

    /// Whether the finger of a delete swipe is past the reach that deletes
    /// everything, as `Some(true)` once it is and `Some(false)` once it has
    /// come back by the slack; `None` while it is between or the reach is off.
    fn deleted_all(&self, touch: &Touch) -> Option<bool> {
        if self.gestures.reach() == DELETE_ALL_OFF {
            return None;
        }
        let w = self.layout.cell_width;
        let from_edge = if self.gestures.delete_from_left { touch.at.x } else { self.layout.width - touch.at.x };
        let reach = self.layout.width * self.gestures.reach() as f32 / 100.0;
        if from_edge >= reach {
            Some(true)
        } else if from_edge < reach - RESTORE_SLACK * w {
            Some(false)
        } else {
            None
        }
    }

    pub fn touch_up(&mut self, at: Point, time: u64) -> Action {
        if self.popup.is_some() {
            self.touch = None;
            return self.popup_release();
        }
        let moved = self.touch_move(at, time);
        let Some(touch) = self.touch.take() else { return moved };
        if touch.acted {
            return Action::Redraw;
        }
        let gesture = self.gesture(&touch);
        let label = self.layout.keys[touch.key].label.clone();
        match gesture {
            // A flick in from the edge, too quick to have counted a letter yet, takes one.
            Gesture::Delete
                if touch.deleting.is_some_and(|deleting| deleting.pieces() == 0)
                    && self.inwards(&touch, touch.at) >= DELETE_FIRST * self.layout.cell_width =>
            {
                Action::Delete { pieces: 1, words: false, started: false }
            }
            Gesture::Delete => moved,
            Gesture::QuickDelete => {
                let elapsed = touch.last_time - touch.start_time;
                let pieces = delete_count(self.inwards(&touch, touch.at) / self.layout.cell_width * self.gestures.pace(), elapsed).max(1);
                Action::Delete { pieces, words: false, started: true }
            }
            Gesture::Tap => self.press(touch.key),
            // A flick sideways on space steps through the languages.
            Gesture::Sideways => {
                Action::Language { forward: touch.at.x > touch.start.x }
            }
            // A flick up on enter, towards the action drawn above its arrow, does the field's action.
            Gesture::Up if label == "⏎" => Action::EnterAction,
            Gesture::Up if label == SYMBOLS_KEY => Action::Emoji,
            Gesture::Up if self.on_letters(&label) => {
                self.letter_typed();
                Action::Commit(upper(&label))
            }
            // On punctuation a flick up types the key's first alternative.
            Gesture::Up if flicked(&label).is_some() => Action::Commit(flicked(&label).unwrap_or_default().to_owned()),
            // Where a flick up means nothing, the key is typed.
            Gesture::Up => self.press(touch.key),
            Gesture::Path if self.is_swipe(&touch) => {
                self.swiped = Some(Swiped { path: touch.path, capital: self.shift != Shift::Off });
                self.letter_typed();
                Action::Swipe
            }
            _ => Action::Nothing,
        }
    }

    pub fn touch_cancel(&mut self) {
        self.touch = None;
        self.popup = None;
    }

    /// When `tick` next has something to do, if ever: the end of a hold that
    /// would open a popup or start deleting words, or the next word.
    pub fn deadline(&self) -> Option<u64> {
        if self.popup.is_some() {
            return None;
        }
        let touch = self.touch.as_ref()?;
        if touch.cursor.is_some() {
            return self.cursor_edge(touch).map(|_| touch.repeat_at.unwrap_or(touch.anchor_time + CURSOR_EDGE_START_MS));
        }
        match self.gesture(touch) {
            Gesture::Delete if touch.deleting.is_some_and(|deleting| deleting.all) => None,
            Gesture::Delete if self.on_far_side(touch) => Some(touch.repeat_at.unwrap_or(touch.anchor_time + FAR_LETTER_START_MS)),
            Gesture::Delete => None,
            Gesture::Tap if self.layout.keys[touch.key].label == "⌫" => {
                Some(touch.repeat_at.unwrap_or(touch.anchor_time + self.long_press_ms))
            }
            _ if touch.acted => None,
            Gesture::Tap if self.layout.keys[touch.key].label == "⇧" => Some(touch.anchor_time + self.long_press_ms),
            Gesture::Tap if matches!(self.layout.keys[touch.key].label.as_str(), "⏎" | "space") || self.in_cursor_zone(touch.start) => {
                Some(touch.anchor_time + self.long_press_ms)
            }
            Gesture::Tap if !self.choices(touch.key).is_empty() => Some(touch.anchor_time + self.long_press_ms),
            _ => None,
        }
    }

    /// Acts on a hold that has lasted long enough: opens the popup of a key
    /// held still, or deletes a whole word while a delete swipe holds still.
    pub fn tick(&mut self, time: u64) -> Action {
        let Some(touch) = self.touch.clone() else { return Action::Nothing };
        if self.popup.is_some() || self.deadline().is_none_or(|due| time < due) {
            return Action::Nothing;
        }
        if let Some(step) = self.cursor_edge(&touch) {
            let w = self.layout.cell_width;
            let touch = self.touch.as_mut().expect("touch");
            let cursor = touch.cursor.as_mut().expect("cursor");
            // The finger stays put while the cursor goes on: the point it counts
            // from moves the other way, so sliding back still steps back.
            cursor.origin.x -= step as f32 * CURSOR_STEP * w;
            cursor.dx += step;
            cursor.edge_steps += 1;
            let pause = (CURSOR_EDGE_REPEAT_MS * 0.85f32.powi(cursor.edge_steps as i32)).max(CURSOR_EDGE_FASTEST_MS);
            touch.repeat_at = Some(time + pause as u64);
            return Action::Cursor { dx: step, dy: 0 };
        }
        match self.gesture(&touch) {
            Gesture::Delete if time < touch.anchor_time + FAR_HOLD_MS => {
                let touch = self.touch.as_mut().expect("touch");
                let deleting = touch.deleting.as_mut().expect("deleting");
                deleting.held += 1;
                touch.repeat_at = Some((time + FAR_LETTER_REPEAT_MS).min(touch.anchor_time + FAR_HOLD_MS));
                Action::Delete { pieces: deleting.pieces(), words: false, started: false }
            }
            Gesture::Delete => {
                let touch = self.touch.as_mut().expect("touch");
                let deleting = touch.deleting.as_mut().expect("deleting");
                deleting.words += 1;
                // Faster the longer it rests there, so a whole text can go.
                let pause = (WORD_REPEAT_MS as f32 * 0.85f32.powi(deleting.words as i32)).max(WORD_REPEAT_FASTEST as f32);
                touch.repeat_at = Some(time + pause as u64);
                Action::Delete { pieces: deleting.pieces(), words: true, started: false }
            }
            Gesture::Tap if self.layout.keys[touch.key].label == "⏎" => {
                self.touch.as_mut().expect("touch").acted = true;
                Action::EnterAction
            }
            // Backspace held deletes again and again until the finger lifts.
            Gesture::Tap if self.layout.keys[touch.key].label == "⌫" => {
                let touch = self.touch.as_mut().expect("touch");
                touch.acted = true;
                touch.repeat_at = Some(time + BACKSPACE_REPEAT_MS);
                Action::Backspace
            }
            // Shift held locks capitals.
            Gesture::Tap if self.layout.keys[touch.key].label == "⇧" => {
                self.touch.as_mut().expect("touch").acted = true;
                self.shift = Shift::Locked;
                Action::Redraw
            }
            // Holding space, or the number row's middle, makes the keys a touchpad for the cursor.
            Gesture::Tap if self.layout.keys[touch.key].label == "space" || self.in_cursor_zone(touch.start) => {
                let touch = self.touch.as_mut().expect("touch");
                touch.acted = true;
                touch.cursor = Some(Cursor { origin: touch.at, dx: 0, dy: 0, edge_steps: 0 });
                Action::Redraw
            }
            Gesture::Tap if self.long_press(touch.key) => Action::Redraw,
            _ => Action::Nothing,
        }
    }

    /// A path counts as swipe typing when it is drawn on the letters and
    /// starts on one.
    fn is_swipe(&self, touch: &Touch) -> bool {
        self.on_letters(&self.layout.keys[touch.key].label)
    }

    /// A letter on the letter page: the symbol pages have alphabetic marks
    /// too, such as π, which has no capital to flick up to.
    fn on_letters(&self, label: &str) -> bool {
        self.layer == 0 && is_letter(label)
    }

    /// The path being drawn while it is swipe typing, for the trail on screen.
    pub fn trail(&self) -> &[Point] {
        match &self.touch {
            Some(touch) if self.gesture(touch) == Gesture::Path && self.is_swipe(touch) => &touch.path,
            _ => &[],
        }
    }

    /// The letter layer, whichever layer is showing.
    pub fn letter_layout(&self) -> &Layout {
        &self.layers[0]
    }

    /// The letter keys of the letter layer, for the swipe decoder.
    pub fn swipe_keys(&self) -> swipe::Keys {
        let letters = self.layers[0]
            .keys
            .iter()
            .filter(|key| is_letter(&key.label))
            .filter_map(|key| Some((key.label.chars().next()?, key.cells[0])))
            .collect();
        swipe::Keys { letters, width: self.layers[0].cell_width }
    }

    /// What typing `typed` for `meant` costs, judged on the letter layer
    /// whichever layer is showing.
    pub fn substitution(&self, typed: char, meant: char) -> f32 {
        self.layers[0].substitution(typed, meant)
    }

    /// How far above the keyboard a popup can reach: one row's pitch.
    pub fn headroom(&self) -> f32 {
        self.layout.cell_height * 0.75
    }

    /// Opens the key's popup if it has alternatives; returns whether it did.
    /// The choices sit in a row of small hexagons centred just above the
    /// key, the most used first and chosen.
    pub fn long_press(&mut self, index: usize) -> bool {
        let choices = self.choices(index);
        if choices.is_empty() {
            return false;
        }
        let layout = &self.layout;
        let center = layout.keys[index].center();
        let (w, h) = (layout.cell_width * POPUP_SCALE, layout.cell_height * POPUP_SCALE);
        // A small honeycomb above the key, the most used straight above it and
        // the rest in the free cells nearest the key, towards the middle of the
        // keyboard first: at an edge the row next to the key runs longer
        // inwards rather than pushing the whole popup off its key.
        let inwards = if center.x > layout.width / 2.0 { -1.0 } else { 1.0 };
        // A key narrower than a popup cell at the edge, as the number row's
        // ends are, has its first choice as near above it as fits.
        let above = center.x.clamp(w / 2.0, layout.width - w / 2.0);
        let fits = |offset: f32| {
            let x = above + offset * w;
            x - w / 2.0 >= -0.01 && x + w / 2.0 <= layout.width + 0.01
        };
        // Overlapping the key's top point a little, as the honeycomb's rows overlap,
        // and no higher than the room above the keyboard, which the number row's
        // popups would leave.
        let y = (center.y - layout.cell_height / 2.0 - h / 4.0).max(h / 2.0 - self.headroom());
        // As many rows as the strip and the room above it hold.
        let rows = 1 + ((y - h / 2.0 + self.headroom() + self.strip) / (h * 0.75) + 0.01).max(0.0) as usize;
        let places = popup_places(choices.len(), rows, inwards, h / w, fits);
        let cells: Vec<Point> =
            places.iter().map(|&(o, row)| Point { x: above + o * w, y: y - row as f32 * h * 0.75 }).collect();
        // The most used choice comes first and starts chosen, wherever it sits.
        let origin = self.touch.as_ref().map_or(center, |touch| touch.at);
        self.popup = Some(Popup { choices, cells, cell_width: w, selected: Some(0), origin, moved: false });
        true
    }

    /// The outline of a popup choice's hexagon.
    pub fn popup_outline(&self, index: usize) -> Vec<Point> {
        let Some(popup) = &self.popup else { return Vec::new() };
        let center = popup.cells[index];
        self.layout
            .hexagon(center)
            .into_iter()
            .map(|p| Point { x: center.x + (p.x - center.x) * POPUP_SCALE, y: center.y + (p.y - center.y) * POPUP_SCALE })
            .collect()
    }

    /// The key's long-press alternatives in the current shift state.
    fn choices(&self, index: usize) -> Vec<String> {
        let shifted = self.shift != Shift::Off;
        let label = &self.layout.keys[index].label;
        let mut choices: Vec<String> = match label.chars().next().filter(|_| is_letter(label)) {
            Some(letter) => self.accents.get(&letter).into_iter().flatten().map(|c| c.to_string()).collect(),
            // What a flick up types already is not offered again.
            None => alternates(label)
                .iter()
                .skip(if flicked(label).is_some() { 1 } else { 0 })
                .map(|c| (*c).to_owned())
                .collect(),
        };
        if shifted {
            choices = choices.iter().map(|c| upper(c)).collect();
        }
        choices
    }

    /// Follows the finger along an open popup.
    pub fn popup_move(&mut self, touch: Point) {
        let still = STILL * self.layout.cell_width;
        if let Some(popup) = &mut self.popup {
            popup.moved |= distance(touch, popup.origin) > still;
            if popup.moved {
                popup.selected = Some(nearest(&popup.cells, touch));
            }
        }
    }

    /// Lifting the finger commits the chosen alternative.
    pub fn popup_release(&mut self) -> Action {
        let Some(popup) = self.popup.take() else { return Action::Redraw };
        self.letter_typed();
        match popup.selected {
            Some(index) => Action::Commit(popup.choices[index].clone()),
            None => Action::Redraw,
        }
    }

    /// Back to the letters with shift off, for a new field. A finger still
    /// down carries on with what it is doing.
    pub fn reset(&mut self) {
        self.show(0);
    }

    fn show(&mut self, layer: usize) -> Action {
        self.layout = self.layers[layer].clone();
        self.layer = layer;
        self.shift = Shift::Off;
        self.popup = None;
        Action::Redraw
    }

    pub fn popup_cancel(&mut self) {
        self.popup = None;
    }

    /// The text shown on a key in the current state. A letter being flicked
    /// up shows the capital it will type.
    pub fn label(&self, index: usize) -> String {
        let label = &self.layout.keys[index].label;
        let flicked = self.touch.as_ref().is_some_and(|touch| touch.key == index && self.gesture(touch) == Gesture::Up);
        if self.on_letters(label) && (self.shift != Shift::Off || flicked) { upper(label) } else { label.clone() }
    }

    /// What a flick up types on a punctuation key, shown small on it; empty
    /// for other keys.
    pub fn hint(&self, index: usize) -> &'static str {
        flicked(&self.layout.keys[index].label).unwrap_or("")
    }

    /// The QWERTZ row of the key's letter, for themes; None for other keys.
    /// Whether the key is one of the number row's.
    pub fn in_number_row(&self, index: usize) -> bool {
        self.layout.keys[index].row == NUMBER_ROW
    }

    pub fn key_row(&self, index: usize) -> Option<usize> {
        qwertz_row(&self.layout.keys[index].label)
    }

    pub fn key_at(&self, touch: Point) -> Option<usize> {
        let key = self.layout.key_at(touch)?;
        self.layout.keys.iter().position(|k| std::ptr::eq(k, key))
    }

    pub fn press(&mut self, index: usize) -> Action {
        let label = self.layout.keys[index].label.as_str();
        match label {
            "⇧" => {
                self.shift = if self.shift == Shift::Off { Shift::Once } else { Shift::Off };
                Action::Redraw
            }
            "⌫" => Action::Backspace,
            "⏎" => Action::Enter,
            "space" => Action::Commit(" ".into()),
            "emoji" => Action::Emoji,
            "clipboard" => Action::Clipboard,
            "voice" => Action::Voice,
            "hide" => Action::Hide,
            "ABC" => self.show(0),
            SYMBOLS_KEY => self.show(1),
            "=\\<" => self.show(2),
            _ => {
                let text = self.label(index);
                self.letter_typed();
                Action::Commit(text)
            }
        }
    }
}

/// The row a letter has on an ordinary QWERTZ keyboard, 0 for qwertzuiop to
/// 2 for yxcvbnm. Themes colour keys by it: the layout moves letters into
/// four rows, and the colour keeps each one where the eye expects it.
pub fn qwertz_row(label: &str) -> Option<usize> {
    let mut chars = label.chars();
    let (Some(c), None) = (chars.next(), chars.next()) else { return None };
    let c = c.to_lowercase().next()?;
    ["qwertzuiop", "asdfghjkl", "yxcvbnm"].iter().position(|row| row.contains(c))
}

/// Long-press alternatives of punctuation, left to right. Letters offer
/// the accented letters of the languages in use instead, see
/// [`Keyboard::accents`].
/// What flicking up on a key that is no letter types: the first alternate
/// of the letter page's punctuation keys. Other keys' alternates, the
/// digits' fractions and the symbols' typographic kinds, are only offered
/// when held, and so are not shown on them.
fn flicked(label: &str) -> Option<&'static str> {
    if matches!(label, "." | "?" | ",") { alternates(label).first().copied() } else { None }
}

fn alternates(label: &str) -> &'static [&'static str] {
    match label {
        "." => &[",", ":", "-", ";"],
        "?" => &["!", "'", "\"", "¿", "¡"],
        "," => &[":", "-", ";", "'", "\""],
        // The digits that start fractions: the fractions they start, most used
        // first. The number row's other digits keep it for the cursor and
        // deleting.
        "1" => &["½", "⅓", "¼", "⅕", "⅙", "⅛"],
        "2" => &["⅔", "⅖"],
        "3" => &["¾", "⅗", "⅜"],
        // On the symbol pages: the typographic kinds of a mark, and the rarer
        // signs of its kind.
        "-" => &["–", "—"],
        "€" => &["£", "¥", "¢", "₹"],
        "=" => &["≈", "≠", "±"],
        "<" => &["≤", "«"],
        ">" => &["≥", "»"],
        "*" => &["×"],
        "/" => &["÷"],
        "\"" => &["„", "“", "”", "«", "»"],
        "'" => &["`", "‘", "’", "‚"],
        _ => &[],
    }
}

/// Upper case of a single character, with the capital sharp s rather than
/// the "SS" that `to_uppercase` spells it as.
fn upper(text: &str) -> String {
    if text == "ß" { "ẞ".to_owned() } else { text.to_uppercase() }
}

/// The cell nearest to a point.
fn nearest(cells: &[Point], at: Point) -> usize {
    (0..cells.len()).min_by(|&a, &b| distance(cells[a], at).total_cmp(&distance(cells[b], at))).unwrap_or(0)
}

/// Where a popup's choices go, in cell widths across from the key and rows
/// up: three to the first row (above the key, right, left), four to the
/// next, shifted half a cell to interlock (right of centre, left, then
/// outer right and left), and so on.
/// Where `count` popup choices go, as (offset in cells sideways, row up),
/// nearest the key first: rows interlock, so every other row sits half a
/// cell over. Of cells equally near, the lower row, the one nearer straight
/// above, then the one `inwards` (1 right, -1 left) comes first. Only
/// the lowest `rows` rows and offsets `fits` accepts are used; `ratio` is a cell's height to its width.
fn popup_places(count: usize, rows: usize, inwards: f32, ratio: f32, fits: impl Fn(f32) -> bool) -> Vec<(f32, usize)> {
    let mut free = Vec::new();
    for row in 0..rows.max(1) {
        let half = if row % 2 == 0 { 0.0 } else { 0.5 };
        for step in -(count as i32)..=count as i32 {
            let offset = step as f32 + half;
            if fits(offset) {
                free.push((offset, row));
            }
        }
    }
    let key = |&(offset, row): &(f32, usize)| {
        let up = row as f32 * 0.75 * ratio;
        let near = ((offset * offset + up * up).sqrt() * 100.0).round() as i32;
        (near, row, (offset.abs() * 2.0) as i32, offset * inwards < 0.0)
    };
    free.sort_by_key(key);
    free.truncate(count);
    free
}

fn is_letter(label: &str) -> bool {
    let mut chars = label.chars();
    matches!((chars.next(), chars.next()), (Some(c), None) if c.is_alphabetic())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The honeycomb without its number row, whose cells are whole hexagons up to the top edge.
    fn plain() -> LayoutSpec {
        LayoutSpec { numbers: None, ..honeycomb() }
    }

    fn layout() -> Layout {
        Layout::fit(&plain(), 1008.0, 1.0)
    }

    #[test]
    fn rows_alternate_seven_and_six_cells() {
        for (index, row) in honeycomb().rows.iter().enumerate() {
            let cells: usize = row.keys.iter().map(|key| key.span).sum();
            assert_eq!(row.start_column + cells, if index % 2 == 0 { 7 } else { 6 });
        }
    }

    #[test]
    fn fits_the_given_width() {
        let layout = layout();
        let right = layout
            .keys
            .iter()
            .map(|key| layout.outline(key).iter().map(|p| p.x).fold(0.0, f32::max))
            .fold(0.0, f32::max);
        assert!((right - 1008.0).abs() < 0.01, "right edge at {right}");
    }

    #[test]
    fn each_cell_centre_hits_its_own_key() {
        let layout = layout();
        for key in &layout.keys {
            for cell in &key.cells {
                assert_eq!(layout.key_at(*cell).unwrap().label, key.label);
            }
        }
    }

    #[test]
    fn touches_inside_a_drawn_cell_hit_that_key() {
        let layout = layout();
        let h = layout.cell_height;
        let w = layout.cell_width;
        // Points just inside the six edges of a hexagon centred on the cell.
        let inside = [
            (0.0, -0.49 * h),
            (0.0, 0.49 * h),
            (-0.49 * w, 0.0),
            (0.49 * w, 0.0),
            (-0.24 * w, -0.36 * h),
            (0.24 * w, 0.36 * h),
        ];
        for key in &layout.keys {
            for cell in &key.cells {
                for (dx, dy) in inside {
                    let touch = Point { x: cell.x + dx, y: cell.y + dy };
                    // Points past the keyboard edge have no neighbour to lose to.
                    if touch.y < 0.0 || touch.y > layout.height {
                        continue;
                    }
                    assert_eq!(
                        layout.key_at(touch).unwrap().label,
                        key.label,
                        "touch {touch:?} in cell {cell:?}"
                    );
                }
            }
        }
    }

    #[test]
    fn inner_single_cell_keys_are_hexagons() {
        let layout = layout();
        let g = layout.find("g");
        assert_eq!(layout.outline(g).len(), 6);
    }

    #[test]
    fn every_outline_is_clockwise() {
        let layout = layout();
        for key in &layout.keys {
            assert!(polygon_area(&layout.outline(key)) > 0.0, "{}", key.label);
        }
    }

    #[test]
    fn keys_tile_the_whole_keyboard() {
        let layout = layout();
        let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
        let expected = layout.width * layout.height;
        assert!((area - expected).abs() / expected < 1e-4, "{area} vs {expected}");
    }

    #[test]
    fn single_cell_outlines_are_clockwise_and_convex() {
        let layout = layout();
        for key in layout.keys.iter().filter(|key| key.regions.len() == 1) {
            let outline = layout.outline(key);
            let n = outline.len();
            for i in 0..n {
                let (o, a, b) = (outline[i], outline[(i + 1) % n], outline[(i + 2) % n]);
                let turn = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
                assert!(turn > 0.0, "{} turns the wrong way at corner {i}", key.label);
            }
        }
    }

    /// Area of a polygon clockwise on screen (y down) is positive.
    fn polygon_area(polygon: &[Point]) -> f32 {
        let n = polygon.len();
        (0..n)
            .map(|i| {
                let (a, b) = (polygon[i], polygon[(i + 1) % n]);
                a.x * b.y - b.x * a.y
            })
            .sum::<f32>()
            / 2.0
    }

    #[test]
    fn shift_capitalises_one_letter() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.3, 400);
        let index = |label: &str| keyboard_index(&keyboard, label);
        let (shift, a, b) = (index("⇧"), index("a"), index("b"));
        assert_eq!(keyboard.press(shift), Action::Redraw);
        assert_eq!(keyboard.label(a), "A");
        assert_eq!(keyboard.press(a), Action::Commit("A".into()));
        assert_eq!(keyboard.press(b), Action::Commit("b".into()));
    }

    #[test]
    fn long_press_on_s_gives_sharp_s_and_capital_after_shift() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.3, 400);
        keyboard.accents.insert('s', vec!['ß']);
        let (shift, s) = (keyboard_index(&keyboard, "⇧"), keyboard_index(&keyboard, "s"));
        assert!(keyboard.long_press(s));
        assert_eq!(keyboard.popup_release(), Action::Commit("ß".into()));
        keyboard.press(shift);
        assert!(keyboard.long_press(s));
        assert_eq!(keyboard.popup_release(), Action::Commit("ẞ".into()));
        assert_eq!(keyboard.label(s), "s", "shift is used up by the alternative");
    }

    #[test]
    fn popup_follows_the_finger_and_stays_on_screen() {
        let mut keyboard = Keyboard::new(plain(), 1008.0, 1.3, 400);
        keyboard.accents.insert('u', vec!['ü', 'ú', 'û']);
        let q = keyboard_index(&keyboard, "q");
        assert!(!keyboard.long_press(q), "q has no alternatives");
        let u = keyboard_index(&keyboard, "u");
        assert!(keyboard.long_press(u));
        let popup = keyboard.popup.clone().unwrap();
        let w = keyboard.layout.cell_width;
        assert!(popup.cells.iter().all(|c| c.x >= w / 2.0 - 0.01));
        assert!(popup.cells[0].y < 0.0, "a top-row popup sits in the headroom");
        assert!(popup.cells[0].y - keyboard.layout.cell_height / 2.0 >= -keyboard.headroom() - 0.01);
        keyboard.popup_move(popup.cells[2]);
        assert_eq!(keyboard.popup_release(), Action::Commit("û".into()));
    }

    #[test]
    fn an_edge_key_keeps_its_popup_above_it() {
        let mut keyboard = Keyboard::new(plain(), 1008.0, 1.3, 400);
        keyboard.accents.insert('w', vec!['ŵ', 'ẃ', 'ẁ', 'ẅ', 'ẇ', 'ẉ']);
        let one = keyboard_index(&keyboard, "w");
        assert!(keyboard.long_press(one));
        let popup = keyboard.popup.clone().unwrap();
        let center = keyboard.layout.keys[one].center();
        let w = keyboard.layout.cell_width * POPUP_SCALE;
        assert!((popup.cells[0].x - center.x.max(w / 2.0)).abs() < 0.01, "the first choice above");
        let top = popup.cells.iter().map(|c| c.y).fold(f32::MAX, f32::min);
        let h = keyboard.layout.cell_height * POPUP_SCALE;
        assert!(top - h / 2.0 >= -keyboard.headroom() - 0.01, "every row in the headroom");
        keyboard.popup_cancel();
        keyboard.strip = keyboard.layout.cell_height * 0.6;
        assert!(keyboard.long_press(one));
        let popup = keyboard.popup.clone().unwrap();
        let top = popup.cells.iter().map(|c| c.y).fold(f32::MAX, f32::min);
        assert!(top - h / 2.0 >= -keyboard.headroom() - keyboard.strip - 0.01, "every row in the room above");
        assert!(popup.cells.iter().all(|c| c.x - w / 2.0 >= -0.01));
    }

    #[test]
    fn symbol_layers_switch_and_type() {
        let mut keyboard = Keyboard::new(space_bottom(), 1008.0, 1.0, 400);
        let symbols = keyboard_index(&keyboard, SYMBOLS_KEY);
        assert_eq!(keyboard.press(symbols), Action::Redraw);
        let at = keyboard_index(&keyboard, "@");
        assert_eq!(keyboard.press(at), Action::Commit("@".into()));
        assert!(keyboard.layout.keys.iter().all(|key| !is_letter(&key.label)), "no letters left");
        let more = keyboard_index(&keyboard, "=\\<");
        keyboard.press(more);
        let pi = keyboard_index(&keyboard, "π");
        assert_eq!(keyboard.press(pi), Action::Commit("π".into()));
        let letters = keyboard_index(&keyboard, "ABC");
        keyboard.press(letters);
        assert_eq!(keyboard.layout.find("q").label, "q");
    }

    #[test]
    fn round_brackets_sit_on_u_and_l() {
        for letters in [space_middle(), space_bottom()] {
            let keyboard = Keyboard::new(letters, 1008.0, 1.0, 400);
            let symbols = &keyboard.layers[1];
            for (letter, bracket) in [("u", "("), ("l", ")")] {
                let cell = keyboard.layers[0].find(letter).cells[0];
                assert_eq!(symbols.key_at(cell).unwrap().label, bracket);
            }
        }
    }

    /// The symbols the pages offer, which a test keeps reachable.
    const SYMBOLS: &str = "@#€&-+()/*\"':;!_%=$<>[]{}\\|~^°";
    const MORE_SYMBOLS: &str = "§¢£¥₹©®™✓•π√÷×±≈≠≤≥‰«»„“”–—`";

    #[test]
    fn every_symbol_is_on_a_page() {
        for letters in [
            space_middle(),
            space_bottom(),
            with_shift_and_backspace(space_middle()),
            with_shift_and_backspace(space_bottom()),
            without_numbers(space_middle()),
            without_numbers(space_bottom()),
        ] {
            let labels: Vec<String> = [symbol_layer(&letters, 0), symbol_layer(&letters, 1), letters.clone()]
                .iter()
                .flat_map(|spec| spec.rows.iter().flat_map(|row| row.keys.iter().map(|key| key.label.clone())).collect::<Vec<_>>())
                .collect();
            let held: Vec<String> = labels.iter().flat_map(|label| alternates(label).iter().map(|a| a.to_string())).collect();
            let missing: String = SYMBOLS
                .chars()
                .chain(MORE_SYMBOLS.chars())
                .filter(|c| !labels.contains(&c.to_string()) && !held.contains(&c.to_string()))
                .collect();
            assert!(missing.is_empty(), "missing {missing}");
        }
    }

    #[test]
    fn without_a_number_row_the_first_symbol_page_has_the_digits() {
        for letters in [without_numbers(space_middle()), without_numbers(space_bottom())] {
            let keyboard = Keyboard::new(letters, 2244.0, 0.4, 400);
            assert_eq!(keyboard.layout.numbers_bottom, 0.0);
            assert!(keyboard.layout.keys.iter().any(|key| key.label == "⌫"));
            let labels: Vec<&str> = keyboard.layers[1].keys.iter().map(|key| key.label.as_str()).collect();
            for digit in "1234567890".chars() {
                assert!(labels.contains(&digit.to_string().as_str()), "{digit} missing");
            }
            for (letter, bracket) in [("u", "("), ("l", ")")] {
                let cell = keyboard.layers[0].find(letter).cells[0];
                assert_eq!(keyboard.layers[1].key_at(cell).unwrap().label, bracket);
            }
        }
    }

    #[test]
    fn every_layer_tiles_the_keyboard() {
        for spec in [symbol_layer(&honeycomb(), 0), symbol_layer(&space_middle(), 1), space_middle()] {
            let layout = Layout::fit(&spec, 1008.0, 1.0);
            let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
            let expected = layout.width * layout.height;
            assert!((area - expected).abs() / expected < 1e-4);
        }
    }

    #[test]
    fn the_number_row_scales_on_its_own_and_every_layer_still_tiles() {
        let usual = Layout::fit(&space_bottom(), 1008.0, 1.0);
        for scale in [0.5, 0.8, 1.0, 1.5] {
            for spec in [space_bottom(), space_middle(), with_shift_and_backspace(space_middle())] {
                let spec = with_number_scale(spec, scale);
                let layout = Layout::fit(&spec, 1008.0, 1.0);
                assert!((layout.numbers_bottom - usual.numbers_bottom * scale).abs() < 0.01, "{scale}");
                let letters = layout.height - layout.numbers_bottom;
                assert!((letters - (usual.height - usual.numbers_bottom)).abs() < 0.01, "letters keep their height at {scale}");
                for page in 0..2 {
                    let layer = Layout::fit(&symbol_layer(&spec, page), 1008.0, 1.0);
                    let area: f32 = layer.keys.iter().map(|key| polygon_area(&layer.outline(key))).sum();
                    assert!((area - layer.width * layer.height).abs() / (layer.width * layer.height) < 1e-4, "{scale} page {page}");
                }
                let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
                assert!((area - layout.width * layout.height).abs() / (layout.width * layout.height) < 1e-4, "{scale}");
            }
        }
        assert_eq!(with_number_scale(space_bottom(), 9.0).number_scale, 1.5);
        assert_eq!(with_number_scale(space_bottom(), 0.0).number_scale, 0.5);
    }

    #[test]
    fn deleting_and_the_cursor_follow_a_scaled_number_row() {
        let mut keyboard = Keyboard::new(with_number_scale(space_bottom(), 1.5), 1008.0, 1.0, 400);
        assert!(keyboard.layout.numbers_bottom > Layout::fit(&space_bottom(), 1008.0, 1.0).numbers_bottom * 1.4);
        let actions = slide_to(&mut keyboard, &[(900.0, 100), (700.0, 500)]);
        assert!(matches!(actions[1], Action::Delete { .. }), "{actions:?}");
    }

    /// A point `right` key widths right and `down` key widths below the
    /// centre of the key labelled `label`.
    fn near(keyboard: &Keyboard, label: &str, right: f32, down: f32) -> Point {
        let center = keyboard.layout.find(label).center();
        let w = keyboard.layout.cell_width;
        Point { x: center.x + right * w, y: center.y + down * w }
    }

    fn gesture(keyboard: &mut Keyboard, label: &str, path: &[(f32, f32)]) -> Action {
        keyboard.touch_down(near(keyboard, label, 0.0, 0.0), 0);
        for (i, &(right, down)) in path.iter().enumerate() {
            keyboard.touch_move(near(keyboard, label, right, down), 10 * (i as u64 + 1));
        }
        let &(right, down) = path.last().unwrap_or(&(0.0, 0.0));
        keyboard.touch_up(near(keyboard, label, right, down), 10 * (path.len() as u64 + 1))
    }

    #[test]
    fn tap_types_the_key_even_when_the_finger_drifts() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "g", &[]), Action::Commit("g".into()));
        assert_eq!(gesture(&mut keyboard, "g", &[(0.2, 0.1)]), Action::Commit("g".into()));
    }

    #[test]
    fn flick_up_types_a_capital_and_previews_it() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let g = keyboard_index(&keyboard, "g");
        keyboard.touch_down(near(&keyboard, "g", 0.0, 0.0), 0);
        keyboard.touch_move(near(&keyboard, "g", 0.1, -0.6), 30);
        assert_eq!(keyboard.label(g), "G");
        assert_eq!(keyboard.touch_up(near(&keyboard, "g", 0.1, -0.6), 40), Action::Commit("G".into()));
        assert_eq!(keyboard.label(g), "g");
        assert_eq!(gesture(&mut keyboard, ",", &[(0.0, -0.6)]), Action::Commit(":".into()), "punctuation gives its first alternative");
        assert_eq!(gesture(&mut keyboard, "space", &[(0.0, -0.6)]), Action::Commit(" ".into()), "where a flick up means nothing the key is typed");
    }

    #[test]
    fn flick_up_on_a_symbol_page_types_the_symbol_even_when_it_is_alphabetic() {
        let mut keyboard = Keyboard::new(space_bottom(), 1008.0, 1.0, 400);
        let symbols = keyboard_index(&keyboard, SYMBOLS_KEY);
        keyboard.press(symbols);
        let more = keyboard_index(&keyboard, MORE);
        keyboard.press(more);
        let pi = keyboard_index(&keyboard, "π");
        keyboard.touch_down(near(&keyboard, "π", 0.0, 0.0), 0);
        keyboard.touch_move(near(&keyboard, "π", 0.1, -0.6), 30);
        assert_eq!(keyboard.label(pi), "π", "no capital is previewed");
        assert_eq!(keyboard.touch_up(near(&keyboard, "π", 0.1, -0.6), 40), Action::Commit("π".into()));
    }

    #[test]
    fn the_key_left_of_space_in_the_middle_layout_is_the_one_chosen() {
        let spaces = |spec: &LayoutSpec| spec.rows.iter().flat_map(|row| &row.keys).filter(|key| key.label == "space").count();
        assert_eq!(spaces(&space_middle()), 1, "the comma is the default, so space is only on the right");
        assert_eq!(spaces(&space_middle_with("space")), 2);
        let expected = [
            ("comma", ",", Action::Commit(",".into())),
            ("backspace", "⌫", Action::Backspace),
            ("emoji", "emoji", Action::Emoji),
            ("clipboard", "clipboard", Action::Clipboard),
            ("voice", "voice", Action::Voice),
            ("hide", "hide", Action::Hide),
            ("space", "space", Action::Commit(" ".into())),
            ("nonsense", ",", Action::Commit(",".into())),
        ];
        for (id, label, action) in expected {
            let mut keyboard = Keyboard::new(space_middle_with(id), 1008.0, 1.0, 400);
            assert_eq!(gesture(&mut keyboard, label, &[]), action, "{id}");
            let counts: Vec<usize> = keyboard.layers.iter().map(|layer| layer.keys.len()).collect();
            assert!(counts.iter().all(|&count| count == counts[0]), "{id}: every page has the key");
        }
        for id in LEFT_KEYS {
            if *id != "comma" {
                assert_ne!(left_key_label(id), ",", "{id} has a key of its own");
            }
        }
    }

    /// A delete slide from the right edge to `x` over `ms`, as the actions it gave.
    fn slide_to(keyboard: &mut Keyboard, xs: &[(f32, u64)]) -> Vec<Action> {
        let y = keyboard.layout.numbers_bottom / 2.0;
        let width = keyboard.layout.width;
        let from_left = keyboard.gestures.delete_from_left;
        let at = |x: f32| Point { x: if from_left { width - x } else { x }, y };
        keyboard.touch_down(at(width - 1.0), 0);
        xs.iter().map(|&(x, time)| keyboard.touch_move(at(x), time)).collect()
    }

    #[test]
    fn a_slide_reaching_the_set_distance_deletes_everything_and_sliding_back_puts_back() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        assert_eq!(keyboard.gestures.delete_all_percent, 80);
        // The reach is 806 from the right edge: x = 202.
        let actions = slide_to(&mut keyboard, &[(900.0, 100), (600.0, 400), (300.0, 500), (250.0, 600), (150.0, 700), (100.0, 800)]);
        assert!(matches!(actions[0], Action::Delete { started: true, .. }), "{:?}", actions[0]);
        assert!(actions[1..4].iter().all(|action| matches!(action, Action::Delete { .. })), "{actions:?}");
        assert_eq!(actions[4], Action::DeleteAll { started: false });
        assert_eq!(actions[5], Action::Nothing, "going on asks for nothing more");
        assert_eq!(keyboard.deleting().map(|(_, _, far)| far), Some(true), "the whole width is washed");
        assert_eq!(keyboard.deadline(), None, "nothing repeats once everything is gone");
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.numbers_bottom / 2.0;
        let at = |x: f32| Point { x, y };
        assert_eq!(keyboard.touch_move(at(202.0 + 0.1 * w), 900), Action::Nothing, "a little slack");
        assert!(matches!(keyboard.touch_move(at(300.0), 1000), Action::Delete { started: false, .. }), "back out puts it back");
        assert_eq!(keyboard.deleting().map(|(_, _, far)| far), Some(false));
        assert_eq!(keyboard.touch_move(at(150.0), 1100), Action::DeleteAll { started: false }, "and in again");
    }

    #[test]
    fn a_slide_reaching_the_distance_in_its_first_move_starts_with_deleting_everything() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let actions = slide_to(&mut keyboard, &[(100.0, 100), (50.0, 200)]);
        assert_eq!(actions[0], Action::DeleteAll { started: true });
        assert_eq!(actions[1], Action::Nothing);
    }

    #[test]
    fn the_distance_for_deleting_everything_is_set_in_percent_of_the_width() {
        let reaches = |percent: u32, from_left: bool| {
            let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
            keyboard.gestures.delete_from_left = from_left;
            keyboard.gestures.delete_all_percent = percent;
            let steps: Vec<(f32, u64)> = (1..=99).map(|i| (1008.0 - 10.0 * i as f32, 100 + 50 * i as u64)).collect();
            let actions = slide_to(&mut keyboard, &steps);
            actions.iter().position(|action| matches!(action, Action::DeleteAll { .. })).map(|at| 1008.0 - steps[at].0)
        };
        let near = |found: Option<f32>, expected: f32| {
            let found = found.expect("everything goes");
            assert!((found - expected).abs() <= 10.0, "{found} against {expected}");
        };
        near(reaches(80, false), 806.0);
        near(reaches(50, false), 504.0);
        near(reaches(90, false), 907.0);
        near(reaches(80, true), 806.0);
        near(reaches(10, false), 504.0, /* held to the least */);
        assert_eq!(reaches(100, false), None, "at the whole width there is no reach");
        assert_eq!(reaches(100, true), None);
    }

    #[test]
    fn a_shorter_reach_takes_more_characters_over_the_same_distance() {
        let pieces = |percent: u32| {
            let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
            keyboard.gestures.delete_all_percent = percent;
            match slide_to(&mut keyboard, &[(900.0, 100), (560.0, 500)]).last() {
                Some(Action::Delete { pieces, .. }) => *pieces,
                other => panic!("{other:?}"),
            }
        };
        assert!(pieces(50) > pieces(80), "{} against {}", pieces(50), pieces(80));
        assert!(pieces(80) > pieces(95), "{} against {}", pieces(80), pieces(95));
        assert!(pieces(80) >= pieces(100), "no reach is the pace of before, not faster");
        // The pace at 80 percent is the one the slide always had: 1.4 key widths take 2.
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let last = slide_to(&mut keyboard, &[(1007.0 - 0.4 * w, 200), (1007.0 - 1.4 * w, 500)]).pop();
        assert_eq!(last, Some(Action::Delete { pieces: 2, words: false, started: false }));
    }

    #[test]
    fn a_slide_from_the_right_edge_deletes_and_sliding_back_puts_back() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.numbers_bottom / 2.0;
        let at = |x: f32| Point { x, y };
        keyboard.touch_down(at(1007.0), 0);
        assert_eq!(keyboard.touch_move(at(1007.0 - 0.2 * w), 100), Action::Nothing, "not yet a slide");
        assert_eq!(keyboard.touch_move(at(1007.0 - 0.4 * w), 200), Action::Delete { pieces: 0, words: false, started: true });
        assert_eq!(keyboard.touch_move(at(1007.0 - 0.75 * w), 400), Action::Delete { pieces: 1, words: false, started: false });
        assert_eq!(keyboard.touch_move(at(1007.0 - 1.4 * w), 500), Action::Delete { pieces: 2, words: false, started: false });
        assert_eq!(keyboard.touch_move(at(1007.0 - 2.0 * w), 600), Action::Delete { pieces: 3, words: false, started: false }, "faster farther in");
        assert_eq!(keyboard.touch_move(at(1007.0 - 1.9 * w), 700), Action::Nothing, "easing back a little keeps them");
        assert_eq!(keyboard.touch_move(at(1007.0 - 1.2 * w), 800), Action::Delete { pieces: 2, words: false, started: false });
        assert_eq!(keyboard.pressed(), None);
        assert_eq!(keyboard.touch_up(at(1007.0 - 1.2 * w), 900), Action::Nothing);
        // On the letters the same slide stays on its key and types it, even from the edge.
        assert_eq!(gesture(&mut keyboard, "g", &[(-0.3, 0.0), (-0.6, 0.1)]), Action::Commit("g".into()));
        let p = keyboard.layout.find("p").cells[0];
        keyboard.touch_down(Point { x: 1007.0, y: p.y }, 1000);
        assert_eq!(keyboard.touch_move(Point { x: 1007.0 - 1.4 * w, y: p.y }, 1200), Action::Nothing);
        assert!(keyboard.deleting().is_none());
    }

    #[test]
    fn left_handed_delete_slides_in_from_the_left() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        keyboard.gestures.delete_from_left = true;
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.numbers_bottom / 2.0;
        keyboard.touch_down(Point { x: 1.0, y }, 0);
        assert_eq!(keyboard.touch_move(Point { x: 1.0 + 0.4 * w, y }, 200), Action::Delete { pieces: 0, words: false, started: true });
        assert_eq!(keyboard.touch_move(Point { x: 1.0 + 0.8 * w, y }, 400), Action::Delete { pieces: 1, words: false, started: false });
    }

    #[test]
    fn moving_within_the_key_types_it() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        // As o in "phone" in the phone's log: 0.35 key widths left in 46 ms.
        keyboard.touch_down(near(&keyboard, "o", 0.0, 0.0), 0);
        keyboard.touch_move(near(&keyboard, "o", -0.08, 0.04), 34);
        assert_eq!(keyboard.touch_up(near(&keyboard, "o", -0.33, 0.11), 46), Action::Commit("o".into()));
        keyboard.touch_down(near(&keyboard, "g", 0.0, 0.0), 0);
        keyboard.touch_move(near(&keyboard, "g", 0.3, 0.0), 100);
        assert_eq!(keyboard.touch_up(near(&keyboard, "g", 0.6, 0.0), 185), Action::Commit("g".into()), "slowly sideways");
        assert_eq!(gesture(&mut keyboard, "g", &[(0.8, 0.0)]), Action::Commit("g".into()));
        assert_eq!(gesture(&mut keyboard, "g", &[(0.0, 0.8)]), Action::Commit("g".into()), "down");
        assert_eq!(gesture(&mut keyboard, "g", &[(0.0, -0.6), (0.0, -0.1)]), Action::Commit("g".into()), "a flick up pulled back");
        assert_eq!(gesture(&mut keyboard, "space", &[(-0.5, 0.0)]), Action::Language { forward: false }, "space's sideways flick picks the language");
        // As in the phone's log, flicks on space run up to almost three key widths.
        assert_eq!(gesture(&mut keyboard, "space", &[(1.0, -0.1), (2.67, -0.35)]), Action::Language { forward: true });
    }

    #[test]
    fn a_short_slide_down_is_a_tap() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "g", &[(0.0, 0.58)]), Action::Commit("g".into()));
        assert_eq!(gesture(&mut keyboard, "space", &[(-0.25, 0.45)]), Action::Commit(" ".into()), "as after weil in the log");
    }

    #[test]
    fn long_paths_from_a_letter_are_swipes_with_their_points() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "g", &[(-0.6, 0.0), (-1.4, 0.0)]), Action::Swipe);
        let swiped = keyboard.swiped.clone().unwrap();
        assert_eq!(swiped.path.len(), 4);
        assert!(!swiped.capital);
        assert_eq!(gesture(&mut keyboard, "space", &[(0.5, 0.1), (1.5, 0.3)]), Action::Language { forward: true }, "not from a letter: space picks the language");
    }

    #[test]
    fn a_delete_slide_resting_on_the_far_side_deletes_letters_then_whole_words_ever_faster() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        // The far side is where words go only when the slide has no reach to delete everything.
        keyboard.gestures.delete_all_percent = DELETE_ALL_OFF;
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.numbers_bottom / 2.0;
        keyboard.touch_down(Point { x: 1007.0, y }, 0);
        keyboard.touch_move(Point { x: 600.0, y }, 300);
        assert_eq!(keyboard.deadline(), None, "resting in the middle only chooses how many characters");
        keyboard.touch_move(Point { x: 0.5 * w, y }, 500);
        assert_eq!(keyboard.deadline(), Some(500 + FAR_LETTER_START_MS));
        let mut letters = 0;
        while keyboard.deadline().unwrap() < 500 + FAR_HOLD_MS {
            let due = keyboard.deadline().unwrap();
            assert!(matches!(keyboard.tick(due), Action::Delete { words: false, .. }), "letters until the second is up");
            letters += 1;
        }
        assert!(letters >= 5, "{letters} letters");
        let first = keyboard.tick(500 + FAR_HOLD_MS);
        assert!(matches!(first, Action::Delete { words: true, .. }));
        let pauses: Vec<u64> = (0..6)
            .map(|_| {
                let due = keyboard.deadline().unwrap();
                keyboard.tick(due);
                keyboard.deadline().unwrap() - due
            })
            .collect();
        assert!(pauses.windows(2).all(|pair| pair[1] <= pair[0]), "{pauses:?}");
        assert!(keyboard.deleting().unwrap().2);
    }

    #[test]
    fn sliding_back_to_the_start_puts_back_what_resting_took() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        // The far side is where words go only when the slide has no reach to delete everything.
        keyboard.gestures.delete_all_percent = DELETE_ALL_OFF;
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.numbers_bottom / 2.0;
        keyboard.touch_down(Point { x: 1007.0, y }, 0);
        keyboard.touch_move(Point { x: 0.5 * w, y }, 500);
        let mut time = 500;
        for _ in 0..20 {
            time = keyboard.deadline().unwrap();
            keyboard.tick(time);
        }
        let mut last = Action::Nothing;
        for step in 1..=20 {
            let x = 0.5 * w + (1007.0 - 0.5 * w) * step as f32 / 20.0;
            let action = keyboard.touch_move(Point { x, y }, time + step * 20);
            if action != Action::Nothing {
                last = action;
            }
        }
        assert!(matches!(last, Action::Delete { pieces: 0, .. }), "{last:?}");
    }

    #[test]
    fn holding_backspace_repeats_and_holding_shift_locks_capitals() {
        let mut keyboard = Keyboard::new(with_shift_and_backspace(space_middle()), 1008.0, 1.0, 400);
        let backspace = near(&keyboard, "⌫", 0.0, 0.0);
        keyboard.touch_down(backspace, 0);
        assert_eq!(keyboard.tick(400), Action::Backspace);
        assert_eq!(keyboard.tick(400 + BACKSPACE_REPEAT_MS), Action::Backspace);
        assert_eq!(keyboard.touch_up(backspace, 500), Action::Redraw);
        let shift = near(&keyboard, "⇧", 0.0, 0.0);
        keyboard.touch_down(shift, 1000);
        keyboard.tick(1400);
        keyboard.touch_up(shift, 1500);
        assert_eq!(gesture(&mut keyboard, "g", &[]), Action::Commit("G".into()));
        assert_eq!(gesture(&mut keyboard, "a", &[]), Action::Commit("A".into()), "still capitals");
        assert_eq!(gesture(&mut keyboard, "⇧", &[]), Action::Redraw);
        assert_eq!(gesture(&mut keyboard, "a", &[]), Action::Commit("a".into()));
    }

    #[test]
    fn a_flick_up_types_a_capital() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "g", &[(0.0, -0.6)]), Action::Commit("G".into()));
    }

    #[test]
    fn holding_enter_asks_for_the_fields_action_once() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let enter = near(&keyboard, "⏎", 0.0, 0.0);
        keyboard.touch_down(enter, 0);
        assert_eq!(keyboard.deadline(), Some(400));
        assert_eq!(keyboard.tick(400), Action::EnterAction);
        assert_eq!(keyboard.deadline(), None);
        assert_eq!(keyboard.touch_up(enter, 700), Action::Redraw);
        keyboard.touch_down(enter, 1000);
        assert_eq!(keyboard.touch_up(enter, 1080), Action::Enter);
        assert_eq!(gesture(&mut keyboard, "⏎", &[(0.0, -0.6)]), Action::EnterAction, "a flick up does it too");
        assert_eq!(gesture(&mut keyboard, SYMBOLS_KEY, &[(0.0, -0.6)]), Action::Emoji, "a flick up on the symbols key opens the emoji");
    }

    #[test]
    fn resting_at_a_side_moves_the_cursor_on_ever_faster() {
        let mut keyboard = Keyboard::new(space_bottom(), 1008.0, 1.0, 400);
        let space = near(&keyboard, "space", 0.0, 0.0);
        keyboard.touch_down(space, 0);
        keyboard.tick(400);
        let edge = Point { x: 5.0, y: space.y };
        assert!(matches!(keyboard.touch_move(edge, 500), Action::Cursor { dx, .. } if dx < 0));
        let first = keyboard.deadline().expect("steps on at the side");
        assert_eq!(keyboard.tick(first), Action::Cursor { dx: -1, dy: 0 });
        let pauses: Vec<u64> = (0..5)
            .map(|_| {
                let due = keyboard.deadline().unwrap();
                assert_eq!(keyboard.tick(due), Action::Cursor { dx: -1, dy: 0 });
                keyboard.deadline().unwrap() - due
            })
            .collect();
        assert!(pauses.windows(2).all(|p| p[1] <= p[0]), "{pauses:?}");
        // Back in the middle it stops, and sliding right steps right again.
        assert!(matches!(keyboard.touch_move(space, 2000), Action::Cursor { dx, .. } if dx > 0));
        assert_eq!(keyboard.deadline(), None);
    }

    #[test]
    fn holding_space_moves_the_cursor_with_the_finger() {
        let mut keyboard = Keyboard::new(space_bottom(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let space = near(&keyboard, "space", 0.0, 0.0);
        keyboard.touch_down(space, 0);
        assert_eq!(keyboard.tick(400), Action::Redraw);
        assert!(keyboard.moving_cursor());
        let at = |dx: f32| Point { x: space.x + dx * w, y: space.y };
        assert_eq!(keyboard.touch_move(at(0.1), 450), Action::Nothing);
        assert_eq!(keyboard.touch_move(at(0.65), 500), Action::Cursor { dx: 2, dy: 0 });
        assert_eq!(keyboard.touch_move(at(-0.35), 550), Action::Cursor { dx: -3, dy: 0 });
        assert_eq!(keyboard.touch_up(at(-0.35), 600), Action::Redraw, "lifting types no space");
        assert!(!keyboard.moving_cursor());
    }

    #[test]
    fn a_sideways_flick_on_space_steps_through_languages() {
        let mut keyboard = Keyboard::new(space_bottom(), 1008.0, 1.0, 400);
        assert_eq!(gesture(&mut keyboard, "space", &[(0.6, 0.0)]), Action::Language { forward: true });
        assert_eq!(gesture(&mut keyboard, "space", &[(-0.6, 0.05)]), Action::Language { forward: false });
        assert_eq!(gesture(&mut keyboard, "space", &[]), Action::Commit(" ".into()));
    }

    #[test]
    fn a_sideways_slide_in_the_middle_of_the_number_row_moves_the_cursor() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.numbers_bottom / 2.0;
        keyboard.touch_down(Point { x: 504.0, y }, 0);
        assert_eq!(keyboard.touch_move(Point { x: 504.0 + 0.65 * w, y }, 100), Action::Cursor { dx: 2, dy: 0 });
        assert_eq!(keyboard.touch_move(Point { x: 504.0 - 0.35 * w, y }, 200), Action::Cursor { dx: -3, dy: 0 });
        assert_eq!(keyboard.touch_up(Point { x: 504.0 - 0.35 * w, y }, 300), Action::Redraw, "no digit typed");
        assert!(matches!(gesture(&mut keyboard, "5", &[]), Action::Commit(_)), "a tap still types its digit");
        keyboard.touch_down(Point { x: 504.0, y }, 1000);
        assert_eq!(keyboard.tick(1400), Action::Redraw);
        assert!(keyboard.moving_cursor(), "holding there makes the keys a touchpad as space does");
    }

    #[test]
    fn holding_still_opens_the_popup_and_lifting_commits_from_it() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        keyboard.accents.insert('s', vec!['ß']);
        keyboard.touch_down(near(&keyboard, "s", 0.0, 0.0), 0);
        assert_eq!(keyboard.tick(400), Action::Redraw);
        assert!(keyboard.popup.is_some());
        assert_eq!(keyboard.touch_up(near(&keyboard, "s", 0.0, 0.0), 500), Action::Commit("ß".into()));
        keyboard.touch_down(near(&keyboard, "q", 0.0, 0.0), 0);
        assert_eq!(keyboard.deadline(), None, "q has no popup to wait for");
    }

    fn keyboard_index(keyboard: &Keyboard, label: &str) -> usize {
        keyboard.layout.keys.iter().position(|k| k.label == label).unwrap()
    }

    #[test]
    fn every_letter_has_its_qwertz_row() {
        let keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let rows: Vec<Option<usize>> =
            ["q", "p", "a", "l", "y", "m", "⇧", "space", "ß"].iter().map(|l| qwertz_row(l)).collect();
        assert_eq!(rows, [Some(0), Some(0), Some(1), Some(1), Some(2), Some(2), None, None, None]);
        let letters = keyboard.layout.keys.iter().enumerate().filter(|(i, _)| keyboard.key_row(*i).is_some());
        assert_eq!(letters.count(), 26);
    }

    #[test]
    fn rows_interlock() {
        let layout = layout();
        let q = layout.find("q").cells[0];
        let w = layout.find("w").cells[0];
        assert!((w.x - q.x - layout.cell_width / 2.0).abs() < 0.01);
        assert!((w.y - q.y - layout.cell_height * 0.75).abs() < 0.01);
    }

    #[test]
    fn layers_share_the_tallest_height_and_still_tile() {
        let keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let tallest = Layout::fit(&space_middle(), 1008.0, 1.0).height;
        for layout in &keyboard.layers {
            assert!((layout.height - tallest).abs() < 0.01);
            let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
            assert!((area - layout.width * tallest).abs() / (layout.width * tallest) < 1e-4);
        }
    }

    #[test]
    fn space_at_the_bottom_keeps_qwertz_order_below_the_shared_rows() {
        let (middle, bottom) = (space_middle(), space_bottom());
        assert_eq!(middle.rows[..2], bottom.rows[..2], "the top letter rows are the same");
        let labels = |row: &RowSpec| row.keys.iter().map(|key| key.label.clone()).collect::<Vec<_>>().concat();
        assert_eq!(labels(&bottom.rows[2]), "sdfhjk");
        assert_eq!(labels(&bottom.rows[3]), "yxcvbnm");
        // f, h and y stay in the cells the middle layout has them in.
        for (row, column) in [(2, 2), (2, 3), (3, 0)] {
            assert_eq!(middle.rows[row].keys[column], bottom.rows[row].keys[column]);
        }
    }

    #[test]
    fn the_top_and_bottom_letter_rows_are_as_tall_as_each_other() {
        for spec in [space_middle(), space_bottom()] {
            let layout = Layout::fit(&spec, 1008.0, 1.0);
            let height = |row: usize| {
                let key = layout.keys.iter().find(|key| key.row == row && key.half.is_none()).unwrap();
                let ys: Vec<f32> = layout.outline(key).iter().map(|p| p.y).collect();
                ys.iter().cloned().fold(f32::MIN, f32::max) - ys.iter().cloned().fold(f32::MAX, f32::min)
            };
            let last = spec.rows.len() - 1;
            assert!((height(0) - height(last)).abs() < 0.5, "{} against {}", height(0), height(last));
        }
    }

    #[test]
    fn space_middle_starts_with_a_short_shifted_row_as_wide_as_the_rest() {
        let layout = Layout::fit(&space_middle(), 1008.0, 1.0);
        let w = layout.find("w").cells[0];
        let q = layout.find("q").cells[0];
        assert!((w.x - q.x - layout.cell_width / 2.0).abs() < 0.01);
        assert!((layout.cell_width - 1008.0 / 7.0).abs() < 0.01);
        let keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let letters = keyboard.layout.keys.iter().enumerate().filter(|(i, _)| keyboard.key_row(*i).is_some());
        assert_eq!(letters.count(), 26);
    }

    #[test]
    fn halves_split_their_cell_and_numbers_zigzag() {
        let mut spec = space_middle();
        spec.rows[4] = row(0, vec![KeySpec::new(SYMBOLS_KEY, 1), KeySpec::new("space", 3), KeySpec::halves(",", "."), KeySpec::new("⏎", 1)]);
        let layout = Layout::fit(&spec, 1008.0, 1.0);
        let comma = layout.keys.iter().find(|key| key.half == Some(Half::Left)).unwrap();
        let cell = comma.cells[0];
        let w = layout.cell_width;
        assert_eq!(layout.key_at(Point { x: cell.x - w / 4.0, y: cell.y }).unwrap().label, ",");
        assert_eq!(layout.key_at(Point { x: cell.x + w / 4.0, y: cell.y }).unwrap().half, Some(Half::Right));
        let dot = layout.keys.iter().find(|key| key.half == Some(Half::Right)).unwrap();
        let (left, right) = (polygon_area(&layout.outline(comma)), polygon_area(&layout.outline(dot)));
        assert!((left - right).abs() / left < 1e-3, "{left} {right}");
        let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
        assert!((area - layout.width * layout.height).abs() / (layout.width * layout.height) < 1e-4);
        let (one, two) = (layout.find("1").cells[0], layout.find("2").cells[0]);
        assert!(two.y > one.y && two.x > one.x);
        for digit in ["1", "2", "5", "0"] {
            let key = layout.find(digit);
            assert_eq!(layout.key_at(key.cells[0]).unwrap().label, digit);
        }
    }

    #[test]
    fn a_delete_slide_moved_about_for_a_while_stays_a_delete() {
        let mut keyboard = Keyboard::new(space_middle(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.numbers_bottom / 2.0;
        let at = |dx: f32, dy: f32| Point { x: 1000.0 + dx * w, y: y + dy * w };
        keyboard.touch_down(at(0.0, 0.0), 0);
        keyboard.touch_move(at(-2.0, 0.0), 300);
        // Back towards the start and drifting down, as a finger does while choosing how much to delete.
        keyboard.touch_move(at(-0.6, 0.7), 900);
        keyboard.touch_move(at(-0.2, 0.75), 1100);
        assert!(keyboard.deleting().is_some());
        assert!(!matches!(keyboard.touch_up(at(-0.2, 0.75), 1200), Action::Swipe));
    }

    #[test]
    fn a_slanted_straight_delete_slide_keeps_deleting() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        let y = keyboard.layout.numbers_bottom / 4.0;
        keyboard.touch_down(Point { x: 1000.0, y }, 0);
        let mut last = Action::Nothing;
        for step in 1..=10 {
            let t = step as f32 / 10.0;
            let action = keyboard.touch_move(Point { x: 1000.0 - 2.3 * w * t, y: y + 1.3 * w * t }, 50 * step);
            if action != Action::Nothing {
                last = action;
            }
        }
        assert!(matches!(last, Action::Delete { pieces, .. } if pieces >= 4), "{last:?}");
    }

    #[test]
    fn quick_straight_strokes_are_flicks_and_deletes_whatever_their_length() {
        let mut keyboard = Keyboard::new(honeycomb(), 1008.0, 1.0, 400);
        let w = keyboard.layout.cell_width;
        // Up 1.6 key widths in 90 ms, as a thumb flicks: a capital.
        let h = keyboard.layout.find("h").cells[0];
        keyboard.touch_down(h, 0);
        keyboard.touch_move(Point { x: h.x + 0.1 * w, y: h.y - 0.8 * w }, 45);
        assert_eq!(keyboard.touch_up(Point { x: h.x + 0.2 * w, y: h.y - 1.6 * w }, 90), Action::Commit("H".into()));
        // The same stroke slowly: swipe typing.
        keyboard.touch_down(h, 0);
        keyboard.touch_move(Point { x: h.x + 0.1 * w, y: h.y - 0.8 * w }, 150);
        assert_eq!(keyboard.touch_up(Point { x: h.x + 0.2 * w, y: h.y - 1.6 * w }, 300), Action::Swipe);
        // Left in the number row's right half, quickly: deletes on lifting.
        let o = Point { x: 0.7 * keyboard.layout.width, y: keyboard.layout.numbers_bottom / 2.0 };
        keyboard.touch_down(o, 0);
        keyboard.touch_move(Point { x: o.x - 0.7 * w, y: o.y }, 50);
        let flicked = keyboard.touch_up(Point { x: o.x - 1.4 * w, y: o.y + 0.1 * w }, 100);
        assert!(matches!(flicked, Action::Delete { pieces: 1, words: false, .. }), "a flick takes one: {flicked:?}");
    }

    #[test]
    fn symbol_pages_keep_every_key_but_the_letters_in_place() {
        for letters in [honeycomb(), space_middle()] {
            let keyboard = Keyboard::new(letters, 1008.0, 1.0, 400);
            let [plain, symbols, more] = &keyboard.layers;
            for (key, symbol) in plain.keys.iter().zip(&symbols.keys) {
                assert_eq!(key.cells, symbol.cells, "{} and {} share a place", key.label, symbol.label);
                if key.label == SYMBOLS_KEY {
                    assert_eq!(symbol.label, "ABC");
                } else if !is_letter(&key.label) && key.label != "⇧" {
                    assert_eq!(key.label, symbol.label);
                }
            }
            assert!(symbols.keys.iter().any(|key| key.label == MORE));
            assert!(more.keys.iter().any(|key| key.label == SYMBOLS_KEY));
            let on_letters: Vec<&str> = plain.keys.iter().map(|key| key.label.as_str()).collect();
            assert!(symbols.keys.iter().filter(|key| !on_letters.contains(&key.label.as_str())).count() >= 20);
        }
    }

    #[test]
    fn space_at_the_bottom_has_the_middle_layouts_letters_and_only_letters_in_the_middle() {
        let layout = Layout::fit(&space_bottom(), 1008.0, 1.0);
        let letters = layout.keys.iter().filter(|key| is_letter(&key.label)).count();
        assert_eq!(letters, 26);
        let bottom = layout.keys.iter().map(|key| key.row).filter(|&row| row != NUMBER_ROW).max().unwrap();
        assert!(layout.keys.iter().filter(|key| !is_letter(&key.label) && key.row != NUMBER_ROW).all(|key| key.row == bottom));
        let area: f32 = layout.keys.iter().map(|key| polygon_area(&layout.outline(key))).sum();
        assert!((area - layout.width * layout.height).abs() / (layout.width * layout.height) < 1e-4);
    }
}
