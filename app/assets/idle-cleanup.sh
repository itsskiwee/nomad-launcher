#!/system/bin/sh
# Only run from PocketDeck's delayed, foreground-only idle task.
# Fail closed when system state cannot be read. No app data is removed.
safe_to_clean() {
    activities=$(dumpsys activity activities) || return 1
    printf '%s\n' "$activities" | grep -q 'topResumedActivity=.* com.rawal.pocketdeck/' || return 1
    # Protect either app in split screen / picture-in-picture too.
    printf '%s\n' "$activities" | awk '
        /\* Hist / { target=0 }
        /packageName=/ { target=($0 ~ /packageName=com.spotify.music / || $0 ~ /packageName=com.google.android.youtube /) }
        target && /mVisibleRequested=true|mVisible=true/ { visible=1 }
        END { exit visible ? 1 : 0 }
    ' || return 1
    sessions=$(dumpsys media_session) || return 1
    printf '%s\n' "$sessions" | grep -q 'MEDIA SESSION SERVICE' || return 1
    # Preserve playing, buffering, connecting and seeking sessions, including casting.
    # An active session with missing/unknown playback state is also protected.
    printf '%s\n' "$sessions" | awk '
        /active=/ { active=($0 ~ /active=true/) }
        /state=PlaybackState/ {
            state=$0; sub(/.*PlaybackState \{state=/,"",state); sub(/,.*/,"",state)
            if (state !~ /^(0|1|2|7)$/) busy=1
        }
        active && /state=null/ { busy=1 }
        END { exit busy ? 1 : 0 }
    ' || return 1
    audio=$(dumpsys audio) || return 1
    printf '%s\n' "$audio" | grep -Eq 'AudioPlaybackConfiguration|players:' || return 1
    # Also protect players that do not publish media sessions.
    printf '%s\n' "$audio" | grep 'AudioPlaybackConfiguration' | grep -q 'state:started' && return 1
    return 0
}

for package in com.spotify.music com.google.android.youtube; do
    pidof "$package" >/dev/null 2>&1 || continue
    safe_to_clean || exit 0
    am force-stop --user 0 "$package" || exit 1
    echo "Stopped idle $package"
done
