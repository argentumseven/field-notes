//! Original bundled notification cues and separate alarm phrases. No filesystem or network lookups.
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
        "alarm_chime_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_25.wav"
        )),
        "alarm_chime_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_50.wav"
        )),
        "alarm_chime_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_75.wav"
        )),
        "alarm_chime_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_chime_100.wav"
        )),
        "alarm_bell_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_25.wav"
        )),
        "alarm_bell_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_50.wav"
        )),
        "alarm_bell_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_75.wav"
        )),
        "alarm_bell_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_bell_100.wav"
        )),
        "alarm_pulse_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_25.wav"
        )),
        "alarm_pulse_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_50.wav"
        )),
        "alarm_pulse_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_75.wav"
        )),
        "alarm_pulse_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_pulse_100.wav"
        )),
        "alarm_beacon_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_25.wav"
        )),
        "alarm_beacon_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_50.wav"
        )),
        "alarm_beacon_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_75.wav"
        )),
        "alarm_beacon_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_beacon_100.wav"
        )),
        "alarm_marimba_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_25.wav"
        )),
        "alarm_marimba_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_50.wav"
        )),
        "alarm_marimba_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_75.wav"
        )),
        "alarm_marimba_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_marimba_100.wav"
        )),
        "alarm_orbit_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_25.wav"
        )),
        "alarm_orbit_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_50.wav"
        )),
        "alarm_orbit_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_75.wav"
        )),
        "alarm_orbit_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_orbit_100.wav"
        )),
        "alarm_sunrise_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_25.wav"
        )),
        "alarm_sunrise_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_50.wav"
        )),
        "alarm_sunrise_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_75.wav"
        )),
        "alarm_sunrise_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_sunrise_100.wav"
        )),
        "alarm_siren_25.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_25.wav"
        )),
        "alarm_siren_50.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_50.wav"
        )),
        "alarm_siren_75.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_75.wav"
        )),
        "alarm_siren_100.wav" => Some(include_bytes!(
            "../../mobile/src/androidMain/res/raw/alarm_siren_100.wav"
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
                let largest_step = clip.windows(2).map(|pair| (pair[1] - pair[0]).abs()).max().unwrap();
                let seam = (clip[0] - clip[clip.len() - 1]).abs();
                assert!(seam <= largest_step, "{tone}@{level}: seam step {seam} > {largest_step}");
                assert!(clip[0].abs() <= largest_step, "{tone}@{level} starts away from silence");
            }
        }
    }
}
