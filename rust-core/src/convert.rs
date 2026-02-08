//! Route/track conversion.
//!
//! Converts between GPX tracks (recorded paths with many dense points)
//! and GPX routes (planned paths with sparse waypoints). Track-to-route
//! uses the Ramer-Douglas-Peucker algorithm to simplify the point list.
//! Route-to-track is a direct copy since routes are a subset of tracks.

use crate::gpx::{Point, Track, Route};

/// Convert a track to a route by simplifying with Ramer-Douglas-Peucker.
///
/// `tolerance_m` controls simplification aggressiveness. Typical values:
/// - 10.0: light simplification, keeps most detail
/// - 50.0: moderate, good for navigation waypoints
/// - 100.0: aggressive, only major direction changes
pub fn track_to_route(track: &Track, tolerance_m: f64) -> Route {
    let simplified = rdp_simplify(&track.points, tolerance_m);
    Route {
        name: track.name.clone(),
        points: simplified,
    }
}

/// Convert a route to a track (direct copy of points).
///
/// Routes and tracks share the same point structure. The conversion
/// preserves all waypoints as track points. Interpolation between
/// route waypoints would require road network data and is not yet
/// implemented.
pub fn route_to_track(route: &Route) -> Track {
    Track {
        name: route.name.clone(),
        points: route.points.clone(),
    }
}

/// Ramer-Douglas-Peucker line simplification.
///
/// Uses perpendicular distance in a local planar approximation
/// (latitude-cosine scaled), which is accurate for track segments
/// with moderate distances between points.
fn rdp_simplify(points: &[Point], tolerance_m: f64) -> Vec<Point> {
    if points.len() <= 2 {
        return points.to_vec();
    }

    let first = &points[0];
    let last = &points[points.len() - 1];

    // Find the point with maximum distance from the line
    let mut max_dist = 0.0;
    let mut max_idx = 0;

    for (i, p) in points.iter().enumerate().skip(1).take(points.len() - 2) {
        let dist = perpendicular_distance_m(p, first, last);
        if dist > max_dist {
            max_dist = dist;
            max_idx = i;
        }
    }

    if max_dist > tolerance_m {
        // Recurse on both halves
        let mut left = rdp_simplify(&points[..=max_idx], tolerance_m);
        let right = rdp_simplify(&points[max_idx..], tolerance_m);

        // Remove duplicate junction point
        left.pop();
        left.extend(right);
        left
    } else {
        // All intermediate points are within tolerance
        vec![first.clone(), last.clone()]
    }
}

/// Perpendicular distance from point P to line segment A-B, in meters.
///
/// Uses a planar approximation with latitude-cosine scaling.
fn perpendicular_distance_m(p: &Point, a: &Point, b: &Point) -> f64 {
    let cos_lat = ((a.lat + b.lat) / 2.0).to_radians().cos();

    // Convert to approximate meters
    let m_per_deg_lat = 111_320.0;
    let m_per_deg_lon = 111_320.0 * cos_lat;

    let ax = a.lon * m_per_deg_lon;
    let ay = a.lat * m_per_deg_lat;
    let bx = b.lon * m_per_deg_lon;
    let by = b.lat * m_per_deg_lat;
    let px = p.lon * m_per_deg_lon;
    let py = p.lat * m_per_deg_lat;

    let dx = bx - ax;
    let dy = by - ay;
    let len_sq = dx * dx + dy * dy;

    if len_sq < 1e-10 {
        return ((px - ax).powi(2) + (py - ay).powi(2)).sqrt();
    }

    // Perpendicular distance using cross product
    let cross = ((px - ax) * dy - (py - ay) * dx).abs();
    cross / len_sq.sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gpx::Point;

    fn pt(lat: f64, lon: f64) -> Point {
        Point { lat, lon, ele: None }
    }

    #[test]
    fn track_to_route_preserves_endpoints() {
        let track = Track {
            name: Some("Test".into()),
            points: vec![pt(48.0, 16.0), pt(48.001, 16.001), pt(48.0, 16.002)],
        };

        let route = track_to_route(&track, 1000.0);
        assert_eq!(route.name.as_deref(), Some("Test"));
        // With high tolerance, only endpoints remain
        assert_eq!(route.points.len(), 2);
        assert!((route.points[0].lat - 48.0).abs() < 1e-6);
        assert!((route.points[1].lat - 48.0).abs() < 1e-6);
    }

    #[test]
    fn track_to_route_keeps_sharp_turn() {
        // L-shaped track: a sharp turn should be preserved
        let track = Track {
            name: None,
            points: vec![
                pt(48.0, 16.0),
                pt(48.0, 16.01),
                pt(48.0, 16.02), // corner
                pt(48.01, 16.02),
                pt(48.02, 16.02),
            ],
        };

        let route = track_to_route(&track, 10.0);
        // Corner point should be preserved with low tolerance
        assert!(route.points.len() >= 3,
            "Expected at least 3 points, got {}", route.points.len());
    }

    #[test]
    fn track_to_route_zero_tolerance() {
        let track = Track {
            name: None,
            points: vec![pt(48.0, 16.0), pt(48.001, 16.001), pt(48.0, 16.002)],
        };

        let route = track_to_route(&track, 0.0);
        // With zero tolerance, all points are preserved
        assert_eq!(route.points.len(), 3);
    }

    #[test]
    fn route_to_track_round_trip() {
        let route = Route {
            name: Some("Route".into()),
            points: vec![pt(48.0, 16.0), pt(48.1, 16.1)],
        };

        let track = route_to_track(&route);
        assert_eq!(track.name.as_deref(), Some("Route"));
        assert_eq!(track.points.len(), 2);
    }

    #[test]
    fn rdp_simplify_two_points() {
        let points = vec![pt(0.0, 0.0), pt(1.0, 1.0)];
        let result = rdp_simplify(&points, 100.0);
        assert_eq!(result.len(), 2);
    }

    #[test]
    fn rdp_simplify_straight_line() {
        // Points on a straight line should simplify to 2
        let points = vec![
            pt(48.0, 16.0),
            pt(48.0, 16.005),
            pt(48.0, 16.01),
            pt(48.0, 16.015),
            pt(48.0, 16.02),
        ];

        let result = rdp_simplify(&points, 10.0);
        assert_eq!(result.len(), 2);
    }
}
