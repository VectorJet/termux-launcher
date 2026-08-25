package com.termux.app.terminal;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.view.Gravity;
import android.view.Window;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import com.termux.shared.logger.Logger;

import java.io.File;

/**
 * Floating media player shown in response to "OSC 7770 ; <source>" requests from the shell.
 * Uses the platform {@link VideoView}, which hands decoding to MediaCodec on a dedicated
 * surface, so playback never passes through the terminal's pty or bitmap pipeline.
 */
public final class MediaOverlayDialog {

    private static final String LOG_TAG = "MediaOverlayDialog";

    private MediaOverlayDialog() {
    }

    /** Resolve and play $source, which may be an absolute path, a file:// or http(s) URL. */
    public static void show(Context context, String source) {
        Activity activity = null;
        if (context instanceof Activity) activity = (Activity) context;
        else if (context instanceof android.content.ContextWrapper)
            activity = findActivity((android.content.ContextWrapper) context);
        if (activity == null || activity.isFinishing()) {
            Logger.logWarn(LOG_TAG, "no usable activity for media overlay request");
            return;
        }
        Uri uri = resolve(context, source);
        if (uri == null) {
            Toast.makeText(activity, "media overlay: cannot resolve '" + source + "'", Toast.LENGTH_LONG).show();
            return;
        }

        final Dialog dialog = new Dialog(activity);
        final VideoView video = new VideoView(activity);
        video.setMediaController(new MediaController(activity));
        video.setVideoURI(uri);
        video.setOnCompletionListener(mp -> dialog.dismiss());
        video.setOnErrorListener((mp, what, extra) -> {
            Logger.logError(LOG_TAG, "playback failed: what=" + what + " extra=" + extra + " for " + uri);
            Toast.makeText(activity, "media overlay: playback failed", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return true;
        });

        dialog.addContentView(video,
            new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        final Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.black);
            window.setGravity(Gravity.CENTER);
        }
        dialog.setCancelable(true);
        dialog.show();
        video.start();
    }

    private static Activity findActivity(android.content.ContextWrapper wrapper) {
        android.content.Context base = wrapper.getBaseContext();
        while (base instanceof android.content.ContextWrapper) base = ((android.content.ContextWrapper) base).getBaseContext();
        return (base instanceof Activity) ? (Activity) base : null;
    }

    private static Uri resolve(Context context, String source) {
        if (source.startsWith("http://") || source.startsWith("https://") || source.startsWith("content://"))
            return Uri.parse(source);
        if (source.startsWith("file://"))
            return Uri.parse(source);
        File file = new File(source);
        if (!file.isAbsolute())
            file = new File(Environment.getExternalStorageDirectory(), source);
        // Termux apps default to their private root; try that too before giving up.
        if (!file.exists()) {
            File termuxRoot = new File(new File(context.getFilesDir().getParentFile(), "files/home"), source);
            if (termuxRoot.exists()) return Uri.fromFile(termuxRoot);
            return null;
        }
        return Uri.fromFile(file);
    }
}
