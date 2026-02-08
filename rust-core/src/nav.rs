//! Navigation computations.
//!
//! Platform-agnostic module for track navigation, nearest-point
//! calculations, and distance computations. All coordinates use
//! WGS84 (lat/lon in degrees).

use serde::Serialize;
use crate::gpx::Point;

/// Result of projecting a position onto a track.
#[derive(Debug, Clone, Serialize)]
pub struct ProjectionResult {
    /// Nearest point on the track segment.
    pub point: Point,
    /// Index of the track segment start point (0-based).
    pub segment_index: usize,
    /// Distance from the position to the nearest point, in meters.
    pub distance_m: f64,
    /// Distance along the track from the start to the projected point, in meters.
    pub distance_along_m: f64,
}

/// Earth radius in meters (WGS84 mean).
const EARTH_RADIUS_M: f64 = 6_371_008.8;

/// Haversine distance between two points in meters.
pub fn haversine(a: &Point, b: &Point) -> f64 {
    let lat1 = a.lat.to_radians();
    let lat2 = b.lat.to_radians();
    let dlat = (b.lat - a.lat).to_radians();
    let dlon = (b.lon - a.lon).to_radians();

    let h = (dlat / 2.0).sin().powi(2)
        + lat1.cos() * lat2.cos() * (dlon / 2.0).sin().powi(2);

    2.0 * EARTH_RADIUS_M * h.sqrt().asin()
}

/// Total length of a track in meters.
pub fn track_length(points: &[Point]) -> f64 {
    points
        .windows(2)
        .map(|w| haversine(&w[0], &w[1]))
        .sum()
}

/// Project a position onto the nearest segment of a track.
///
/// Returns the nearest point on the track, the segment index,
/// the perpendicular distance, and the distance along the track
/// to the projected point.
///
/// Returns None if the track has fewer than 2 points.
pub fn project_on_track(position: &Point, track: &[Point]) -> Option<ProjectionResult> {
    if track.len() < 2 {
        return None;
    }

    let mut best: Option<ProjectionResult> = None;
    let mut cumulative_distance = 0.0;

    for (i, segment) in track.windows(2).enumerate() {
        let a = &segment[0];
        let b = &segment[1];
        let seg_len = haversine(a, b);

        let projected = project_on_segment(position, a, b);
        let dist = haversine(position, &projected);

        // Distance along track to this projected point
        let along = cumulative_distance + haversine(a, &projected);

        let is_better = match &best {
            Some(prev) => dist < prev.distance_m,
            None => true,
        };

        if is_better {
            best = Some(ProjectionResult {
                point: projected,
                segment_index: i,
                distance_m: dist,
                distance_along_m: along,
            });
        }

        cumulative_distance += seg_len;
    }

    best
}

/// Project a point onto a line segment defined by two endpoints.
///
/// Uses a planar approximation scaled by latitude cosine, which is
/// accurate enough for short segments (< 10 km).
fn project_on_segment(p: &Point, a: &Point, b: &Point) -> Point {
    let cos_lat = ((a.lat + b.lat) / 2.0).to_radians().cos();

    let dx = (b.lon - a.lon) * cos_lat;
    let dy = b.lat - a.lat;
    let px = (p.lon - a.lon) * cos_lat;
    let py = p.lat - a.lat;

    let seg_len_sq = dx * dx + dy * dy;

    if seg_len_sq < 1e-20 {
        // Degenerate segment, return endpoint
        return a.clone();
    }

    // Clamp parameter t to [0, 1] to stay on the segment
    let t = ((px * dx + py * dy) / seg_len_sq).clamp(0.0, 1.0);

    Point {
        lat: a.lat + t * (b.lat - a.lat),
        lon: a.lon + t * (b.lon - a.lon),
        ele: match (a.ele, b.ele) {
            (Some(ea), Some(eb)) => Some(ea + t * (eb - ea)),
            _ => None,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pt(lat: f64, lon: f64) -> Point {
        Point { lat, lon, ele: None }
    }

    #[test]
    fn haversine_same_point() {
        let p = pt(48.2082, 16.3738);
        assert!((haversine(&p, &p)).abs() < 0.01);
    }

    #[test]
    fn haversine_known_distance() {
        // Vienna to Bratislava ~55 km
        let vienna = pt(48.2082, 16.3738);
        let bratislava = pt(48.1486, 17.1077);
        let dist = haversine(&vienna, &bratislava);
        assert!(dist > 50_000.0 && dist < 60_000.0,
            "Expected ~55 km, got {:.0} m", dist);
    }

    #[test]
    fn track_length_simple() {
        let track = vec![pt(0.0, 0.0), pt(0.0, 1.0), pt(0.0, 2.0)];
        let len = track_length(&track);
        // Each degree of longitude at equator ~111 km
        assert!(len > 200_000.0 && len < 230_000.0,
            "Expected ~222 km, got {:.0} m", len);
    }

    #[test]
    fn project_on_track_midpoint() {
        // Track goes west-east, position is directly north of midpoint
        let track = vec![pt(48.0, 16.0), pt(48.0, 17.0)];
        let pos = pt(48.1, 16.5);

        let result = project_on_track(&pos, &track).unwrap();
        assert!((result.point.lat - 48.0).abs() < 0.01);
        assert!((result.point.lon - 16.5).abs() < 0.01);
        assert_eq!(result.segment_index, 0);
        assert!(result.distance_m > 10_000.0); // ~11 km north
    }

    #[test]
    fn project_on_track_start() {
        let track = vec![pt(48.0, 16.0), pt(48.0, 17.0)];
        let pos = pt(48.0, 15.5); // West of start

        let result = project_on_track(&pos, &track).unwrap();
        // Should clamp to start point
        assert!((result.point.lat - 48.0).abs() < 0.01);
        assert!((result.point.lon - 16.0).abs() < 0.01);
    }

    #[test]
    fn project_on_track_returns_none_for_single_point() {
        let track = vec![pt(48.0, 16.0)];
        assert!(project_on_track(&pt(48.0, 16.0), &track).is_none());
    }

    #[test]
    fn project_on_track_multi_segment() {
        // L-shaped track: east then north
        let track = vec![
            pt(48.0, 16.0),
            pt(48.0, 17.0),
            pt(49.0, 17.0),
        ];
        // Position near the second segment
        let pos = pt(48.5, 17.1);

        let result = project_on_track(&pos, &track).unwrap();
        assert_eq!(result.segment_index, 1);
        assert!((result.point.lon - 17.0).abs() < 0.01);
    }

    #[test]
    fn project_distance_along_increases() {
        let track = vec![
            pt(48.0, 16.0),
            pt(48.0, 16.5),
            pt(48.0, 17.0),
        ];

        let r1 = project_on_track(&pt(48.0, 16.2), &track).unwrap();
        let r2 = project_on_track(&pt(48.0, 16.8), &track).unwrap();
        assert!(r2.distance_along_m > r1.distance_along_m);
    }
}
