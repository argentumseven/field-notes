// Original PCM encoded losslessly; v1 WAV previews stay byte-for-byte compatible.
pub fn get(name: &str) -> Option<&'static [u8]> {
    match name {
        "alarm_beacon_100.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_beacon_100.wav")),
        "alarm_beacon_25.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_beacon_25.wav")),
        "alarm_beacon_50.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_beacon_50.wav")),
        "alarm_beacon_75.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_beacon_75.wav")),
        "alarm_bell_100.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_bell_100.wav")),
        "alarm_bell_25.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_bell_25.wav")),
        "alarm_bell_50.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_bell_50.wav")),
        "alarm_bell_75.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_bell_75.wav")),
        "alarm_chime_100.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_chime_100.wav")),
        "alarm_chime_25.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_chime_25.wav")),
        "alarm_chime_50.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_chime_50.wav")),
        "alarm_chime_75.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_chime_75.wav")),
        "alarm_marimba_100.wav" => {
            Some(include_bytes!("../assets/sounds/v1/alarm_marimba_100.wav"))
        }
        "alarm_marimba_25.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_marimba_25.wav")),
        "alarm_marimba_50.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_marimba_50.wav")),
        "alarm_marimba_75.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_marimba_75.wav")),
        "alarm_orbit_100.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_orbit_100.wav")),
        "alarm_orbit_25.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_orbit_25.wav")),
        "alarm_orbit_50.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_orbit_50.wav")),
        "alarm_orbit_75.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_orbit_75.wav")),
        "alarm_pulse_100.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_pulse_100.wav")),
        "alarm_pulse_25.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_pulse_25.wav")),
        "alarm_pulse_50.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_pulse_50.wav")),
        "alarm_pulse_75.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_pulse_75.wav")),
        "alarm_siren_100.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_siren_100.wav")),
        "alarm_siren_25.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_siren_25.wav")),
        "alarm_siren_50.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_siren_50.wav")),
        "alarm_siren_75.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_siren_75.wav")),
        "alarm_sunrise_100.wav" => {
            Some(include_bytes!("../assets/sounds/v1/alarm_sunrise_100.wav"))
        }
        "alarm_sunrise_25.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_sunrise_25.wav")),
        "alarm_sunrise_50.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_sunrise_50.wav")),
        "alarm_sunrise_75.wav" => Some(include_bytes!("../assets/sounds/v1/alarm_sunrise_75.wav")),
        "reminder_beacon_100.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_beacon_100.wav"
        )),
        "reminder_beacon_25.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_beacon_25.wav"))
        }
        "reminder_beacon_50.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_beacon_50.wav"))
        }
        "reminder_beacon_75.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_beacon_75.wav"))
        }
        "reminder_bell_100.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_bell_100.wav"))
        }
        "reminder_bell_25.wav" => Some(include_bytes!("../assets/sounds/v1/reminder_bell_25.wav")),
        "reminder_bell_50.wav" => Some(include_bytes!("../assets/sounds/v1/reminder_bell_50.wav")),
        "reminder_bell_75.wav" => Some(include_bytes!("../assets/sounds/v1/reminder_bell_75.wav")),
        "reminder_chime_100.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_chime_100.wav"))
        }
        "reminder_chime_25.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_chime_25.wav"))
        }
        "reminder_chime_50.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_chime_50.wav"))
        }
        "reminder_chime_75.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_chime_75.wav"))
        }
        "reminder_marimba_100.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_marimba_100.wav"
        )),
        "reminder_marimba_25.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_marimba_25.wav"
        )),
        "reminder_marimba_50.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_marimba_50.wav"
        )),
        "reminder_marimba_75.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_marimba_75.wav"
        )),
        "reminder_orbit_100.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_orbit_100.wav"))
        }
        "reminder_orbit_25.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_orbit_25.wav"))
        }
        "reminder_orbit_50.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_orbit_50.wav"))
        }
        "reminder_orbit_75.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_orbit_75.wav"))
        }
        "reminder_pulse_100.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_pulse_100.wav"))
        }
        "reminder_pulse_25.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_pulse_25.wav"))
        }
        "reminder_pulse_50.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_pulse_50.wav"))
        }
        "reminder_pulse_75.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_pulse_75.wav"))
        }
        "reminder_siren_100.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_siren_100.wav"))
        }
        "reminder_siren_25.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_siren_25.wav"))
        }
        "reminder_siren_50.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_siren_50.wav"))
        }
        "reminder_siren_75.wav" => {
            Some(include_bytes!("../assets/sounds/v1/reminder_siren_75.wav"))
        }
        "reminder_sunrise_100.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_sunrise_100.wav"
        )),
        "reminder_sunrise_25.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_sunrise_25.wav"
        )),
        "reminder_sunrise_50.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_sunrise_50.wav"
        )),
        "reminder_sunrise_75.wav" => Some(include_bytes!(
            "../assets/sounds/v1/reminder_sunrise_75.wav"
        )),
        _ => None,
    }
}
pub fn lossless(name: &str) -> Option<&'static [u8]> {
    match name {
        "alarm_beacon_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_100.flac"
        )),
        "alarm_beacon_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_125.flac"
        )),
        "alarm_beacon_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_150.flac"
        )),
        "alarm_beacon_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_200.flac"
        )),
        "alarm_beacon_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_25.flac"
        )),
        "alarm_beacon_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_50.flac"
        )),
        "alarm_beacon_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_75.flac"
        )),
        "alarm_bell_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_100.flac"
        )),
        "alarm_bell_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_125.flac"
        )),
        "alarm_bell_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_150.flac"
        )),
        "alarm_bell_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_200.flac"
        )),
        "alarm_bell_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_25.flac"
        )),
        "alarm_bell_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_50.flac"
        )),
        "alarm_bell_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_75.flac"
        )),
        "alarm_chime_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_100.flac"
        )),
        "alarm_chime_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_125.flac"
        )),
        "alarm_chime_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_150.flac"
        )),
        "alarm_chime_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_200.flac"
        )),
        "alarm_chime_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_25.flac"
        )),
        "alarm_chime_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_50.flac"
        )),
        "alarm_chime_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_75.flac"
        )),
        "alarm_crystal_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_crystal_100.flac"
        )),
        "alarm_crystal_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_crystal_125.flac"
        )),
        "alarm_crystal_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_crystal_150.flac"
        )),
        "alarm_crystal_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_crystal_200.flac"
        )),
        "alarm_crystal_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_crystal_25.flac"
        )),
        "alarm_crystal_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_crystal_50.flac"
        )),
        "alarm_crystal_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_crystal_75.flac"
        )),
        "alarm_klaxon_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_klaxon_100.flac"
        )),
        "alarm_klaxon_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_klaxon_125.flac"
        )),
        "alarm_klaxon_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_klaxon_150.flac"
        )),
        "alarm_klaxon_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_klaxon_200.flac"
        )),
        "alarm_klaxon_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_klaxon_25.flac"
        )),
        "alarm_klaxon_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_klaxon_50.flac"
        )),
        "alarm_klaxon_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_klaxon_75.flac"
        )),
        "alarm_marimba_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_100.flac"
        )),
        "alarm_marimba_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_125.flac"
        )),
        "alarm_marimba_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_150.flac"
        )),
        "alarm_marimba_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_200.flac"
        )),
        "alarm_marimba_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_25.flac"
        )),
        "alarm_marimba_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_50.flac"
        )),
        "alarm_marimba_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_75.flac"
        )),
        "alarm_orbit_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_100.flac"
        )),
        "alarm_orbit_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_125.flac"
        )),
        "alarm_orbit_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_150.flac"
        )),
        "alarm_orbit_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_200.flac"
        )),
        "alarm_orbit_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_25.flac"
        )),
        "alarm_orbit_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_50.flac"
        )),
        "alarm_orbit_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_75.flac"
        )),
        "alarm_pulse_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_100.flac"
        )),
        "alarm_pulse_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_125.flac"
        )),
        "alarm_pulse_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_150.flac"
        )),
        "alarm_pulse_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_200.flac"
        )),
        "alarm_pulse_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_25.flac"
        )),
        "alarm_pulse_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_50.flac"
        )),
        "alarm_pulse_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_75.flac"
        )),
        "alarm_radar_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_radar_100.flac"
        )),
        "alarm_radar_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_radar_125.flac"
        )),
        "alarm_radar_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_radar_150.flac"
        )),
        "alarm_radar_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_radar_200.flac"
        )),
        "alarm_radar_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_radar_25.flac"
        )),
        "alarm_radar_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_radar_50.flac"
        )),
        "alarm_radar_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_radar_75.flac"
        )),
        "alarm_signal_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_signal_100.flac"
        )),
        "alarm_signal_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_signal_125.flac"
        )),
        "alarm_signal_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_signal_150.flac"
        )),
        "alarm_signal_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_signal_200.flac"
        )),
        "alarm_signal_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_signal_25.flac"
        )),
        "alarm_signal_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_signal_50.flac"
        )),
        "alarm_signal_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_signal_75.flac"
        )),
        "alarm_siren_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_100.flac"
        )),
        "alarm_siren_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_125.flac"
        )),
        "alarm_siren_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_150.flac"
        )),
        "alarm_siren_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_200.flac"
        )),
        "alarm_siren_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_25.flac"
        )),
        "alarm_siren_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_50.flac"
        )),
        "alarm_siren_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_75.flac"
        )),
        "alarm_sunrise_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_100.flac"
        )),
        "alarm_sunrise_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_125.flac"
        )),
        "alarm_sunrise_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_150.flac"
        )),
        "alarm_sunrise_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_200.flac"
        )),
        "alarm_sunrise_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_25.flac"
        )),
        "alarm_sunrise_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_50.flac"
        )),
        "alarm_sunrise_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_75.flac"
        )),
        "reminder_beacon_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_100.flac"
        )),
        "reminder_beacon_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_125.flac"
        )),
        "reminder_beacon_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_150.flac"
        )),
        "reminder_beacon_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_200.flac"
        )),
        "reminder_beacon_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_25.flac"
        )),
        "reminder_beacon_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_50.flac"
        )),
        "reminder_beacon_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_75.flac"
        )),
        "reminder_bell_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_100.flac"
        )),
        "reminder_bell_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_125.flac"
        )),
        "reminder_bell_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_150.flac"
        )),
        "reminder_bell_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_200.flac"
        )),
        "reminder_bell_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_25.flac"
        )),
        "reminder_bell_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_50.flac"
        )),
        "reminder_bell_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_75.flac"
        )),
        "reminder_chime_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_100.flac"
        )),
        "reminder_chime_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_125.flac"
        )),
        "reminder_chime_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_150.flac"
        )),
        "reminder_chime_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_200.flac"
        )),
        "reminder_chime_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_25.flac"
        )),
        "reminder_chime_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_50.flac"
        )),
        "reminder_chime_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_75.flac"
        )),
        "reminder_crystal_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_crystal_100.flac"
        )),
        "reminder_crystal_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_crystal_125.flac"
        )),
        "reminder_crystal_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_crystal_150.flac"
        )),
        "reminder_crystal_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_crystal_200.flac"
        )),
        "reminder_crystal_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_crystal_25.flac"
        )),
        "reminder_crystal_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_crystal_50.flac"
        )),
        "reminder_crystal_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_crystal_75.flac"
        )),
        "reminder_klaxon_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_klaxon_100.flac"
        )),
        "reminder_klaxon_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_klaxon_125.flac"
        )),
        "reminder_klaxon_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_klaxon_150.flac"
        )),
        "reminder_klaxon_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_klaxon_200.flac"
        )),
        "reminder_klaxon_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_klaxon_25.flac"
        )),
        "reminder_klaxon_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_klaxon_50.flac"
        )),
        "reminder_klaxon_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_klaxon_75.flac"
        )),
        "reminder_marimba_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_100.flac"
        )),
        "reminder_marimba_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_125.flac"
        )),
        "reminder_marimba_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_150.flac"
        )),
        "reminder_marimba_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_200.flac"
        )),
        "reminder_marimba_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_25.flac"
        )),
        "reminder_marimba_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_50.flac"
        )),
        "reminder_marimba_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_75.flac"
        )),
        "reminder_orbit_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_100.flac"
        )),
        "reminder_orbit_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_125.flac"
        )),
        "reminder_orbit_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_150.flac"
        )),
        "reminder_orbit_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_200.flac"
        )),
        "reminder_orbit_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_25.flac"
        )),
        "reminder_orbit_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_50.flac"
        )),
        "reminder_orbit_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_75.flac"
        )),
        "reminder_pulse_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_100.flac"
        )),
        "reminder_pulse_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_125.flac"
        )),
        "reminder_pulse_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_150.flac"
        )),
        "reminder_pulse_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_200.flac"
        )),
        "reminder_pulse_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_25.flac"
        )),
        "reminder_pulse_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_50.flac"
        )),
        "reminder_pulse_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_75.flac"
        )),
        "reminder_radar_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_radar_100.flac"
        )),
        "reminder_radar_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_radar_125.flac"
        )),
        "reminder_radar_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_radar_150.flac"
        )),
        "reminder_radar_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_radar_200.flac"
        )),
        "reminder_radar_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_radar_25.flac"
        )),
        "reminder_radar_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_radar_50.flac"
        )),
        "reminder_radar_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_radar_75.flac"
        )),
        "reminder_signal_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_signal_100.flac"
        )),
        "reminder_signal_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_signal_125.flac"
        )),
        "reminder_signal_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_signal_150.flac"
        )),
        "reminder_signal_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_signal_200.flac"
        )),
        "reminder_signal_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_signal_25.flac"
        )),
        "reminder_signal_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_signal_50.flac"
        )),
        "reminder_signal_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_signal_75.flac"
        )),
        "reminder_siren_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_100.flac"
        )),
        "reminder_siren_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_125.flac"
        )),
        "reminder_siren_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_150.flac"
        )),
        "reminder_siren_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_200.flac"
        )),
        "reminder_siren_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_25.flac"
        )),
        "reminder_siren_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_50.flac"
        )),
        "reminder_siren_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_75.flac"
        )),
        "reminder_sunrise_100.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_100.flac"
        )),
        "reminder_sunrise_125.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_125.flac"
        )),
        "reminder_sunrise_150.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_150.flac"
        )),
        "reminder_sunrise_200.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_200.flac"
        )),
        "reminder_sunrise_25.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_25.flac"
        )),
        "reminder_sunrise_50.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_50.flac"
        )),
        "reminder_sunrise_75.flac" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_75.flac"
        )),
        _ => None,
    }
}
#[cfg(test)]
mod tests {
    #[test]
    fn notification_cues_are_brief_and_alarm_audio_is_distinct() {
        for tone in [
            "chime", "bell", "pulse", "beacon", "marimba", "orbit", "sunrise", "siren",
        ] {
            for level in [25, 50, 75, 100] {
                let cue = super::get(&format!("reminder_{tone}_{level}.wav")).unwrap();
                let alarm = super::get(&format!("alarm_{tone}_{level}.wav")).unwrap();
                assert_eq!(&cue[..4], b"RIFF");
                assert_eq!(&alarm[..4], b"RIFF");
                assert!(cue.len() < 44 + 22050 * 2);
                assert!(alarm.len() >= 44 + 22050 * 2 * 3);
                assert_ne!(cue, alarm);
            }
        }
        assert!(super::get("../alarm_siren_100.wav").is_none());
    }

