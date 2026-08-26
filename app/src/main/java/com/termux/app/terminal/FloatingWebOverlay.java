package com.termux.app.terminal;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.color.MaterialColors;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.inappkeyboard.TerminalKeyEventHandler;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;

import androidx.core.content.ContextCompat;
import juloo.keyboard2.KeyValue;

/**
 * A draggable, resizable floating window hosting a {@link WebView}, shown over the launcher.
 *
 * Opened from the shell via "OSC 7770 ; <source>". A source starting with "browse:" loads a plain
 * page in the overlay's WebView; any other source is resolved to a media file or stream and played
 * through an HTML5 video element, which hands decoding to the Chromium/media stack instead of the
 * terminal bitmap pipeline.
 *
 * While the overlay is open it claims the embedded keyboard's interceptor slot: keys typed on the
 * launcher's own keyboard are forwarded to the focused view inside the window (page or URL bar)
 * as synthesized key events, so the shell behind never sees them. Minimizing collapses the
 * window to a floating circle — the WebView stays attached, so media keeps playing — and returns
 * the keyboard to the terminal.
 *
 * The border follows the pane convention: Material {@code colorPrimary} while the overlay owns
 * input, {@code colorOutlineVariant} otherwise.
 */
public final class FloatingWebOverlay {

    private static final String LOG_TAG = "FloatingWebOverlay";

    private static final int BAR_HEIGHT_DP = 36;
    private static final int HANDLE_SIZE_DP = 22;
    private static final int MIN_WIDTH_DP = 180;
    private static final int MIN_HEIGHT_DP = 140;
    private static final int CIRCLE_SIZE_DP = 48;

    /** Same default shape as the scratchpad float (TerminalPaneController fractions). */
    private static final float DEFAULT_LEFT_FRAC = 0.07f;
    private static final float DEFAULT_TOP_FRAC = 0.06f;
    private static final float DEFAULT_RIGHT_FRAC = 0.93f;
    private static final float DEFAULT_BOTTOM_FRAC = 0.58f;

    private static final String PREFS_NAME = "web_overlay_bounds";
    private static final String KEY_LEFT = "left";
    private static final String KEY_TOP = "top";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";

    /** Bounds survive hide/show for the process lifetime, and persist to prefs across restarts. */
    private static int sLeftDp = -1, sTopDp = -1, sWidthDp = -1, sHeightDp = -1;
    private static boolean sBoundsLoaded;

    /** Live overlay state, so the host activity can hide it on stop and restore it on start. */
    @Nullable private static FrameLayout sWindow;
    @Nullable private static ViewGroup sRoot;
    @Nullable private static TerminalKeyEventHandler.KeyValueInterceptor sRouter;
    private static boolean sMinimized;

    /** The overlay is launcher-owned chrome, not content: gone while the host is stopped. */
    public static void onHostStopped(TermuxActivity activity) {
        if (sWindow == null || activity.getWindow() == null
            || sWindow.getRootView() != activity.getWindow().getDecorView()) return;
        if (sWindow.getVisibility() == View.VISIBLE) {
            sWindow.setVisibility(View.GONE);
            sWasVisibleBeforeStop = true;
        }
        if (sCircle != null && sCircle.getVisibility() == View.VISIBLE) {
            sCircle.setVisibility(View.GONE);
            sCircleWasVisibleBeforeStop = true;
        }
        activity.setWebOverlayKeyInterceptor(null);
    }

    private static boolean sWasVisibleBeforeStop;
    private static boolean sCircleWasVisibleBeforeStop;
    @Nullable private static FrameLayout sCircle;

    /** identityHashCode of the pane window the overlay was opened over; 0 = unbound. */
    private static int sBoundWindowId;

