package com.termux.app.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * A draggable, resizable floating window hosting a {@link WebView}, shown over the launcher.
 *
 * Opened from the shell via "OSC 7770 ; <source>". A source starting with "browse:" loads a plain
 * page in the overlay's WebView; any other source is resolved to a media file or stream and played
 * through an HTML5 video element, which hands decoding to the Chromium/media stack instead of the
 * terminal bitmap pipeline.
 *
 * This deliberately lives outside {@link com.termux.app.terminal.TerminalPaneController}: pane
 * leaves are bound to a {@link com.termux.terminal.TerminalSession} and reflow a PTY on resize,
 * none of which applies to web content. The overlay floats above everything in the activity's
 * content view and consumes its own touches, so the terminal underneath never sees them.
 *
 * While the WebView owns focus the system IME is handed over through the activity's
 * external-input path, because the embedded keyboard otherwise suppresses the system IME
 * app-wide; on blur or close ownership returns to the embedded keyboard.
 */
public final class FloatingWebOverlay {

    private static final String LOG_TAG = "FloatingWebOverlay";

    private static final int BAR_HEIGHT_DP = 36;
    private static final int HANDLE_SIZE_DP = 22;
    private static final int MIN_WIDTH_DP = 180;
    private static final int MIN_HEIGHT_DP = 140;

    private static final int BORDER_COLOR_IDLE = 0xFF555555;
    private static final int BORDER_COLOR_FOCUSED = 0xFF4FC3F7;

    private static final String PREFS_NAME = "web_overlay_bounds";
    private static final String KEY_LEFT = "left";
    private static final String KEY_TOP = "top";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";

