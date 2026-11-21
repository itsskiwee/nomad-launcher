package com.rawal.nomadlauncher;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Emulators {
    private static final String PSP_ACTIVITY = "org.ppsspp.ppsspp.PpssppActivity";
    private static final String[] PSP_PACKAGES = {"org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"};

    static final class Player {
        final ComponentName component;
        final String label, mime;

        Player(ComponentName component, String label, String mime) {
            this.component = component;
            this.label = label;
            this.mime = mime;
        }

        boolean isPpsspp() {
            if (!component.getClassName().equals(PSP_ACTIVITY)) return false;
            for (String name : PSP_PACKAGES) {
                if (component.getPackageName().equals(name)) return true;
            }
            return false;
        }
    }

    private final PackageManager pm;
    private final SharedPreferences settings;

    Emulators(Context context) {
        pm = context.getPackageManager();
        settings = context.getSharedPreferences("emulators", Context.MODE_PRIVATE);
    }

    List<Player> discover(Library.Game game) {
        Map<ComponentName, Player> choices = new LinkedHashMap<>();
        if (game.system == GameSystem.PSP) {
            for (String name : PSP_PACKAGES) {
                ComponentName activity = new ComponentName(name, PSP_ACTIVITY);
                try {
                    ActivityInfo info = pm.getActivityInfo(activity, 0);
                    if (info.exported && info.enabled && info.applicationInfo.enabled) {
                        choices.put(activity, new Player(activity, info.applicationInfo.loadLabel(pm).toString(), null));
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                    // This edition of PPSSPP is not installed.
                }
            }
        }
        addViewHandlers(choices, game, null);
        if (game.mime != null && game.mime.contains("/") && !game.mime.contains("*")) {
            addViewHandlers(choices, game, game.mime);
        }
        // Generic binary handlers are shown only with the provider's actual MIME type.
        return new ArrayList<>(choices.values());
    }

    private void addViewHandlers(Map<ComponentName, Player> choices, Library.Game game, String mime) {
        Intent query = new Intent(Intent.ACTION_VIEW).setDataAndType(game.uri, mime);
        for (ResolveInfo result : pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo info = result.activityInfo;
            if (info == null || !info.exported) continue;
            ComponentName component = new ComponentName(info.packageName, info.name);
            choices.putIfAbsent(component, new Player(component, result.loadLabel(pm).toString(), mime));
        }
    }

    Player preferred(GameSystem system, List<Player> players) {
        String saved = settings.getString(system.name(), "");
        for (Player player : players) {
            if (player.component.flattenToString().equals(saved)) return player;
        }
        return null;
    }

    void remember(GameSystem system, Player player) {
        SharedPreferences.Editor edit = settings.edit();
        if (player == null) edit.remove(system.name());
        else edit.putString(system.name(), player.component.flattenToString());
        edit.apply();
    }

    void launch(Context context, Library.Game game, Player player)
            throws ActivityNotFoundException, SecurityException {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(player.component)
                .setDataAndType(game.uri, player.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(game.filename, game.uri));
        context.startActivity(intent);
    }
}
