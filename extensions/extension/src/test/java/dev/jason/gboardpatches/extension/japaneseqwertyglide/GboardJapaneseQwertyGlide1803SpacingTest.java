package dev.jason.gboardpatches.extension.japaneseqwertyglide;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class GboardJapaneseQwertyGlide1803SpacingTest {
    @After
    public void cleanSession() {
        GboardJapaneseQwertyGlide1803Spacing.reset();
    }

    @Test
    public void selectedAlternativeCommitsOnceWithoutChangingCandidatesOrOriginalProto() {
        Fixture fixture = new Fixture();
        fixture.deliver("hello");
        Command command = new Command("help");
        Command spaced = (Command) GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, command, fixture.editor);
        Assert.assertNotSame(command, spaced);
        Assert.assertNotSame(command.e, spaced.e);
        Assert.assertEquals("help ", spaced.e.c);
        Assert.assertEquals("help", command.e.c);
        Assert.assertSame(command.f, spaced.f);
        Assert.assertSame(command, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, command, fixture.editor));
        Command normal = new Command("typed");
        Assert.assertSame(normal, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, normal, fixture.editor));
    }

    @Test
    public void queuedGlidesConsumeDistinctTokensAndDuplicateCallbacksDoNotStealNextToken() {
        Fixture fixture = new Fixture();
        fixture.deliver("first");
        fixture.deliver("second");
        Command first = new Command("first");
        Assert.assertEquals("first ", ((Command) GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, first, fixture.editor)).e.c);
        Assert.assertSame(first, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, first, fixture.editor));
        Command second = new Command("second  ");
        Assert.assertEquals("second ", ((Command) GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, second, fixture.editor)).e.c);
    }

    @Test
    public void emptyComposingOutputDoesNotConsumePendingGlide() {
        Fixture fixture = new Fixture();
        fixture.deliver("hello");
        Command preview = new Command("");
        Assert.assertSame(preview, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, preview, fixture.editor));
        Command commit = new Command("hello");
        Assert.assertEquals("hello ", ((Command) GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, commit, fixture.editor)).e.c);
    }

    @Test
    public void editorSwitchRendererMismatchCancelAndResetLeaveStockOutput() {
        Fixture fixture = new Fixture();
        fixture.deliver("hello");
        Command commit = new Command("hello");
        fixture.ime.G = new Object();
        Assert.assertSame(commit, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, commit, fixture.editor));
        fixture.ime.G = fixture.editor;
        Assert.assertSame(commit, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                new Renderer(fixture.ime.D), commit, fixture.editor));
        GboardJapaneseQwertyGlide1803Spacing.cancel(fixture.manager);
        Assert.assertSame(commit, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, commit, fixture.editor));
        fixture.deliver("hello");
        GboardJapaneseQwertyGlide1803Spacing.reset();
        Assert.assertSame(commit, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, commit, fixture.editor));
    }

    @Test
    public void unsupportedCursorRewriteDisarmsGlideAndKeepsNativeResult() {
        Fixture fixture = new Fixture();
        fixture.deliver("hello");
        Command replacement = new Command("hello");
        replacement.e.e = -1;
        Assert.assertSame(replacement, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, replacement, fixture.editor));
        Command later = new Command("later");
        Assert.assertSame(later, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, later, fixture.editor));
    }

    @Test
    public void separatorAcceptsAlphabetApostrophesHyphensAndRejectsOtherEdits() {
        Assert.assertEquals("don't ", GboardJapaneseQwertyGlide1803Spacing.normalizedWord("don't"));
        Assert.assertEquals("well-known ", GboardJapaneseQwertyGlide1803Spacing.normalizedWord("well-known  "));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Spacing.normalizedWord("two words"));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Spacing.normalizedWord("word\n"));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Spacing.normalizedWord("日本語"));
        Assert.assertNull(GboardJapaneseQwertyGlide1803Spacing.normalizedWord(""));
    }

    @Test
    public void unrelatedPlainTextOutsideDeliveryIsNotTracked() {
        Fixture fixture = new Fixture();
        GboardJapaneseQwertyGlide1803Spacing.observeIme(fixture.ime, new Event("typed"));
        Command command = new Command("typed");
        Assert.assertSame(command, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, command, fixture.editor));
    }

    @Test
    public void fastFinalDeliveriesRetainIndependentSelectionTokensInOrder() {
        Fixture fixture = new Fixture();
        fixture.deliver("one");
        fixture.deliver("two");
        fixture.deliver("three");
        for (String selected : new String[] {"One", "too", "three"}) {
            Command result = new Command(selected);
            Assert.assertEquals(selected + " ", ((Command) GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                    fixture.ime.d.c, result, fixture.editor)).e.c);
        }
        Command ordinary = new Command("four");
        Assert.assertSame(ordinary, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, ordinary, fixture.editor));
    }

    @Test
    public void inactiveManagerKeyboardOrChangedConnectionCannotAddSpace() {
        Fixture fixture = new Fixture();
        fixture.deliver("hello");
        Command command = new Command("hello");
        fixture.manager.m = false;
        Assert.assertSame(command, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, command, fixture.editor));
        fixture.manager.m = true;
        fixture.keyboard.D = false;
        Assert.assertSame(command, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, command, fixture.editor));
        fixture.keyboard.D = true;
        fixture.ime.d.c.h = new Object();
        Assert.assertSame(command, GboardJapaneseQwertyGlide1803Spacing.appendCommit(
                fixture.ime.d.c, command, fixture.editor));
    }

    private static final class Fixture {
        final Object editor = new Object();
        final Keyboard keyboard = new Keyboard(editor);
        final Manager manager = new Manager();
        final Ime ime = new Ime(editor);

        void deliver(String word) {
            GboardJapaneseQwertyGlide1803Spacing.beginDelivery(manager, keyboard, editor, 1);
            try { GboardJapaneseQwertyGlide1803Spacing.observeIme(ime, new Event(word)); }
            finally { GboardJapaneseQwertyGlide1803Spacing.endDelivery(); }
        }
    }

    public static final class Keyboard {
        public Object E;
        public boolean D = true;
        Keyboard(Object editor) { E = editor; }
    }

    public static final class Manager {
        public Object j = new Object();
        public boolean m = true;
    }

    public static final class Ime {
        public Object b = new Object();
        public Object g = new Object();
        public Object G;
        public Object D = new Object();
        public State d;
        Ime(Object editor) { G = editor; d = new State(new Renderer(D)); }
    }

    public static final class State {
        public Renderer c;
        State(Renderer renderer) { c = renderer; }
    }

    public static final class Renderer {
        public Object h;
        Renderer(Object connection) { h = connection; }
    }

    public static final class Event {
        public Action[] b;
        Event(String word) { b = new Action[] {new Action(word)}; }
    }

    public static final class Action {
        public int c = -10009;
        public Object e;
        Action(String word) { e = word; }
    }

    public static final class Command {
        public int b = 8;
        public Result e;
        public Object f = new Object();
        Command(String text) { e = new Result(text); }
        public CommandBuilder G() {
            Command copy = new Command(e.c);
            copy.e = e;
            copy.f = f;
            copy.b = b;
            return new CommandBuilder(copy);
        }
    }

    public static final class Result {
        public String c;
        public int e;
        Result(String text) { c = text; }
        public ResultBuilder G() {
            Result copy = new Result(c);
            copy.e = e;
            return new ResultBuilder(copy);
        }
    }

    public static final class CommandBuilder {
        public Command b;
        CommandBuilder(Command command) { b = command; }
        public void t() { }
        public Command q() { return b; }
    }

    public static final class ResultBuilder {
        public Result b;
        ResultBuilder(Result result) { b = result; }
        public void t() { }
        public Result q() { return b; }
    }
}
