#!/system/bin/sh
# Begonia / MediaTek GED + PPM controller. No voltage or thermal overrides.
set -eu
export PATH=/system/bin:/system/xbin
mode=${1:-status}
case "$mode" in status|saver|balanced|performance|turbo) ;; *) echo 'error=Unknown mode'; exit 2;; esac
fail() { echo "error=$*"; exit 1; }
[ "$(id -u)" = 0 ] || fail 'Root access is required'
C=/sys/devices/system/cpu/cpufreq
# Other devices: profiles are off, but live readings come from whatever this SoC exposes.
if [ "$(getprop ro.product.device)" != begonia ]; then
  [ "$mode" = status ] || fail 'Performance modes are not available on this device'
  echo 'active=unsupported'
  echo 'available=true'
  echo "cpus=$(cat "$C"/policy*/scaling_cur_freq 2>/dev/null | xargs)"
  gpu=''
  # Adreno (kgsl) and devfreq report Hz; MediaTek GED reports kHz.
  if [ -r /sys/class/kgsl/kgsl-3d0/gpuclk ]; then
    gpu=$(awk '{print int($1 / 1000)}' /sys/class/kgsl/kgsl-3d0/gpuclk)
  elif [ -r /sys/kernel/ged/hal/current_freqency ]; then
    gpu=$(awk '{print $2}' /sys/kernel/ged/hal/current_freqency)
  else
    for d in /sys/class/devfreq/*; do
      case "$d $(cat "$d/name" 2>/dev/null)" in *mali*|*gpu*|*kgsl*|*g3d*) ;; *) continue;; esac
      [ -r "$d/cur_freq" ] && gpu=$(awk '{print int($1 / 1000)}' "$d/cur_freq") && break
    done
  fi
  echo "gpu=$gpu"
  echo "temperature=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)"
  exit 0
fi
G=/sys/kernel/ged/hal
P=/proc/ppm/policy
R=/sys/devices/platform/10012000.dvfsrc/helio-dvfsrc
D=/data/adb/pocketdeck
umask 077
mkdir -p "$D"
if ! mkdir "$D/lock" 2>/dev/null; then
  oldpid=$(cat "$D/lock/pid" 2>/dev/null || echo 0)
  case "$oldpid" in ''|*[!0-9]*|0) fail 'Controller busy; try again';; esac
  kill -0 "$oldpid" 2>/dev/null && fail 'Controller busy; try again'
  rm -f "$D/lock/pid"; rmdir "$D/lock"; mkdir "$D/lock"
fi
echo $$ > "$D/lock/pid"
trap 'rm -f "$D/lock/pid"; rmdir "$D/lock"' EXIT
for node in "$P/hard_userlimit_min_cpu_freq" "$P/hard_userlimit_max_cpu_freq" "$G/custom_boost_gpu_freq" "$G/custom_upbound_gpu_freq" "$C/policy0/scaling_governor" "$C/policy6/scaling_governor"; do
  [ -r "$node" ] && [ -w "$node" ] || fail "Control unavailable: $node"
done
# Index ordering must match this device's actual DVFS tables.
t0=$(cat /proc/ppm/dump_cluster_0_dvfs_table | xargs)
t1=$(cat /proc/ppm/dump_cluster_1_dvfs_table | xargs)
[ "$t0" = '2000000 1933000 1866000 1800000 1733000 1666000 1618000 1500000 1375000 1275000 1175000 1075000 975000 875000 774000 500000' ] || fail 'CPU table changed; controller needs updating'
[ "$t1" = '2050000 1986000 1923000 1860000 1796000 1733000 1670000 1530000 1419000 1308000 1169000 1085000 1002000 919000 835000 774000' ] || fail 'CPU table changed; controller needs updating'
gpu_table=$(sed -n 's/.*freq = \([0-9]*\),.*/\1/p' /proc/gpufreq/gpufreq_opp_dump | xargs)
gpu_count=$(echo "$gpu_table" | wc -w)
[ "$(cat "$G/total_gpu_freq_level_count")" = "$gpu_count" ] || fail 'GPU table changed'
# An overclocked kernel may prepend MediaTek's calibrated OPPs above 806 MHz.
# The stock entries must follow unchanged; presets below use stock indices.
gpu_extra=$((gpu_count - 28))
gpu_last=$((gpu_count - 1))
[ "$gpu_extra" -ge 0 ] && [ "$gpu_extra" -le 15 ] || fail 'GPU table changed; controller needs updating'
gpu_oc='900000 897000 892000 888000 884000 880000 875000 871000 867000 862000 858000 854000 850000 835000 821000'
gpu_expected="$(echo "$gpu_oc" | awk -v n="$gpu_extra" '{for (i = NF - n + 1; i <= NF; i++) printf "%s ", $i}')806000 792000 778000 763000 749000 735000 720000 706000 691000 677000 663000 648000 634000 620000 595000 570000 545000 520000 495000 470000 445000 420000 395000 370000 345000 320000 295000 270000"
[ "$gpu_table" = "$gpu_expected" ] || fail 'GPU table changed; controller needs updating'
gpu() { echo $(($1 + gpu_extra)); }
snapshot() {
  awk '{gsub(",", "", $4); print $4; print $7}' "$P/hard_userlimit_cpu_freq"
  cat "$C/policy0/scaling_governor" "$C/policy6/scaling_governor" "$G/custom_boost_gpu_freq" "$G/custom_upbound_gpu_freq"
}
get() { sed -n "${2}p" "$1"; }
valid() {
  [ "$(wc -l < "$1")" -eq 8 ] || return 1
  for n in 1 2 3 4; do v=$(get "$1" "$n"); case "$v" in -1|[0-9]|1[0-5]) ;; *) return 1;; esac; done
  for n in 5 6; do v=$(get "$1" "$n"); case "$v" in schedutil|performance|powersave|ondemand|userspace) ;; *) return 1;; esac; done
  for n in 7 8; do v=$(get "$1" "$n"); case "$v" in [0-9]|[1-9][0-9]) [ "$v" -le "$gpu_last" ] || return 1;; *) return 1;; esac; done
}
freq() { if [ "$2" = -1 ]; then echo -1; else echo "$1" | awk -v n="$2" '{print $(n+1)}'; fi; }
restore() {
  valid "$1" || return 1
  echo '0 -1' > "$P/hard_userlimit_min_cpu_freq" || return 1
  echo '1 -1' > "$P/hard_userlimit_min_cpu_freq" || return 1
  echo "0 $(freq "$t0" "$(get "$1" 2)")" > "$P/hard_userlimit_max_cpu_freq" || return 1
  echo "1 $(freq "$t1" "$(get "$1" 4)")" > "$P/hard_userlimit_max_cpu_freq" || return 1
  echo "0 $(freq "$t0" "$(get "$1" 1)")" > "$P/hard_userlimit_min_cpu_freq" || return 1
  echo "1 $(freq "$t1" "$(get "$1" 3)")" > "$P/hard_userlimit_min_cpu_freq" || return 1
  get "$1" 5 > "$C/policy0/scaling_governor" || return 1
  get "$1" 6 > "$C/policy6/scaling_governor" || return 1
  echo "$gpu_last" > "$G/custom_boost_gpu_freq" || return 1
  get "$1" 8 > "$G/custom_upbound_gpu_freq" || return 1
  get "$1" 7 > "$G/custom_boost_gpu_freq" || return 1
  snapshot > "$D/readback"
  cmp -s "$1" "$D/readback"
}
boot=$(cat /proc/sys/kernel/random/boot_id)
if [ "$(cat "$D/boot" 2>/dev/null || true)" != "$boot" ]; then
  snapshot > "$D/baseline.tmp"
  valid "$D/baseline.tmp" || fail 'Could not capture original settings'
  mv "$D/baseline.tmp" "$D/baseline"
  cp "$D/baseline" "$D/expected"
  echo balanced > "$D/mode"
  rm -f "$D/pending"
  echo "$boot" > "$D/boot"
