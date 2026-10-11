#!/usr/bin/env python3
"""Opt-in helper UI acceptance against an already installed target; never clicks repair/install."""
import argparse
import json
import os
import pathlib
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as E
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='ernest.nl2sh.helper')
parser.add_argument('--target-host', required=True)
parser.add_argument('--adb-port', type=int, required=True)
parser.add_argument('--profile', required=True, help='Label for externally configured screen size, font and orientation')
parser.add_argument('--output', type=pathlib.Path, required=True)
parser.add_argument('--disposable-controller', action='store_true', required=True)
args = parser.parse_args()
if not 1 <= args.adb_port <= 65535:
    parser.error('ADB port must be 1–65535')
if not re.fullmatch('[A-Za-z0-9_.]+', args.package):
    parser.error('Invalid helper package')
if not re.fullmatch('[A-Za-z0-9_-]+', args.profile):
    parser.error('Profile is a simple artifact label')
folder = args.output.resolve()
folder.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(folder, 0o700)
adb = ['adb', '-s', args.serial]
pkg = args.package

def shell(*cmd):
    return subprocess.check_output(adb + ['shell', shlex.join(map(str, cmd))], text=True, timeout=30)

def dump():
    shell('uiautomator', 'dump', '/data/local/tmp/helper-acceptance.xml')
    return E.fromstring(shell('cat', '/data/local/tmp/helper-acceptance.xml'))

def bounds(n):
    return list(map(int, re.findall('\\d+', n.get('bounds', ''))))

def tap(n):
    a, b, c, d = bounds(n)
    shell('input', 'tap', (a + c) // 2, (b + d) // 2)

def find(root, text):
    return next((n for n in root.iter('node') if n.get('text') == text), None)

def scroll(root):
    n = max((n for n in root.iter('node') if n.get('scrollable') == 'true'), key=lambda n: bounds(n)[3] - bounds(n)[1])
    a, b, c, d = bounds(n)
    start = d - 75
    end = max(b + 75, start - 320)
    shell('input', 'swipe', c - 35, start, c - 35, end, 350)

def search(text):
    for _ in range(30):
        root = dump()
        n = find(root, text)
        if n is not None:
            return n
        scroll(root)
    raise RuntimeError('Required visible control missing: ' + text)
profile = args.profile
shell('am', 'force-stop', pkg)
shell('am', 'start', '-n', pkg + '/ernest.nl2sh.helper.MainActivity')
time.sleep(1)
tap(search('TCP'))
for label, value in [('目标设备地址', args.target_host), ('ADB 端口', str(args.adb_port))]:
    search(label)
    root = dump()
    n = next((n for n in root.iter('node') if n.get('class') == 'android.widget.EditText' and any((c.get('text') == label for c in n.iter('node')))))
    old = n.get('text', '')
    tap(n)
    shell('input', 'keyevent', 'KEYCODE_MOVE_END')
    for _ in old:
        shell('input', 'keyevent', 'KEYCODE_DEL')
    shell('input', 'text', value)
    shell('input', 'keyevent', 'KEYCODE_BACK')
tap(search('只读诊断（详情见服务页）'))
search('设备一键诊断')
time.sleep(15)
search(f'诊断目标：{args.target_host}:{args.adb_port}（本次快照）')
(folder / (profile + '-doctor-top.png')).write_bytes(subprocess.check_output(adb + ['exec-out', 'screencap', '-p']))
search('打开键盘设置')
root = dump()
last = next((n for n in root.iter('node') if n.get('clickable') == 'true' and any((c.get('text') == '打开键盘设置' for c in n.iter('node')))))
a, b, c, d = bounds(last)
density = int(re.findall('Physical density: (\\d+)', shell('wm', 'density'))[0])
assert d - b >= 48 * density / 160 - 3
(folder / (profile + '-doctor-bottom.png')).write_bytes(subprocess.check_output(adb + ['exec-out', 'screencap', '-p']))
root = dump()
text = '\n'.join((n.get('text', '') for n in root.iter('node')))
assert 'Bridge 输入法' in text
(folder / (profile + '-ui-result.json')).write_text(json.dumps({'profile': profile, 'actual_target_report': True, 'keyboard_repair_height_px': d - b, 'density': density, 'repair_target_at_least_48dp': True}, indent=2))
print('Actual helper UI diagnosis and scrolling passed: ' + profile, flush=True)
for artifact in folder.glob(profile + '-*'):
    if artifact.is_file():
        artifact.chmod(0o600)