    fn samples(wav: &[u8]) -> Vec<i32> {
        wav[44..]
            .chunks_exact(2)
            .map(|pair| i16::from_le_bytes([pair[0], pair[1]]) as i32)
            .collect()
    }

    /// Alarms repeat for their whole ring time. Each clip must therefore be a whole number of its
    /// own phrases and join its own start without a jump, or every repeat would tick or stumble.
    #[test]
    fn alarm_clips_repeat_without_a_seam() {
        for (tone, period) in [
            ("chime", 1.5),
            ("bell", 1.5),
            ("pulse", 1.5),
            ("beacon", 1.5),
            ("marimba", 1.5),
            ("orbit", 2.0),
            ("sunrise", 1.5),
            ("siren", 1.25),
        ] {
            for level in [25, 50, 75, 100] {
                let clip = samples(super::get(&format!("alarm_{tone}_{level}.wav")).unwrap());
                let phrases = clip.len() as f64 / 22050.0 / period;
                assert!(
                    (phrases - phrases.round()).abs() < 1e-6,
                    "{tone} is {phrases} phrases long"
                );
                let largest_step = clip
                    .windows(2)
                    .map(|pair| (pair[1] - pair[0]).abs())
                    .max()
                    .unwrap();
                let seam = (clip[0] - clip[clip.len() - 1]).abs();
                assert!(
                    seam <= largest_step,
                    "{tone}@{level}: seam step {seam} > {largest_step}"
                );
                assert!(
                    clip[0].abs() <= largest_step,
                    "{tone}@{level} starts away from silence"
                );
            }
        }
    }
}

#[cfg(test)]
mod lossless_tests {
    #[test]
    fn all_original_tones_and_boost_levels_have_valid_lossless_stream_info() {
        for tone in [
            "chime", "bell", "pulse", "beacon", "marimba", "orbit", "sunrise", "siren", "signal",
            "crystal", "radar", "klaxon",
        ] {
            for level in [25, 50, 75, 100, 125, 150, 200] {
                for prefix in ["alarm", "reminder"] {
                    let bytes = super::lossless(&format!("{prefix}_{tone}_{level}.flac")).unwrap();
                    assert_eq!(&bytes[..4], b"fLaC");
                    let info = u64::from_be_bytes(bytes[18..26].try_into().unwrap());
                    assert_eq!(info >> 44, 22050);
                    assert_eq!((info >> 41) & 7, 0);
                    assert_eq!((info >> 36) & 31, 15);
                    let samples = info & 0xfffffffff;
                    if prefix == "alarm" {
                        assert!(samples >= 22050 * 3)
                    } else {
                        assert!(samples < 22050)
                    }
                }
            }
        }
        assert!(super::lossless("../anything.flac").is_none());
    }
}
