package dev.jason.gboardpatches.extension.extendeddictionary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public final class GboardPersonalDictionaryReflectionGatewayTest {
    private static final String POS_TABLE = "ja_ext";
    private static final String NOUN = ExtendedDictionaryImporter.JAPANESE_DEFAULT_POS_TAG;

    private Context context;
    private SQLiteOpenHelper helper;
    private AtomicInteger notifications;
    private GboardPersonalDictionaryReflectionGateway gateway;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        helper = new PersonalDictionaryHelper(context, true);
        notifications = new AtomicInteger();
        gateway = new GboardPersonalDictionaryReflectionGateway(helper, POS_TABLE,
                notifications::incrementAndGet, tag -> tag);
    }

    @Test
    public void insertAllWritesEntriesAndPosRowsOnlyForJapaneseAndNotifiesOnce() {
        long userId = userRow("Mine", "m", "en-US");

        long[] ids = gateway.insertAll(Arrays.asList(
                entry("構築", "こう", "ja-JP", NOUN),
                entry("On my way!", "omw", "en-US", ""),
                entry("犬", "いぬ", "ja-JP", "")), noProgress(), new AtomicBoolean());

        assertEquals(3, ids.length);
        assertEquals(4, count("entry"));
        assertEquals(1, count(POS_TABLE));
        assertEquals(NOUN, posTag(ids[0]));
        assertEquals(1, notifications.get());
        assertTrue(gateway.exists(ids[1], "On my way!", "omw", "en-US"));
        assertTrue(gateway.contains("Mine", "m", "en-US"));
        assertFalse(gateway.exists(userId, "On my way!", "omw", "en-US"));
    }

    @Test
    public void insertAllHandlesAFullSizeImportInOneTransaction() {
        long[] ids = gateway.insertAll(rows(WordListParser.MAX_ROWS_PER_IMPORT), noProgress(),
                new AtomicBoolean());

        assertEquals(WordListParser.MAX_ROWS_PER_IMPORT, ids.length);
        assertEquals(WordListParser.MAX_ROWS_PER_IMPORT, count("entry"));
        assertEquals(1, notifications.get());
    }

    @Test
    public void orphanPosRowUnderAReusedRowidIsReplacedInsteadOfFailingTheImport() {
        insertPos(1L, "人名");

        long[] ids = gateway.insertAll(rows(1), noProgress(), new AtomicBoolean());

        assertEquals(1L, ids[0]);
        assertEquals(1, count(POS_TABLE));
        assertEquals(NOUN, posTag(1L));
        assertEquals(1, notifications.get());
    }

    @Test
    public void cancellingMidwayRollsBackEveryRowAndDoesNotNotify() {
        userRow("Mine", "m", "en-US");
        AtomicBoolean cancel = new AtomicBoolean();

        assertThrows(CancellationException.class, () -> gateway.insertAll(
                rows(10), (done, total) -> cancel.set(done == 3), cancel));

        assertEquals(1, count("entry"));
        assertEquals(0, count(POS_TABLE));
        assertEquals(0, notifications.get());
    }

    @Test
    public void failureMidwayRollsBackEveryRowAndDoesNotNotify() {
        try {
            gateway.insertAll(rows(10), (done, total) -> {
                if (done == 5) {
                    throw new IllegalStateException("simulated failure");
                }
            }, new AtomicBoolean());
            fail("Expected the batch to fail");
        } catch (IllegalStateException expected) {
            assertEquals(0, count("entry"));
            assertEquals(0, count(POS_TABLE));
            assertEquals(0, notifications.get());
        }
    }

    @Test
    public void deleteAllRemovesOnlyGivenRowsWithTheirPosRows() {
        long userId = userRow("Mine", "m", "ja-JP");
        insertPos(userId, NOUN);
        long[] ids = gateway.insertAll(rows(3), noProgress(), new AtomicBoolean());
        notifications.set(0);

        gateway.deleteAll(Collections.singletonList(
                new ManagedEntryStore.Entry(ids[1], "w1", "s1", "ja-JP", NOUN)), noProgress());

        assertEquals(3, count("entry"));
        assertEquals(3, count(POS_TABLE));
        assertNull(posTag(ids[1]));
        assertEquals(NOUN, posTag(userId));
        assertFalse(gateway.exists(ids[1], "w1", "s1", "ja-JP"));
        assertTrue(gateway.exists(ids[0], "w0", "s0", "ja-JP"));
        assertEquals(1, notifications.get());
    }

    @Test
    public void schemaCheckFailsClosedWhenThePosTableIsMissing() {
        assertTrue(gateway.hasExpectedSchema());
        GboardPersonalDictionaryReflectionGateway withoutPos =
                new GboardPersonalDictionaryReflectionGateway(
                        new PersonalDictionaryHelper(context, false), POS_TABLE, () -> {
                        }, tag -> tag);
        assertFalse(withoutPos.hasExpectedSchema());
    }

    @Test
    public void preflightFailsClosedWhenGboardClassesAreMissing() {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
        };

        assertFalse(GboardPersonalDictionaryReflectionGateway.isSupported(loader));
        assertNull(GboardPersonalDictionaryReflectionGateway.open(context, loader));
        assertFalse(GboardPersonalDictionaryReflectionGateway.isSupported(null));
        assertNull(GboardPersonalDictionaryReflectionGateway.resolve(getClass().getClassLoader(),
                FakeDatabase.class.getName(), WrongBase.class.getName(),
                FakeTag.class.getName()));
    }

    @Test
    public void reflectionPathUsesTheHelperFieldPosTableNameAndNotifier() {
        GboardPersonalDictionaryReflectionGateway.Api api =
                GboardPersonalDictionaryReflectionGateway.resolve(getClass().getClassLoader(),
                        FakeDatabase.class.getName(), FakeBase.class.getName(),
                        FakeTag.class.getName());
        assertNotNull(api);
        FakeBase.notified = 0;

        GboardPersonalDictionaryReflectionGateway opened =
                GboardPersonalDictionaryReflectionGateway.open(context, api);
        assertNotNull(opened);
        long[] ids = opened.insertAll(Collections.singletonList(
                entry("犬", "いぬ", "ja-jp", NOUN)), noProgress(), new AtomicBoolean());

        assertEquals(1, FakeBase.notified);
        assertTrue(opened.exists(ids[0], "犬", "いぬ", "ja-jp"));
        opened.close();
    }

    private static ExtendedDictionaryImporter.Progress noProgress() {
        return (done, total) -> {
        };
    }

    private static ManagedEntryStore.Entry entry(String word, String shortcut, String tag,
            String pos) {
        return new ManagedEntryStore.Entry(-1L, word, shortcut, tag, pos);
    }

    private static List<ManagedEntryStore.Entry> rows(int count) {
        List<ManagedEntryStore.Entry> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(entry("w" + index, "s" + index, "ja-JP", NOUN));
        }
        return rows;
    }

    private long userRow(String word, String shortcut, String tag) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.execSQL("INSERT INTO entry(word, shortcut, locale) VALUES (?, ?, ?)",
                new Object[] {word, shortcut, tag});
        try (Cursor cursor = db.rawQuery("SELECT last_insert_rowid()", null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    private void insertPos(long id, String pos) {
        helper.getWritableDatabase().execSQL(
                "INSERT INTO " + POS_TABLE + "(_id, pos_tag) VALUES (?, ?)",
                new Object[] {id, pos});
    }

    private int count(String table) {
        try (Cursor cursor = helper.getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private String posTag(long id) {
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT pos_tag FROM " + POS_TABLE + " WHERE _id = ?",
                new String[] {String.valueOf(id)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    /** Same schema as Gboard 18.0.3 qcd (PersonalDictionary.db version 2), kept in memory. */
    static final class PersonalDictionaryHelper extends SQLiteOpenHelper {
        private final boolean withPosTable;

        PersonalDictionaryHelper(Context context, boolean withPosTable) {
            super(context, null, null, 2);
            this.withPosTable = withPosTable;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE entry(_id INTEGER PRIMARY KEY, word TEXT, shortcut TEXT,"
                    + "locale TEXT)");
            if (withPosTable) {
                db.execSQL("CREATE TABLE IF NOT EXISTS " + POS_TABLE
                        + "(_id INTEGER PRIMARY KEY, pos_tag TEXT)");
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    public static final class FakeTag {
        private final String tag;

        private FakeTag(String tag) {
            this.tag = tag;
        }

        public static FakeTag g(String tag) {
            return new FakeTag("ja-jp".equals(tag) ? "ja-JP" : tag);
        }

        @Override
        public String toString() {
            return tag;
        }
    }

    public abstract static class FakeBase {
        protected static final String a = POS_TABLE;
        static int notified;
        protected final SQLiteOpenHelper b;

        protected FakeBase(SQLiteOpenHelper helper) {
            this.b = helper;
        }

        static void f() {
            notified++;
        }
    }

    public static final class FakeDatabase extends FakeBase {
        public FakeDatabase(Context context) {
            super(new PersonalDictionaryHelper(context, true));
        }
    }

    public abstract static class WrongBase {
        protected static final String a = POS_TABLE;
        protected final Object b = null;

        static void f() {
        }
    }
}
