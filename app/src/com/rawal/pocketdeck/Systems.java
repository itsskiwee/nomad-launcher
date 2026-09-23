package com.rawal.pocketdeck;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The consoles the launcher knows and the emulators that can play each one. A game's system
 * comes from the folder it sits in ("ROMs/N64", "roms/snes", ES-DE's "psx"); anything unlabelled
 * is PSP, which keeps the original PPSSPP folder working unchanged. Launch intents follow the
 * ones ES-DE ships for Android (resources/systems/android/es_systems.xml), so they match what
 * each emulator's developers support.
 */
final class Systems {
    /** How an emulator wants to be handed a game. */
    enum Style {
        VIEW,           // ACTION_VIEW with the document as data
        VIEW_TYPED,     // the same with an octet-stream type (Mupen64Plus's splash activity wants one)
        DATA,           // explicit activity with the document as data, no action
        BOOT_PATH,      // ACTION_MAIN with extra bootPath=<document uri> (DuckStation, NetherSX2)
        DOLPHIN,        // ACTION_MAIN, LEANBACK category, extra AutoStartFile=<document uri>
        DOLPHIN_VIEW,   // ACTION_VIEW, extra AutoStartFile=<document uri> (MMJR builds)
        MELONDS,        // custom action, extra uri=<document uri>
        KENJI,          // custom action, extra bootPath=<document uri>
        EDEN,           // android.nfc.action.TECH_DISCOVERED with the document as data
        YABA,           // ACTION_VIEW, extra org.uoyabause.android.FileNameUri
        VITA3K,         // string-array extra AppStartParameters = {-r, <title id from the .psvita file>}
        WINLATOR,       // extra shortcut_path=<plain path to a .desktop shortcut>
        RETROARCH       // RetroArch's own extras with a core
    }

    static final class Emulator {
        final String id, name, activity, site;
        final String[] pkgs;
        final Style style;
        final String action;
        Emulator(String id, String name, String pkgs, String activity, Style style, String action, String site) {
            this.id = id; this.name = name; this.pkgs = pkgs.split(" "); this.activity = activity;
            this.style = style; this.action = action; this.site = site;
        }
        /** The first of this emulator's package variants that is installed, or null. */
        String installed(Context ctx) {
            for (String p : pkgs) if (Systems.installed(ctx, p)) return p;
            return null;
        }
        /** Activities given as ".Name" are relative to the package that is installed. */
        ComponentName component(String pkg) {
            return new ComponentName(pkg, activity.startsWith(".") ? pkg + activity : activity);
        }
    }

    static final class Choice {
        final Emulator emulator; final String core;
        Choice(Emulator emulator, String core) { this.emulator = emulator; this.core = core; }
        String key() { return core == null ? emulator.id : emulator.id + ":" + core; }
        String label() { return core == null ? emulator.name : emulator.name + " (" + core.replace("_", " ") + ")"; }
    }

    static final class System {
        final String id, name;
        final String[] folders, exts;
        final List<Choice> choices = new ArrayList<>();
        System(String id, String name, String folders, String exts) {
            this.id = id; this.name = name;
            this.folders = folders.split(" "); this.exts = exts.split(" ");
        }
        System with(Emulator e) { choices.add(new Choice(e, null)); return this; }
        System core(String core) { choices.add(new Choice(RA, core)); return this; }
        boolean accepts(String lowerName) {
            for (String e : exts) if (lowerName.endsWith("." + e)) return true;
            return false;
        }
        Choice choice(String key) {
            for (Choice c : choices) if (c.key().equals(key)) return c;
            return null;
        }
    }

    static final String RETROARCH = "com.retroarch.aarch64";
    static final Emulator
        PPSSPP = new Emulator("ppsspp", "PPSSPP", "org.ppsspp.ppssppgold org.ppsspp.ppsspp", "org.ppsspp.ppsspp.PpssppActivity", Style.VIEW, null, "https://www.ppsspp.org/"),
        RA = new Emulator("retroarch", "RetroArch", RETROARCH + " com.retroarch com.retroarch.ra32", "com.retroarch.browser.retroactivity.RetroActivityFuture", Style.RETROARCH, null, "https://www.retroarch.com/"),
        DUCKSTATION = new Emulator("duckstation", "DuckStation", "com.github.stenzek.duckstation", ".EmulationActivity", Style.BOOT_PATH, null, "https://github.com/stenzek/duckstation"),
        NETHERSX2 = new Emulator("nethersx2", "NetherSX2", "xyz.aethersx2.android", ".EmulationActivity", Style.BOOT_PATH, null, "https://github.com/Trixarian/NetherSX2-classic"),
        ARMSX2 = new Emulator("armsx2", "ARMSX2", "come.nanodata.armsx2 com.armsx2", "com.armsx2.MainActivity", Style.VIEW, null, null),
        M64FZ = new Emulator("m64fz", "M64Plus FZ", "org.mupen64plusae.v3.fzurita.pro org.mupen64plusae.v3.fzurita org.mupen64plusae.v3.fzurita.amazon", "paulscode.android.mupen64plusae.SplashActivity", Style.VIEW_TYPED, null, null),
        MUPEN_AE = new Emulator("mupen64ae", "Mupen64Plus AE", "org.mupen64plusae.v3.alpha", "paulscode.android.mupen64plusae.SplashActivity", Style.VIEW_TYPED, null, null),
        DOLPHIN = new Emulator("dolphin", "Dolphin", "org.dolphinemu.dolphinemu", ".ui.main.TvMainActivity", Style.DOLPHIN, null, "https://dolphin-emu.org/"),
        DOLPHIN_MMJR = new Emulator("dolphin-mmjr", "Dolphin MMJR", "org.mm.jr", "org.dolphinemu.dolphinemu.ui.main.MainActivity", Style.DOLPHIN_VIEW, null, null),
        AZAHAR = new Emulator("azahar", "Azahar", "org.azahar_emu.azahar io.github.lime3ds.android", "org.citra.citra_emu.activities.EmulationActivity", Style.DATA, null, "https://azahar-emu.org/"),
        AZAHAR_PLUS = new Emulator("azaharplus", "AzaharPlus", "io.github.azaharplus.android", "org.citra.citra_emu.activities.EmulationActivity", Style.DATA, null, null),
        CITRA = new Emulator("citra", "Citra", "org.citra.citra_emu", ".activities.EmulationActivity", Style.DATA, null, null),
        MELONDS = new Emulator("melonds", "melonDS", "me.magnum.melonds me.magnum.melondualds", "me.magnum.melonds.ui.emulator.EmulatorActivity", Style.MELONDS, "me.magnum.melonds.LAUNCH_ROM", "https://github.com/rafaelvcaetano/melonDS-android"),
        DRASTIC = new Emulator("drastic", "DraStic", "com.dsemu.drastic", ".DraSticActivity", Style.DATA, null, null),
        FLYCAST = new Emulator("flycast", "Flycast", "com.flycast.emulator", "com.flycast.emulator.MainActivity", Style.VIEW, null, "https://github.com/flyinghead/flycast"),
        REDREAM = new Emulator("redream", "Redream", "io.recompiled.redream", ".MainActivity", Style.VIEW, null, null),
        YABA = new Emulator("yaba", "Yaba Sanshiro 2", "org.devmiyax.yabasanshioro2.pro org.devmiyax.yabasanshioro2", "org.uoyabause.android.Yabause", Style.YABA, null, null),
        VITA3K = new Emulator("vita3k", "Vita3K", "org.vita3k.emulator", "org.vita3k.emulator.Emulator", Style.VITA3K, null, "https://vita3k.org/"),
        EDEN = new Emulator("eden", "Eden", "dev.eden.eden_emulator dev.legacy.eden_emulator", "org.yuzu.yuzu_emu.activities.EmulationActivity", Style.EDEN, "android.nfc.action.TECH_DISCOVERED", null),
        KENJI = new Emulator("kenji", "Kenji-NX", "org.kenjinx.android", ".MainActivity", Style.KENJI, "org.kenjinx.android.LAUNCH_GAME", null),
        MY_BOY = new Emulator("myboy", "My Boy!", "com.fastemulator.gba", ".EmulatorActivity", Style.VIEW, null, null),
        SNES9X_EX = new Emulator("snes9xex", "Snes9x EX+", "com.explusalpha.Snes9xPlus", "com.imagine.BaseActivity", Style.DATA, null, null),
        WINLATOR_CMOD = new Emulator("winlator-cmod", "Winlator Cmod", "com.winlator.cmod", ".XServerDisplayActivity", Style.WINLATOR, null, null),
        WINLATOR = new Emulator("winlator", "Winlator", "com.winlator", ".XServerDisplayActivity", Style.WINLATOR, null, null);

    static final Emulator[] EMULATORS = {
        PPSSPP, RA, DUCKSTATION, NETHERSX2, ARMSX2, M64FZ, MUPEN_AE, DOLPHIN, DOLPHIN_MMJR, AZAHAR, AZAHAR_PLUS, CITRA,
        MELONDS, DRASTIC, FLYCAST, REDREAM, YABA, VITA3K, EDEN, KENJI, MY_BOY, SNES9X_EX, WINLATOR_CMOD, WINLATOR
    };

    // The first installed choice in each list is the automatic one.
    static final System PSP = new System("psp", "PSP", "psp", "iso cso chd pbp").with(PPSSPP).core("ppsspp");
    static final System[] ALL = {
        PSP,
        new System("ps2", "PlayStation 2", "ps2 playstation2", "iso chd cso gz bin").with(NETHERSX2).with(ARMSX2),
        new System("ps1", "PlayStation", "ps1 psx playstation", "cue chd pbp iso m3u").with(DUCKSTATION).core("swanstation").core("pcsx_rearmed"),
        new System("n64", "Nintendo 64", "n64 nintendo64", "z64 n64 v64").with(M64FZ).with(MUPEN_AE).core("mupen64plus_next_gles3"),
        new System("gc", "GameCube", "gc gamecube ngc", "iso rvz gcz ciso gcm wia").with(DOLPHIN).with(DOLPHIN_MMJR),
        new System("wii", "Wii", "wii", "iso rvz wbfs gcz ciso wia wad").with(DOLPHIN).with(DOLPHIN_MMJR),
        new System("3ds", "Nintendo 3DS", "3ds n3ds", "3ds cci cxi 3dsx app z3dsx zcci zcxi").with(AZAHAR).with(AZAHAR_PLUS).with(CITRA),
        new System("nds", "Nintendo DS", "nds ds", "nds zip").with(MELONDS).with(DRASTIC).core("melondsds"),
        new System("switch", "Switch", "switch", "nsp xci nro").with(EDEN).with(KENJI),
        new System("vita", "PlayStation Vita", "vita psvita", "psvita").with(VITA3K),
        new System("snes", "Super Nintendo", "snes sfc", "sfc smc zip").core("snes9x").with(SNES9X_EX),
        new System("nes", "NES", "nes famicom", "nes zip").core("fceumm"),
        new System("gba", "Game Boy Advance", "gba", "gba zip").core("mgba").with(MY_BOY),
        new System("gb", "Game Boy", "gb gbc gameboy", "gb gbc zip").core("gambatte"),
        new System("genesis", "Genesis", "genesis md megadrive", "md gen bin smd zip").core("genesis_plus_gx"),
        new System("32x", "32X", "32x sega32x", "32x zip").core("picodrive"),
        new System("saturn", "Saturn", "saturn", "chd cue m3u").with(YABA),
        new System("dreamcast", "Dreamcast", "dreamcast dc", "chd gdi cdi m3u").with(FLYCAST).with(REDREAM).core("flycast"),
        new System("arcade", "Arcade", "arcade fbneo mame", "zip").core("fbneo"),
        new System("pce", "PC Engine", "pce pcengine turbografx tg16", "pce zip").core("mednafen_pce_fast"),
        new System("windows", "Windows", "windows winlator pc", "desktop").with(WINLATOR_CMOD).with(WINLATOR),
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

    static Emulator emulatorById(String id) {
        for (Emulator e : EMULATORS) if (e.id.equals(id)) return e;
        return null;
    }

    /** Installed emulator packages, refreshed off the UI thread whenever the launcher comes back. */
    private static volatile Set<String> present;

    static void refresh(Context ctx) {
        Set<String> found = new HashSet<>();
        for (String pkg : emulatorPackages()) if (installedNow(ctx, pkg)) found.add(pkg);
        present = found;
    }

    static boolean installed(Context ctx, String pkg) {
        Set<String> known = present;
        if (known != null && emulatorPackages().contains(pkg)) return known.contains(pkg);
        return installedNow(ctx, pkg);
    }

    private static boolean installedNow(Context ctx, String pkg) {
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** Every package name that belongs to a known emulator; these stay out of the game library. */
    static List<String> emulatorPackages() { return PACKAGES; }
    private static final List<String> PACKAGES = new ArrayList<>();
    static { for (Emulator e : EMULATORS) PACKAGES.addAll(Arrays.asList(e.pkgs)); }

    /**
     * The choice that plays a game: the per-game pick, then the per-system pick, then the first
     * installed option. Null when nothing that plays this system is installed.
     */
    static Choice resolve(Context ctx, System system, String gameKey, String systemKey) {
        for (String key : new String[] { gameKey, systemKey }) {
            Choice c = key == null ? null : system.choice(key);
            if (c != null && c.emulator.installed(ctx) != null) return c;
        }
        for (Choice c : system.choices) if (c.emulator.installed(ctx) != null) return c;
        return null;
    }

    /** "primary:ROMs/N64/x.z64" -> "/storage/emulated/0/ROMs/N64/x.z64", for emulators that want a plain path. */
    static String pathFor(Uri doc) {
        String id = DocumentsContract.getDocumentId(doc);
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon), rest = id.substring(colon + 1);
        return (volume.equals("primary") ? "/storage/emulated/0/" : "/storage/" + volume + "/") + rest;
    }

    /** The intent that plays `doc` with `choice`; its package must already be installed. */
    static Intent launch(Context ctx, System system, Choice choice, Uri doc) throws Exception {
        Emulator emu = choice.emulator;
        String pkg = emu.installed(ctx);
        if (pkg == null) throw new IllegalStateException(emu.name + " is not installed");
        Intent intent = new Intent().setComponent(emu.component(pkg));
        switch (emu.style) {
            case RETROARCH: {
                String base = "/storage/emulated/0/Android/data/" + pkg + "/files";
                intent.setAction(Intent.ACTION_MAIN)
                    .putExtra("ROM", pathFor(doc))
                    .putExtra("LIBRETRO", "/data/data/" + pkg + "/cores/" + choice.core + "_libretro_android.so")
                    .putExtra("CONFIGFILE", base + "/retroarch.cfg")
                    .putExtra("IME", Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD))
                    .putExtra("DATADIR", "/data/data/" + pkg)
                    .putExtra("APK", ctx.getPackageManager().getApplicationInfo(pkg, 0).sourceDir)
                    .putExtra("SDCARD", "/storage/emulated/0")
                    .putExtra("DOWNLOADS", "/storage/emulated/0/Download")
                    .putExtra("SCREENSHOTS", "/storage/emulated/0/Pictures")
                    .putExtra("EXTERNAL", base)
                    .putExtra("QUITFOCUS", true);
                break;
            }
            case BOOT_PATH:
                intent.setAction(Intent.ACTION_MAIN).putExtra("bootPath", doc.toString()).putExtra("resumeState", false);
                clearTask(intent);
                break;
            case DOLPHIN:
                intent.setAction(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER").putExtra("AutoStartFile", doc.toString());
                break;
            case DOLPHIN_VIEW:
                intent.setAction(Intent.ACTION_VIEW).putExtra("AutoStartFile", doc.toString());
                break;
            case MELONDS:
                intent.setAction(emu.action).putExtra("uri", doc.toString());
                break;
            case KENJI:
                intent.setAction(emu.action).putExtra("bootPath", doc.toString());
                break;
            case EDEN:
                intent.setAction(emu.action).setDataAndType(doc, "application/octet-stream");
                break;
            case YABA:
                intent.setAction(Intent.ACTION_VIEW).putExtra("org.uoyabause.android.FileNameUri", doc.toString());
                clearTask(intent);
                break;
            case VITA3K:
                intent.putExtra("AppStartParameters", new String[] { "-r", titleId(ctx, doc) });
                break;
            case WINLATOR:
                intent.putExtra("shortcut_path", pathFor(doc));
                clearTask(intent);
                break;
            case DATA:
                intent.setDataAndType(doc, "application/octet-stream");
                clearTask(intent);
                break;
            case VIEW_TYPED:
                intent.setAction(Intent.ACTION_VIEW).setDataAndType(doc, "application/octet-stream");
                break;
            default:
                intent.setAction(Intent.ACTION_VIEW).setData(doc);
                break;
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(system.name + " game", doc));
        // An intent grant only lives as long as the activity it lands on; some emulators (FZ's splash
        // screen, Dolphin's TV activity) hand the document on and finish, so grant the whole package.
        try { ctx.grantUriPermission(pkg, doc, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static void clearTask(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /** A Vita game is installed inside Vita3K; its .psvita file holds the title id to boot ("PCSE00082"). */
    private static String titleId(Context ctx, Uri doc) throws Exception {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(ctx.getContentResolver().openInputStream(doc), "UTF-8"))) {
            String line = in.readLine();
            if (line == null || !line.trim().matches("[A-Z]{4}\\d{5}")) throw new IllegalArgumentException("The .psvita file must contain a title id such as PCSE00082.");
            return line.trim();
        }
    }
}
