#!/usr/bin/env python3
"""Explicit, on-device integration check. Restores Balanced in finally."""
import pathlib, subprocess, time

def adb(*args, check=True):
    return subprocess.run(['adb', *args], text=True, capture_output=True, check=check).stdout

def root(command):
    return adb('shell', 'su -c ' + "'" + command.replace("'", "'\\''") + "'")

def run(mode):
    return dict(line.split('=', 1) for line in root('sh /data/local/tmp/pocketdeck-performance.sh ' + mode).splitlines() if '=' in line)

base = pathlib.Path(__file__).resolve().parents[1]
adb('push', str(base / 'app/assets/performance-control.sh'), '/data/local/tmp/pocketdeck-performance.sh')
# Top GPU OPP: 806000 on stock kernels, higher on an overclocked kernel.
gpu_top = root('head -1 /proc/gpufreq/gpufreq_opp_dump').split('freq = ')[1].split(',')[0]
try:
    for mode, values in [('saver', ('1375000','1419000','270000','545000')),
                         ('performance', ('2000000','2050000','595000',gpu_top)),
                         ('turbo', ('2000000','2050000',gpu_top,gpu_top))]:
        state = run(mode)
        assert state['active'] == mode, state
        assert tuple(state[k] for k in ('cpu0max','cpu1max','gpuMin','gpuMax')) == values, state
        if mode == 'turbo':
            assert state['cpu0min'] == '2000000' and state['cpu1min'] == '2050000', state
            assert state['ddr'] == '4266000', state
        print(mode, 'PASS', values)
    # Unknown commands cannot change controls.
    before = root('cat /data/adb/pocketdeck/current')
    bad = adb('shell', "su -c 'sh /data/local/tmp/pocketdeck-performance.sh invalid'", check=False)
    assert 'Unknown mode' in bad, bad
    assert root('cat /data/adb/pocketdeck/current') == before
    print('invalid mode rejected PASS')
    # Inject one write failure into a temporary copy, after the first CPU write.
    source = (base / 'app/assets/performance-control.sh').read_text()
    needle = '  echo \'0 -1\' > "$P/hard_userlimit_min_cpu_freq" || return 1'
    broken = source.replace(needle, needle + '\n  [ "$1" != "$D/target" ] || return 1')
    temp = base / 'build/controller-failure-test.sh'
    temp.write_text(broken)
    adb('push', str(temp), '/data/local/tmp/pocketdeck-failure-test.sh')
    output = adb('shell', "su -c 'sh /data/local/tmp/pocketdeck-failure-test.sh saver'", check=False)
    assert 'previous controls restored' in output, output
    assert run('status')['active'] == 'turbo'
    print('partial-write rollback PASS')
    # Simulate interrupted operation's journal, then recover through status.
    root('cp /data/adb/pocketdeck/baseline /data/adb/pocketdeck/pending')
    run('status')
    assert root('cat /data/adb/pocketdeck/current') == root('cat /data/adb/pocketdeck/baseline')
    print('interrupted-operation recovery PASS')
finally:
    state = run('balanced')
    assert state['active'] == 'balanced', state
    assert root('cat /data/adb/pocketdeck/current') == root('cat /data/adb/pocketdeck/baseline')
    print('exact Balanced restoration PASS')
    # Balanced withdraws Turbo's DRAM request; memory scales down again at idle.
    for _ in range(10):
        if run('status')['ddr'] != '4266000':
            break
        time.sleep(1)
    else:
        raise AssertionError('DRAM still held at 4266 MHz after Balanced')
    print('DRAM request released PASS')
    root('rm -f /data/local/tmp/pocketdeck-failure-test.sh')
