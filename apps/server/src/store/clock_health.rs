use super::*;
use std::io::Read;
impl Store {
    pub fn clock_health(&self) -> Value {
        let read = || -> Option<Value> {
            let path = self.peer_status.as_ref()?.parent()?.join("clock.json");
            let file = std::fs::File::open(path).ok()?;
            if file.metadata().ok()?.len() > 8192 {
                return None;
            }
            let mut raw = String::new();
            file.take(8193).read_to_string(&mut raw).ok()?;
            serde_json::from_str(&raw).ok()
        };
        let Some(mut v) = read() else {
            return json!({"status":"unknown","source":"unavailable"});
        };
        let boot = std::fs::read_to_string("/proc/sys/kernel/random/boot_id").unwrap_or_default();
        let uptime = std::fs::read_to_string("/proc/uptime")
            .ok()
            .and_then(|s| s.split_whitespace().next()?.parse::<f64>().ok());
        let age = uptime.and_then(|u| Some(u - v["captured_uptime"].as_f64()?));
        let wall = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs_f64();
        let status = if v["version"] != 1
            || v["boot_id"].as_str() != Some(boot.trim())
            || age.is_none_or(|a| !(0.0..=120.0).contains(&a))
        {
            "stale"
        } else if v["captured_at"]
            .as_f64()
            .is_none_or(|t| (wall - t - age.unwrap_or_default()).abs() > 5.0)
        {
            "clock_jump"
        } else {
            match v["status"].as_str() {
                Some("synchronized") => "synchronized",
                Some("unsynchronized") => "unsynchronized",
                _ => "unknown",
            }
        };
        v["status"] = status.into();
        if let Some(o) = v.as_object_mut() {
            o.remove("boot_id");
            o.remove("captured_uptime");
        }
        v
    }
}
