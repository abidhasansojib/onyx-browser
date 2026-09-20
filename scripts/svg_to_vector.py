#!/usr/bin/env python3
import os
import sys
import xml.etree.ElementTree as ET

def convert_svg_to_vector(svg_path, out_xml_path):
    tree = ET.parse(svg_path)
    root = tree.getroot()

    paths = []

    for elem in root.iter():
        tag = elem.tag.split('}')[-1]
        if tag == 'path':
            d = elem.get('d')
            if d:
                paths.append(d)
        elif tag == 'line':
            x1, y1 = elem.get('x1'), elem.get('y1')
            x2, y2 = elem.get('x2'), elem.get('y2')
            paths.append(f"M{x1},{y1} L{x2},{y2}")
        elif tag == 'circle':
            cx = float(elem.get('cx', '0'))
            cy = float(elem.get('cy', '0'))
            r = float(elem.get('r', '0'))
            # 2 arcs for circle
            paths.append(f"M{cx},{cy - r} a{r},{r} 0 1,0 0,{2*r} a{r},{r} 0 1,0 0,{-2*r}")
        elif tag == 'rect':
            x = float(elem.get('x', '0'))
            y = float(elem.get('y', '0'))
            w = float(elem.get('width', '0'))
            h = float(elem.get('height', '0'))
            rx = float(elem.get('rx', '0'))
            if rx > 0:
                paths.append(
                    f"M{x + rx},{y} h{w - 2*rx} a{rx},{rx} 0 0,1 {rx},{rx} v{h - 2*rx} a{rx},{rx} 0 0,1 {-rx},{rx} h{-(w - 2*rx)} a{rx},{rx} 0 0,1 {-rx},{-rx} v{-(h - 2*rx)} a{rx},{rx} 0 0,1 {rx},{-rx} z"
                )
            else:
                paths.append(f"M{x},{y} h{w} v{h} h{-w} z")
        elif tag == 'polyline':
            points = elem.get('points', '').strip().split()
            if points:
                first = points[0].replace(',', ' ')
                rest = ' '.join(f"L{p.replace(',', ' ')}" for p in points[1:])
                paths.append(f"M{first} {rest}")

    xml_content = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
    ]

    for p in paths:
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
