package dev.jason.gboardpatches.extension.japaneseqwertyglide;

import android.view.View;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowSystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.FutureTask;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public final class GboardJapaneseQwertyGlide1803RuntimeTest {
    @Test
    public void rapidFinalStrokesQueueInOrderAndSessionChangeDiscardsThem() throws Exception {
        FakeManager manager = new FakeManager();
        GboardJapaneseQwertyGlide1803Runtime.augmentMotionEventHandlers(manager,
                new FakeInfo[]{new FakeInfo(SPATIAL, null, false)},
                new FakeDefinition(0x7f170704), new FakeViewDefinition(), null);
        Field bridges = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredField("BRIDGES");
        bridges.setAccessible(true);
        Object bridge = ((Map<?, ?>) bridges.get(null)).get(manager);
        Field layout = bridge.getClass().getDeclaredField("layout");
        layout.setAccessible(true);
        layout.set(bridge, new FakeLayout());
        Class<?> requestType = Class.forName(GboardJapaneseQwertyGlide1803Runtime.class.getName() + "$DecodeRequest");
        Constructor<?> constructor = requestType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object running = constructor.newInstance(new Object(), manager, bridge);
        Field pendingField = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredField("PENDING");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Long, Object> pending = (Map<Long, Object>) pendingField.get(null);
        pending.put(992233L, running);
        Method submit = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredMethod("submitDecode",
                Object.class, Object.class, bridge.getClass(), Object.class);
        submit.setAccessible(true);
        Object first = new Object();
        Object second = new Object();
        submit.invoke(null, new Object(), manager, bridge, first);
        submit.invoke(null, new Object(), manager, bridge, second);
        Field finalsField = bridge.getClass().getDeclaredField("finals");
        finalsField.setAccessible(true);
        java.util.ArrayDeque<?> finals = (java.util.ArrayDeque<?>) finalsField.get(bridge);
        Assert.assertEquals(2, finals.size());
        Field touch = finals.peekFirst().getClass().getDeclaredField("touch");
        touch.setAccessible(true);
        Assert.assertSame(first, touch.get(finals.peekFirst()));
        Assert.assertSame(second, touch.get(finals.peekLast()));
        GboardJapaneseQwertyGlide1803Runtime.onInputSessionChanged();
        Assert.assertTrue(finals.isEmpty());
        Assert.assertTrue(pending.isEmpty());
    }

    @Test
    public void previewGroupIsCenteredAndOverflowKeepsStockPosition() {
        android.widget.FrameLayout strip = new android.widget.FrameLayout(RuntimeEnvironment.getApplication());
        strip.layout(0, 0, 600, 50);
        View first = new View(strip.getContext());
        View second = new View(strip.getContext());
        strip.addView(first);
        strip.addView(second);
        first.layout(0, 0, 80, 50);
        second.layout(80, 0, 200, 50);
        GboardJapaneseQwertyGlide1803Runtime.centerPreviewChildren(strip);
        Assert.assertEquals(200f, first.getTranslationX(), 0f);
        Assert.assertEquals(200f, second.getTranslationX(), 0f);
        second.layout(80, 0, 700, 50);
        GboardJapaneseQwertyGlide1803Runtime.centerPreviewChildren(strip);
        Assert.assertEquals(0f, first.getTranslationX(), 0f);
    }

    @Test
    public void previewOnlyRendersWithoutSelectingOrInjectingTextAndCanBeCleared() throws Exception {
        Class<?> keyboardType = Class.forName("JpGlidePreviewKeyboardFixture");
        Object keyboard = keyboardType.getConstructor().newInstance();
        Method render = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredMethod(
                "renderPreview", Object.class, List.class);
        render.setAccessible(true);
        render.invoke(null, keyboard, java.util.Arrays.asList("hello", "help"));
        List<?> candidates = (List<?>) keyboardType.getField("candidates").get(keyboard);
        Assert.assertEquals(2, candidates.size());
        Assert.assertEquals("hello", candidates.get(0).getClass().getField("a").get(candidates.get(0)));
        Assert.assertNull(keyboardType.getField("selected").get(keyboard));
        Assert.assertEquals(false, keyboardType.getField("autoSelect").get(keyboard));
        render.invoke(null, keyboard, java.util.Collections.emptyList());
        Assert.assertTrue(((List<?>) keyboardType.getField("candidates").get(keyboard)).isEmpty());
    }

    @Test
    public void liveCandidatesFilterDuplicatesInvalidWordsAndLimitToThree() throws Exception {
        FakeDiff diff = new FakeDiff();
        for (String word : new String[]{"hello", "hello", "123", "help", "held", "hell"}) {
            diff.e.d.add(new FakeCandidate(word));
        }
        Assert.assertEquals(java.util.Arrays.asList("hello", "help", "held"),
                GboardJapaneseQwertyGlide1803Runtime.previewWords(diff));
        diff.e.d.clear();
        diff.d.h = "word";
        Assert.assertEquals(java.util.Collections.singletonList("word"),
                GboardJapaneseQwertyGlide1803Runtime.previewWords(diff));
    }

    private static final String SPATIAL =
            "com.google.android.libraries.inputmethod.motioneventhandler.SpatialModelMotionEventHandler";

    @Test
    public void japaneseModeRecognisesOnlyTheJapaneseQwertyDefinitions() {
        Assert.assertEquals("ABC", GboardJapaneseQwertyGlide1803Runtime.japaneseMode(
                new int[] {0x7f170704}));
        Assert.assertEquals("kana", GboardJapaneseQwertyGlide1803Runtime.japaneseMode(
                new int[] {0x7f170705}));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Runtime.japaneseMode(
                new int[] {0x7f1704ab}));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Runtime.japaneseMode(null));
    }

    @Test
    public void bodyHandlersGainTheGestureHandlerRightAfterSpatialModel() throws Exception {
        FakeInfo[] stock = {
                new FakeInfo("a.BasicMotionEventHandler", "pref", true),
                new FakeInfo(SPATIAL, "pref", false),
                new FakeInfo(".inlinesuggestion.InlineSuggestionScrubSpaceMotionEventHandler", null, false),
        };

        Object[] augmented =
                GboardJapaneseQwertyGlide1803Runtime.augmentedHandlerInfos(stock, "BODY");

        Assert.assertNotNull(augmented);
        Assert.assertEquals(FakeInfo.class, augmented.getClass().getComponentType());
        Assert.assertEquals(4, augmented.length);
        Assert.assertSame(stock[0], augmented[0]);
        Assert.assertSame(stock[1], augmented[1]);
        Assert.assertSame(stock[2], augmented[3]);
        FakeInfo gesture = (FakeInfo) augmented[2];
        Assert.assertEquals(GboardJapaneseQwertyGlide1803Runtime.GESTURE_HANDLER_CLASS, gesture.a);
        Assert.assertEquals("enable_gesture_input", gesture.b);
        Assert.assertFalse(gesture.c);
        Assert.assertEquals(3, stock.length);
    }

    @Test
    public void nonBodyViewsAndAlreadyGlidingHandlersAreLeftAlone() throws Exception {
        FakeInfo[] stock = {new FakeInfo(SPATIAL, null, false)};
        FakeInfo[] gliding = {
                new FakeInfo(".libs.latin5.handler.LatinGestureMotionEventHandler", "p", false),
        };

        Assert.assertNull(GboardJapaneseQwertyGlide1803Runtime.augmentedHandlerInfos(stock, "HEADER"));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Runtime.augmentedHandlerInfos(gliding, "BODY"));
    }

    @Test
    public void handlersWithoutSpatialModelGetTheGestureHandlerAppended() throws Exception {
        FakeInfo[] stock = {new FakeInfo("a.BasicMotionEventHandler", null, false)};

        Object[] augmented =
                GboardJapaneseQwertyGlide1803Runtime.augmentedHandlerInfos(stock, "BODY");

        Assert.assertNotNull(augmented);
        Assert.assertEquals(2, augmented.length);
        Assert.assertSame(stock[0], augmented[0]);
    }

    @Test
    public void kanaDoesNotGainAHandlerThroughThePublicAttachHook() {
        FakeInfo[] stock = {new FakeInfo(SPATIAL, null, false)};
        Assert.assertSame(stock, GboardJapaneseQwertyGlide1803Runtime.augmentMotionEventHandlers(
                new Object(), stock, new FakeDefinition(0x7f170705), new FakeViewDefinition(), null));
    }

    @Test
    public void abcTrailDoesNotRequireDecoderProxyLayoutOrDecodeCompletion() throws Exception {
        FakeManager manager = new FakeManager();
        FakeInfo[] stock = {new FakeInfo(SPATIAL, null, false)};
        GboardJapaneseQwertyGlide1803Runtime.augmentMotionEventHandlers(manager, stock,
                new FakeDefinition(0x7f170704), new FakeViewDefinition(), null);
        // The fake host has neither a Gboard decoder nor an observed layout.
        Assert.assertEquals(1, GboardJapaneseQwertyGlide1803Runtime.gestureReadyForManager(manager));
        GboardJapaneseQwertyGlide1803Runtime.onInputSessionChanged();
        Assert.assertEquals(1, GboardJapaneseQwertyGlide1803Runtime.gestureReadyForManager(manager));
        FakeManager kana = new FakeManager();
        GboardJapaneseQwertyGlide1803Runtime.augmentMotionEventHandlers(kana, stock,
                new FakeDefinition(0x7f170705), new FakeViewDefinition(), null);
        Assert.assertEquals(-1, GboardJapaneseQwertyGlide1803Runtime.gestureReadyForManager(kana));
        Assert.assertEquals(-1, GboardJapaneseQwertyGlide1803Runtime.gestureReadyForManager(new Object()));
    }

    @Test
    public void diagnosticsFailureDoesNotDisableTrackedAbcTrail() throws Exception {
        final FakeManager manager = new FakeManager();
        GboardJapaneseQwertyGlide1803Runtime.augmentMotionEventHandlers(manager,
                new FakeInfo[]{new FakeInfo(SPATIAL, null, false)},
                new FakeDefinition(0x7f170704), new FakeViewDefinition(), null);
        final Object context = Class.forName("ozi").getConstructor(Object.class).newInstance(manager);
        Object handler = new Object() {
            public final Object p = context;
        };
        // The handler deliberately lacks the fields used only by readiness diagnostics.
        Assert.assertEquals(1, GboardJapaneseQwertyGlide1803Runtime.overrideGestureReady(handler));
    }

    @Test
    public void resultUsesCommittedThenComposingTextAndThenTopCandidate() throws Exception {
        FakeDiff diff = new FakeDiff();
        diff.d.e = " world ";
        diff.d.h = "hello";
        diff.e.d.add(new FakeCandidate("other"));
        Assert.assertEquals("world", GboardJapaneseQwertyGlide1803Runtime.gestureWord(diff));
        diff.d.e = "";
        Assert.assertEquals("hello", GboardJapaneseQwertyGlide1803Runtime.gestureWord(diff));
        diff.d.h = "";
        Assert.assertEquals("other", GboardJapaneseQwertyGlide1803Runtime.gestureWord(diff));
    }

    @Test
    public void resultRejectsControlCharactersNonAlphabetAndArbitraryContext() {
        Assert.assertNull(GboardJapaneseQwertyGlide1803Runtime.alphabetWord("hello\nworld"));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Runtime.alphabetWord("日本語"));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Runtime.alphabetWord("two words"));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Runtime.alphabetWord(""));
        Assert.assertEquals("don't", GboardJapaneseQwertyGlide1803Runtime.alphabetWord("don't"));
    }

    @Test
    public void layoutRequiresAllAlphabetKeysAndPositiveFiniteDimensions() throws Exception {
        FakeLayout layout = new FakeLayout();
        for (int code = 'a'; code <= 'z'; code++) layout.g.add(new FakeKey(code));
        Assert.assertTrue(GboardJapaneseQwertyGlide1803Runtime.validAlphabetLayout(layout));
        layout.g.remove(0);
        Assert.assertFalse(GboardJapaneseQwertyGlide1803Runtime.validAlphabetLayout(layout));
        layout.g.add(new FakeKey('A'));
        layout.e = Float.NaN;
        Assert.assertFalse(GboardJapaneseQwertyGlide1803Runtime.validAlphabetLayout(layout));
        layout.e = 100;
        layout.f = 0;
        Assert.assertFalse(GboardJapaneseQwertyGlide1803Runtime.validAlphabetLayout(layout));
    }

    @Test
    public void pendingSnapshotRejectsEditorViewModeGenerationAndSessionChanges() throws Exception {
        FakeManager manager = new FakeManager();
        FakeInfo[] stock = {new FakeInfo(SPATIAL, null, false)};
        GboardJapaneseQwertyGlide1803Runtime.augmentMotionEventHandlers(manager, stock,
                new FakeDefinition(0x7f170704), new FakeViewDefinition(), null);
        Field bridgesField = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredField("BRIDGES");
        bridgesField.setAccessible(true);
        Object bridge = ((Map<?, ?>) bridgesField.get(null)).get(manager);
        Field layout = bridge.getClass().getDeclaredField("layout");
        layout.setAccessible(true);
        layout.set(bridge, new FakeLayout());
        Class<?> requestType = Class.forName(GboardJapaneseQwertyGlide1803Runtime.class.getName()
                + "$DecodeRequest");
        Constructor<?> constructor = requestType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object request = constructor.newInstance(new Object(), manager, bridge);
        Method current = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredMethod("current", requestType);
        current.setAccessible(true);
        Assert.assertEquals(true, current.invoke(null, request));
        ((View) manager.j).layout(0, 0, 200, 100);
        Assert.assertEquals(false, current.invoke(null, request));
        ((View) manager.j).layout(0, 0, 100, 100);
        Assert.assertEquals(true, current.invoke(null, request));
        Object editor = manager.e.E;
        manager.e.E = new Object();
        Assert.assertEquals(false, current.invoke(null, request));
        manager.e.E = editor;
        manager.e.C++;
        Assert.assertEquals(false, current.invoke(null, request));
        manager.e.C--;
        Object view = manager.j;
        manager.j = new Object();
        Assert.assertEquals(false, current.invoke(null, request));
        manager.j = view;
        manager.m = false;
        Assert.assertEquals(false, current.invoke(null, request));
        manager.m = true;
        Field generation = bridge.getClass().getDeclaredField("generation");
        generation.setAccessible(true);
        generation.setLong(bridge, generation.getLong(bridge) + 1);
        Assert.assertEquals(false, current.invoke(null, request));
        Method currentFinal = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredMethod("currentFinal", requestType);
        currentFinal.setAccessible(true);
        Assert.assertEquals(true, currentFinal.invoke(null, request));
        Field editVersion = bridge.getClass().getDeclaredField("editVersion");
        editVersion.setAccessible(true);
        editVersion.setLong(bridge, editVersion.getLong(bridge) + 1);
        Assert.assertEquals(false, currentFinal.invoke(null, request));
        editVersion.setLong(bridge, editVersion.getLong(bridge) - 1);
        generation.setLong(bridge, generation.getLong(bridge) - 1);
        ShadowSystemClock.advanceBy(Duration.ofMillis(10001));
        Assert.assertEquals(false, current.invoke(null, request));
        Assert.assertEquals(false, currentFinal.invoke(null, request));
        request = constructor.newInstance(new Object(), manager, bridge);
        Assert.assertEquals(true, current.invoke(null, request));
        GboardJapaneseQwertyGlide1803Runtime.onInputSessionChanged();
        Assert.assertEquals(false, current.invoke(null, request));
    }

    @Test
    public void sessionChangeCancelsQueuedDispatchAndLateFutureRegistration() throws Exception {
        FakeManager manager = new FakeManager();
        FakeInfo[] stock = {new FakeInfo(SPATIAL, null, false)};
        GboardJapaneseQwertyGlide1803Runtime.augmentMotionEventHandlers(manager, stock,
                new FakeDefinition(0x7f170704), new FakeViewDefinition(), null);
        Field bridgesField = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredField("BRIDGES");
        bridgesField.setAccessible(true);
        Object bridge = ((Map<?, ?>) bridgesField.get(null)).get(manager);
        Class<?> requestType = Class.forName(GboardJapaneseQwertyGlide1803Runtime.class.getName()
                + "$DecodeRequest");
        Constructor<?> constructor = requestType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object request = constructor.newInstance(new Object(), manager, bridge);
        Field pendingField = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredField("PENDING");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Long, Object> pending = (Map<Long, Object>) pendingField.get(null);
        Method retain = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredMethod(
                "retainFuture", requestType, long.class, java.util.concurrent.Future.class);
        retain.setAccessible(true);
        long id = 99112234L;
        final int[] executions = {0};
        Runnable task = new Runnable() {
            @Override
            public void run() { executions[0]++; }
        };
        FutureTask<Void> dispatch = new FutureTask<Void>(task, null);
        FutureTask<Void> lateDispatch = new FutureTask<Void>(task, null);
        pending.put(id, request);
        try {
            retain.invoke(null, request, id, dispatch);
            Assert.assertFalse(dispatch.isCancelled());
            GboardJapaneseQwertyGlide1803Runtime.onInputSessionChanged();
            Assert.assertTrue(dispatch.isCancelled());
            Assert.assertFalse(pending.containsKey(id));
            retain.invoke(null, request, id, lateDispatch);
            Assert.assertTrue(lateDispatch.isCancelled());
            dispatch.run();
            lateDispatch.run();
            Assert.assertEquals(0, executions[0]);
        } finally {
            pending.remove(id);
        }
    }

    public static final class FakeManager {
        public FakeKeyboard e = new FakeKeyboard();
        public Object j = new View(RuntimeEnvironment.getApplication());
        public boolean m = true;

        FakeManager() {
            ((View) j).layout(0, 0, 100, 100);
        }
    }

    @Test
    public void readinessDiagnosticsSurviveMissingGboardClassesAndReportIndependentConditions() throws Exception {
        FakeManager manager = new FakeManager();
        FakeInfo[] stock = {new FakeInfo(SPATIAL, null, false)};
        GboardJapaneseQwertyGlide1803Runtime.augmentMotionEventHandlers(manager, stock,
                new FakeDefinition(0x7f170704), new FakeViewDefinition(), null);
        Method diagnostics = GboardJapaneseQwertyGlide1803Runtime.class.getDeclaredMethod(
                "readinessDiagnostics", Object.class);
        diagnostics.setAccessible(true);
        String details = (String) diagnostics.invoke(null, new FakeHandler(manager));
        Assert.assertTrue(details.contains("trackedBridge=true"));
        Assert.assertTrue(details.contains("layoutPresent=false"));
        Assert.assertTrue(details.contains("view=100x100"));
        Assert.assertTrue(details.contains("layout=null"));
        Assert.assertTrue(details.contains("facilitatorProbe=probe-failed("));
        Assert.assertTrue(details.contains("primaryLocale=null"));
        Assert.assertTrue(details.contains("proxyPresent=false"));
        Assert.assertTrue(details.contains("pendingDecodes="));
    }

    public static final class FakeHandler {
        public FakeContext p;
        FakeHandler(FakeManager manager) { p = new FakeContext(manager); }
    }

    public static final class FakeContext {
        public FakeManager b;
        FakeContext(FakeManager manager) { b = manager; }
    }

    public static final class FakeKeyboard {
        public boolean D = true;
        public Object E = new Object();
        public long C;
    }

    public static final class FakeDefinition {
        public int[] b;
        public String k = "QWERTY";
        public String c = "Japanese";
        FakeDefinition(int id) { b = new int[] {id}; }
    }

    public static final class FakeViewDefinition {
        public String b = "BODY";
    }

    public static final class FakeDiff {
        public FakeText d = new FakeText();
        public FakeSuggestions e = new FakeSuggestions();
    }

    public static final class FakeText {
        public String e = "";
        public String h = "";
    }

    public static final class FakeSuggestions {
        public List<FakeCandidate> d = new ArrayList<FakeCandidate>();
    }

    public static final class FakeCandidate {
        public String e;
        FakeCandidate(String word) { e = word; }
    }

    public static final class FakeLayout {
        public float e = 100;
        public float f = 100;
        public List<FakeKey> g = new ArrayList<FakeKey>();
    }

    public static final class FakeKey {
        public int d;
        FakeKey(int code) { d = code; }
    }

    public static final class FakeInfo {
        public final String a;
        public final String b;
        public final boolean c;

        public FakeInfo(String a, String b, boolean c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }
}