    /**
     * The overlay belongs to the terminal window it opened over, like a pane float. Switching to
     * another window hides it (media keeps playing); switching back reveals it.
     */
    public static void onActiveWindowChanged(TermuxActivity activity) {
        if (sWindow == null || sBoundWindowId == 0
            || activity.getWindow() == null
            || sWindow.getRootView() != activity.getWindow().getDecorView()
            || sWasVisibleBeforeStop) return;
        com.termux.app.terminal.TerminalPaneController panes = activity.getPaneController();
        int currentId = panes == null || panes.activeWindow() == null
            ? 0 : System.identityHashCode(panes.activeWindow());
        if (currentId == 0) return;
        if (currentId == sBoundWindowId) {
            // Back on the binding window: reveal again unless the user minimized it.
            if (!sMinimized && !sWasVisibleBeforeStop && sWindow.getVisibility() != View.VISIBLE) {
                sWindow.setVisibility(View.VISIBLE);
                if (sCircle != null) sCircle.setVisibility(View.GONE);
                if (sRouter != null) activity.setWebOverlayKeyInterceptor(sRouter);
            }
            return;
        }
        if (sWindow.getVisibility() == View.VISIBLE) {
            sWindow.setVisibility(View.GONE);
            if (sCircle != null) sCircle.setVisibility(View.GONE);
            activity.setWebOverlayKeyInterceptor(null);
        }
    }

    public static void onHostStarted(TermuxActivity activity) {
        if (sWindow == null || activity.getWindow() == null
            || sWindow.getRootView() != activity.getWindow().getDecorView()) return;
        if (sWasVisibleBeforeStop) {
            sWindow.setVisibility(View.VISIBLE);
            sWasVisibleBeforeStop = false;
            if (sRouter != null && !sMinimized) activity.setWebOverlayKeyInterceptor(sRouter);
        } else if (sMinimized && sCircle != null && sCircleWasVisibleBeforeStop) {
            sCircle.setVisibility(View.VISIBLE);
            sCircleWasVisibleBeforeStop = false;
        }
    }

    private FloatingWebOverlay() {
    }

    public static void show(Context context, String source) {
        Activity found = null;
        if (context instanceof Activity) found = (Activity) context;
        else if (context instanceof android.content.ContextWrapper)
            found = findActivity((android.content.ContextWrapper) context);
        if (!(found instanceof TermuxActivity)) {
            Logger.logWarn(LOG_TAG, "web overlay requires a TermuxActivity host");
            return;
        }
        final TermuxActivity activity = (TermuxActivity) found;
        if (activity.isFinishing()) return;

        final boolean browseMode = source.startsWith("browse:");
        final String target = browseMode ? source.substring("browse:".length()) : source;

        final float density = activity.getResources().getDisplayMetrics().density;
        final ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
        if (root == null) {
            Logger.logError(LOG_TAG, "activity has no content view");
            return;
        }
        final int screenW = root.getWidth() > 0 ? root.getWidth()
            : activity.getResources().getDisplayMetrics().widthPixels;
        final int screenH = root.getHeight() > 0 ? root.getHeight()
            : activity.getResources().getDisplayMetrics().heightPixels;

        // Replace an existing overlay rather than stacking windows.
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            Object tag = root.getChildAt(i).getTag();
            if (LOG_TAG.equals(tag)) root.removeViewAt(i);
        }

        loadPersistedBounds(activity, screenW, screenH);
        // Hard ceiling regardless of what persistence or defaults say: 92% per axis.
        final int width = dp(density, Math.min(
            clamp(sWidthDp, MIN_WIDTH_DP, screenW / density,
                (screenW * (DEFAULT_RIGHT_FRAC - DEFAULT_LEFT_FRAC)) / density),
            pxToDp(density, (int) (screenW * 0.92f))));
        final int height = dp(density, Math.min(
            clamp(sHeightDp, MIN_HEIGHT_DP, screenH / density,
                (screenH * (DEFAULT_BOTTOM_FRAC - DEFAULT_TOP_FRAC)) / density),
            pxToDp(density, (int) (screenH * 0.92f))));
        final int left = dp(density, clamp(sLeftDp, 0, (screenW - width) / density,
            (screenW * DEFAULT_LEFT_FRAC) / density));
        final int top = dp(density, clamp(sTopDp, 0, (screenH - height) / density,
            (screenH * DEFAULT_TOP_FRAC) / density));

