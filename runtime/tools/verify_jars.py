"""Check built Kite jars for corruption and version metadata mismatches.

Run after Gradle builds: python runtime/tools/verify_jars.py
This validates packaging; use runAudit/runAutotest to check game behavior.
"""

from pathlib import Path
import argparse
import json
import zipfile


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_VERSIONS = (
    "1.7.10", "1.8.9", "1.12.2", "1.16.5", "1.17.1", "1.18.2",
    "1.19", "1.19.2", "1.19.3", "1.19.4",
    "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
    "1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
    "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2", "26.2", "26.3",
)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--all-requested", action="store_true", help="fail if any requested Minecraft version lacks a jar")
args = parser.parse_args()
JARS = sorted(ROOT.glob("v*/build/libs/kite-*.jar"))
JARS += sorted(ROOT.glob("legacy/v*/build/libs/kite-*.jar"))

failures = []
found_versions = set()
for jar in JARS:
    try:
        with zipfile.ZipFile(jar) as archive:
            bad_member = archive.testzip()
            if bad_member:
                raise ValueError(f"bad CRC: {bad_member}")
            metadata = json.loads(archive.read("fabric.mod.json"))
            mc = metadata["depends"]["minecraft"]
            if not jar.name.startswith(f"kite-{mc}-"):
                raise ValueError(f"Minecraft metadata {mc} does not match filename")
            if "gg/kite/mods/BuiltinMods.class" not in archive.namelist():
                raise ValueError("implemented mod set is missing")
            found_versions.add(mc)
            print(f"OK {mc}: {jar.relative_to(ROOT)}")
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        failures.append(f"{jar}: {error}")

if not JARS:
    failures.append("No Kite jars found")
if args.all_requested:
    for mc in REQUIRED_VERSIONS:
        if mc not in found_versions:
            failures.append(f"Minecraft {mc}: no verified jar")
for failure in failures:
    print(f"FAIL {failure}")
print(f"Checked {len(JARS)} jars; {len(failures)} failed")
raise SystemExit(bool(failures))
