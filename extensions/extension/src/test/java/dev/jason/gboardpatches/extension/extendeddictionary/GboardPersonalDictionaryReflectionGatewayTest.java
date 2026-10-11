package dev.jason.gboardpatches.extension.extendeddictionary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public final class GboardPersonalDictionaryReflectionGatewayTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeDatabase.reset();
    }

    @Test
    public void preflightFailsClosedWhenGboardClassesAreMissing() {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
        };

        assertFalse(GboardPersonalDictionaryReflectionGateway.isSupported(loader));
        assertNull(GboardPersonalDictionaryReflectionGateway.open(context, loader));
        assertFalse(GboardPersonalDictionaryReflectionGateway.isSupported(null));
    }

    @Test
    public void preflightRejectsMismatchedSignatures() {
        assertNull(resolve(WrongDatabase.class));
    }

    @Test
    public void gatewayUsesGboardApiAndVerifiesRowsByContent() {
        GboardPersonalDictionaryReflectionGateway.Api api = resolve(FakeDatabase.class);
        assertNotNull(api);
        long userId = FakeDatabase.put("Mine", "m", "en-US");

        GboardPersonalDictionaryReflectionGateway gateway =
                GboardPersonalDictionaryReflectionGateway.open(context, api);
        assertNotNull(gateway);
        assertTrue(gateway.contains("Mine", "m", "en-US"));
        assertFalse(gateway.contains("Mine", "m", "en-GB"));

        long id = gateway.insert("Word", "w", "ja-JP", "pos");
        assertTrue(id > 0L);
        assertEquals("pos", FakeDatabase.lastPosTag);
        assertTrue(gateway.contains("Word", "w", "ja-JP"));
        assertTrue(gateway.exists(id, "Word", "w", "ja-JP"));
        assertFalse(gateway.exists(userId, "Word", "w", "ja-JP"));
        assertEquals(-1L, gateway.insert("Word", "w", "ja-JP", "pos"));

        gateway.delete(id);
        assertFalse(FakeDatabase.ROWS.containsKey(id));
        assertFalse(gateway.exists(id, "Word", "w", "ja-JP"));
        assertTrue(FakeDatabase.ROWS.containsKey(userId));
        gateway.close();
        assertTrue(FakeDatabase.closed);
    }

    private static GboardPersonalDictionaryReflectionGateway.Api resolve(Class<?> database) {
        return GboardPersonalDictionaryReflectionGateway.resolve(
                GboardPersonalDictionaryReflectionGatewayTest.class.getClassLoader(),
                database.getName(), FakeEntry.class.getName(), FakeTag.class.getName());
    }

    public static final class FakeTag {
        private final String tag;

        private FakeTag(String tag) {
            this.tag = tag;
        }

        public static FakeTag g(String tag) {
            return new FakeTag(tag == null ? "" : tag);
        }

        @Override
        public String toString() {
            return tag;
        }
    }

    public static final class FakeEntry {
        final String word;
        final String shortcut;
        final FakeTag tag;
        final String posTag;

        public FakeEntry(long id, String word, String shortcut, FakeTag tag, String posTag) {
            this.word = word;
            this.shortcut = shortcut;
            this.tag = tag;
            this.posTag = posTag;
        }
    }

    public static final class FakeDatabase implements AutoCloseable {
        static final Map<Long, String[]> ROWS = new LinkedHashMap<>();
        static long nextId;
        static String lastPosTag;
        static boolean closed;

        public FakeDatabase(Context context) {
        }

        static void reset() {
            ROWS.clear();
            nextId = 1L;
            lastPosTag = null;
            closed = false;
        }

        static long put(String word, String shortcut, String tag) {
            long id = nextId++;
            ROWS.put(id, new String[] {word, shortcut, tag});
            return id;
        }

        public long a(FakeEntry entry) {
            lastPosTag = entry.posTag;
            for (String[] row : ROWS.values()) {
                if (row[0].equals(entry.word) && row[1].equals(entry.shortcut)
                        && row[2].equals(entry.tag.toString())) {
                    return -1L;
                }
            }
            return put(entry.word, entry.shortcut, entry.tag.toString());
        }

        public Cursor c() {
            MatrixCursor cursor = new MatrixCursor(
                    new String[] {"_id", "word", "shortcut", "locale", "pos_tag"});
            ROWS.forEach((id, row) -> cursor.addRow(new Object[] {id, row[0], row[1], row[2], ""}));
            return cursor;
        }

        public void e(long id) {
            ROWS.remove(id);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    public static final class WrongDatabase implements AutoCloseable {
        public WrongDatabase(Context context) {
        }

        public int a(FakeEntry entry) {
            return 0;
        }

        public Cursor c() {
            return null;
        }

        public void e(long id) {
        }

        @Override
        public void close() {
        }
    }
}
