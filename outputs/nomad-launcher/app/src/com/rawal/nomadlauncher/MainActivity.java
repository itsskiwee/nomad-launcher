package com.rawal.nomadlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
    private static final int BACKGROUND = 0xff0c1118;
    private static final int TEXT = 0xfff3f6f4;
    private static final int MUTED = 0xffa7b4bf;
    private static final int BLUE = 0xffa0e4e3;
    private static final int PANEL = 0xff19242f;
    private static final int LINE = 0xff303d49;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private enum Screen { HOME, LIBRARY, SETTINGS }

    private Library library;
    private Emulators emulators;
    private Library.Snapshot snapshot = new Library.Snapshot();
    private Library.Snapshot homeSnapshot = snapshot;
    private Library.Game selected;
    private Screen screen = Screen.HOME;
    private Library.Shelf shelf = Library.Shelf.ALL;
    private Library.Shelf homeShelf = Library.Shelf.ALL;
    private GameSystem system;
    private String search = "";
    private String focusToRestore;
    private String selectionToRestore;
    private int page, hatDirection;
    private volatile int generation;
    private boolean busy, ready;
    private LinearLayout body, results, topTabs, homeRail, homeFilters;
    private ScrollView scroller;
    private HorizontalScrollView homeScroller;
    private EditText searchField;
    private TextView activityStatus, heroLabel, heroTitle, heroHint;
    private Button heroPlay, heroFavorite, heroOptions;
    private Artwork heroArt;
    private final List<View> homeCards = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = Library.get(this);
        emulators = new Emulators(this);
        if (state != null) {
            screen = Screen.valueOf(state.getString("screen", Screen.HOME.name()));
            shelf = Library.Shelf.valueOf(state.getString("shelf", Library.Shelf.ALL.name()));
            homeShelf = Library.Shelf.valueOf(state.getString("homeShelf", Library.Shelf.ALL.name()));
            String savedSystem = state.getString("system");
            if (savedSystem != null) system = GameSystem.valueOf(savedSystem);
            search = state.getString("search", "");
            page = state.getInt("page");
            focusToRestore = state.getString("focus");
            selectionToRestore = state.getString("selection");
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
        state.putString("homeShelf", homeShelf.name());
        state.putString("search", search);
        state.putInt("page", page);
        state.putString("focus", focusTag());
        if (selected != null) state.putString("selection", selected.uri.toString());
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
        int width = getResources().getConfiguration().screenWidthDp;
        int gutter = pageGutter();
        boolean wide = width >= 620;
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
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

        LinearLayout top = row();
        top.setPadding(dp(gutter), dp(4), dp(gutter), dp(4));
        LinearLayout brand = row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.nomad);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark, dimensions(27, 27));
        if (width >= 420) {
            TextView name = label("NOMAD", 14, TEXT, true);
            name.setLetterSpacing(.19f);
            name.setPadding(dp(9), 0, 0, 0);
            brand.addView(name, dimensions(WRAP, 50));
        }
        top.addView(brand, new LinearLayout.LayoutParams(0, WRAP, 1));
        topTabs = row();
        addTab(topTabs, "Home", Screen.HOME);
        addTab(topTabs, "Library", Screen.LIBRARY);
        addTab(topTabs, "Settings", Screen.SETTINGS);
        top.addView(topTabs, dimensions(WRAP, 50));
        root.addView(top, dimensions(MATCH, WRAP));
        addDivider(root);

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
        body.setPadding(dp(gutter), dp(wide ? 22 : 14), dp(gutter), dp(24));
        scroller.addView(body, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroller, new LinearLayout.LayoutParams(MATCH, 0, 1));
        setContentView(root);
        root.requestApplyInsets();

        heroArt = null;
        homeRail = null;
        homeFilters = null;
        homeScroller = null;
        homeCards.clear();
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

    private void addTab(LinearLayout tabs, String text, Screen target) {
        tabs.addView(tab(text, "tab-" + target.name(), screen == target, () -> navigate(target)), dimensions(WRAP, 50));
    }

    private int pageGutter() {
        int width = getResources().getConfiguration().screenWidthDp;
        return width >= 1000 ? 52 : width >= 620 ? 34 : 18;
    }

    private void navigate(Screen target) {
        if (screen == target) return;
        hideKeyboard();
        screen = target;
        if (target == Screen.HOME) snapshot = homeSnapshot;
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
        boolean hasGames = !snapshot.games.isEmpty();
        android.content.res.Configuration config = getResources().getConfiguration();
        boolean sideBySide = wide && config.screenWidthDp >= config.screenHeightDp;
        LinearLayout stage = sideBySide ? row() : column();
        LinearLayout caption = column();
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(0, dp(sideBySide ? 4 : 20), dp(sideBySide ? 32 : 0), dp(sideBySide ? 4 : 20));

        heroLabel = label("", 13, BLUE, true);
        caption.addView(heroLabel, dimensions(MATCH, WRAP));
        heroTitle = label("", wide ? 52 : 38, TEXT, true);
        heroTitle.setTypeface(MEDIUM);
        heroTitle.setLetterSpacing(-.035f);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setPadding(0, dp(8), 0, dp(8));
        caption.addView(heroTitle, dimensions(MATCH, WRAP));
        heroHint = label("", 16, MUTED, false);
        caption.addView(heroHint, dimensions(MATCH, WRAP));

        LinearLayout actions = row();
        actions.setPadding(0, dp(22), 0, 0);
        heroPlay = button("", "hero-play", true, this::activateHero);
        actions.addView(heroPlay, dimensions(WRAP, 50));
        heroFavorite = button("☆", "hero-favorite", false, () -> {
            if (selected != null) toggleFavorite(selected);
        });
        heroOptions = button("⋯", "hero-options", false, () -> {
            if (selected != null) gameActions(selected);
        });
        heroOptions.setContentDescription("Game options");
        for (Button action : new Button[]{heroFavorite, heroOptions}) {
            action.setTextSize(22);
            LinearLayout.LayoutParams box = dimensions(50, 50);
            box.setMarginStart(dp(8));
            actions.addView(action, box);
        }
        caption.addView(actions, dimensions(MATCH, WRAP));

        heroArt = new Artwork(this, true);
        if (sideBySide) {
            stage.addView(caption, new LinearLayout.LayoutParams(0, WRAP, 1f));
            stage.addView(heroArt, new LinearLayout.LayoutParams(0, MATCH, 1.1f));
            int stageHeight = Math.max(300, Math.min(config.screenHeightDp / 2, 490));
            body.addView(stage, dimensions(MATCH, stageHeight));
        } else {
            int artHeight = wide ? Math.min(390, (config.screenWidthDp - 2 * pageGutter()) * 9 / 16) : 205;
            if (hasGames) stage.addView(heroArt, dimensions(MATCH, artHeight));
            stage.addView(caption, dimensions(MATCH, WRAP));
            if (!hasGames) stage.addView(heroArt, dimensions(MATCH, artHeight));
            body.addView(stage, dimensions(MATCH, WRAP));
        }

        Library.Game previous = selected;
        selected = null;
        for (List<Library.Game> games : java.util.Arrays.asList(snapshot.games, snapshot.recent, snapshot.favorites)) {
            for (Library.Game game : games) {
                if (sameGame(game, previous) || game.uri.toString().equals(selectionToRestore)) selected = game;
            }
        }
        if (selected == null && hasGames) {
            selected = snapshot.recent.isEmpty() ? snapshot.games.get(0) : snapshot.recent.get(0);
        }
        if (ready && hasGames) selectionToRestore = null;
        if (hasGames) {
            homeRail = column();
            LinearLayout.LayoutParams railSpace = dimensions(MATCH, WRAP);
            railSpace.topMargin = dp(15);
            body.addView(homeRail, railSpace);
            renderHomeRail();
        }
        showHero(selected);
    }

    private void showHero(Library.Game game) {
        selected = game;
        if (heroArt == null) return;
        heroArt.show(game);
        for (View card : homeCards) card.setSelected(game != null && cardTag("home", game).equals(card.getTag()));
        heroFavorite.setVisibility(game == null ? View.GONE : View.VISIBLE);
        heroOptions.setVisibility(game == null ? View.GONE : View.VISIBLE);
        if (game == null) {
            boolean connected = snapshot.folders > 0;
            heroLabel.setVisibility(View.GONE);
            heroTitle.setText(connected ? "No games found" : "Add a game folder");
            heroHint.setText(connected ? "Scan again or choose a folder with supported games."
                    : "Choose a folder on this device to start your library.");
            heroPlay.setText(connected ? "Rescan folders" : "Connect folder");
            heroPlay.setEnabled(ready && !busy);
        } else {
            boolean recent = !snapshot.recent.isEmpty() && sameGame(snapshot.recent.get(0), game);
            heroLabel.setText("Continue playing");
            heroLabel.setVisibility(recent ? View.VISIBLE : View.GONE);
            heroTitle.setText(game.title);
            heroHint.setText(game.system.label);
            heroPlay.setText(recent ? "Continue" : "Play");
            heroPlay.setEnabled(!busy);
            heroFavorite.setText(game.favorite ? "★" : "☆");
            heroFavorite.setContentDescription(game.favorite ? "Remove from favorites" : "Add to favorites");
        }
        wireHomeFocus();
    }

    private void wireHomeFocus() {
        View home = topTabs.findViewWithTag("tab-HOME");
        for (int i = 0; i < topTabs.getChildCount(); i++) {
            topTabs.getChildAt(i).setNextFocusDownId(heroPlay.getId());
        }
        View activeFilter = homeFilters == null ? null : homeFilters.findViewWithTag("shelf-" + homeShelf.name());
        int filterId = activeFilter == null ? View.NO_ID : activeFilter.getId();
        for (Button action : new Button[]{heroPlay, heroFavorite, heroOptions}) {
            action.setNextFocusUpId(home.getId());
            action.setNextFocusDownId(filterId);
        }
        if (homeFilters == null) return;

        View entry = homeCards.isEmpty() ? null : homeCards.get(0);
        for (View card : homeCards) {
            card.setNextFocusUpId(filterId);
            if (card.isSelected()) entry = card;
        }
        for (int i = 0; i < homeFilters.getChildCount(); i++) {
            View filter = homeFilters.getChildAt(i);
            if (!filter.isFocusable()) continue;
            filter.setNextFocusUpId(heroPlay.getId());
            filter.setNextFocusDownId(entry == null ? View.NO_ID : entry.getId());
        }
    }

    private static boolean sameGame(Library.Game a, Library.Game b) {
        return a != null && b != null && a.uri.equals(b.uri) && a.root.equals(b.root);
    }

    private void activateHero() {
        if (busy || !ready) return;
        if (selected != null) play(selected);
        else if (snapshot.folders > 0) scanAll();
        else pickFolder();
    }

    private List<Library.Game> homeGames() {
        switch (homeShelf) {
            case FAVORITES: return snapshot.favorites;
            case RECENT: return snapshot.recent;
            default: return snapshot.games;
        }
    }

    private void renderHomeRail() {
        homeRail.removeAllViews();
        homeCards.clear();
        homeScroller = null;
        addDivider(homeRail);
        homeFilters = row();
        homeFilters.addView(shelfTab("All games", Library.Shelf.ALL), dimensions(WRAP, 48));
        homeFilters.addView(shelfTab("Favorites", Library.Shelf.FAVORITES), dimensions(WRAP, 48));
        homeFilters.addView(shelfTab("Recent", Library.Shelf.RECENT), dimensions(WRAP, 48));
        homeFilters.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        Button browseButton = tab("Browse all", "browse", false, () -> browse(homeShelf));
        boolean wide = getResources().getConfiguration().screenWidthDp >= 460;
        if (wide) homeFilters.addView(browseButton, dimensions(WRAP, 48));
        homeRail.addView(homeFilters, dimensions(MATCH, 48));

        List<Library.Game> games = homeGames();
        if (games.isEmpty()) {
            String empty = homeShelf == Library.Shelf.FAVORITES ? "No favorites yet." : "No games launched yet.";
            homeRail.addView(label(empty, 15, MUTED, false), dimensions(MATCH, 58));
        } else {
            addRail(homeRail, games, "home", 0, games.size(), 150);
        }
        if (!wide && !games.isEmpty()) homeRail.addView(browseButton, dimensions(WRAP, 48));
    }

    private Button shelfTab(String title, Library.Shelf target) {
        String key = "shelf-" + target.name();
        return tab(title, key, homeShelf == target, () -> {
            if (homeShelf == target) return;
            homeShelf = target;
            renderHomeRail();
            List<Library.Game> games = homeGames();
            Library.Game preview = !games.isEmpty() && games.stream().noneMatch(game -> sameGame(game, selected))
                    ? games.get(0) : selected;
            showHero(preview);
            restoreFocus(homeRail, key);
        });
    }

    private void addRail(LinearLayout parent, List<Library.Game> games, String key, int begin, int end, int width) {
        HorizontalScrollView rail = new HorizontalScrollView(this);
        if (key.equals("home")) homeScroller = rail;
        rail.setClipToPadding(false);
        rail.setHorizontalScrollBarEnabled(false);
        rail.setFocusable(false);
        LinearLayout cards = row();
        cards.setGravity(Gravity.TOP);
        cards.setPadding(dp(2), dp(8), dp(2), dp(7));
        for (int i = begin; i < end; i++) {
            Library.Game game = games.get(i);
            LinearLayout.LayoutParams box = dimensions(width, WRAP);
            if (i + 1 < end) box.setMarginEnd(dp(12));
            View card = card(game, key, width);
            if (key.equals("home")) homeCards.add(card);
            cards.addView(card, box);
        }
        rail.addView(cards, new HorizontalScrollView.LayoutParams(WRAP, WRAP));
        parent.addView(rail, dimensions(MATCH, WRAP));
    }

    private static String cardTag(String key, Library.Game game) {
        return key + "-" + game.uri;
    }

    private View card(Library.Game game, String key, int width) {
        LinearLayout card = column();
        card.setId(View.generateViewId());
        card.setTag(cardTag(key, game));
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(game.title + ", " + game.system.label + (game.favorite ? ", favorite" : ""));
        Artwork artwork = new Artwork(this, false);
        artwork.setBackground(shape(PANEL, Color.TRANSPARENT, 5));
        artwork.setClipToOutline(true);
        artwork.setForeground(tileRing());
        artwork.setDuplicateParentStateEnabled(true);
        artwork.show(game);
        card.addView(artwork, dimensions(MATCH, width * 2 / 3));
        TextView title = label(game.title + (game.favorite ? "  ★" : ""), 14, TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(title, dimensions(MATCH, 30));
        if (!key.equals("home")) {
            TextView type = label(game.system.label, 12, MUTED, false);
            type.setSingleLine(true);
            type.setEllipsize(TextUtils.TruncateAt.END);
            type.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(type, dimensions(MATCH, 20));
        }
        card.setOnFocusChangeListener((view, focused) -> {
            if (focused && screen == Screen.HOME) showHero(game);
        });
        card.setOnClickListener(view -> {
            if (screen != Screen.HOME) {
                gameActions(game);
            } else if (sameGame(game, selected)) {
                play(game);
            } else {
                showHero(game);
            }
        });
        card.setOnLongClickListener(view -> {
            gameActions(game);
            return true;
        });
        return card;
    }

    private void buildLibrary(boolean wide) {
        pageTitle(shelf == Library.Shelf.FAVORITES ? "Favorites"
                : shelf == Library.Shelf.RECENT ? "Recently launched" : "Library");
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
        searchField.setBackground(shape(PANEL, LINE, 6));
        searchField.setForeground(focusRing(6));
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
            int available = getResources().getConfiguration().screenWidthDp - (pageGutter() * 2 + 4);
            int perRail = Math.max(1, Math.min(8, (available + 12) / 148));
            int tileWidth = (available - (perRail - 1) * 12) / perRail;
            for (int begin = 0; begin < count; begin += perRail) {
                addRail(results, snapshot.games, "results", begin, Math.min(count, begin + perRail), tileWidth);
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
        pageTitle("Settings");
        section("Library");
        setting("Connect folder", "connect", this::pickFolder);
        setting("Manage folders", "folders", this::manageFolders);
        setting("Rescan library", "scan", this::scanAll);
        setting("Clear artwork cache", "cache", () -> {
            Artwork.clear();
            message("Artwork cache cleared.");
        });
        section("Playback");
        setting("Emulator preferences", "emulators", this::chooseEmulatorSystem);
        section("Device");
        setting("Default Home app", "default-home", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (ActivityNotFoundException error) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
    }

    private void pageTitle(String text) {
        TextView title = label(text, 36, TEXT, true);
        title.setTypeface(MEDIUM);
        title.setLetterSpacing(-.025f);
        body.addView(title, dimensions(MATCH, 72));
    }

    private void section(String title) {
        TextView heading = label(title, 14, MUTED, true);
        heading.setPadding(0, dp(8), 0, 0);
        body.addView(heading, dimensions(MATCH, 43));
    }

    private void setting(String title, String key, Runnable action) {
        LinearLayout item = row();
        item.setTag(key);
        item.setFocusable(true);
        item.setClickable(true);
        item.setForeground(focusRing(6));
        item.setContentDescription(title);
        item.setOnClickListener(view -> action.run());
        item.setPadding(dp(12), 0, dp(12), 0);
        TextView text = label(title, 17, TEXT, false);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(text, new LinearLayout.LayoutParams(0, MATCH, 1));
        TextView chevron = label("›", 24, MUTED, false);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(chevron, dimensions(20, MATCH));
        addDivider(body);
        body.addView(item, dimensions(MATCH, 53));
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
                        homeSnapshot = state;
                        int scroll = scroller.getScrollY();
                        int railScroll = homeScroller == null ? 0 : homeScroller.getScrollX();
                        String focus = focusTag();
                        if (focus != null) focusToRestore = focus;
                        render();
                        ScrollView view = scroller;
                        view.post(() -> view.scrollTo(0, scroll));
                        HorizontalScrollView rail = homeScroller;
                        if (rail != null) rail.post(() -> rail.scrollTo(railScroll, 0));
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
        button.setId(View.generateViewId());
        button.setTag(key);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(MEDIUM);
        int color = primary ? 0xff102020 : TEXT;
        button.setTextColor(new ColorStateList(new int[][]{ {-android.R.attr.state_enabled}, new int[0] },
                new int[]{primary ? 0xff42595a : MUTED, color}));
        button.setStateListAnimator(null);
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setPadding(dp(primary ? 20 : 14), 0, dp(primary ? 20 : 14), 0);
        button.setBackground(shape(primary ? BLUE : PANEL, primary ? BLUE : LINE, 6));
        button.setForeground(focusRing(6));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private Button tab(String text, String key, boolean active, Runnable action) {
        Button tab = button(text, key, false, action);
        tab.setTextColor(active ? TEXT : MUTED);
        tab.setSelected(active);
        if (active) {
            LayerDrawable underline = new LayerDrawable(new Drawable[]{
                    shape(Color.TRANSPARENT, Color.TRANSPARENT, 0), shape(BLUE, BLUE, 1)
            });
            underline.setLayerSize(1, dp(22), dp(2));
            underline.setLayerGravity(1, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            tab.setBackground(underline);
        } else {
            tab.setBackgroundColor(Color.TRANSPARENT);
        }
        return tab;
    }

    private void addDivider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(LINE);
        parent.addView(line, dimensions(MATCH, 1));
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
        GradientDrawable ring = shape(0x101cbabd, TEXT, radius);
        ring.setStroke(dp(2), TEXT);
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[]{android.R.attr.state_pressed}, shape(0x334c9df3, BLUE, radius));
        states.addState(new int[0], shape(Color.TRANSPARENT, Color.TRANSPARENT, radius));
        return states;
    }

    private StateListDrawable tileRing() {
        StateListDrawable tile = new StateListDrawable();
        GradientDrawable ring = shape(Color.TRANSPARENT, TEXT, 5);
        ring.setStroke(dp(2), TEXT);
        tile.addState(new int[]{android.R.attr.state_focused}, ring);
        tile.addState(new int[]{android.R.attr.state_pressed}, shape(0x331cbabd, BLUE, 5));
        tile.addState(new int[]{android.R.attr.state_selected}, shape(Color.TRANSPARENT, BLUE, 5));
        tile.addState(new int[0], shape(Color.TRANSPARENT, Color.TRANSPARENT, 5));
        return tile;
    }
}