fi
# Finish recovery if a previous apply was interrupted.
if [ -f "$D/pending" ]; then
  restore "$D/pending" || fail 'Recovery failed; original settings remain saved'
  rm "$D/pending"
fi
if [ "$mode" != status ]; then
  case "$mode" in
    balanced) cp "$D/baseline" "$D/target";;
    saver) printf '%s\n' -1 8 -1 8 schedutil schedutil "$(gpu 27)" "$(gpu 16)" > "$D/target";;
    performance) printf '%s\n' 12 0 12 0 schedutil schedutil "$(gpu 14)" 0 > "$D/target";;
    turbo) printf '%s\n' 0 0 0 0 performance performance 0 0 > "$D/target";;
  esac
  snapshot > "$D/pending.tmp"
  valid "$D/pending.tmp" || fail 'Could not save current settings'
  mv "$D/pending.tmp" "$D/pending"
  if restore "$D/target"; then
    cp "$D/target" "$D/expected"
    echo "$mode" > "$D/mode"
    rm "$D/pending"
    # Turbo also holds DRAM at its top OPP (4266 MHz). This is Nomad's own PM QoS
    # request; 16 withdraws it, so other modes leave memory scaling to the kernel.
    if [ -w "$R/dvfsrc_req_ddr_opp" ]; then
      if [ "$mode" = turbo ]; then echo 0; else echo 16; fi > "$R/dvfsrc_req_ddr_opp" || fail 'Memory request failed'
    fi
  else
    if restore "$D/pending"; then rm "$D/pending"; fail 'Apply failed; previous controls restored';
    else fail 'Apply and rollback failed; select Balanced to retry recovery'; fi
  fi
