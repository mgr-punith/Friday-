#!/usr/bin/env python3
"""Exercise Friday's debug UI without executing phone commands.

Build/install a debug APK first. Outputs screenshots and a report outside the repo.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', default='adb')
parser.add_argument('--output', default='/tmp/friday-ui-check')
args = parser.parse_args()
output = Path(args.output)
output.mkdir(parents=True, exist_ok=True)
checks = []
events = []


def adb(*command):
    result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, timeout=40)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace').strip())
    events.append({'command': list(command), 'output': result.stdout.decode(errors='replace')[:500]})
    return result.stdout


def shell(*command):
    return adb('shell', *command).decode(errors='replace')


def start(activity='MainActivity', state=None):
    if activity == 'MainActivity':
        shell('am', 'force-stop', 'com.example.assistant')
    command = ['am', 'start', '-W', '-n', 'com.example.assistant/.' + activity]
    if activity == 'MainActivity':
        command += ['--activity-clear-top']
    if state:
        command += ['--es', 'debug_ui_state', state]
    shell(*command)
    time.sleep(0.8)


def hierarchy():
    shell('uiautomator', 'dump', '/sdcard/friday-qa.xml')
    data = adb('exec-out', 'cat', '/sdcard/friday-qa.xml')
    (output / 'last-hierarchy.xml').write_bytes(data)
    return list(ElementTree.fromstring(data).iter('node'))


def texts(nodes):
    return [node.get('text') or node.get('content-desc') for node in nodes
            if node.get('text') or node.get('content-desc')]


def tap(nodes, label):
    node = next(node for node in nodes if node.get('clickable') == 'true' and
                (node.get('text') == label or node.get('content-desc') == label))
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    assert x2 > x1 and y2 > y1, 'Control is offscreen: ' + label
    shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)


def capture(name):
    shell('screencap', '-p', '/sdcard/friday-qa.png')
    adb('pull', '/sdcard/friday-qa.png', str(output / (name + '.png')))


def expect(condition, message):
    if not condition:
        raise AssertionError(message)
    checks.append(message)
    print('PASS:', message, flush=True)


font_scale = shell('settings', 'get', 'system', 'font_scale').strip()
rotation = shell('settings', 'get', 'system', 'user_rotation').strip()
auto_rotation = shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
night = shell('cmd', 'uimode', 'night').strip().split()[-1]
try:
    shell('input', 'keyevent', 'KEYCODE_WAKEUP')
    shell('wm', 'dismiss-keyguard')
    start()
    nodes = hierarchy()
    expect('Settings' in texts(nodes), 'Home exposes Settings')
    expect(any(node.get('content-desc') == 'Home' and node.get('selected') == 'true' for node in nodes),
           'Home navigation announces the selected tab')
    expect('Quick access' not in texts(nodes) and 'Permissions' not in texts(nodes), 'Setup cards stay off the voice screen')
    expect('English · Phone commands' not in texts(nodes), 'The removed footer stays absent')
    capture('home')
    tap(nodes, 'Settings')
    nodes = hierarchy()
    expect('Ready notification' in ' '.join(texts(nodes)), 'Settings preserves the ready notification control')
    expect('Default assistant' in ' '.join(texts(nodes)), 'Settings preserves assistant selection')
    expect(any(node.get('content-desc') == 'Settings' and node.get('selected') == 'true' for node in nodes),
           'Settings navigation announces the selected tab')
    capture('settings')
    tap(nodes, 'Home')
    expect('Talk to Friday' in texts(hierarchy()), 'Home tab from Settings restores the voice screen')

    def top_activity():
        state = shell('dumpsys', 'activity', 'activities')
        return next((line.strip() for line in state.splitlines() if 'topResumedActivity=' in line or 'mResumedActivity:' in line), '')

    home_activity = top_activity()
    nodes = hierarchy()
    tap(nodes, 'Settings')
    expect(top_activity() == home_activity and '.MainActivity' in home_activity,
           'Home and Settings share the same Activity instance')
    nodes = hierarchy()
    tap(nodes, 'Settings')
    expect(top_activity() == home_activity, 'Reselecting a tab does not launch an Activity')
    shell('input', 'swipe', '900', '1600', '900', '550', '250')
    nodes = hierarchy()
    before_scroll = next(node.get('bounds') for node in nodes if node.get('text') == 'Tools')
    tap(nodes, 'Home')
    tap(hierarchy(), 'Settings')
    nodes = hierarchy()
    after_scroll = next(node.get('bounds') for node in nodes if node.get('text') == 'Tools')
    expect(before_scroll == after_scroll, 'Settings keeps its scroll position across tabs')
    shell('input', 'keyevent', 'KEYCODE_BACK')
    expect('Talk to Friday' in texts(hierarchy()), 'Android Back from Settings returns Home')
    shell('am', 'start', '-W', '-n', 'com.example.assistant/.MainActivity', '--es', 'debug_ui_state', 'success')
    nodes = hierarchy()
    tap(nodes, 'Settings')
    tap(hierarchy(), 'Home')
    expect('Preview: Timer request sent for 10 minutes' in texts(hierarchy()),
           'The latest result survives switching tabs')

    shell('input', 'keyevent', 'KEYCODE_HOME')
    start('AssistantActivity', 'processing')
    nodes = hierarchy()
    expect('Working on it' in texts(nodes), 'Compact panel exposes processing status')
    expect(any(node.get('content-desc') == 'Cancel command' for node in nodes), 'Processing exposes cancellation')
    capture('processing')
    tap(nodes, 'Cancel command')
    expect('Talk to Friday' in texts(hierarchy()), 'Cancel returns the panel to idle')

    start('AssistantActivity', 'unsupported')
    nodes = hierarchy()
    expect("That feature isn't built yet" in texts(nodes), 'Unsupported feedback stays in the voice surface')
    expect(not any(node.get('class') == 'android.app.Dialog' for node in nodes), 'Unsupported feedback does not open a dialog')
    capture('unsupported')
    start('AssistantActivity', 'error')
    expect('No internet. Connect and try again.' in texts(hierarchy()), 'Error keeps a readable recovery message')
    capture('error')
    start('AssistantActivity', 'permission')
    expect('Manage permissions' in texts(hierarchy()), 'Missing permission exposes a recovery button')
    capture('permission')
    start('AssistantActivity', 'success')
    nodes = hierarchy()
    expect('Set timer · 10 minutes' in texts(nodes), 'Result preserves the interpreted action')
    expect('Preview: Timer request sent for 10 minutes' in texts(nodes), 'Preview labels the result without performing an action')
    capture('result')
    tap(nodes, 'Close assistant')
    nodes = hierarchy()
    expect('.AssistantActivity' not in top_activity(), 'Closing the panel returns to the previous app')
    expect(not any(node.get('content-desc') == 'Close assistant' for node in nodes),
           'A Friday launcher widget does not count as an open assistant panel')

    # Real microphone lifecycle, without sending any captured audio to the backend.
    start('AssistantActivity', 'idle')
    nodes = hierarchy()
    tap(nodes, 'Start listening')
    capture('listening')
    shell('input', 'keyevent', 'KEYCODE_HOME')
    start('AssistantActivity', 'idle')
    expect('Talk to Friday' in texts(hierarchy()), 'Leaving capture and reopening does not restart the microphone')

    shell('settings', 'put', 'system', 'font_scale', '1.5')
    start('MainActivity')
    nodes = hierarchy()
    expect('Talk to Friday' in texts(nodes), 'Large text preserves the home heading')
    expect(any(node.get('content-desc') == 'Start listening' for node in nodes), 'Large text keeps the microphone accessible')
    capture('home-large-text')
    shell('input', 'keyevent', 'KEYCODE_HOME')
    start('AssistantActivity', 'permission')
    nodes = hierarchy()
    capture('panel-large-text')
    expect('Permission needed' in texts(nodes), 'Large text preserves permission status')
    expect(any(node.get('content-desc') == 'Start listening' for node in nodes), 'Large text keeps compact voice controls visible')
    shell('input', 'keyevent', 'KEYCODE_BACK')
    shell('settings', 'put', 'system', 'font_scale', '1.0')
    shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
    shell('settings', 'put', 'system', 'user_rotation', '1')
    time.sleep(1)
    start('MainActivity')
    nodes = hierarchy()
    capture('home-landscape')
    from struct import unpack
    width, height = unpack('>II', (output / 'home-landscape.png').read_bytes()[16:24])
    expect(width > height, 'Landscape check uses a rotated display')
    expect('Settings' in texts(nodes), 'Landscape preserves navigation')
    nodes = hierarchy()
    expect(any(node.get('content-desc') == 'Start listening' for node in nodes), 'Landscape keeps the microphone visible')
    shell('settings', 'put', 'system', 'user_rotation', '0')
    shell('cmd', 'uimode', 'night', 'no')
    start()
    capture('home-system-light')
    shell('cmd', 'uimode', 'night', 'yes')
    start()
    capture('home-system-dark')
    expect('Talk to Friday' in texts(hierarchy()), 'Both system themes retain the voice screen')
finally:
    for key, value in [('font_scale', font_scale), ('user_rotation', rotation), ('accelerometer_rotation', auto_rotation)]:
        if value == 'null':
            shell('settings', 'delete', 'system', key)
        else:
            shell('settings', 'put', 'system', key, value)
    if night in ('yes', 'no', 'auto', 'custom'):
        shell('cmd', 'uimode', 'night', night)
    shell('am', 'force-stop', 'com.example.assistant')
    shell('am', 'start', '-W', '-n', 'com.example.assistant/.MainActivity')
    (output / 'report.json').write_text(json.dumps({'passed': checks, 'events': events}, indent=2))
print(f'{len(checks)} phone UI checks passed. Screenshots: {output}', flush=True)
