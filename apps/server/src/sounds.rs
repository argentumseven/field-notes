//! Bundled original tones; fixed names only, without filesystem or outbound lookups.
pub fn get(name: &str) -> Option<&'static [u8]> {
    match name {
        "reminder_chime_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_25.wav"
        )),
        "reminder_chime_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_50.wav"
        )),
        "reminder_chime_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_75.wav"
        )),
        "reminder_chime_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_chime_100.wav"
        )),
        "reminder_bell_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_25.wav"
        )),
        "reminder_bell_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_50.wav"
        )),
        "reminder_bell_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_75.wav"
        )),
        "reminder_bell_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_bell_100.wav"
        )),
        "reminder_pulse_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_25.wav"
        )),
        "reminder_pulse_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_50.wav"
        )),
        "reminder_pulse_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_75.wav"
        )),
        "reminder_pulse_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_pulse_100.wav"
        )),
        "reminder_beacon_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_25.wav"
        )),
        "reminder_beacon_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_50.wav"
        )),
        "reminder_beacon_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_75.wav"
        )),
        "reminder_beacon_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_beacon_100.wav"
        )),
        "reminder_marimba_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_25.wav"
        )),
        "reminder_marimba_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_50.wav"
        )),
        "reminder_marimba_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_75.wav"
        )),
        "reminder_marimba_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_marimba_100.wav"
        )),
        "reminder_orbit_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_25.wav"
        )),
        "reminder_orbit_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_50.wav"
        )),
        "reminder_orbit_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_75.wav"
        )),
        "reminder_orbit_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_orbit_100.wav"
        )),
        "reminder_sunrise_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_25.wav"
        )),
        "reminder_sunrise_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_50.wav"
        )),
        "reminder_sunrise_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_75.wav"
        )),
        "reminder_sunrise_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_sunrise_100.wav"
        )),
        "reminder_siren_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_25.wav"
        )),
        "reminder_siren_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_50.wav"
        )),
        "reminder_siren_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_75.wav"
        )),
        "reminder_siren_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/reminder_siren_100.wav"
        )),
        _ => None,
    }
}
