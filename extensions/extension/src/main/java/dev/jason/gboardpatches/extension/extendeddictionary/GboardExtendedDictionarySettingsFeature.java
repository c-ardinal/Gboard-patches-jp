package dev.jason.gboardpatches.extension.extendeddictionary;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;

public final class GboardExtendedDictionarySettingsFeature
        implements GboardPatchesSettingsContract.Feature {
    private static final String TAG = "GboardExtendedDictionary";
    private static final String[] WORD_LIST_MIME_TYPES = {"text/plain", "application/zip"};
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "GboardExtendedDictionary");
        thread.setDaemon(true);
        return thread;
    });

    private final String entryTitle;
    private final String entrySummary;
    private final ClassLoader gboardClassLoader;

    public GboardExtendedDictionarySettingsFeature(Context context) {
        this(context, context.getClassLoader());
    }

    GboardExtendedDictionarySettingsFeature(Context context, ClassLoader gboardClassLoader) {
        this.gboardClassLoader = gboardClassLoader;
        entryTitle = text(context, R.string.gboard_patches_extended_dictionary_title);
        entrySummary = text(context, R.string.gboard_patches_extended_dictionary_summary);
    }

    @Override
    public String getEntryTitle() {
        return entryTitle;
    }

    @Override
    public String getEntrySummary() {
        return entrySummary;
    }

    @Override
    public boolean isAvailable(Context context) {
        return GboardPatchesFeatureAvailability.hasFeature(
                context,
                GboardPatchesFeatureAvailability.FEATURE_EXTENDED_DICTIONARY);
    }

    @Override
    public GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host) {
        Context context = host.getContext();
        boolean supported = GboardPersonalDictionaryReflectionGateway.isSupported(
                gboardClassLoader);
        int managedCount = managedCount(context);
        List<GboardPatchesSettingsContract.Row> rows = new ArrayList<>();
        rows.add(new GboardPatchesSettingsContract.CommandRow(
                text(context, R.string.gboard_patches_extended_dictionary_import_title),
                GboardSettingsText.format(context,
                        R.string.gboard_patches_extended_dictionary_import_summary,
                        WordListParser.MAX_ROWS_PER_IMPORT),
                supported,
                () -> importWordList(host)));
        rows.add(new GboardPatchesSettingsContract.DangerRow(
                text(context, R.string.gboard_patches_extended_dictionary_remove_title),
                text(context, R.string.gboard_patches_extended_dictionary_remove_summary),
                supported && managedCount > 0,
                () -> removeImportedWords(host),
                text(context, R.string.gboard_patches_extended_dictionary_remove_confirm_title),
                text(context,
                        R.string.gboard_patches_extended_dictionary_remove_confirm_message)));
        rows.add(new GboardPatchesSettingsContract.InfoRow(
                text(context, R.string.gboard_patches_extended_dictionary_status_title),
                managedCount < 0
                        ? text(context, R.string.gboard_patches_extended_dictionary_status_unreadable)
                        : GboardSettingsText.format(context,
                                R.string.gboard_patches_extended_dictionary_status_summary,
                                managedCount),
                true));
        List<GboardPatchesSettingsContract.Section> sections = Collections.singletonList(
                new GboardPatchesSettingsContract.Section(
                        text(context, R.string.gboard_patches_extended_dictionary_section_title),
                        rows));
        return new GboardPatchesSettingsContract.Screen(
                entryTitle,
                "Gboard",
                entryTitle,
                supported ? entrySummary : text(context,
                        R.string.gboard_patches_extended_dictionary_unsupported_summary),
                Collections.emptyList(),
                sections);
    }

    private static int managedCount(Context context) {
        try {
            return ManagedEntryStore.open(context).load().size();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Managed dictionary entries are unreadable");
            return -1;
        }
    }

    private void importWordList(GboardPatchesSettingsContract.FeatureHost host) {
        GboardPatchesSettingsContract.openBinaryDocument(host, WORD_LIST_MIME_TYPES,
                document -> runInBackground(host,
                        text(host.getContext(),
                                R.string.gboard_patches_extended_dictionary_import_failed),
                        () -> importDocument(host.getContext(), document.getData())));
    }

    private void removeImportedWords(GboardPatchesSettingsContract.FeatureHost host) {
        runInBackground(host,
                text(host.getContext(), R.string.gboard_patches_extended_dictionary_remove_failed),
                () -> removeAll(host.getContext()));
    }

    private static void runInBackground(GboardPatchesSettingsContract.FeatureHost host,
            String failureMessage, Task task) {
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            String message = runSafely(task, failureMessage);
            main.post(() -> {
                GboardPatchesSettingsContract.showMessage(host, message);
                GboardPatchesSettingsContract.refresh(host);
            });
        });
    }

    /** The worker runs inside Gboard's main process, so nothing may escape it. */
    static String runSafely(Task task, String failureMessage) {
        try {
            return task.run();
        } catch (Throwable failure) {
            Log.w(TAG, "Dictionary task failed: " + failure.getClass().getName());
            return failureMessage;
        }
    }

    String importDocument(Context context, byte[] data) {
        List<WordListParser.Row> rows;
        try {
            rows = WordListParser.parse(data);
        } catch (WordListParser.ParseException failure) {
            return parseErrorMessage(context, failure);
        }
        if (rows.isEmpty()) {
            return text(context, R.string.gboard_patches_extended_dictionary_import_empty);
        }
        GboardPersonalDictionaryReflectionGateway gateway =
                GboardPersonalDictionaryReflectionGateway.open(context, gboardClassLoader);
        if (gateway == null) {
            return text(context, R.string.gboard_patches_extended_dictionary_unsupported_summary);
        }
        try {
            ExtendedDictionaryImporter.ImportResult result =
                    ExtendedDictionaryImporter.importRows(
                            gateway, ManagedEntryStore.open(context), rows);
            Log.i(TAG, "Import rows=" + rows.size() + " inserted=" + result.inserted
                    + " skipped=" + result.skippedExisting + " failed=" + result.failed
                    + " rolledBack=" + result.rolledBack);
            if (!result.rolledBack) {
                return GboardSettingsText.format(context,
                        R.string.gboard_patches_extended_dictionary_import_done,
                        result.inserted, result.skippedExisting, result.failed);
            }
            return result.rollbackLeftovers == 0
                    ? text(context, R.string.gboard_patches_extended_dictionary_import_rolled_back)
                    : GboardSettingsText.format(context,
                            R.string.gboard_patches_extended_dictionary_import_rollback_incomplete,
                            result.rollbackLeftovers);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Import failed: " + failure.getClass().getName());
            return text(context, R.string.gboard_patches_extended_dictionary_import_failed);
        } finally {
            gateway.close();
        }
    }

    String removeAll(Context context) {
        GboardPersonalDictionaryReflectionGateway gateway =
                GboardPersonalDictionaryReflectionGateway.open(context, gboardClassLoader);
        if (gateway == null) {
            return text(context, R.string.gboard_patches_extended_dictionary_unsupported_summary);
        }
        try {
            ExtendedDictionaryImporter.RemoveResult result =
                    ExtendedDictionaryImporter.removeAll(gateway, ManagedEntryStore.open(context));
            Log.i(TAG, "Remove deleted=" + result.deleted + " forgotten=" + result.forgotten
                    + " failed=" + result.failed);
            return GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_remove_done,
                    result.deleted, result.forgotten, result.failed);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Remove failed: " + failure.getClass().getName());
            return text(context, R.string.gboard_patches_extended_dictionary_remove_failed);
        } finally {
            gateway.close();
        }
    }

    static String parseErrorMessage(Context context, WordListParser.ParseException failure) {
        int line = failure.lineNumber;
        return switch (failure.error) {
            case INVALID_ENCODING -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_encoding, line);
            case UNSUPPORTED_HEADER -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_header, line);
            case WRONG_COLUMN_COUNT -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_columns, line);
            case INVALID_LANGUAGE_TAG -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_language_tag, line);
            case FIELD_TOO_LONG -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_too_long, line,
                    WordListParser.MAX_FIELD_LENGTH);
            case TOO_MANY_ROWS -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_too_many,
                    WordListParser.MAX_ROWS_PER_IMPORT);
            case TOO_MANY_LINES -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_too_many_lines,
                    WordListParser.MAX_LINES);
            case LINE_TOO_LONG -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_line_too_long, line,
                    WordListParser.MAX_LINE_CHARS);
            case INPUT_TOO_LARGE -> GboardSettingsText.format(context,
                    R.string.gboard_patches_extended_dictionary_error_input_too_large,
                    WordListParser.MAX_INPUT_BYTES / (1024 * 1024));
            case UNREADABLE -> text(context,
                    R.string.gboard_patches_extended_dictionary_error_unreadable);
        };
    }

    private static String text(Context context, int resourceId) {
        return GboardSettingsText.get(context, resourceId);
    }

    interface Task {
        String run();
    }
}
