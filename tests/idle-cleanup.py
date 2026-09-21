"""Exercise the shipped controller with simulated Android system services."""
import os
from pathlib import Path
import subprocess
import tempfile

script = Path('app/assets/idle-cleanup.sh').resolve()
with tempfile.TemporaryDirectory() as folder:
    root = Path(folder)
    service = root / 'dumpsys'
    service.write_text('#!/bin/sh\ncase "$1" in\nactivity) cat "$FIXTURE/activity";;\nmedia_session) cat "$FIXTURE/media";;\naudio) cat "$FIXTURE/audio";;\nesac\n')
    (root / 'pidof').write_text('#!/bin/sh\nexit 0\n')
    (root / 'am').write_text('#!/bin/sh\necho "$*" >> "$FIXTURE/actions"\n')
    for name in ('dumpsys', 'pidof', 'am'):
        (root / name).chmod(0o755)
    env = dict(os.environ, PATH=f'{root}:' + os.environ['PATH'], FIXTURE=folder)
    home = 'topResumedActivity=ActivityRecord{abc u0 com.rawal.pocketdeck/.MainActivity}\n'
    idle = 'MEDIA SESSION SERVICE\n active=true\n state=PlaybackState {state=2, position=0}\n'
    audio = '  players:\n AudioPlaybackConfiguration state:idle\n'
    def check(name, activity=home, media=idle, sound=audio, stops=False):
        for filename, value in [('activity', activity), ('media', media), ('audio', sound), ('actions', '')]:
            (root / filename).write_text(value)
        subprocess.run(['sh', str(script)], env=env, check=True, capture_output=True)
        actions = (root / 'actions').read_text().splitlines()
        expected = ['force-stop --user 0 com.spotify.music', 'force-stop --user 0 com.google.android.youtube'] if stops else []
        assert actions == expected, (name, actions)
        print('PASS:', name)
    check('paused apps cleaned', stops=True)
    for state in (3, 4, 5, 6, 8, 9, 10, 11, 99):
        check(f'playback state {state} preserved', media=idle.replace('state=2', f'state={state}'))
    check('unknown active session preserved', media='MEDIA SESSION SERVICE\n active=true\n state=null\n')
    check('audio without media session preserved', sound=audio.replace('state:idle', 'state:started'))
    check('other foreground app preserved', activity=home.replace('com.rawal.pocketdeck', 'com.spotify.music'))
    for package in ('com.spotify.music', 'com.google.android.youtube'):
        visible = home + f'* Hist #0\n packageName={package} processName={package}\n mVisibleRequested=true mVisible=true\n'
        check(f'{package} visible/PiP preserved', activity=visible)
        check(f'{package} hidden can be cleaned', activity=visible.replace('=true', '=false'), stops=True)
    check('missing activity dump skips', activity='')
    check('missing media dump skips', media='')
    check('missing audio dump skips', sound='')