        final FrameLayout window = new FrameLayout(activity);
        window.setTag(LOG_TAG);
        window.setClipToOutline(true);
        window.setElevation(dp(density, 12));
        final GradientDrawable border = new GradientDrawable();
        border.setColor(Color.BLACK);
        border.setCornerRadius(dp(density, 8));
        window.setBackground(border);
        window.setForeground(borderDrawable(activity, density, true));

        final FrameLayout.LayoutParams windowParams = new FrameLayout.LayoutParams(width, height);
        windowParams.leftMargin = left;
        windowParams.topMargin = top;
        root.addView(window, windowParams);
        sBoundWindowId = currentWindowId(activity);
        Logger.logInfo(LOG_TAG, "overlay shown " + width + "x" + height + " at "
            + left + "," + top + " on screen " + screenW + "x" + screenH
            + " bound to window " + sBoundWindowId);

        final LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        window.addView(column, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Title bar: drag surface, inline URL entry, navigation ---
        final LinearLayout bar = new LinearLayout(activity);
        final WebView webView = makeWebView(activity);
        int surface = MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorSurface,
            Color.BLACK);
        int onSurface = MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorOnSurface, 0xFFDDDDDD);
        android.graphics.drawable.GradientDrawable barShape =
            new android.graphics.drawable.GradientDrawable();
        barShape.setColor(surface);
        float corner = dp(density, 8);
        barShape.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        bar.setBackground(barShape);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(density, BAR_HEIGHT_DP)));

        final EditText urlBar = new EditText(activity);
        urlBar.setText(browseMode ? target : shortName(target));
        urlBar.setTextColor(onSurface);
        urlBar.setHintTextColor(0xFF888888);
        urlBar.setTextSize(13);
        urlBar.setSingleLine(true);
        urlBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setBackgroundResource(android.R.color.transparent);
        urlBar.setPadding(dp(density, 10), 0, dp(density, 10), 0);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || event != null
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                commitUrl(urlBar, webView);
                urlBar.clearFocus();
                return true;
            }
            return false;
        });
        urlBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus)
                urlBar.setText(displayUrl(webView.getUrl(), browseMode, target));
        });
        bar.addView(urlBar, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button backButton = barButton(activity, "‹", density);
        backButton.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        bar.addView(backButton);

        Button reloadButton = barButton(activity, "↻", density);
        reloadButton.setOnClickListener(v -> webView.reload());
        bar.addView(reloadButton);

        Button minimizeButton = barButton(activity, "—", density);
        bar.addView(minimizeButton);

        Button fullscreenButton = barButton(activity, "⛶", density);
        bar.addView(fullscreenButton);

        Button closeButton = barButton(activity, "✕", density);
        closeButton.setOnClickListener(v -> close(activity, root, window, webView));
        bar.addView(closeButton);

        column.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loadSource(activity, webView, browseMode, target);

        final WebKeyRouter router = new WebKeyRouter(activity, window, webView, urlBar);
        sWindow = window;
        sRoot = root;
        sRouter = router;
        sMinimized = false;

        // Keyboard follows focus: a stroke goes to the overlay only while a view inside it holds
        // focus, so tapping the terminal behind hands typing straight back to the shell.
        final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> {
            if (sWindow == null || activity.isFinishing()) return;
            boolean inside = isDescendant(sWindow, newFocus);
            activity.setWebOverlayKeyInterceptor(inside ? router : null);
            window.setForeground(borderDrawable(activity, density, inside));
        };
        activity.getWindow().getDecorView().getViewTreeObserver()
            .addOnGlobalFocusChangeListener(focusListener);
        sFocusListener = focusListener;
        // A tap anywhere in the window claims focus for the page, so typing lands there.
        column.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !webView.hasFocus())
                webView.requestFocus();
            return false;
        });

        // --- Resize handle, bottom-right corner ---
        final View handle = new View(activity);
        handle.setBackgroundColor(0x66888888);
        final FrameLayout.LayoutParams handleParams =
            new FrameLayout.LayoutParams(dp(density, HANDLE_SIZE_DP), dp(density, HANDLE_SIZE_DP),
                Gravity.BOTTOM | Gravity.END);
        window.addView(handle, handleParams);

        attachDrag(bar, window, windowParams, density, null, () -> {});
        attachDrag(handle, window, windowParams, density, true, () -> persistBounds(activity));

        // --- Minimize to a floating circle; media keeps playing in the hidden WebView ---
        minimizeButton.setOnClickListener(v -> {
            window.setVisibility(View.GONE);
            sMinimized = true;
            activity.setWebOverlayKeyInterceptor(null);
            showRestoreCircle(activity, root, window, router, density);
        });

        // --- Fullscreen: the whole floating window fills the screen, shape restored on exit ---
        final int[] preFullscreen = {-1, -1, -1, -1}; // left, top, width, height (px)
        fullscreenButton.setOnClickListener(v -> {
            if (preFullscreen[0] < 0) {
                preFullscreen[0] = windowParams.leftMargin;
                preFullscreen[1] = windowParams.topMargin;
                preFullscreen[2] = windowParams.width;
                preFullscreen[3] = windowParams.height;
                windowParams.leftMargin = 0;
                windowParams.topMargin = 0;
                windowParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                windowParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                window.setLayoutParams(windowParams);
            } else {
                windowParams.leftMargin = preFullscreen[0];
                windowParams.topMargin = preFullscreen[1];
                windowParams.width = preFullscreen[2];
                windowParams.height = preFullscreen[3];
                window.setLayoutParams(windowParams);
                preFullscreen[0] = -1;
                persistBounds(activity);
            }
        });
    }

    /**
     * Forwards embedded-keyboard strokes into whichever view inside the overlay holds focus —
     * the page's WebView, or the inline URL bar. Chords (Ctrl/Alt) are swallowed so shortcuts
     * never leak into the page.
     */
    private static final class WebKeyRouter implements TerminalKeyEventHandler.KeyValueInterceptor {
        private final TermuxActivity mActivity;
        private final ViewGroup mWindow;
        private final WebView mWebView;
        private final EditText mUrlBar;

        WebKeyRouter(TermuxActivity activity, ViewGroup window, WebView webView, EditText urlBar) {
            mActivity = activity;
            mWindow = window;
            mWebView = webView;
            mUrlBar = urlBar;
        }

        private View target() {
            if (mWindow.getVisibility() != View.VISIBLE) return null;
            return mUrlBar.hasFocus() ? mUrlBar : mWebView;
        }

        @Override
        public boolean interceptKeyValue(@NonNull KeyValue value, boolean ctrl, boolean alt,
                                         boolean shift) {
            View focus = target();
            if (focus == null) return false;
            if (ctrl || alt) return true;
            switch (value.getKind()) {
                case Char:
                    sendChars(focus, String.valueOf(value.getChar()));
                    return true;
                case String:
                    sendChars(focus, value.getString());
                    return true;
                case Editing:
                    switch (value.getEditing()) {
                        case SPACE_BAR: sendChars(focus, " "); break;
                        case BACKSPACE: sendBackspace(focus); break;
                        default: break;
                    }
                    return true;
                case Keyevent:
                    dispatchKeyCode(focus, value.getKeyevent());
                    return true;
                case Event:
                    if (value.getEvent() == KeyValue.Event.ACTION)
                        dispatchKeyCode(focus, KeyEvent.KEYCODE_ENTER);
                    return true;
                case Slider:
                    switch (value.getSlider()) {
                        case Cursor_left: dispatchKeyCode(focus, KeyEvent.KEYCODE_DPAD_LEFT); break;
                        case Cursor_right: dispatchKeyCode(focus, KeyEvent.KEYCODE_DPAD_RIGHT); break;
                        default: break;
                    }
                    return true;
                default:
                    return true;
            }
        }

        /**
         * Text reaches the page through document.execCommand('insertText'), which works on any
         * focused HTML field without needing a KeyCharacterMap; the URL bar is edited through its
         * own text model. Control keys stay synthetic key events.
         */
        private void sendChars(View focus, String chars) {
            if (focus == mUrlBar) {
                int start = Math.max(mUrlBar.getSelectionStart(), 0);
                int end = Math.max(mUrlBar.getSelectionEnd(), 0);
                mUrlBar.getText().replace(Math.min(start, end), Math.max(start, end), chars);
            } else {
                String escaped = chars.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n");
                mWebView.evaluateJavascript(
                    "document.execCommand('insertText', false, '" + escaped + "');", null);
            }
        }

        private void sendBackspace(View focus) {
            if (focus == mUrlBar) {
                int start = mUrlBar.getSelectionStart();
                int end = mUrlBar.getSelectionEnd();
                if (start == end && start > 0) {
                    mUrlBar.getText().delete(start - 1, start);
                } else if (start != end) {
                    mUrlBar.getText().delete(Math.min(start, end), Math.max(start, end));
                }
            } else {
                mWebView.evaluateJavascript("document.execCommand('delete');", null);
            }
        }

        private void dispatchKeyCode(View focus, int keyCode) {
            focus.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            focus.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    private static void commitUrl(EditText urlBar, WebView webView) {
        if (webView == null) return;
        String text = urlBar.getText().toString().trim();
        if (text.isEmpty()) return;
        Uri uri = resolve(urlBar.getContext(), text);
        if (uri != null && uri.getScheme() != null && !uri.getScheme().startsWith("http")
            && !"content".equals(uri.getScheme())) {
            webView.loadUrl(localMediaPage(urlBar.getContext(), uri));
        } else {
            webView.loadUrl(uri != null ? uri.toString() : ensureScheme(text));
        }
    }

    private static String displayUrl(String current, boolean browseMode, String original) {
        if (current != null) return current;
        return browseMode ? original : shortName(original);
    }

    @Nullable private static ViewTreeObserver.OnGlobalFocusChangeListener sFocusListener;

    private static int currentWindowId(TermuxActivity activity) {
        com.termux.app.terminal.TerminalPaneController panes = activity.getPaneController();
        return panes == null || panes.activeWindow() == null
            ? 0 : System.identityHashCode(panes.activeWindow());
    }

    private static boolean isDescendant(ViewGroup ancestor, @Nullable View view) {
        while (view != null) {
            if (view == ancestor) return true;
            if (!(view.getParent() instanceof View)) return false;
            view = (View) view.getParent();
        }
        return false;
    }

    private static WebView makeWebView(TermuxActivity activity) {
        final WebView webView = new WebView(activity);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // Pages (YouTube etc.) offer a fullscreen surface view; taking it would paint
                // over the whole launcher. Decline it and the video keeps playing inline in the
                // window — real fullscreen is the overlay's own ⛶ button, which sizes the whole
                // floating window to the screen.
                callback.onCustomViewHidden();
            }

            @Override
            public void onHideCustomView() {
                // Nothing to tear down: custom views are never attached.
            }
        });
        return webView;
    }

    private static void close(TermuxActivity activity, ViewGroup root, FrameLayout window,
                              WebView webView) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            Object tag = root.getChildAt(i).getTag();
            if ("web_overlay_circle".equals(tag) || LOG_TAG.equals(tag)) root.removeViewAt(i);
        }
        if (sFocusListener != null) {
            activity.getWindow().getDecorView().getViewTreeObserver()
                .removeOnGlobalFocusChangeListener(sFocusListener);
            sFocusListener = null;
        }
        activity.endWebOverlayTextInput();
        activity.setWebOverlayKeyInterceptor(null);
        sWindow = null;
        sRoot = null;
        sRouter = null;
        sCircle = null;
        sMinimized = false;
        persistBounds(activity);
        webView.stopLoading();
        webView.destroy();
        window.setVisibility(View.GONE);
    }

    private static void showRestoreCircle(TermuxActivity activity, ViewGroup root,
                                          FrameLayout window,
                                          TerminalKeyEventHandler.KeyValueInterceptor router,
                                          float density) {
        final FrameLayout circle = new FrameLayout(activity);
        circle.setTag("web_overlay_circle");
        sCircle = circle;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorSurface,
            Color.BLACK));
        shape.setStroke(Math.max(1, dp(density, 2)), MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(activity, R.color.termux_primary)));
        circle.setBackground(shape);
        circle.setElevation(dp(density, 12));
        TextView glyph = new TextView(activity);
        glyph.setText("▶");
        glyph.setTextColor(0xFFDDDDDD);
        glyph.setGravity(Gravity.CENTER);
        circle.addView(glyph, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final FrameLayout.LayoutParams params =
            new FrameLayout.LayoutParams(dp(density, CIRCLE_SIZE_DP), dp(density, CIRCLE_SIZE_DP),
                Gravity.BOTTOM | Gravity.END);
        params.rightMargin = dp(density, 24);
        params.bottomMargin = dp(density, 120);
        root.addView(circle, params);

        // Drag moves the circle; a tap (under half the circle's width of travel) restores.
        final int slop = dp(density, CIRCLE_SIZE_DP / 2);
        circle.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                        if (!dragged && Math.hypot(dx, dy) > slop) dragged = true;
                        if (dragged) {
                            // Keep the circle fully on screen: margins clamp to the content view.
                            int size = dp(density, CIRCLE_SIZE_DP);
                            params.gravity = Gravity.TOP | Gravity.START;
                            params.leftMargin = Math.max(0, Math.min((int) (event.getRawX()
                                + v.getLeft() - startX - slop), root.getWidth() - size));
                            params.topMargin = Math.max(0, Math.min((int) (event.getRawY()
                                + v.getTop() - startY - slop), root.getHeight() - size));
                            v.setLayoutParams(params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            root.removeView(circle);
                            sCircle = null;
                            sMinimized = false;
                            window.setVisibility(View.VISIBLE);
                            activity.setWebOverlayKeyInterceptor(router);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Pane-style border: Material primary while the overlay owns input, dim outline otherwise. */
    private static GradientDrawable borderDrawable(TermuxActivity activity, float density,
                                                   boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        int tint = MaterialColors.getColor(activity,
            focused ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOutlineVariant,
            ContextCompat.getColor(activity, focused ? R.color.termux_primary
                : R.color.termux_outline_variant));
        drawable.setStroke(Math.max(1, dp(density, 2)), tint);
        return drawable;
    }

    private static void loadSource(Context context, WebView webView, boolean browseMode,
                                   String target) {
        if (browseMode || target.startsWith("http://") || target.startsWith("https://")
            || target.startsWith("content://")) {
            webView.loadUrl(browseMode ? ensureScheme(target) : target);
            return;
        }
        Uri uri = resolve(context, target);
        if (uri == null) {
            Toast.makeText(context, "web overlay: cannot resolve '" + target + "'",
                Toast.LENGTH_LONG).show();
            return;
        }
        webView.loadUrl(localMediaPage(context, uri));
    }

    /**
     * Local media goes through a tiny HTML5 page so the platform player UI and hardware decoder
     * are used; WebView alone would try to download bare file:// media links.
     */
    private static String localMediaPage(Context context, Uri uri) {
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,"
            + "initial-scale=1'><style>html,body{margin:0;height:100%;background:#000}"
            + "video{width:100%;height:100%;}</style></head>"
            + "<body><video src='" + uri + "' controls autoplay playsinline></video></body></html>";
        File page = new File(context.getCacheDir(), "media_overlay.html");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(page)) {
            out.write(html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "failed to write media overlay page", e);
            return null;
        }
        return Uri.fromFile(page).toString();
    }

    private static Uri resolve(Context context, String source) {
        if (source.startsWith("file://")) return Uri.parse(source);
        if (source.startsWith("http://") || source.startsWith("https://")
            || source.startsWith("content://")) return Uri.parse(source);
        File file = new File(source);
        if (!file.isAbsolute())
            file = new File(Environment.getExternalStorageDirectory(), source);
        if (!file.exists()) {
            File termuxRoot = new File(new File(context.getFilesDir().getParentFile(),
                "files/home"), source);
            if (termuxRoot.exists()) return Uri.fromFile(termuxRoot);
            return null;
        }
        return Uri.fromFile(file);
    }

    /**
     * Make $grabber drag $window by rewriting the margin params. When $resize is true the drag
     * changes the window's size instead of its position, anchored at the top-left. $onDrop runs
     * once per completed gesture.
     */
    private static void attachDrag(View grabber, ViewGroup window,
                                   FrameLayout.LayoutParams params, float density,
                                   Boolean resize, Runnable onDrop) {
        grabber.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int startLeft, startTop, startW, startH;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        startLeft = params.leftMargin;
                        startTop = params.topMargin;
                        startW = params.width;
                        startH = params.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = pxToDp(density, (int) (event.getRawX() - startX));
                        float dy = pxToDp(density, (int) (event.getRawY() - startY));
                        if (resize == null) {
                            // Keep the whole window reachable: margins clamp to the content view.
                            ViewGroup host = (ViewGroup) window.getParent();
                            int maxLeft = Math.max(0,
                                (host != null ? host.getWidth() : 0) - params.width);
                            int maxTop = Math.max(0,
                                (host != null ? host.getHeight() : 0) - params.height);
                            params.leftMargin = Math.max(0, Math.min(startLeft
                                + (int) (dx * density), maxLeft));
                            params.topMargin = Math.max(0, Math.min(startTop
                                + (int) (dy * density), maxTop));
                            sLeftDp = pxToDp(density, params.leftMargin);
                            sTopDp = pxToDp(density, params.topMargin);
                        } else {
                            params.width = Math.max(dp(density, MIN_WIDTH_DP),
                                startW + (int) (dx * density));
                            params.height = Math.max(dp(density, MIN_HEIGHT_DP),
                                startH + (int) (dy * density));
                            sWidthDp = Math.max(MIN_WIDTH_DP, startW + (int) dx);
                            sHeightDp = Math.max(MIN_HEIGHT_DP, startH + (int) dy);
                        }
                        window.setLayoutParams(params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        onDrop.run();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Persisted shapes larger than the current screen are stale (rotation, old builds), not user intent. */
    private static void loadPersistedBounds(TermuxActivity activity, int screenW, int screenH) {
        if (sBoundsLoaded) return;
        android.content.SharedPreferences prefs =
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sLeftDp = prefs.getInt(KEY_LEFT, -1);
        sTopDp = prefs.getInt(KEY_TOP, -1);
        sWidthDp = prefs.getInt(KEY_WIDTH, -1);
        sHeightDp = prefs.getInt(KEY_HEIGHT, -1);
        float density = activity.getResources().getDisplayMetrics().density;
        int maxW = pxToDp(density, (int) (screenW * 0.95f));
        int maxH = pxToDp(density, (int) (screenH * 0.95f));
        if (sWidthDp > maxW || sHeightDp > maxH
            || sLeftDp > pxToDp(density, screenW) || sTopDp > pxToDp(density, screenH)) {
            sLeftDp = sTopDp = sWidthDp = sHeightDp = -1;
        }
        sBoundsLoaded = true;
    }

    private static void persistBounds(TermuxActivity activity) {
        if (sWidthDp < 0) return;
        android.content.SharedPreferences.Editor editor =
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putInt(KEY_LEFT, sLeftDp).putInt(KEY_TOP, sTopDp)
            .putInt(KEY_WIDTH, sWidthDp).putInt(KEY_HEIGHT, sHeightDp).apply();
    }

    private static Button barButton(Activity activity, String label, float density) {
        Button button = new Button(activity);
        button.setText(label);
        int onSurface = MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorOnSurface, 0xFFDDDDDD);
        button.setTextColor(onSurface);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(density, 10), 0, dp(density, 10), 0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinHeight(dp(density, BAR_HEIGHT_DP));
        return button;
    }

    private static String ensureScheme(String url) {
        return url.contains("://") ? url : "https://" + url;
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }

    private static int dp(float density, float value) {
        return Math.round(value * density);
    }

    private static int pxToDp(float density, int px) {
        return Math.round(px / density);
    }

    private static float clamp(int value, int min, float max, float fallback) {
        if (value < min || max < min) return fallback;
        return Math.min(value, max);
    }

    private static Activity findActivity(android.content.ContextWrapper wrapper) {
        android.content.Context base = wrapper.getBaseContext();
        while (base instanceof android.content.ContextWrapper)
            base = ((android.content.ContextWrapper) base).getBaseContext();
        return (base instanceof Activity) ? (Activity) base : null;
    }
}
