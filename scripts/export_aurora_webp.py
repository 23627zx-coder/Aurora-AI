#!/usr/bin/env python3
"""将 aurora-mark.svg 中的 #ribbon-left 与 #ribbon-right 各自导出为透明背景高分辨率 WebP"""
import copy, subprocess, tempfile, xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image  # 仅构建期离线导出使用: pip install pillow cairosvg

SVG_SRC = Path("aurora-mark.svg")
OUT_DIR = Path("app/src/main/res/drawable-nodpi")
TARGET_PX = 1080  # 对应 viewBox="332 -46 900 900" 的高分辨率输出


def export_layer(keep_id: str, out_name: str):
    tree = ET.parse(SVG_SRC)
    root = tree.getroot()
    ns = {"svg": "http://www.w3.org/2000/svg"}
    ET.register_namespace("", "http://www.w3.org/2000/svg")

    mark_group = root.find(".//svg:g[@id='mark']", ns)
    for child in list(mark_group):
        if child.attrib.get("id") != keep_id:
            mark_group.remove(child)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix=".svg", delete=False) as tmp_svg, \
         tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp_png:
        tree.write(tmp_svg.name, encoding="utf-8", xml_declaration=True)
        # 优先使用 cairosvg，亦可替换为 inkscape / rsvg-convert
        import cairosvg
        cairosvg.svg2png(
            url=tmp_svg.name,
            write_to=tmp_png.name,
            output_width=TARGET_PX,
            output_height=TARGET_PX,
        )
        Image.open(tmp_png.name).convert("RGBA").save(
            OUT_DIR / out_name, format="WEBP", lossless=True, quality=100, method=6
        )


if __name__ == "__main__":
    export_layer("ribbon-left", "aurora_ribbon_left.webp")
    export_layer("ribbon-right", "aurora_ribbon_right.webp")
