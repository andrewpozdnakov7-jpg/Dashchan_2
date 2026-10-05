"""Expose the native source pins from gradle.properties to GitHub Actions."""

from pathlib import Path
import re


pins = {
    "androidNdkVersion": "ANDROID_NDK_VERSION",
    "nativeDav1dVersion": "DAV1D_VERSION",
    "nativeFfmpegVersion": "FFMPEG_VERSION",
}
properties = {}
for line in Path("gradle.properties").read_text(encoding="utf-8").splitlines():
    key, separator, value = line.partition("=")
    if separator and key in pins:
        if key in properties or not re.fullmatch(r"[A-Za-z0-9.]+", value):
            raise SystemExit(f"Invalid native version: {key}")
        properties[key] = value

for key, env_name in pins.items():
    if key not in properties:
        raise SystemExit(f"Missing native version: {key}")
    print(f"{env_name}={properties[key]}")
