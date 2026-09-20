#!/usr/bin/env python3
import os
import sys
import xml.etree.ElementTree as ET

# Known rock-solid standard vector path overrides for icons with intricate geometry
ICON_OVERRIDES = {
    "ic_more_vert": [
        ("M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z", True)
    ],
    "ic_share": [
        ("M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92c0,-1.61 -1.31,-2.92 -2.92,-2.92z", True)
    ]
}

def convert_svg_to_vector(svg_path, out_xml_path):
    base_name = os.path.splitext(os.path.basename(svg_path))[0]
    
    if base_name in ICON_OVERRIDES:
        paths = ICON_OVERRIDES[base_name]
    else:
        tree = ET.parse(svg_path)
        root = tree.getroot()
        paths = []

        for elem in root.iter():
            tag = elem.tag.split('}')[-1]
            if tag == 'path':
                d = elem.get('d')
                if d:
                    paths.append((d, False))
            elif tag == 'line':
                x1, y1 = elem.get('x1'), elem.get('y1')
                x2, y2 = elem.get('x2'), elem.get('y2')
                paths.append((f"M{x1},{y1} L{x2},{y2}", False))
            elif tag == 'circle':
                cx = float(elem.get('cx', '0'))
                cy = float(elem.get('cy', '0'))
                r = float(elem.get('r', '0'))
                # 2 arcs for circle
                paths.append((f"M{cx},{cy - r} a{r},{r} 0 1,0 0,{2*r} a{r},{r} 0 1,0 0,{-2*r}", False))
            elif tag == 'rect':
                x = float(elem.get('x', '0'))
                y = float(elem.get('y', '0'))
                w = float(elem.get('width', '0'))
                h = float(elem.get('height', '0'))
                rx = float(elem.get('rx', '0'))
                if rx > 0:
                    paths.append((
                        f"M{x + rx},{y} h{w - 2*rx} a{rx},{rx} 0 0,1 {rx},{rx} v{h - 2*rx} a{rx},{rx} 0 0,1 {-rx},{rx} h{-(w - 2*rx)} a{rx},{rx} 0 0,1 {-rx},{-rx} v{-(h - 2*rx)} a{rx},{rx} 0 0,1 {rx},{-rx} z",
                        False
                    ))
                else:
                    paths.append((f"M{x},{y} h{w} v{h} h{-w} z", False))
            elif tag == 'polyline':
                points = elem.get('points', '').strip().split()
                if points:
                    first = points[0].replace(',', ' ')
                    rest = ' '.join(f"L{p.replace(',', ' ')}" for p in points[1:])
                    paths.append((f"M{first} {rest}", False))

    if not paths:
        print(f"Warning: No valid paths found in {svg_path}, skipping write.")
        return

    xml_content = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
    ]

    for p, is_filled in paths:
        if is_filled:
            xml_content.append('    <path')
            xml_content.append('        android:fillColor="#FFFFFFFF"')
            xml_content.append(f'        android:pathData="{p}" />')
        else:
            xml_content.append('    <path')
            xml_content.append('        android:fillColor="#00000000"')
            xml_content.append('        android:strokeColor="?attr/colorControlNormal"')
            xml_content.append('        android:strokeWidth="2"')
            xml_content.append('        android:strokeLineCap="round"')
            xml_content.append('        android:strokeLineJoin="round"')
            xml_content.append(f'        android:pathData="{p}" />')

    xml_content.append('</vector>\n')

    with open(out_xml_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(xml_content))

if __name__ == '__main__':
    in_dir = sys.argv[1]
    out_dir = sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for f in os.listdir(in_dir):
        if f.endswith('.svg'):
            name = os.path.splitext(f)[0]
            convert_svg_to_vector(os.path.join(in_dir, f), os.path.join(out_dir, f"{name}.xml"))
    print(f"Converted SVG files from {in_dir} to {out_dir}")
