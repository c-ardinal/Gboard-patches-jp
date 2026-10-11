package dev.jason.gboardpatches.extension.extendeddictionary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;

/** Modal progress for one import/remove job; re-attachable after the Activity is recreated. */
final class ExtendedDictionaryProgressDialog {
    private static final String TAG = "GboardExtendedDictionary";
    static final String UNKNOWN_REMAINING = "…";
    private static final int RING_SIZE_DP = 72;
    private static final int RING_STROKE_DP = 6;
    private static final int PADDING_DP = 24;

    /** Process-wide state of a running job; view fields are touched on the main thread only. */
    static final class Job {
        final int titleResId;
        final boolean cancellable;
        final AtomicBoolean cancel = new AtomicBoolean();
        final AtomicReference<Object> attachedHost = new AtomicReference<>();
        final long startNanos = System.nanoTime();
        volatile int done;
        volatile int total;
        volatile int samples;
        volatile boolean finished;
        Runnable view;

        Job(int titleResId, boolean cancellable) {
            this.titleResId = titleResId;
            this.cancellable = cancellable;
        }

        void onProgress(int done, int total) {
            this.done = done;
            this.total = total;
            if (done > 0) {
                samples++;
            }
        }

        /** Main thread only. Never throws, and drops every UI reference once finished. */
        void render() {
            Runnable current = view;
            try {
                if (current != null) {
                    current.run();
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "Progress dialog update failed: " + failure.getClass().getName());
                view = null;
            }
            if (finished) {
                view = null;
                attachedHost.set(null);
            }
        }
    }

    /** True when the host Activity can no longer show or dismiss its windows. */
    static boolean isHostGone(Context context) {
        return context instanceof Activity activity
                && (activity.isFinishing() || activity.isDestroyed());
    }

    private ExtendedDictionaryProgressDialog() {
    }

    /** Seconds left estimated from the elapsed time per finished row; -1 while unknown. */
    static long remainingSeconds(long elapsedNanos, int done, int total, int samples) {
        if (samples < 2 || done <= 0) {
            return -1L;
        }
        if (done >= total) {
            return 0L;
        }
        double nanosPerRow = (double) elapsedNanos / done;
        return (long) Math.ceil(nanosPerRow * (total - done) / 1_000_000_000d);
    }

    static String remainingText(Context context, long seconds) {
        return seconds < 0 ? UNKNOWN_REMAINING : GboardSettingsText.format(context,
                R.string.gboard_patches_extended_dictionary_progress_remaining, seconds);
    }

    static String countText(int done, int total) {
        return String.format(Locale.ROOT, "%d / %d", done, total);
    }

    static GboardPatchesSettingsContract.ManagedDialogAction create(Context context, Job job) {
        return onDismiss -> {
            if (isHostGone(context) || job.finished) {
                job.attachedHost.compareAndSet(context, null);
                return false;
            }
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER_HORIZONTAL);
            int padding = dp(context, PADDING_DP);
            layout.setPadding(padding, padding, padding, padding);
            ProgressRing ring = new ProgressRing(context);
            layout.addView(ring, new LinearLayout.LayoutParams(
                    dp(context, RING_SIZE_DP), dp(context, RING_SIZE_DP)));
            TextView count = new TextView(context);
            count.setGravity(Gravity.CENTER);
            count.setPadding(0, padding / 2, 0, 0);
            layout.addView(count);
            TextView remaining = new TextView(context);
            remaining.setGravity(Gravity.CENTER);
            layout.addView(remaining);

            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle(GboardSettingsText.get(context, job.titleResId))
                    .setView(layout)
                    .setCancelable(false);
            if (job.cancellable) {
                builder.setNegativeButton(
                        GboardSettingsText.get(context, R.string.gboard_patches_dialog_cancel),
                        null);
            }
            AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            Runnable[] self = new Runnable[1];
            Runnable update = () -> {
                if (isHostGone(context)) {
                    if (job.view == self[0]) {
                        job.view = null;
                    }
                    job.attachedHost.compareAndSet(context, null);
                    return;
                }
                if (job.finished) {
                    dialog.dismiss();
                    return;
                }
                int total = job.total;
                int done = job.done;
                ring.setFraction(total <= 0 ? 0f : (float) done / total);
                count.setText(countText(done, total));
                remaining.setText(job.cancel.get()
                        ? GboardSettingsText.get(context,
                                R.string.gboard_patches_extended_dictionary_progress_cancelling)
                        : remainingText(context, remainingSeconds(
                                System.nanoTime() - job.startNanos, done, total, job.samples)));
            };
            self[0] = update;
            dialog.setOnDismissListener(ignored -> {
                if (job.view == update) {
                    job.view = null;
                }
                try {
                    onDismiss.run();
                } catch (Throwable failure) {
                    Log.w(TAG, "Failed to dismiss progress dialog", failure);
                }
            });
            job.view = update;
            dialog.show();
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) {
                cancel.setOnClickListener(button -> {
                    job.cancel.set(true);
                    button.setEnabled(false);
                    job.render();
                });
            }
            job.render();
            return true;
        };
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Determinate circular progress drawn as an arc over a faint track. */
    static final class ProgressRing extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private float fraction;

        ProgressRing(Context context) {
            super(context);
            float stroke = dp(context, RING_STROKE_DP);
            TypedValue accent = new TypedValue();
            int color = context.getTheme().resolveAttribute(
                    android.R.attr.colorAccent, accent, true) ? accent.data : 0xFF1A73E8;
            for (Paint paint : new Paint[] {track, arc}) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(color);
            }
            track.setAlpha(48);
        }

        void setFraction(float value) {
            fraction = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        float getFraction() {
            return fraction;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = arc.getStrokeWidth() / 2f;
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawArc(bounds, 0f, 360f, false, track);
            canvas.drawArc(bounds, -90f, 360f * fraction, false, arc);
        }
    }
}