fi
snapshot > "$D/current"
active=$(cat "$D/mode")
if ! cmp -s "$D/current" "$D/expected"; then active=custom; fi
echo "active=$active"
echo 'available=true'
echo "cpu0min=$(cat "$C/policy0/scaling_min_freq")"
echo "cpu0max=$(cat "$C/policy0/scaling_max_freq")"
echo "cpu1min=$(cat "$C/policy6/scaling_min_freq")"
echo "cpu1max=$(cat "$C/policy6/scaling_max_freq")"
echo "cpu0=$(cat "$C/policy0/scaling_cur_freq")"
echo "cpu1=$(cat "$C/policy6/scaling_cur_freq")"
echo "gpuMin=$(freq "$gpu_table" "$(cat "$G/custom_boost_gpu_freq")")"
echo "gpuMax=$(freq "$gpu_table" "$(cat "$G/custom_upbound_gpu_freq")")"
echo "gpu=$(awk '{print $2}' "$G/current_freqency")"
echo "gpuTop=$(echo "$gpu_table" | awk '{print $1}')"
# Measured by the SoC clock meter; 0 while the GPU is power-gated between frames.
echo "gpuReal=$(sed -n 's/.*real clock freq = \([0-9]*\),.*/\1/p' /proc/gpufreq/gpufreq_var_dump)"
echo "ddr=$(sed -n 's/^DDR *: *\([0-9]*\).*/\1/p' "$R/dvfsrc_dump" 2>/dev/null)"
# Driver-side GPU cap (0 = none); array slots: thermal, low battery, battery %, battery OC, PBM.
limit=$(sed -n 's/^g_max_limited_idx = \([0-9]*\)$/\1/p' /proc/gpufreq/gpufreq_var_dump)
case "$limit" in ''|0) ;; *)
  echo "gpuLimit=$(freq "$gpu_table" "$limit")"
  by=$(awk -v n="$limit" '/^g_limited_idx_array\[[0-9]\] = / && $3 == n {split("thermal,low battery,battery level,battery current,power budget", w, ","); print w[substr($1, 21, 1) + 1]; exit}' /proc/gpufreq/gpufreq_var_dump)
  echo "gpuLimitBy=${by:-power}";;
esac
pll=$(sed -n 's/.*nomad_pll_failures = \([0-9]*\),.*/\1/p' /proc/gpufreq/gpufreq_var_dump)
[ -z "$pll" ] || echo "pllFailures=$pll"
echo "temperature=$(cat /sys/class/power_supply/battery/temp)"
echo 'message=Controls verified. Thermal and battery protections remain active.'
