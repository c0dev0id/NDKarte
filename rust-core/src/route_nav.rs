//! Route navigation with turn-by-turn instructions.
//!
//! Computes turn directions between consecutive route waypoints
//! and generates instruction strings. Platform-agnostic.

use serde::Serialize;
use crate::gpx::Point;
use crate::nav::haversine;

/// A single turn-by-turn instruction.
#[derive(Debug, Clone, Serialize)]
pub struct Instruction {
    /// Waypoint index this instruction applies to.
    pub waypoint_index: usize,
    /// Distance from previous waypoint in meters.
    pub distance_m: f64,
    /// Turn direction at this waypoint.
    pub turn: Turn,
    /// Human-readable instruction text.
    pub text: String,
}

/// Turn direction categories.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Turn {
    Start,
    Straight,
    SlightLeft,
    Left,
    SharpLeft,
    SlightRight,
    Right,
    SharpRight,
    UTurn,
    Arrive,
}

/// Generate turn-by-turn instructions for a route.
///
/// The route must have at least 2 points to produce meaningful
/// instructions. Returns one instruction per waypoint.
pub fn generate_instructions(points: &[Point]) -> Vec<Instruction> {
    if points.len() < 2 {
        return Vec::new();
    }

    let mut instructions = Vec::with_capacity(points.len());

    // Start instruction
    instructions.push(Instruction {
        waypoint_index: 0,
        distance_m: 0.0,
        turn: Turn::Start,
        text: "Start navigation".to_string(),
    });

    // Middle instructions (turn at each waypoint)
    for i in 1..points.len() - 1 {
        let dist = haversine(&points[i - 1], &points[i]);
        let turn = compute_turn(&points[i - 1], &points[i], &points[i + 1]);

        let dist_text = format_distance(dist);
        let turn_text = turn_to_text(turn);

        instructions.push(Instruction {
            waypoint_index: i,
            distance_m: dist,
            turn,
            text: format!("In {dist_text}, {turn_text}"),
        });
    }

    // Arrival instruction
    let last = points.len() - 1;
    let dist = haversine(&points[last - 1], &points[last]);
    instructions.push(Instruction {
        waypoint_index: last,
        distance_m: dist,
        turn: Turn::Arrive,
        text: format!("In {}, arrive at destination", format_distance(dist)),
    });

    instructions
}

/// Compute the turn direction at point B given the approach from A
/// and the exit toward C.
///
/// Uses the bearing change (relative angle) to categorize the turn.
fn compute_turn(a: &Point, b: &Point, c: &Point) -> Turn {
    let bearing_ab = bearing(a, b);
    let bearing_bc = bearing(b, c);

    // Relative angle: positive = right turn, negative = left turn
    let mut angle = bearing_bc - bearing_ab;

    // Normalize to [-180, 180]
    while angle > 180.0 {
        angle -= 360.0;
    }
    while angle < -180.0 {
        angle += 360.0;
    }

    classify_turn(angle)
}

/// Classify a relative bearing angle into a turn direction.
fn classify_turn(angle: f64) -> Turn {
    let abs_angle = angle.abs();

    if abs_angle > 170.0 {
        Turn::UTurn
    } else if abs_angle > 120.0 {
        if angle > 0.0 { Turn::SharpRight } else { Turn::SharpLeft }
    } else if abs_angle > 60.0 {
        if angle > 0.0 { Turn::Right } else { Turn::Left }
    } else if abs_angle > 20.0 {
        if angle > 0.0 { Turn::SlightRight } else { Turn::SlightLeft }
    } else {
        Turn::Straight
    }
}

/// Compute the initial bearing from point A to point B in degrees [0, 360).
fn bearing(a: &Point, b: &Point) -> f64 {
    let lat1 = a.lat.to_radians();
    let lat2 = b.lat.to_radians();
    let dlon = (b.lon - a.lon).to_radians();

    let y = dlon.sin() * lat2.cos();
    let x = lat1.cos() * lat2.sin() - lat1.sin() * lat2.cos() * dlon.cos();

    let bearing = y.atan2(x).to_degrees();
    (bearing + 360.0) % 360.0
}

fn turn_to_text(turn: Turn) -> &'static str {
    match turn {
        Turn::Start => "start navigation",
        Turn::Straight => "continue straight",
        Turn::SlightLeft => "keep slightly left",
        Turn::Left => "turn left",
        Turn::SharpLeft => "turn sharp left",
        Turn::SlightRight => "keep slightly right",
        Turn::Right => "turn right",
        Turn::SharpRight => "turn sharp right",
        Turn::UTurn => "make a U-turn",
        Turn::Arrive => "arrive at destination",
    }
}

fn format_distance(meters: f64) -> String {
    if meters >= 1000.0 {
        format!("{:.1} km", meters / 1000.0)
    } else {
        format!("{} m", (meters / 10.0).round() as i64 * 10)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pt(lat: f64, lon: f64) -> Point {
        Point { lat, lon, ele: None }
    }

    #[test]
    fn bearing_east() {
        let b = bearing(&pt(0.0, 0.0), &pt(0.0, 1.0));
        assert!((b - 90.0).abs() < 0.1, "Expected ~90, got {b}");
    }

    #[test]
    fn bearing_north() {
        let b = bearing(&pt(0.0, 0.0), &pt(1.0, 0.0));
        assert!(b.abs() < 0.1, "Expected ~0, got {b}");
    }

    #[test]
    fn classify_straight() {
        assert_eq!(classify_turn(5.0), Turn::Straight);
        assert_eq!(classify_turn(-10.0), Turn::Straight);
    }

    #[test]
    fn classify_right_turn() {
        assert_eq!(classify_turn(90.0), Turn::Right);
        assert_eq!(classify_turn(-90.0), Turn::Left);
    }

    #[test]
    fn classify_u_turn() {
        assert_eq!(classify_turn(175.0), Turn::UTurn);
        assert_eq!(classify_turn(-175.0), Turn::UTurn);
    }

    #[test]
    fn generate_simple_route() {
        // Straight north route: 3 points along same meridian
        let route = vec![pt(48.0, 16.0), pt(48.5, 16.0), pt(49.0, 16.0)];
        let instructions = generate_instructions(&route);

        assert_eq!(instructions.len(), 3);
        assert_eq!(instructions[0].turn, Turn::Start);
        assert_eq!(instructions[1].turn, Turn::Straight);
        assert_eq!(instructions[2].turn, Turn::Arrive);
    }

    #[test]
    fn generate_right_turn() {
        // North then east: should produce a right turn
        let route = vec![
            pt(48.0, 16.0),
            pt(48.5, 16.0),
            pt(48.5, 17.0),
        ];
        let instructions = generate_instructions(&route);

        assert_eq!(instructions.len(), 3);
        assert_eq!(instructions[1].turn, Turn::Right);
    }

    #[test]
    fn generate_too_few_points() {
        let route = vec![pt(48.0, 16.0)];
        assert!(generate_instructions(&route).is_empty());
    }

    #[test]
    fn generate_two_points() {
        let route = vec![pt(48.0, 16.0), pt(49.0, 16.0)];
        let instructions = generate_instructions(&route);

        assert_eq!(instructions.len(), 2);
        assert_eq!(instructions[0].turn, Turn::Start);
        assert_eq!(instructions[1].turn, Turn::Arrive);
    }

    #[test]
    fn format_distance_meters() {
        assert_eq!(format_distance(150.0), "150 m");
        assert_eq!(format_distance(5.0), "10 m");
    }

    #[test]
    fn format_distance_km() {
        assert_eq!(format_distance(2500.0), "2.5 km");
    }
}
