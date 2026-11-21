package com.rawal.nomadlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.window.OnBackInvokedDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int PICK_FOLDER = 10;
    private static final int BACKGROUND = 0xff0b111a;
    static final int TEXT = 0xfff3f6fc;
    static final int MUTED = 0xffafbbce;
    static final int BLUE = 0xffc0d8ff;
    private static final int PANEL = 0xff192437;
    static final int LINE = 0xff354156;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private enum Screen { HOME, LIBRARY, SETTINGS }

    private enum SettingsGroup {
        LIBRARY("Game folders"), PLAYBACK("Playback"), DEVICE("Device");
        final String title;
        SettingsGroup(String title) { this.title = title; }
    }

    private Library library;
    private Emulators emulators;
    private Library.Snapshot snapshot = new Library.Snapshot();
    private Library.Snapshot homeSnapshot = snapshot;
    private Library.Game selected;
    private Screen screen = Screen.HOME;
    private Library.Shelf shelf = Library.Shelf.ALL;
    private Library.Shelf homeShelf = Library.Shelf.ALL;
    private SettingsGroup settingsGroup = SettingsGroup.LIBRARY;
    private GameSystem system;
    private String search = "";
    private String focusToRestore;
    private String selectionToRestore;
    private int page, hatDirection;
    private volatile int generation;
    private boolean busy, ready;
    private LinearLayout body, results, topTabs, homeRail, homeFilters, settingsNav, settingsPanel;
    private ScrollView scroller;
    private HorizontalScrollView homeScroller;
    private EditText searchField;
    private TextView activityStatus, heroLabel, heroTitle, heroHint, feedback;
    private String feedbackText;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable clearFeedback = () -> {
        feedbackText = null;
        if (feedback != null) feedback.setVisibility(View.GONE);
    };
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
            settingsGroup = SettingsGroup.valueOf(state.getString("settingsGroup", SettingsGroup.LIBRARY.name()));
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
        state.putString("settingsGroup", settingsGroup.name());
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

    @Override protected void onDestroy() {
        ui.removeCallbacks(clearFeedback);
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) Artwork.clear();
    }

    private void render() {
        int width = getResources().getConfiguration().screenWidthDp;
        int gutter = pageGutter();
        boolean wide = width >= 620;
        boolean home = screen == Screen.HOME;
        LinearLayout root = column();
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
        if (width >= 480) {
            TextView name = label("NOMAD", 14, TEXT, true);
            name.setLetterSpacing(.19f);
            name.setPadding(dp(9), 0, 0, 0);
            brand.addView(name, dimensions(WRAP, 50));
        }
        LinearLayout.LayoutParams brandSpace = dimensions(WRAP, 50);
        brandSpace.setMarginEnd(dp(wide ? 24 : 6));
        top.addView(brand, brandSpace);
        topTabs = row();
        addTab(topTabs, "Home", Screen.HOME);
        addTab(topTabs, "Library", Screen.LIBRARY);
        topTabs.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        addTab(topTabs, "Settings", Screen.SETTINGS);
        top.addView(topTabs, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(top, dimensions(MATCH, WRAP));
        if (!home) addDivider(root);

        activityStatus = label("", 13, BLUE, false);
        activityStatus.setPadding(dp(20), 0, dp(20), 0);
        activityStatus.setBackgroundColor(0x660b1524);
        activityStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        activityStatus.setVisibility(View.GONE);
        root.addView(activityStatus, dimensions(MATCH, 36));

        scroller = new ScrollView(this);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFocusable(false);
        scroller.setFillViewport(home);
        body = column();
        body.setPadding(dp(gutter), dp(home ? 6 : wide ? 22 : 14), dp(gutter), dp(home ? 42 : 24));
        scroller.addView(body, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroller, new LinearLayout.LayoutParams(MATCH, 0, 1));
        FrameLayout scene = new FrameLayout(this);
        scene.setBackgroundColor(BACKGROUND);
        Artwork backdrop = new Artwork(this, true);
        if (!home) backdrop.dimForMenu();
        heroArt = home ? backdrop : null;
        scene.addView(backdrop, new FrameLayout.LayoutParams(MATCH, MATCH));
        scene.addView(root, new FrameLayout.LayoutParams(MATCH, MATCH));

        feedback = label("", 14, TEXT, false);
        feedback.setPadding(dp(20), dp(12), dp(20), dp(12));
        feedback.setMaxWidth(dp(Math.min(440, width - 2 * gutter)));
        feedback.setBackground(shape(0xf2121e30, 0xff4a5e79, 12));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams feedbackBox = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        scene.addView(feedback, feedbackBox);
        TextView noticeView = feedback;
        root.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int bottom = root.getPaddingBottom() + dp(20);
            int end = root.getPaddingEnd() + dp(gutter);
            if (feedbackBox.bottomMargin != bottom || feedbackBox.getMarginEnd() != end) {
                feedbackBox.bottomMargin = bottom;
                feedbackBox.setMarginEnd(end);
                noticeView.setLayoutParams(feedbackBox);
            }
        });
        showFeedback();
        setContentView(scene);
        root.requestApplyInsets();

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

    private void focusTabsDown(int target) {
        for (int i = 0; i < topTabs.getChildCount(); i++) {
            topTabs.getChildAt(i).setNextFocusDownId(target);
        }
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
        if (hasGames) {
            homeRail = column();
            body.addView(homeRail, dimensions(MATCH, WRAP));
        }
        body.addView(new View(this), new LinearLayout.LayoutParams(MATCH, dp(wide ? 86 : 72), 1));
        LinearLayout caption = column();
        heroLabel = label("", 14, BLUE, true);
        heroLabel.setLetterSpacing(.06f);
        caption.addView(heroLabel, dimensions(MATCH, WRAP));
        heroTitle = label("", wide ? 52 : 38, TEXT, false);
        heroTitle.setTypeface(LIGHT);
        heroTitle.setLetterSpacing(-.025f);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setPadding(0, dp(5), 0, dp(5));
        caption.addView(heroTitle, dimensions(MATCH, WRAP));
        heroHint = label("", 16, MUTED, false);
        caption.addView(heroHint, dimensions(MATCH, WRAP));

        LinearLayout actions = row();
        actions.setPadding(0, dp(19), 0, 0);
        heroPlay = button("", "hero-play", true, this::activateHero);
        heroPlay.setBackground(shape(TEXT, Color.TRANSPARENT, 24));
        heroPlay.setForeground(focusRing(24));
        heroPlay.setPadding(dp(24), 0, dp(24), 0);
        actions.addView(heroPlay, dimensions(WRAP, 48));
        heroFavorite = button("☆", "hero-favorite", false, () -> {
            if (selected != null) toggleFavorite(selected);
        });
        heroOptions = button("⋯", "hero-options", false, () -> {
            if (selected != null) gameActions(selected);
        });
        heroOptions.setContentDescription("Game options");
        for (Button action : new Button[]{heroFavorite, heroOptions}) {
            action.setTextSize(22);
            action.setBackground(shape(0x40070e18, 0x737a8ca4, 24));
            action.setForeground(focusRing(24));
            action.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams box = dimensions(48, 48);
            box.setMarginStart(dp(8));
            actions.addView(action, box);
        }
        caption.addView(actions, dimensions(MATCH, WRAP));
        int captionWidth = getResources().getConfiguration().screenWidthDp - 2 * pageGutter();
        body.addView(caption, dimensions(Math.min(captionWidth, 620), WRAP));

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
        if (hasGames) renderHomeRail();
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
        View activeFilter = homeFilters == null ? null : homeFilters.findViewWithTag("shelf-" + homeShelf.name());
        int filterId = activeFilter == null ? View.NO_ID : activeFilter.getId();
        int playId = heroPlay.getId();
        focusTabsDown(activeFilter == null ? playId : filterId);

        View browse = homeRail == null ? null : homeRail.findViewWithTag("browse");
        boolean browseBelowTiles = browse != null && browse.getParent() == homeRail;
        View entry = homeCards.isEmpty() ? null : homeCards.get(0);
        for (View card : homeCards) {
            card.setNextFocusUpId(filterId);
            card.setNextFocusDownId(browseBelowTiles ? browse.getId() : playId);
            if (card.isSelected()) entry = card;
        }
        int entryId = entry == null ? playId : entry.getId();
        int actionUp = entry != null ? entryId : activeFilter == null ? home.getId() : filterId;
        if (browseBelowTiles) {
            browse.setNextFocusUpId(entryId);
            browse.setNextFocusDownId(playId);
            actionUp = browse.getId();
        }
        for (Button action : new Button[]{heroPlay, heroFavorite, heroOptions}) {
            action.setNextFocusUpId(actionUp);
        }
        if (homeFilters == null) return;

        for (int i = 0; i < homeFilters.getChildCount(); i++) {
            View filter = homeFilters.getChildAt(i);
            if (!filter.isFocusable()) continue;
            filter.setNextFocusUpId(home.getId());
            filter.setNextFocusDownId(entryId);
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
            int size = getResources().getConfiguration().screenWidthDp >= 900 ? 108 : 92;
            addRail(homeRail, games, "home", 0, games.size(), size);
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
        boolean homeTile = key.equals("home");
        card.addView(artwork, dimensions(MATCH, width));
        TextView title = label(game.title + (game.favorite ? "  ★" : ""), homeTile ? 13 : 14, TEXT, !homeTile);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(title, dimensions(MATCH, 30));
        if (!homeTile) {
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
            } else if (view.hasFocus() && !view.isInTouchMode()) {
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
        pageTitle("Library");
        LinearLayout shelves = row();
        addLibraryShelf(shelves, "All games", Library.Shelf.ALL);
        addLibraryShelf(shelves, "Favorites", Library.Shelf.FAVORITES);
        addLibraryShelf(shelves, "Recent", Library.Shelf.RECENT);
        body.addView(shelves, dimensions(MATCH, 48));

        LinearLayout controls = wide ? row() : column();
        int available = getResources().getConfiguration().screenWidthDp - 2 * pageGutter();
        LinearLayout.LayoutParams toolbar = dimensions(wide ? Math.min(720, available) : MATCH, WRAP);
        toolbar.topMargin = dp(16);
        LinearLayout searchBox = row();
        searchBox.setBackground(shape(0xa9162234, LINE, 26));
        searchField = new EditText(this);
        searchField.setId(View.generateViewId());
        searchField.setTag("search");
        searchField.setTextColor(TEXT);
        searchField.setHintTextColor(MUTED);
        searchField.setTextSize(16);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setHint("Search games");
        searchField.setText(search);
        searchField.setBackgroundColor(Color.TRANSPARENT);
        searchField.setForeground(focusRing(26));
        searchField.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.search, 0, 0, 0);
        searchField.setCompoundDrawablePadding(dp(12));
        searchField.setPadding(dp(18), 0, dp(14), 0);
        searchField.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            return true;
        });
        searchBox.addView(searchField, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button clearSearch = pill("×", "clear-search", false, () -> {
            if (!searchField.isInTouchMode()) searchField.requestFocus();
            searchField.setText("");
        });
        clearSearch.setContentDescription("Clear search");
        clearSearch.setTextSize(23);
        clearSearch.setPadding(0, 0, 0, 0);
        clearSearch.setBackgroundColor(Color.TRANSPARENT);
        clearSearch.setVisibility(search.isEmpty() ? View.GONE : View.VISIBLE);
        searchBox.addView(clearSearch, dimensions(48, 48));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                search = value.toString();
                clearSearch.setVisibility(search.isEmpty() ? View.GONE : View.VISIBLE);
                page = 0;
                refresh();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        controls.addView(searchBox, wide ? weighted(52) : dimensions(MATCH, 52));

        LinearLayout.LayoutParams filterSpace = dimensions(wide ? WRAP : MATCH, 52);
        if (wide) filterSpace.setMarginStart(dp(12));
        else filterSpace.topMargin = dp(10);
        Button filter = pill("", "filter", false, this::chooseSystem);
        updateSystemFilter(filter);
        controls.addView(filter, filterSpace);
        body.addView(controls, toolbar);
        results = column();
        results.setPadding(0, dp(24), 0, 0);
        body.addView(results, dimensions(MATCH, WRAP));
        focusTabsDown(shelves.findViewWithTag("library-" + shelf.name()).getId());
    }

    private void addLibraryShelf(LinearLayout shelves, String title, Library.Shelf target) {
        String tag = "library-" + target.name();
        shelves.addView(tab(title, tag, shelf == target, () -> {
            hideKeyboard();
            if (shelf == target) return;
            shelf = target;
            page = 0;
            for (int i = 0; i < shelves.getChildCount(); i++) {
                Button tab = (Button) shelves.getChildAt(i);
                setTabActive(tab, tag.equals(tab.getTag()));
            }
            focusTabsDown(shelves.findViewWithTag(tag).getId());
            refresh();
        }), dimensions(WRAP, 48));
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
            TextView title = label(empty, 24, TEXT, false);
            title.setTypeface(LIGHT);
            title.setPadding(0, dp(18), 0, dp(18));
            results.addView(title, dimensions(MATCH, WRAP));
            if (snapshot.folders == 0) results.addView(pill("Connect folder", "results-connect", true, this::pickFolder),
                    dimensions(WRAP, 48));
        } else {
            int count = Math.min(Library.PAGE, snapshot.games.size());
            int available = getResources().getConfiguration().screenWidthDp - (pageGutter() * 2);
            int columns = Math.max(1, Math.min(7, (available + 16) / 142));
            int tileWidth = (available - (columns - 1) * 16) / columns;
            for (int begin = 0; begin < count; begin += columns) {
                LinearLayout tiles = row();
                tiles.setGravity(Gravity.TOP);
                int end = Math.min(count, begin + columns);
                for (int index = begin; index < end; index++) {
                    LinearLayout.LayoutParams space = dimensions(tileWidth, WRAP);
                    if (index > begin) space.setMarginStart(dp(16));
                    tiles.addView(card(snapshot.games.get(index), "results", tileWidth), space);
                }
                LinearLayout.LayoutParams space = dimensions(MATCH, WRAP);
                space.bottomMargin = dp(18);
                results.addView(tiles, space);
            }
        }
        if (page > 0 || snapshot.games.size() > Library.PAGE) {
            LinearLayout pages = row();
            if (page > 0) pages.addView(pill("Previous page", "previous", false, () -> changePage(-1)),
                    dimensions(WRAP, 48));
            if (snapshot.games.size() > Library.PAGE) {
                LinearLayout.LayoutParams space = dimensions(WRAP, 48);
                if (page > 0) space.setMarginStart(dp(10));
                pages.addView(pill("Next page", "next", false, () -> changePage(1)), space);
            }
            results.addView(pages, dimensions(MATCH, WRAP));
        }
        restoreFocus(getWindow().getDecorView(), focus);
    }

    private void changePage(int offset) {
        page += offset;
        refresh();
        scroller.scrollTo(0, 0);
    }

    private void updateSystemFilter(Button filter) {
        String name = system == null ? "All systems" : system.label;
        filter.setText(name + "   ▾");
        filter.setContentDescription(name);
    }

    private void chooseSystem() {
        hideKeyboard();
        List<GameSystem> systems = snapshot.systems;
        String[] names = new String[systems.size() + 1];
        names[0] = "All systems";
        for (int i = 0; i < systems.size(); i++) names[i + 1] = systems.get(i).label;
        choices("Filter by system", null, names, system == null ? 0 : systems.indexOf(system) + 1, "Cancel", which -> {
            system = which == 0 ? null : systems.get(which - 1);
            page = 0;
            updateSystemFilter(body.findViewWithTag("filter"));
            refresh();
        });
    }

    private void buildSettings() {
        pageTitle("Settings");
        boolean split = getResources().getConfiguration().screenWidthDp >= 690;
        LinearLayout workspace = split ? row() : column();
        workspace.setGravity(Gravity.TOP);
        settingsNav = split ? column() : row();
        settingsPanel = column();

        for (SettingsGroup group : SettingsGroup.values()) {
            Button category = button(group.title, "settings-" + group.name(), false, () -> {
                selectSettingsGroup(group, split);
                if (split && !settingsNav.isInTouchMode()) settingsPanel.getChildAt(0).requestFocus();
            });
            category.setPadding(dp(split ? 17 : 5), 0, dp(split ? 17 : 5), 0);
            category.setMinWidth(dp(56));
            if (split) category.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            category.setOnFocusChangeListener((view, focused) -> {
                if (focused && screen == Screen.SETTINGS) selectSettingsGroup(group, split);
            });
            LinearLayout.LayoutParams categoryBox = split ? dimensions(MATCH, 57) : weighted(48);
            if (split) categoryBox.bottomMargin = dp(6);
            settingsNav.addView(category, categoryBox);
        }

        workspace.addView(settingsNav, dimensions(split ? 214 : MATCH, WRAP));
        int panelWidth = getResources().getConfiguration().screenWidthDp - 2 * pageGutter() - 250;
        LinearLayout.LayoutParams panelBox = split ? dimensions(Math.min(800, panelWidth), WRAP)
                : dimensions(MATCH, WRAP);
        if (split) panelBox.setMarginStart(dp(36));
        else panelBox.topMargin = dp(18);
        workspace.addView(settingsPanel, panelBox);
        body.addView(workspace, dimensions(MATCH, WRAP));
        renderSettingsOptions(split);
    }

    private void selectSettingsGroup(SettingsGroup group, boolean split) {
        if (settingsGroup == group) return;
        settingsGroup = group;
        renderSettingsOptions(split);
    }

    private void renderSettingsOptions(boolean split) {
        settingsPanel.removeAllViews();
        switch (settingsGroup) {
            case LIBRARY:
                setting("Connect folder", "Choose a folder containing your games.", "connect", this::pickFolder);
                setting("Manage folders", "Rescan or disconnect a specific folder.", "folders", this::manageFolders);
                setting("Rescan library", "Check connected folders for changes.", "scan", this::scanAll);
                setting("Clear artwork cache", "Reload covers and backdrops from their files.", "cache", () -> {
                    Artwork.clear();
                    message("Artwork cache cleared.");
                });
                break;
            case PLAYBACK:
                setting("Emulator preferences", "Choose which app opens games for each system.", "emulators",
                        this::chooseEmulatorSystem);
                break;
            case DEVICE:
                setting("Default Home app", "Choose the app Android opens when you press Home.", "default-home", () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                    } catch (ActivityNotFoundException error) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                });
                break;
        }

        View activeCategory = settingsNav.findViewWithTag("settings-" + settingsGroup.name());
        int firstOption = settingsPanel.getChildAt(0).getId();
        for (int i = 0; i < settingsNav.getChildCount(); i++) {
            Button category = (Button) settingsNav.getChildAt(i);
            boolean selected = category == activeCategory;
            if (split) {
                category.setSelected(selected);
                category.setTextColor(selected ? TEXT : MUTED);
                if (selected) {
                    LayerDrawable marker = new LayerDrawable(new Drawable[]{
                            shape(0x662a3b55, Color.TRANSPARENT, 6), shape(BLUE, BLUE, 1)
                    });
                    marker.setLayerSize(1, dp(2), dp(24));
                    marker.setLayerGravity(1, Gravity.START | Gravity.CENTER_VERTICAL);
                    category.setBackground(marker);
                } else {
                    category.setBackgroundColor(Color.TRANSPARENT);
                }
                category.setNextFocusRightId(firstOption);
            } else {
                setTabActive(category, selected);
                category.setNextFocusDownId(firstOption);
            }
        }
        focusTabsDown(activeCategory.getId());
        for (int i = 0; i < settingsPanel.getChildCount(); i++) {
            View option = settingsPanel.getChildAt(i);
            if (split) option.setNextFocusLeftId(activeCategory.getId());
            if (!split && i == 0) option.setNextFocusUpId(activeCategory.getId());
        }
    }

    private void pageTitle(String text) {
        TextView title = label(text, 44, TEXT, false);
        title.setTypeface(LIGHT);
        title.setLetterSpacing(-.024f);
        LinearLayout.LayoutParams space = dimensions(MATCH, 70);
        space.bottomMargin = dp(12);
        body.addView(title, space);
    }

    private void setting(String title, String description, String key, Runnable action) {
        LinearLayout item = menuRow(title, description, "›", false, action);
        item.setTag(key);
        LinearLayout.LayoutParams space = dimensions(MATCH, WRAP);
        space.bottomMargin = dp(3);
        settingsPanel.addView(item, space);
    }

    private void chooseEmulatorSystem() {
        List<GameSystem> systems = snapshot.systems;
        if (systems.isEmpty()) {
            message("Connect a folder with supported games first.");
            return;
        }
        String[] names = new String[systems.size()];
        for (int i = 0; i < names.length; i++) names[i] = systems.get(i).label;
        choices("Choose a system", null, names, -1, "Cancel", which -> {
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
        });
    }

    private void gameActions(Library.Game game) {
        if (busy) return;
        String[] actions = {"Play", game.favorite ? "Remove from favorites" : "Add to favorites", "Choose emulator"};
        choices(game.title, game.system.label, actions, -1, "Close", which -> {
            if (which == 0) play(game);
            else if (which == 1) toggleFavorite(game);
            else choosePlayer(game, false);
        });
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
            notice("No compatible emulator found",
                    "Install a " + game.system.label + " emulator that can open Android content URIs, then try again."
                            + (game.system == GameSystem.PSP ? " Nomad can hand off PSP games to PPSSPP." : ""));
            return;
        }
        String[] choices = new String[players.size() + (play ? 0 : 1)];
        Emulators.Player preferred = emulators.preferred(game.system, players);
        int preferredIndex = play ? -1 : players.size();
        for (int i = 0; i < players.size(); i++) {
            Emulators.Player player = players.get(i);
            choices[i] = player.label;
            if (preferred != null && preferred.component.equals(player.component)) preferredIndex = i;
        }
        if (!play) choices[players.size()] = "Use automatic selection";
        choices(play ? "Open with" : game.system.label + " emulator", null, choices, preferredIndex, "Cancel", which -> {
            Emulators.Player player = which == players.size() ? null : players.get(which);
            if (play) launch(game, player);
            else {
                emulators.remember(game.system, player);
                message(player == null ? "Automatic selection restored." : "Preferred emulator saved.");
            }
        });
    }

    private void launch(Library.Game game, Emulators.Player player) {
        try {
            emulators.launch(this, game, player);
            change(null, () -> {
                library.launched(game);
                return null;
            });
        } catch (ActivityNotFoundException | SecurityException error) {
            notice("Could not open " + game.title,
                    "Confirm the game folder is still connected and the emulator can read this file format.");
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
                    choices("Game folders", null, names, -1, "Close", which -> folderActions(folders.get(which)));
                });
            } catch (RuntimeException error) {
                showOnMain(() -> message("Could not read connected folders."));
            }
        });
    }

    private void folderActions(Library.Folder folder) {
        choices(folder.name, null, new String[]{"Rescan folder", "Disconnect folder"}, -1, "Close", which -> {
            if (which == 0) change("Scanning " + folder.name + "…", () -> scanMessage(library.scan(folder)));
            else ConsoleDialog.notice(this, "Disconnect " + folder.name + "?",
                    "Remove this folder and its favorites and recents from Nomad. Your game files stay in place.",
                    "Disconnect", () -> change("Disconnecting folder…", () -> {
                        library.disconnect(folder);
                        return "Folder disconnected.";
                    }), "Cancel");
        });
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
                    notice("Scan results", result);
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
        feedbackText = text;
        showFeedback();
        ui.removeCallbacks(clearFeedback);
        ui.postDelayed(clearFeedback, 3400);
    }

    private void showFeedback() {
        feedback.setText(feedbackText);
        feedback.setVisibility(feedbackText == null ? View.GONE : View.VISIBLE);
    }

    private void choices(String title, String description, String[] items, int selected, String close,
                         IntConsumer action) {
        ConsoleDialog.choose(this, title, description, items, selected, close, action);
    }

    private void notice(String title, String description) {
        ConsoleDialog.notice(this, title, description, "OK", null, null);
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

    static KeyEvent confirmKey(KeyEvent original) {
        return new KeyEvent(original.getDownTime(), original.getEventTime(), original.getAction(),
                KeyEvent.KEYCODE_DPAD_CENTER, original.getRepeatCount(), original.getMetaState(),
                original.getDeviceId(), original.getScanCode(), original.getFlags(), original.getSource());
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        int direction = hatDirection(event);
        if (direction != 0 && direction != hatDirection) {
            View focused = getCurrentFocus();
            View next = focused == null ? getWindow().getDecorView().findViewWithTag("tab-" + screen.name())
                    : focused.focusSearch(direction);
            moveFocus(next, direction);
        }
        boolean handled = direction != 0 || hatDirection != 0;
        hatDirection = direction;
        return handled || super.dispatchGenericMotionEvent(event);
    }

    static int hatDirection(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        return x < -.5f ? View.FOCUS_LEFT : x > .5f ? View.FOCUS_RIGHT
                : y < -.5f ? View.FOCUS_UP : y > .5f ? View.FOCUS_DOWN : 0;
    }

    static void moveFocus(View next, int direction) {
        if (next == null) return;
        if (next.isInTouchMode()) next.requestFocusFromTouch();
        else next.requestFocus(direction);
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

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int size(int value) { return value < 0 ? value : dp(value); }

    private LinearLayout.LayoutParams dimensions(int width, int height) {
        return new LinearLayout.LayoutParams(size(width), size(height));
    }

    private LinearLayout.LayoutParams weighted(int height) {
        return new LinearLayout.LayoutParams(0, dp(height), 1);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView label(String text, int size, int color, boolean bold) {
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

    Button pill(String text, String key, boolean primary, Runnable action) {
        Button pill = button(text, key, primary, action);
        pill.setBackground(shape(primary ? TEXT : 0x98142235, primary ? Color.TRANSPARENT : 0xff526581, 26));
        pill.setForeground(focusRing(26));
        pill.setPadding(dp(20), 0, dp(20), 0);
        return pill;
    }

    LinearLayout menuRow(String title, String description, String indicator, boolean selected, Runnable action) {
        LinearLayout item = row();
        item.setId(View.generateViewId());
        item.setMinimumHeight(dp(description == null ? 52 : 73));
        item.setFocusable(true);
        item.setSelected(selected);
        item.setContentDescription(title);
        item.setForeground(focusRing(8));
        if (selected) {
            item.setBackground(shape(0xb32c405d, 0xff667f9f, 8));
        } else {
            LayerDrawable surface = new LayerDrawable(new Drawable[]{
                    shape(Color.TRANSPARENT, Color.TRANSPARENT, 0), shape(0x99506078, 0x99506078, 0)
            });
            surface.setLayerHeight(1, dp(1));
            surface.setLayerGravity(1, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM);
            surface.setLayerInset(1, dp(16), 0, dp(13), 0);
            item.setBackground(surface);
        }
        item.setPadding(dp(16), dp(8), dp(13), dp(8));
        item.setOnClickListener(view -> action.run());

        LinearLayout text = column();
        TextView heading = label(title, 17, TEXT, false);
        heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        text.addView(heading, dimensions(MATCH, WRAP));
        if (description != null) {
            TextView supporting = label(description, 14, MUTED, false);
            supporting.setPadding(0, dp(4), 0, 0);
            text.addView(supporting, dimensions(MATCH, WRAP));
        }
        item.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView arrow = label(indicator, 23, selected ? BLUE : MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(arrow, dimensions(30, 36));
        return item;
    }

    private Button tab(String text, String key, boolean active, Runnable action) {
        Button tab = button(text, key, false, action);
        if (getResources().getConfiguration().screenWidthDp < 380) {
            tab.setMinWidth(dp(48));
            tab.setPadding(dp(9), 0, dp(9), 0);
        }
        setTabActive(tab, active);
        return tab;
    }

    private void setTabActive(Button tab, boolean active) {
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
    }

    private void addDivider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(LINE);
        parent.addView(line, dimensions(MATCH, 1));
    }

    GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), border);
        return shape;
    }

    private StateListDrawable focusRing(int radius) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable ring = shape(0x16406ea1, TEXT, radius);
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
