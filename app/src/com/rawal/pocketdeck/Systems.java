package com.rawal.pocketdeck;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.Settings;
import java.util.Arrays;
import java.util.Locale;

/**
 * The consoles the launcher knows and the emulator each one hands its games to. A game's system
 * comes from the folder it sits in ("ROMs/N64", "roms/snes"); anything unlabelled is PSP, which
 * keeps the original PPSSPP folder working unchanged.
 */
final class Systems {
    static final class System {
        final String id, name, pkg, core;
        final String[] folders, exts;
        System(String id, String name, String pkg, String core, String folders, String exts) {
            this.id = id; this.name = name; this.pkg = pkg; this.core = core;
            this.folders = folders.split(" "); this.exts = exts.split(" ");
        }
        boolean accepts(String lowerName) {
            for (String e : exts) if (lowerName.endsWith("." + e)) return true;
            return false;
        }
    }

    static final String RETROARCH = "com.retroarch.aarch64";
    static final System PSP = new System("psp", "PSP", "org.ppsspp.ppsspp", null, "psp", "iso cso chd pbp");
    static final System[] ALL = {
        PSP,
        new System("ps2", "PlayStation 2", "xyz.aethersx2.android", null, "ps2 playstation2", "iso chd cso gz"),
        new System("ps1", "PlayStation", "com.github.stenzek.duckstation", null, "ps1 psx playstation", "cue chd pbp iso m3u"),
        new System("n64", "Nintendo 64", "org.mupen64plusae.v3.fzurita", null, "n64 nintendo64", "z64 n64 v64"),
        new System("snes", "Super Nintendo", RETROARCH, "snes9x", "snes sfc", "sfc smc zip"),
        new System("nes", "NES", RETROARCH, "fceumm", "nes famicom", "nes zip"),
        new System("gba", "Game Boy Advance", RETROARCH, "mgba", "gba", "gba zip"),
        new System("gb", "Game Boy", RETROARCH, "gambatte", "gb gbc gameboy", "gb gbc zip"),
        new System("genesis", "Genesis", RETROARCH, "genesis_plus_gx", "genesis md megadrive", "md gen bin smd zip"),
        new System("32x", "32X", RETROARCH, "picodrive", "32x", "32x zip"),
        new System("nds", "Nintendo DS", RETROARCH, "melondsds", "nds ds", "nds zip"),
        new System("dreamcast", "Dreamcast", RETROARCH, "flycast", "dreamcast dc", "chd gdi cdi"),
        new System("arcade", "Arcade", RETROARCH, "fbneo", "arcade fbneo", "zip"),
        new System("pce", "PC Engine", RETROARCH, "mednafen_pce_fast", "pce pcengine turbografx", "pce zip"),
    };

    /** The system a folder name stands for, or null when the name means nothing. */
    static System forFolder(String folderName) {
        if (folderName == null) return null;
        String lower = folderName.toLowerCase(Locale.ROOT);
        for (System s : ALL) if (Arrays.asList(s.folders).contains(lower)) return s;
        return null;
    }

    static System byId(String id) {
        for (System s : ALL) if (s.id.equals(id)) return s;
        return PSP;
    }

    static boolean installed(Context ctx, String pkg) {
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** "primary:ROMs/N64/x.z64" -> "/storage/emulated/0/ROMs/N64/x.z64", for emulators that want a plain path. */
    static String pathFor(Uri doc) {
        String id = DocumentsContract.getDocumentId(doc);
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon), rest = id.substring(colon + 1);
        return (volume.equals("primary") ? "/storage/emulated/0/" : "/storage/" + volume + "/") + rest;
    }

    /** The intent that plays `doc` on `system`; the emulator's package must already be installed. */
    static Intent launch(Context ctx, System system, Uri doc) throws Exception {
        Intent intent;
        if (RETROARCH.equals(system.pkg)) {
            String base = "/storage/emulated/0/Android/data/" + RETROARCH + "/files";
            intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(RETROARCH, "com.retroarch.browser.retroactivity.RetroActivityFuture"))
                .putExtra("ROM", pathFor(doc))
                .putExtra("LIBRETRO", "/data/data/" + RETROARCH + "/cores/" + system.core + "_libretro_android.so")
                .putExtra("CONFIGFILE", base + "/retroarch.cfg")
                .putExtra("IME", Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD))
                .putExtra("DATADIR", "/data/data/" + RETROARCH)
                .putExtra("APK", ctx.getPackageManager().getApplicationInfo(RETROARCH, 0).sourceDir)
                .putExtra("SDCARD", "/storage/emulated/0")
                .putExtra("DOWNLOADS", "/storage/emulated/0/Download")
                .putExtra("SCREENSHOTS", "/storage/emulated/0/Pictures")
                .putExtra("EXTERNAL", base)
                .putExtra("QUITFOCUS", true);
        } else if ("ps1".equals(system.id) || "ps2".equals(system.id)) {
            // DuckStation and NetherSX2 boot straight into a game from a document URI.
            intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(system.pkg, system.pkg + ".EmulationActivity"))
                .putExtra("bootPath", doc.toString());
        } else {
            // M64Plus FZ opens documents through its splash activity.
            intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(system.pkg, "paulscode.android.mupen64plusae.SplashActivity"))
                .setDataAndType(doc, "application/octet-stream");
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(system.name + " game", doc));
        // An intent grant only lives as long as the activity it lands on; FZ's splash screen hands the
        // path to its gallery as a string and finishes, so give the whole package access instead.
        try { ctx.grantUriPermission(system.pkg, doc, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
