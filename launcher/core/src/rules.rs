//! Evaluation of Mojang `rules` arrays (OS / arch / feature gating).
use serde::Deserialize;
use std::collections::HashMap;

#[derive(Debug, Clone, Deserialize)]
pub struct Rule {
    pub action: String,
    #[serde(default)]
    pub os: Option<OsRule>,
    #[serde(default)]
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct OsRule {
    pub name: Option<String>,
    pub arch: Option<String>,
}

pub fn current_os() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}

pub fn current_arch() -> &'static str {
    if cfg!(target_arch = "x86") {
        "x86"
    } else if cfg!(target_arch = "aarch64") {
        "arm64"
    } else {
        "x86_64"
    }
}

/// Mojang semantics: no rules => allowed; last matching rule wins; default disallow once rules exist.
pub fn allowed(rules: &[Rule], features: &HashMap<String, bool>) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut result = false;
    for r in rules {
        if matches(r, features) {
            result = r.action == "allow";
        }
    }
    result
}

fn matches(r: &Rule, features: &HashMap<String, bool>) -> bool {
    if let Some(os) = &r.os {
        if let Some(n) = &os.name {
            if n != current_os() {
                return false;
            }
        }
        if let Some(a) = &os.arch {
            if a != current_arch() {
                return false;
            }
        }
    }
    if let Some(f) = &r.features {
        for (k, v) in f {
            if features.get(k).copied().unwrap_or(false) != *v {
                return false;
            }
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rules(json: &str) -> Vec<Rule> {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn empty_is_allowed() {
        assert!(allowed(&[], &HashMap::new()));
    }

    #[test]
    fn os_specific_rule() {
        let other = if current_os() == "windows" { "linux" } else { "windows" };
        let r = rules(&format!(r#"[{{"action":"allow","os":{{"name":"{other}"}}}}]"#));
        assert!(!allowed(&r, &HashMap::new()));
        let r = rules(&format!(
            r#"[{{"action":"allow"}},{{"action":"disallow","os":{{"name":"{}"}}}}]"#,
            current_os()
        ));
        assert!(!allowed(&r, &HashMap::new()));
    }

    #[test]
    fn feature_rule() {
        let r = rules(r#"[{"action":"allow","features":{"is_demo_user":true}}]"#);
        assert!(!allowed(&r, &HashMap::new()));
        let mut f = HashMap::new();
        f.insert("is_demo_user".to_string(), true);
        assert!(allowed(&r, &f));
    }
}
