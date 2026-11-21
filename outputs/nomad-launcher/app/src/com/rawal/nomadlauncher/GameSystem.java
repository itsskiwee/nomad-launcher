package com.rawal.nomadlauncher;

import java.util.Locale;

enum GameSystem {
    NES("NES", 0xff569ddd),
    SNES("Super Nintendo", 0xff9b85e8),
    GB("Game Boy", 0xff73b79f),
    GBC("Game Boy Color", 0xff6cc9a9),
    GBA("Game Boy Advance", 0xff8998fc),
    GENESIS("Mega Drive / Genesis", 0xffdc8cb1),
    SMS("Master System", 0xffd69574),
    GG("Game Gear", 0xff6dbabd),
    PS1("PlayStation", 0xff73b4ff),
    N64("Nintendo 64", 0xff75bea9),
    DS("Nintendo DS", 0xff87abc7),
    PSP("PSP", 0xff77d5ee),
    DC("Dreamcast · experimental", 0xffe2a980);

    final String label;
    final int color;

    GameSystem(String label, int color) {
        this.label = label;
        this.color = color;
    }

    static GameSystem folder(String name) {
        switch (name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")) {
            case "nes": case "famicom": case "nintendoentertainmentsystem": return NES;
            case "snes": case "sfc": case "supernintendo": case "superfamicom": return SNES;
            case "gb": case "gameboy": return GB;
            case "gbc": case "gameboycolor": return GBC;
            case "gba": case "gameboyadvance": return GBA;
            case "md": case "megadrive": case "genesis": case "segagenesis": case "segamegadrive": return GENESIS;
            case "sms": case "mastersystem": case "segamastersystem": return SMS;
            case "gg": case "gamegear": case "segagamegear": return GG;
            case "ps1": case "psx": case "psone": case "playstation": case "sonyplaystation": return PS1;
            case "n64": case "nintendo64": return N64;
            case "ds": case "nds": case "nintendods": return DS;
            case "psp": case "playstationportable": case "sonypsp": return PSP;
            case "dc": case "dreamcast": case "segadreamcast": return DC;
            default: return null;
        }
    }

    static GameSystem detect(String name, GameSystem folder) {
        switch (extension(name)) {
            case "nes": case "fds": return NES;
            case "sfc": case "smc": return SNES;
            case "gb": return GB;
            case "gbc": return GBC;
            case "gba": return GBA;
            case "gen": case "md": case "smd": return GENESIS;
            case "sms": return SMS;
            case "gg": return GG;
            case "z64": case "n64": case "v64": return N64;
            case "nds": return DS;
            case "cso": return PSP;
            case "bin": return folder == GENESIS ? GENESIS : null;
            case "iso": case "chd": return folder == PS1 || folder == PSP || folder == DC ? folder : null;
            case "pbp": return folder == PS1 || folder == PSP ? folder : null;
            case "cdi": return DC;
            default: return null;
        }
    }

    static boolean isArtwork(String name) {
        switch (extension(name)) {
            case "png": case "jpg": case "jpeg": case "webp": return true;
            default: return false;
        }
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String title(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