    /** Bounds survive hide/show for the process lifetime, and persist to prefs across restarts. */
    private static int sLeftDp = -1, sTopDp = -1, sWidthDp = -1, sHeightDp = -1;
    private static boolean sBoundsLoaded;

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
            if (LOG_TAG.equals(root.getChildAt(i).getTag()))
                root.removeViewAt(i);
        }

        loadPersistedBounds(activity);
        final int width = dp(density, clamp(sWidthDp, MIN_WIDTH_DP, screenW / density,
            Math.min(screenW, 420) / density));
        final int height = dp(density, clamp(sHeightDp, MIN_HEIGHT_DP, screenH / density,
            Math.min(screenH, 360) / density));
        final int left = dp(density, clamp(sLeftDp, 0, (screenW - width) / density,
            (screenW - width) / 2f / density));
        final int top = dp(density, clamp(sTopDp, 0, (screenH - height) / density,
            (screenH - height) / 4f / density));

        final FrameLayout window = new FrameLayout(activity);
        window.setTag(LOG_TAG);
        window.setClipToOutline(true);
        window.setElevation(dp(density, 12));
        // Border doubles as the focus indicator: gray when idle, cyan when the overlay owns input.
        final GradientDrawable border = new GradientDrawable();
        border.setColor(Color.BLACK);
        border.setCornerRadius(dp(density, 8));
        border.setStroke(Math.max(1, dp(density, 1)), BORDER_COLOR_IDLE);
        window.setBackground(border);

        final FrameLayout.LayoutParams windowParams = new FrameLayout.LayoutParams(width, height);
        windowParams.leftMargin = left;
        windowParams.topMargin = top;
        root.addView(window, windowParams);

        final LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        window.addView(column, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Title bar: drag surface plus navigation and close ---
        final LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xEE1F1F24);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(density, BAR_HEIGHT_DP)));

        final TextView title = new TextView(activity);
        title.setText(browseMode ? target : shortName(target));
        title.setTextColor(0xFFDDDDDD);
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setPadding(dp(density, 10), 0, dp(density, 10), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final WebView webView = makeWebView(activity);

        Button backButton = barButton(activity, "‹", density);
        backButton.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        bar.addView(backButton);

        Button reloadButton = barButton(activity, "↻", density);
        reloadButton.setOnClickListener(v -> webView.reload());
        bar.addView(reloadButton);

        Button urlButton = barButton(activity, "⌗", density);
        urlButton.setOnClickListener(v -> promptForUrl(activity, webView, title, density));
        bar.addView(urlButton);

        Button closeButton = barButton(activity, "✕", density);
        closeButton.setOnClickListener(v -> {
            webView.stopLoading();
            webView.destroy();
            root.removeView(window);
            activity.endWebOverlayTextInput();
            dismissFullscreen(root);
            persistBounds(activity);
        });
        bar.addView(closeButton);

        // --- Content ---
        column.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loadSource(activity, webView, browseMode, target);

        // Focus hand-off: while the page holds focus it also owns the system IME.
        webView.setOnFocusChangeListener((v, hasFocus) -> {
            border.setStroke(Math.max(1, dp(density, hasFocus ? 3 : 1)),
                hasFocus ? BORDER_COLOR_FOCUSED : BORDER_COLOR_IDLE);
            if (hasFocus) activity.beginWebOverlayTextInput(webView);
            else if (!activity.isFinishing()) activity.endWebOverlayTextInput();
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
                // Fullscreen video requested by the page: cover the whole content view.
                ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
                dismissFullscreen(root);
                view.setTag("web_overlay_fullscreen");
                view.setBackgroundColor(Color.BLACK);
                root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                mFullscreenCallback = callback;
            }

            @Override
            public void onHideCustomView() {
                ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
                dismissFullscreen(root);
            }
        });
        return webView;
    }

    private static WebChromeClient.CustomViewCallback mFullscreenCallback;

    private static void dismissFullscreen(ViewGroup root) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            if ("web_overlay_fullscreen".equals(child.getTag())) {
                root.removeView(child);
                if (mFullscreenCallback != null) {
                    mFullscreenCallback.onCustomViewHidden();
                    mFullscreenCallback = null;
                }
            }
        }
    }

    private static void promptForUrl(TermuxActivity activity, WebView webView, TextView title,
                                     float density) {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(webView.getUrl() != null ? webView.getUrl() : "https://");
        new AlertDialog.Builder(activity)
            .setTitle("Open URL")
            .setView(input)
            .setPositiveButton("Open", (d, w) -> {
                String url = input.getText().toString().trim();
                if (!url.isEmpty()) {
                    if (!url.contains("://")) url = "https://" + url;
                    webView.loadUrl(url);
                    title.setText(url);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
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
        // Local media goes through a tiny HTML5 page so the platform player UI and hardware
        // decoder are used; WebView alone would try to download bare file:// media links.
        String src = uri.toString();
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,"
            + "initial-scale=1'><style>html,body{margin:0;height:100%;background:#000}"
            + "video{width:100%;height:100%;}</style></head>"
            + "<body><video src='" + src + "' controls autoplay playsinline></video></body></html>";
        File page = new File(context.getCacheDir(), "media_overlay.html");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(page)) {
            out.write(html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "failed to write media overlay page", e);
            return;
        }
        webView.loadUrl(Uri.fromFile(page).toString());
    }

    private static Uri resolve(Context context, String source) {
        if (source.startsWith("file://")) return Uri.parse(source);
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
                            params.leftMargin = Math.max(0, startLeft + (int) (dx * density));
                            params.topMargin = Math.max(0, startTop + (int) (dy * density));
                            sLeftDp = Math.max(0, startLeft + (int) dx);
                            sTopDp = Math.max(0, startTop + (int) dy);
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

    private static void loadPersistedBounds(TermuxActivity activity) {
        if (sBoundsLoaded) return;
        android.content.SharedPreferences prefs =
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sLeftDp = prefs.getInt(KEY_LEFT, -1);
        sTopDp = prefs.getInt(KEY_TOP, -1);
        sWidthDp = prefs.getInt(KEY_WIDTH, -1);
        sHeightDp = prefs.getInt(KEY_HEIGHT, -1);
        sBoundsLoaded = true;
    }

    private static void persistBounds(TermuxActivity activity) {
        android.content.SharedPreferences.Editor editor =
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putInt(KEY_LEFT, sLeftDp).putInt(KEY_TOP, sTopDp)
            .putInt(KEY_WIDTH, sWidthDp).putInt(KEY_HEIGHT, sHeightDp).apply();
    }

    private static Button barButton(Activity activity, String label, float density) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextColor(0xFFDDDDDD);
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
