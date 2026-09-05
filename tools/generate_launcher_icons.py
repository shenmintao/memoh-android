"""Generate bitmap fallbacks for OEM readers that don't decode adaptive/vector icons.

Run: uv run --with resvg-py==0.2.6 python tools/generate_launcher_icons.py
The existing official vector remains the source of paths and colors.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

import resvg_py

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
vector = ET.parse(ROOT / "drawable/ic_memoh.xml").getroot()
paths = "".join(
    f'<path fill="{path.attrib[ANDROID + "fillColor"]}" d="{path.attrib[ANDROID + "pathData"]}"/>'
    for path in vector.iter("path")
)
# Square, opaque backdrop; leave 14% around the official mark for OEM masks.
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 454.08 454.08">
<rect width="454.08" height="454.08" fill="white"/>
<g transform="translate(63.5712 63.5712) scale(0.72)"><g transform="translate(0 27.06)">{paths}</g></g>
</svg>'''
for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    target = ROOT / f"mipmap-{density}" / "ic_memoh_launcher.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(resvg_py.svg_to_bytes(svg_string=svg, width=size, height=size))
    print(target.relative_to(ROOT), f"{size}x{size}")
