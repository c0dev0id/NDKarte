//! GPX 1.1 file parsing.
//!
//! Wraps the `gpx` crate and extracts tracks, routes, and waypoints
//! into serializable structures that can cross the JNI boundary as JSON
//! or be used directly by a non-Android frontend.

use serde::{Deserialize, Serialize};
use std::io::Read;

/// A geographic coordinate with optional elevation.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Point {
    pub lat: f64,
    pub lon: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ele: Option<f64>,
}

/// A named sequence of points representing a recorded path.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Track {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub points: Vec<Point>,
}

/// A named sequence of points representing a planned route.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Route {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub points: Vec<Point>,
}

/// A single named point of interest.
///
/// The `icon` field is populated from the GPX `<sym>` element, which is
/// the standard GPX 1.1 mechanism for waypoint symbols. NDKarte uses
/// this for custom waypoint icon rendering.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Waypoint {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub point: Point,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
}

/// All data extracted from a GPX file.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpxData {
    pub tracks: Vec<Track>,
    pub routes: Vec<Route>,
    pub waypoints: Vec<Waypoint>,
}

/// Parse a GPX file from any reader and return structured data.
pub fn parse<R: Read>(reader: R) -> Result<GpxData, String> {
    let gpx = gpx::read(reader).map_err(|e| format!("GPX parse error: {e}"))?;

    let tracks = gpx
        .tracks
        .iter()
        .map(|t| {
            let points = t
                .segments
                .iter()
                .flat_map(|seg| seg.points.iter())
                .map(|wp| Point {
                    lat: wp.point().y(),
                    lon: wp.point().x(),
                    ele: wp.elevation,
                })
                .collect();

            Track {
                name: t.name.clone(),
                points,
            }
        })
        .collect();

    let routes = gpx
        .routes
        .iter()
        .map(|r| {
            let points = r
                .points
                .iter()
                .map(|wp| Point {
                    lat: wp.point().y(),
                    lon: wp.point().x(),
                    ele: wp.elevation,
                })
                .collect();

            Route {
                name: r.name.clone(),
                points,
            }
        })
        .collect();

    let waypoints = gpx
        .waypoints
        .iter()
        .map(|wp| Waypoint {
            name: wp.name.clone(),
            point: Point {
                lat: wp.point().y(),
                lon: wp.point().x(),
                ele: wp.elevation,
            },
            icon: wp.symbol.clone(),
        })
        .collect();

    Ok(GpxData {
        tracks,
        routes,
        waypoints,
    })
}

/// Parse GPX from a byte slice. Convenience wrapper for JNI.
pub fn parse_bytes(data: &[u8]) -> Result<GpxData, String> {
    parse(data)
}

/// Parse GPX and return the result as a JSON string.
pub fn parse_to_json(data: &[u8]) -> Result<String, String> {
    let gpx_data = parse_bytes(data)?;
    serde_json::to_string(&gpx_data).map_err(|e| format!("JSON serialize error: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    const MINIMAL_GPX: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test"
     xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Test Track</name>
    <trkseg>
      <trkpt lat="48.2082" lon="16.3738"><ele>171</ele></trkpt>
      <trkpt lat="48.2090" lon="16.3750"><ele>173</ele></trkpt>
      <trkpt lat="48.2100" lon="16.3760"><ele>170</ele></trkpt>
    </trkseg>
  </trk>
  <rte>
    <name>Test Route</name>
    <rtept lat="48.2000" lon="16.3500"></rtept>
    <rtept lat="48.2100" lon="16.3600"></rtept>
  </rte>
  <wpt lat="48.2082" lon="16.3738">
    <name>Vienna</name>
    <ele>171</ele>
    <sym>fuel</sym>
  </wpt>
</gpx>"#;

    #[test]
    fn parse_minimal_gpx() {
        let data = parse_bytes(MINIMAL_GPX.as_bytes()).unwrap();

        assert_eq!(data.tracks.len(), 1);
        assert_eq!(data.tracks[0].name.as_deref(), Some("Test Track"));
        assert_eq!(data.tracks[0].points.len(), 3);

        let p = &data.tracks[0].points[0];
        assert!((p.lat - 48.2082).abs() < 1e-6);
        assert!((p.lon - 16.3738).abs() < 1e-6);
        assert_eq!(p.ele, Some(171.0));
    }

    #[test]
    fn parse_route() {
        let data = parse_bytes(MINIMAL_GPX.as_bytes()).unwrap();

        assert_eq!(data.routes.len(), 1);
        assert_eq!(data.routes[0].name.as_deref(), Some("Test Route"));
        assert_eq!(data.routes[0].points.len(), 2);
    }

    #[test]
    fn parse_waypoint() {
        let data = parse_bytes(MINIMAL_GPX.as_bytes()).unwrap();

        assert_eq!(data.waypoints.len(), 1);
        assert_eq!(data.waypoints[0].name.as_deref(), Some("Vienna"));
        assert!((data.waypoints[0].point.lat - 48.2082).abs() < 1e-6);
        assert_eq!(data.waypoints[0].icon.as_deref(), Some("fuel"));
    }

    #[test]
    fn parse_to_json_produces_valid_json() {
        let json = parse_to_json(MINIMAL_GPX.as_bytes()).unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();

        assert!(parsed["tracks"].is_array());
        assert!(parsed["routes"].is_array());
        assert!(parsed["waypoints"].is_array());
    }

    #[test]
    fn parse_empty_gpx() {
        let empty = r#"<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test"
     xmlns="http://www.topografix.com/GPX/1/1">
</gpx>"#;

        let data = parse_bytes(empty.as_bytes()).unwrap();
        assert!(data.tracks.is_empty());
        assert!(data.routes.is_empty());
        assert!(data.waypoints.is_empty());
    }

    #[test]
    fn parse_invalid_xml_returns_error() {
        let result = parse_bytes(b"not xml at all");
        assert!(result.is_err());
    }

    #[test]
    fn parse_track_without_elevation() {
        let gpx = r#"<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test"
     xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
      <trkpt lat="48.0" lon="16.0"></trkpt>
    </trkseg>
  </trk>
</gpx>"#;

        let data = parse_bytes(gpx.as_bytes()).unwrap();
        assert_eq!(data.tracks[0].points[0].ele, None);
    }

    #[test]
    fn parse_multi_segment_track() {
        let gpx = r#"<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test"
     xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Multi Segment</name>
    <trkseg>
      <trkpt lat="48.0" lon="16.0"></trkpt>
      <trkpt lat="48.1" lon="16.1"></trkpt>
    </trkseg>
    <trkseg>
      <trkpt lat="48.2" lon="16.2"></trkpt>
      <trkpt lat="48.3" lon="16.3"></trkpt>
    </trkseg>
  </trk>
</gpx>"#;

        let data = parse_bytes(gpx.as_bytes()).unwrap();
        // Multi-segment tracks are flattened into a single point list
        assert_eq!(data.tracks[0].points.len(), 4);
    }
}
