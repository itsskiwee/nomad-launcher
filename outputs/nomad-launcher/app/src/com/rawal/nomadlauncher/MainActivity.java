package com.rawal.nomadlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int PICK_FOLDER = 10;
    private static final int TEXT = 0xffedf4ff;
    private static final int MUTED = 0xffafc1da;
    private static final int BLUE = 0xffa7d0ff;
    private static final int PANEL = 0xff132136;
    private static final int LINE = 0xff2d405a;
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private enum Screen { HOME, LIBRARY, SETTINGS }

    private Library library;
    private Emulators emulators;
    private Library.Snapshot snapshot = new Library.Snapshot();
    private Library.Game selected;
    private Screen screen = Screen.HOME;
    private Library.Shelf shelf = Library.Shelf.ALL;
    private GameSystem system;
    private String search = "";
    private String focusToRestore;
    private int page, hatDirection;
    private volatile int generation;
    private boolean busy, ready;
    private LinearLayout body, results;
    private ScrollView scroller;
    private EditText searchField;
    private TextView activityStatus, heroLabel, heroTitle, heroHint;
    private Button heroPlay, heroFavorite;
    private Artwork heroArt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = Library.get(this);
        emulators = new Emulators(this);
        if (state != null) {
            screen = Screen.valueOf(state.getString("screen", Screen.HOME.name()));
            shelf = Library.Shelf.valueOf(state.getString("shelf", Library.Shelf.ALL.name()));
            String savedSystem = state.getString("system");
            if (savedSystem != null) system = GameSystem.valueOf(savedSystem);
            search = state.getString("search", "");
            page = state.getInt("page");
            focusToRestore = state.getString("focus");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
        render();
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", screen.name());
        state.putString("shelf", shelf.name());
        state.putString("search", search);
        state.putInt("page", page);
        state.putString("focus", focusTag());
        if (system != null) state.putString("system", system.name());
    }

    @Override protected void onPause() {
        hatDirection = 0;
        super.onPause();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) Artwork.clear();
    }

    private void render() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 620;
        LinearLayout root = column();
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff101d30, 0xff080e1a, 0xff0d1727}));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return WindowInsets.CONSUMED;
        });

        LinearLayout top = wide ? row() : column();
        top.setPadding(dp(wide ? 32 : 16), dp(8), dp(wide ? 32 : 16), dp(8));
        LinearLayout brand = row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.nomad);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView name = label("NOMAD", 17, TEXT, true);
        name.setLetterSpacing(.20f);
        name.setPadding(dp(9), 0, dp(16), 0);
        brand.addView(name, dimensions(WRAP, 44));
        top.addView(brand, wide ? new LinearLayout.LayoutParams(0, WRAP, 1) : dimensions(WRAP, WRAP));
        LinearLayout tabs = row();
        addTab(tabs, "Home", Screen.HOME, wide);
        addTab(tabs, "Library", Screen.LIBRARY, wide);
        addTab(tabs, "Settings", Screen.SETTINGS, wide);
        top.addView(tabs, dimensions(wide ? WRAP : MATCH, 48));
        root.addView(top, dimensions(MATCH, WRAP));

        activityStatus = label("", 13, BLUE, false);
        activityStatus.setPadding(dp(20), 0, dp(20), 0);
        activityStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        activityStatus.setVisibility(View.GONE);
        root.addView(activityStatus, dimensions(MATCH, 36));

        scroller = new ScrollView(this);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFocusable(false);
        body = column();
        body.setPadding(dp(wide ? 32 : 16), dp(10), dp(wide ? 32 : 16), dp(24));
        scroller.addView(body, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroller, new LinearLayout.LayoutParams(MATCH, 0, 1));
        setContentView(root);
        root.requestApplyInsets();

        heroArt = null;
        searchField = null;
        results = null;
        switch (screen) {
            case HOME: buildHome(wide); break;
            case LIBRARY: buildLibrary(wide); break;
            case SETTINGS: buildSettings(); break;
        }
        if (!ready) status("Opening library…");
        else if (busy) status("Working…");
        restoreFocus(root, focusToRestore);
    }

    private void addTab(LinearLayout tabs, String text, Screen target, boolean wide) {
        LinearLayout.LayoutParams box = wide ? dimensions(WRAP, 48) : weighted(48);
        if (tabs.getChildCount() > 0) box.setMarginStart(dp(6));
        tabs.addView(button(text, "tab-" + target.name(), screen == target, () -> navigate(target)), box);
    }

    private void navigate(Screen target) {
        if (screen == target) return;
        hideKeyboard();
        screen = target;
        focusToRestore = "tab-" + target.name();
        if (target == Screen.LIBRARY) {
            shelf = Library.Shelf.ALL;
            system = null;
            search = "";
            page = 0;
        }
        render();
        refresh();
    }

    private void browse(Library.Shelf next) {
        screen = Screen.LIBRARY;
        shelf = next;
        system = null;
        search = "";
        page = 0;
        focusToRestore = "filter";
        render();
        refresh();
    }

    private void buildHome(boolean wide) {
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(shape(PANEL, LINE, 22));
        hero.setClipToOutline(true);
        heroArt = new Artwork(this, true);
        hero.addView(heroArt, new FrameLayout.LayoutParams(MATCH, MATCH));
        hero.addView(new Artwork.Shade(this), new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout caption = column();
        caption.setGravity(Gravity.CENTER_VERTICAL);
        int padding = dp(wide ? 30 : 18);
        caption.setPadding(padding, dp(18), padding, dp(18));
        heroLabel = label("", 12, BLUE, true);
        heroLabel.setLetterSpacing(.15f);
        caption.addView(heroLabel, dimensions(MATCH, WRAP));
        heroTitle = label("", wide ? 38 : 30, TEXT, true);
        heroTitle.setMaxLines(wide ? 2 : 3);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setPadding(0, dp(6), 0, dp(4));
        caption.addView(heroTitle, dimensions(MATCH, WRAP));
        heroHint = label("", 14, MUTED, false);
        heroHint.setMaxLines(3);
        caption.addView(heroHint, dimensions(MATCH, WRAP));
        LinearLayout actions = row();
        actions.setPadding(0, dp(14), 0, 0);
        heroPlay = button("Play", "hero-play", true, this::activateHero);
        actions.addView(heroPlay, dimensions(WRAP, 50));
        heroFavorite = button("☆", "hero-favorite", false, () -> {
            if (selected != null) toggleFavorite(selected);
        });
        LinearLayout.LayoutParams star = dimensions(52, 50);
        star.setMarginStart(dp(8));
        actions.addView(heroFavorite, star);
        caption.addView(actions, dimensions(MATCH, WRAP));
        hero.addView(caption, new FrameLayout.LayoutParams(wide ? dp(Math.min(560,
                getResources().getConfiguration().screenWidthDp * 2 / 3)) : MATCH, MATCH));
        body.addView(hero, dimensions(MATCH, wide ? 250 : 262));

        Library.Game first = snapshot.recent.isEmpty()
                ? (snapshot.games.isEmpty() ? null : snapshot.games.get(0)) : snapshot.recent.get(0);
        Library.Game previous = selected;
        selected = null;
        if (previous != null) {
            for (List<Library.Game> list : java.util.Arrays.asList(snapshot.games, snapshot.recent, snapshot.favorites)) {
                for (Library.Game game : list) {
                    if (game.uri.equals(previous.uri) && game.root.equals(previous.root)) selected = game;
                }
            }
        }
        showHero(selected == null ? first : selected);
        if (!snapshot.recent.isEmpty()) addShelf(body, "Recently launched", snapshot.recent, "recent", Library.Shelf.RECENT);
        if (!snapshot.favorites.isEmpty()) addShelf(body, "Favorites", snapshot.favorites, "favorites", Library.Shelf.FAVORITES);
        if (!snapshot.games.isEmpty()) addShelf(body, "Game library", snapshot.games, "all", Library.Shelf.ALL);
    }

    private void showHero(Library.Game game) {
        selected = game;
        if (heroArt == null) return;
        heroArt.show(game);
        heroFavorite.setVisibility(game == null ? View.GONE : View.VISIBLE);
        if (game == null) {
            boolean connected = snapshot.folders > 0;
            heroLabel.setText("OFFLINE LIBRARY");
            heroTitle.setText(connected ? "No games found" : "Connect your game library");
            heroHint.setText(connected ? "Scan again or connect a folder with supported game files."
                    : "Connect a folder to browse and launch the games you already have.");
            heroPlay.setText(connected ? "Rescan folders" : "Connect folder");
            heroPlay.setEnabled(ready && !busy);
        } else {
            boolean recent = !snapshot.recent.isEmpty() && snapshot.recent.get(0).uri.equals(game.uri);
            heroLabel.setText(recent ? "CONTINUE PLAYING" : game.system.label.toUpperCase(java.util.Locale.ROOT));
            heroTitle.setText(game.title);
            heroHint.setText(recent ? game.system.label : "");
            heroHint.setVisibility(recent ? View.VISIBLE : View.GONE);
            heroPlay.setText(recent ? "Continue" : "Play");
            heroPlay.setEnabled(!busy);
            heroFavorite.setText(game.favorite ? "★" : "☆");
            heroFavorite.setContentDescription(game.favorite ? "Remove from favorites" : "Add to favorites");
            return;
        }
        heroHint.setVisibility(View.VISIBLE);
    }

    private void activateHero() {
        if (busy || !ready) return;
        if (selected != null) play(selected);
        else if (snapshot.folders > 0) scanAll();
        else pickFolder();
    }

    private void addShelf(LinearLayout parent, String heading, List<Library.Game> games, String key, Library.Shelf link) {
        LinearLayout header = row();
        header.setPadding(0, dp(10), 0, 0);
        TextView title = label(heading, 20, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        if (link != null) {
            Button more = button("Browse", key + "-browse", false, () -> browse(link));
            more.setContentDescription("Browse " + heading.toLowerCase(java.util.Locale.ROOT));
            header.addView(more, dimensions(WRAP, 48));
        }
        parent.addView(header, dimensions(MATCH, WRAP));
        addRail(parent, games, key, 0, games.size());
    }

    private void addRail(LinearLayout parent, List<Library.Game> games, String key, int begin, int end) {
        HorizontalScrollView rail = new HorizontalScrollView(this);
        rail.setClipToPadding(false);
        rail.setHorizontalScrollBarEnabled(false);
        rail.setFocusable(false);
        LinearLayout cards = row();
        cards.setGravity(Gravity.TOP);
        cards.setPadding(dp(2), dp(3), dp(2), dp(6));
        for (int i = begin; i < end; i++) {
            Library.Game game = games.get(i);
            LinearLayout.LayoutParams box = dimensions(166, WRAP);
            box.setMarginEnd(dp(10));
            cards.addView(card(game, key), box);
        }
        rail.addView(cards, new HorizontalScrollView.LayoutParams(WRAP, WRAP));
        parent.addView(rail, dimensions(MATCH, WRAP));
    }

    private View card(Library.Game game, String key) {
        LinearLayout card = column();
        card.setTag(key + "-" + game.uri);
        card.setBackground(shape(PANEL, LINE, 13));
        card.setForeground(focusRing(13));
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(game.title + ", " + game.system.label + (game.favorite ? ", favorite" : ""));
        Artwork artwork = new Artwork(this, false);
        artwork.show(game);
        card.addView(artwork, dimensions(MATCH, 96));
        TextView title = label(game.title, 14, TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(10), 0, dp(10), 0);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(title, dimensions(MATCH, 30));
        TextView type = label(game.system.label + (game.favorite ? "  ★" : ""), 11, MUTED, false);
        type.setSingleLine(true);
        type.setEllipsize(TextUtils.TruncateAt.END);
        type.setPadding(dp(10), 0, dp(10), dp(5));
        type.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(type, dimensions(MATCH, 21));
        card.setOnFocusChangeListener((view, focused) -> {
            if (focused && screen == Screen.HOME) showHero(game);
        });
        card.setOnClickListener(view -> {
            if (screen == Screen.HOME) showHero(game);
            gameActions(game);
        });
        return card;
    }

    private void buildLibrary(boolean wide) {
        body.addView(label(shelf == Library.Shelf.FAVORITES ? "Favorites"
                : shelf == Library.Shelf.RECENT ? "Recently launched" : "Game library", 30, TEXT, true),
                dimensions(MATCH, 56));
        LinearLayout controls = wide ? row() : column();
        searchField = new EditText(this);
        searchField.setTag("search");
        searchField.setTextColor(TEXT);
        searchField.setHintTextColor(MUTED);
        searchField.setTextSize(16);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setHint("Search games");
        searchField.setText(search);
        searchField.setBackground(shape(PANEL, LINE, 11));
        searchField.setForeground(focusRing(11));
        searchField.setPadding(dp(14), 0, dp(14), 0);
        searchField.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            return true;
        });
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                search = value.toString();
                page = 0;
                refresh();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        controls.addView(searchField, wide ? weighted(50) : dimensions(MATCH, 50));
        LinearLayout.LayoutParams filterSpace = dimensions(wide ? WRAP : MATCH, 50);
        if (wide) filterSpace.setMarginStart(dp(8));
        else filterSpace.topMargin = dp(8);
        controls.addView(button(system == null ? "All systems" : system.label, "filter", false, this::chooseSystem),
                filterSpace);
        body.addView(controls, dimensions(MATCH, WRAP));
        results = column();
        results.setPadding(0, dp(14), 0, 0);
        body.addView(results, dimensions(MATCH, WRAP));
    }

    private void renderResults() {
        if (results == null) return;
        String focus = focusTag();
        results.removeAllViews();
        if (snapshot.games.isEmpty()) {
            String empty = !search.trim().isEmpty() ? "No games match your search."
                    : shelf == Library.Shelf.FAVORITES ? "No favorites yet."
                    : shelf == Library.Shelf.RECENT ? "No games launched yet."
                    : snapshot.folders == 0 ? "Connect a folder to start your library." : "No games match this system.";
            TextView message = label(empty, 15, MUTED, false);
            results.addView(message, dimensions(MATCH, 64));
            if (snapshot.folders == 0) results.addView(button("Connect folder", "results-connect", true, this::pickFolder),
                    dimensions(WRAP, 48));
        } else {
            int count = Math.min(Library.PAGE, snapshot.games.size());
            int width = getResources().getConfiguration().screenWidthDp;
            int perRail = Math.max(2, Math.min(8, (width - 48) / 176));
            for (int begin = 0; begin < count; begin += perRail) {
                addRail(results, snapshot.games, "results", begin, Math.min(count, begin + perRail));
            }
        }
        if (page > 0 || snapshot.games.size() > Library.PAGE) {
            LinearLayout pages = row();
            if (page > 0) pages.addView(button("Previous page", "previous", false, () -> changePage(-1)), dimensions(WRAP, 48));
            if (snapshot.games.size() > Library.PAGE) pages.addView(button("Next page", "next", false, () -> changePage(1)),
                    dimensions(WRAP, 48));
            results.addView(pages, dimensions(MATCH, WRAP));
        }
        restoreFocus(getWindow().getDecorView(), focus);
    }

    private void changePage(int offset) {
        page += offset;
        refresh();
        scroller.scrollTo(0, 0);
    }

    private void chooseSystem() {
        List<GameSystem> systems = snapshot.systems;
        String[] names = new String[systems.size() + 1];
        names[0] = "All systems";
        for (int i = 0; i < systems.size(); i++) names[i + 1] = systems.get(i).label;
        dialog().setTitle("Filter by system").setSingleChoiceItems(names, system == null ? 0 : systems.indexOf(system) + 1,
                (dialog, which) -> {
                    system = which == 0 ? null : systems.get(which - 1);
                    page = 0;
                    dialog.dismiss();
                    Button filter = body.findViewWithTag("filter");
                    filter.setText(names[which]);
                    refresh();
                }).setNegativeButton("Cancel", null).show();
    }

    private void buildSettings() {
        body.addView(label("Settings", 30, TEXT, true), dimensions(MATCH, 58));
        section("Game folders");
        setting("Connect folder", "Grant read access to a game folder.", "connect", this::pickFolder);
        setting("Manage folders", "Rescan or disconnect individual folders.", "folders", this::manageFolders);
        setting("Rescan library", "Update the index from connected folders.", "scan", this::scanAll);
        section("Playback");
        setting("Emulator preferences", "Choose an Android app for each system.", "emulators", this::chooseEmulatorSystem);
        section("Device");
        setting("Clear artwork cache", "Reload local artwork the next time it is shown.", "cache", () -> {
            Artwork.clear();
            message("Artwork cache cleared.");
        });
        setting("Default Home app", "Open Android settings to choose your Home launcher.", "default-home", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (ActivityNotFoundException error) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
    }

    private void section(String title) {
        body.addView(label(title, 18, BLUE, true), dimensions(MATCH, 45));
    }

    private void setting(String title, String hint, String key, Runnable action) {
        Button control = button(title, key, false, action);
        control.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        body.addView(control, dimensions(MATCH, 50));
        TextView note = label(hint, 13, MUTED, false);
        note.setPadding(dp(16), 0, dp(12), dp(8));
        body.addView(note, dimensions(MATCH, WRAP));
    }

    private void chooseEmulatorSystem() {
        List<GameSystem> systems = snapshot.systems;
        if (systems.isEmpty()) {
            message("Connect a folder with supported games first.");
            return;
        }
        String[] names = new String[systems.size()];
        for (int i = 0; i < names.length; i++) names[i] = systems.get(i).label;
        dialog().setTitle("Choose a system").setItems(names, (dialog, which) -> {
            GameSystem target = systems.get(which);
            Library.IO.execute(() -> {
                try {
                    List<Library.Game> games = library.snapshot(false, Library.Shelf.ALL, target, "", 0).games;
                    showOnMain(() -> {
                        if (games.isEmpty()) message("Scan a game for this system first.");
                        else choosePlayer(games.get(0), false);
                    });
                } catch (RuntimeException error) {
                    showOnMain(() -> message("Could not read this system from the library."));
                }
            });
        }).setNegativeButton("Cancel", null).show();
    }

    private void gameActions(Library.Game game) {
        if (busy) return;
        String[] actions = {"Play", game.favorite ? "Remove from favorites" : "Add to favorites", "Choose emulator"};
        dialog().setTitle(game.title).setItems(actions, (dialog, which) -> {
            if (which == 0) play(game);
            else if (which == 1) toggleFavorite(game);
            else choosePlayer(game, false);
        }).setNegativeButton("Close", null).show();
    }

    private void toggleFavorite(Library.Game game) {
        change("Updating favorites…", () -> {
            library.favorite(game);
            return game.favorite ? "Removed from favorites." : "Added to favorites.";
        });
    }

    private void play(Library.Game game) {
        if (busy) return;
        List<Emulators.Player> players = emulators.discover(game);
        Emulators.Player preferred = emulators.preferred(game.system, players);
        if (preferred != null) launch(game, preferred);
        else if (players.size() == 1 && game.system == GameSystem.PSP && players.get(0).isPpsspp()) {
            launch(game, players.get(0));
        } else choosePlayer(game, true);
    }

    private void choosePlayer(Library.Game game, boolean play) {
        List<Emulators.Player> players = emulators.discover(game);
        if (players.isEmpty()) {
            dialog().setTitle("No compatible emulator found")
                    .setMessage("Install a " + game.system.label + " emulator that can open Android content URIs, then try again."
                            + (game.system == GameSystem.PSP ? " Nomad can hand off PSP games to PPSSPP." : ""))
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] choices = new String[players.size() + (play ? 0 : 1)];
        Emulators.Player preferred = emulators.preferred(game.system, players);
        for (int i = 0; i < players.size(); i++) {
            Emulators.Player player = players.get(i);
            choices[i] = player.label + (preferred != null && preferred.component.equals(player.component) ? " (preferred)" : "");
        }
        if (!play) choices[players.size()] = "Use automatic selection";
        dialog().setTitle(play ? "Open with" : game.system.label + " emulator")
                .setItems(choices, (dialog, which) -> {
                    Emulators.Player player = which == players.size() ? null : players.get(which);
                    if (play) launch(game, player);
                    else {
                        emulators.remember(game.system, player);
                        message(player == null ? "Automatic selection restored." : "Preferred emulator saved.");
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void launch(Library.Game game, Emulators.Player player) {
        try {
            emulators.launch(this, game, player);
            change(null, () -> {
                library.launched(game);
                return null;
            });
        } catch (ActivityNotFoundException | SecurityException error) {
            dialog().setTitle("Could not open " + game.title)
                    .setMessage("Confirm the game folder is still connected and the emulator can read this file format.")
                    .setPositiveButton("OK", null).show();
        }
    }

    private void pickFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_FOLDER);
        } catch (ActivityNotFoundException error) {
            message("Android’s folder picker is unavailable.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        int flags = data.getFlags();
        change("Scanning selected folder…", () -> {
            Library.Folder folder = library.connect(tree, flags);
            return scanMessage(library.scan(folder));
        });
    }

    private void manageFolders() {
        if (busy) return;
        Library.IO.execute(() -> {
            try {
                List<Library.Folder> folders = library.folders();
                showOnMain(() -> {
                    if (folders.isEmpty()) {
                        message("No game folders are connected.");
                        return;
                    }
                    String[] names = new String[folders.size()];
                    for (int i = 0; i < names.length; i++) names[i] = folders.get(i).name;
                    dialog().setTitle("Game folders").setItems(names, (dialog, which) -> folderActions(folders.get(which)))
                            .setNegativeButton("Close", null).show();
                });
            } catch (RuntimeException error) {
                showOnMain(() -> message("Could not read connected folders."));
            }
        });
    }

    private void folderActions(Library.Folder folder) {
        dialog().setTitle(folder.name).setItems(new String[]{"Rescan folder", "Disconnect folder"}, (dialog, which) -> {
            if (which == 0) change("Scanning " + folder.name + "…", () -> scanMessage(library.scan(folder)));
            else dialog().setTitle("Disconnect " + folder.name + "?")
                    .setMessage("Remove this folder and its favorites and recents from Nomad. Your game files stay in place.")
                    .setPositiveButton("Disconnect", (confirmation, button) ->
                            change("Disconnecting folder…", () -> {
                                library.disconnect(folder);
                                return "Folder disconnected.";
                            })).setNegativeButton("Cancel", null).show();
        }).setNegativeButton("Close", null).show();
    }

    private void scanAll() {
        change("Scanning connected folders…", () -> {
            List<Library.Folder> folders = library.folders();
            if (folders.isEmpty()) return "Connect a folder before scanning.";
            int total = 0;
            List<String> problems = new ArrayList<>();
            for (Library.Folder folder : folders) {
                try {
                    total += library.scan(folder);
                } catch (IOException | RuntimeException error) {
                    problems.add(scanError(error, folder.name));
                }
            }
            if (!problems.isEmpty()) return scanMessage(total) + "\n" + TextUtils.join("\n", problems);
            return scanMessage(total);
        });
    }

    private static String scanMessage(int count) {
        return count == 1 ? "1 game indexed." : count + " games indexed.";
    }

    private static String scanError(Exception error, String folder) {
        if (error instanceof IOException) return error.getMessage();
        if (error instanceof SecurityException) return "Reconnect " + folder + " to restore read access.";
        return "Could not scan " + folder + ".";
    }

    private interface Change { String run() throws IOException; }

    private void change(String progress, Change work) {
        if (busy) return;
        busy = true;
        if (progress != null) status(progress);
        Library.IO.execute(() -> {
            String outcome;
            try {
                outcome = work.run();
            } catch (IOException error) {
                outcome = error.getMessage();
            } catch (SecurityException error) {
                outcome = "Folder access was lost. Reconnect the folder and try again.";
            } catch (RuntimeException error) {
                android.util.Log.e("NomadLauncher", "Library update failed", error);
                outcome = "Could not update the library.";
            }
            String result = outcome;
            showOnMain(() -> {
                busy = false;
                if (result != null && result.contains("\n")) {
                    dialog().setTitle("Scan results").setMessage(result).setPositiveButton("OK", null).show();
                } else if (result != null) {
                    message(result);
                }
                refresh();
            });
        });
    }

    private void refresh() {
        int token = ++generation;
        Screen target = screen;
        Library.Shelf targetShelf = shelf;
        GameSystem targetSystem = system;
        String text = search.trim();
        int targetPage = page;
        Library.IO.execute(() -> {
            try {
                if (token != generation) return;
                Library.Snapshot state = library.snapshot(target == Screen.HOME, targetShelf, targetSystem, text, targetPage);
                showOnMain(() -> {
                    if (token != generation) return;
                    ready = true;
                    snapshot = state;
                    if (target == Screen.LIBRARY && targetPage > 0 && state.games.isEmpty()) {
                        page = targetPage - 1;
                        refresh();
                        return;
                    }
                    status(busy ? "Working…" : null);
                    if (target == Screen.HOME) {
                        int scroll = scroller.getScrollY();
                        String focus = focusTag();
                        if (focus != null) focusToRestore = focus;
                        render();
                        ScrollView view = scroller;
                        view.post(() -> view.scrollTo(0, scroll));
                    } else if (target == Screen.LIBRARY) {
                        renderResults();
                    }
                });
            } catch (RuntimeException error) {
                android.util.Log.e("NomadLauncher", "Library read failed", error);
                showOnMain(() -> {
                    if (token == generation) status("Could not read the library.");
                });
            }
        });
    }

    private void status(String text) {
        activityStatus.setText(text);
        activityStatus.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void showOnMain(Runnable action) {
        runOnUiThread(() -> {
            if (!isDestroyed() && !isFinishing()) action.run();
        });
    }

    private void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this).setOnKeyListener((dialog, key, event) -> {
            if (key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
                return true;
            }
            if (!(dialog instanceof AlertDialog)) return false;
            return ((AlertDialog) dialog).getWindow().superDispatchKeyEvent(confirmKey(event));
        });
    }

    private void hideKeyboard() {
        if (searchField != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        }
    }

    @Override public void onBackPressed() { goBack(); }

    private void goBack() {
        if (screen != Screen.HOME) navigate(Screen.HOME);
        else finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) goBack();
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A) {
            return super.dispatchKeyEvent(confirmKey(event));
        }
        return super.dispatchKeyEvent(event);
    }

    private static KeyEvent confirmKey(KeyEvent original) {
        return new KeyEvent(original.getDownTime(), original.getEventTime(), original.getAction(),
                KeyEvent.KEYCODE_DPAD_CENTER, original.getRepeatCount(), original.getMetaState(),
                original.getDeviceId(), original.getScanCode(), original.getFlags(), original.getSource());
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int direction = x < -.5f ? View.FOCUS_LEFT : x > .5f ? View.FOCUS_RIGHT
                : y < -.5f ? View.FOCUS_UP : y > .5f ? View.FOCUS_DOWN : 0;
        if (direction != 0 && direction != hatDirection) {
            View focused = getCurrentFocus();
            View next = focused == null ? getWindow().getDecorView().findViewWithTag("tab-" + screen.name())
                    : focused.focusSearch(direction);
            if (next != null) {
                if (next.isInTouchMode()) next.requestFocusFromTouch();
                else next.requestFocus(direction);
            }
        }
        boolean handled = direction != 0 || hatDirection != 0;
        hatDirection = direction;
        return handled || super.dispatchGenericMotionEvent(event);
    }

    private String focusTag() {
        View focus = getCurrentFocus();
        return focus != null && focus.getTag() instanceof String ? (String) focus.getTag() : null;
    }

    private void restoreFocus(View root, String tag) {
        if (tag == null || root.isInTouchMode()) return;
        View view = root.findViewWithTag(tag);
        if (view != null) view.requestFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int size(int value) { return value < 0 ? value : dp(value); }

    private LinearLayout.LayoutParams dimensions(int width, int height) {
        return new LinearLayout.LayoutParams(size(width), size(height));
    }

    private LinearLayout.LayoutParams weighted(int height) {
        return new LinearLayout.LayoutParams(0, dp(height), 1);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private Button button(String text, String key, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setTag(key);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? 0xff071522 : TEXT);
        button.setStateListAnimator(null);
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(shape(primary ? BLUE : PANEL, primary ? BLUE : LINE, 11));
        button.setForeground(focusRing(11));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), border);
        return shape;
    }

    private StateListDrawable focusRing(int radius) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable ring = shape(Color.TRANSPARENT, Color.WHITE, radius);
        ring.setStroke(dp(3), Color.WHITE);
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[]{android.R.attr.state_pressed}, shape(0x334c9df3, BLUE, radius));
        states.addState(new int[0], shape(Color.TRANSPARENT, Color.TRANSPARENT, radius));
        return states;
    }
}
