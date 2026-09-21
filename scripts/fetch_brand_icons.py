#!/usr/bin/env python3
import os
import re
import urllib.request
import xml.etree.ElementTree as ET

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLE_DIR = os.path.join(BASE_DIR, "app", "src", "main", "res", "drawable")

BRANDS = {
    "youtube": {
        "slug": "youtube",
        "target": "ic_brand_youtube.xml",
        "color": "#FF0000"
    },
    "github": {
        "slug": "github",
        "target": "ic_brand_github.xml",
        "color": "#FFFFFF"
    },
    "wikipedia": {
        "slug": "wikipedia",
        "target": "ic_brand_wikipedia.xml",
        "color": "#FFFFFF"
    },
    "facebook": {
        "slug": "facebook",
        "target": "ic_brand_facebook.xml",
        "color": "#1877F2"
    },
    "reddit": {
        "slug": "reddit",
        "target": "ic_brand_reddit.xml",
        "color": "#FF4500"
    }
}

FLOAT_RE = re.compile(r'^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?')

def parse_svg_path(path_str):
    i = 0
    n = len(path_str)
    result = []
    cmd = None

    while i < n:
        while i < n and (path_str[i].isspace() or path_str[i] == ','):
            i += 1
        if i >= n:
            break

        c = path_str[i]
        if c in 'MmLlHhVvCcSsQqTtAaZz':
            result.append(c)
            cmd = c
            i += 1
            continue

        if cmd in 'Aa':
            last_cmd_idx = len(result) - 1
            while last_cmd_idx >= 0 and result[last_cmd_idx] not in 'Aa':
                last_cmd_idx -= 1
            arg_index = (len(result) - 1 - last_cmd_idx) % 7

            if arg_index in (3, 4) and c in '01':
                result.append(c)
                i += 1
                continue

            m = FLOAT_RE.match(path_str[i:])
            if m:
                val = m.group(0)
                result.append(val)
                i += len(val)
            else:
                i += 1
        else:
            m = FLOAT_RE.match(path_str[i:])
            if m:
                val = m.group(0)
                result.append(val)
                i += len(val)
            else:
                i += 1

    return ' '.join(result)

def fetch_and_convert():
    os.makedirs(DRAWABLE_DIR, exist_ok=True)
    print("==> Fetching brand SVGs from simple-icons CDN...")

    for key, info in BRANDS.items():
        slug = info["slug"]
        url = f"https://cdn.jsdelivr.net/npm/simple-icons@v11/icons/{slug}.svg"
        target_file = os.path.join(DRAWABLE_DIR, info["target"])
        color = info["color"]

        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=15) as resp:
                svg_data = resp.read().decode('utf-8')

            tree = ET.fromstring(svg_data)
            paths = []
            for elem in tree.iter():
                tag = elem.tag.split('}')[-1]
                if tag == 'path':
                    d = elem.get('d')
                    if d:
                        paths.append(d)

            if not paths:
                print(f"  [!] No path found for {key}, skipping.")
                continue

            vector_xml = [
                '<?xml version="1.0" encoding="utf-8"?>',
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                '    android:width="24dp"',
                '    android:height="24dp"',
                '    android:viewportWidth="24"',
                '    android:viewportHeight="24">'
            ]

            for p in paths:
                normalized_p = parse_svg_path(p)
                vector_xml.append('    <path')
                vector_xml.append(f'        android:fillColor="{color}"')
                vector_xml.append(f'        android:pathData="{normalized_p}" />')

            vector_xml.append('</vector>\n')

            with open(target_file, 'w', encoding='utf-8') as out_f:
                out_f.write('\n'.join(vector_xml))

            print(f"  [+] Successfully generated {info['target']} ({color})")

        except Exception as e:
            print(f"  [!] Error fetching {key} from {url}: {e}")

if __name__ == '__main__':
    fetch_and_convert()
