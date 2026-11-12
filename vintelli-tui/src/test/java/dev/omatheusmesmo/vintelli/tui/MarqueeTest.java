package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class MarqueeTest {

    private static final long MS = 1_000_000L;
    private static final int OVERFLOW = 10;

    @Test
    void waitsOneSecondBeforeTheFirstStep() {
        assertEquals(0, Marquee.offsetAt(999 * MS, OVERFLOW));
        assertEquals(0, Marquee.offsetAt(1000 * MS, OVERFLOW));
        assertEquals(1, Marquee.offsetAt(1150 * MS, OVERFLOW));
    }

    @Test
    void movesOneCellPerStepUntilTheEnd() {
        assertEquals(2, Marquee.offsetAt(1300 * MS, OVERFLOW));
        assertEquals(2, Marquee.offsetAt(1449 * MS, OVERFLOW));
        assertEquals(3, Marquee.offsetAt(1450 * MS, OVERFLOW));
    }

    @Test
    void pausesAtTheEndThenMovesBackOneCellPerStep() {
        assertEquals(OVERFLOW, Marquee.offsetAt(2500 * MS, OVERFLOW));
        assertEquals(OVERFLOW, Marquee.offsetAt(3499 * MS, OVERFLOW));
        assertEquals(9, Marquee.offsetAt(3650 * MS, OVERFLOW));
        assertEquals(1, Marquee.offsetAt(4850 * MS, OVERFLOW));
    }

    @Test
    void returnsToTheStartAndPausesAgainBeforeTheNextRun() {
        assertEquals(1, Marquee.offsetAt(4999 * MS, OVERFLOW));
        assertEquals(0, Marquee.offsetAt(5000 * MS, OVERFLOW));
        assertEquals(0, Marquee.offsetAt(5999 * MS, OVERFLOW));
        assertEquals(1, Marquee.offsetAt(6150 * MS, OVERFLOW));
    }

    @Test
    void aNameThatFitsNeverMoves() {
        AtomicLong now = new AtomicLong();
        Marquee marquee = new Marquee(now::get);
        marquee.enable(true);
        marquee.fit(0);
        marquee.follow("README.md");

        now.set(60_000 * MS);
        marquee.tick();

        assertFalse(marquee.moving());
        assertEquals(0, marquee.offset());
        assertEquals(0, Marquee.offsetAt(60_000 * MS, 0));
    }

    @Test
    void aDisabledMarqueeNeverMovesEvenWhenTheNameOverflows() {
        AtomicLong now = new AtomicLong();
        Marquee marquee = new Marquee(now::get);
        marquee.enable(false);
        marquee.fit(OVERFLOW);
        marquee.follow("AVeryLongName.java");

        now.set(10_000 * MS);
        marquee.tick();

        assertFalse(marquee.enabled());
        assertFalse(marquee.moving());
        assertEquals(0, marquee.offset());
    }

    @Test
    void turningTheMarqueeOffStopsAMovingName() {
        AtomicLong now = new AtomicLong();
        Marquee marquee = new Marquee(now::get);
        marquee.enable(true);
        marquee.fit(OVERFLOW);
        marquee.follow("AVeryLongName.java");
        now.set(2500 * MS);
        marquee.tick();
        assertTrue(marquee.moving());

        marquee.enable(false);
        marquee.tick();

        assertFalse(marquee.moving());
        assertEquals(0, marquee.offset());
    }

    @Test
    void aSelectionChangeRestartsTheTimeline() {
        AtomicLong now = new AtomicLong();
        Marquee marquee = new Marquee(now::get);
        marquee.enable(true);
        marquee.fit(OVERFLOW);
        marquee.follow("first.java");
        now.set(2500 * MS);
        marquee.tick();
        assertEquals(OVERFLOW, marquee.offset());

        marquee.follow("second.java");
        marquee.tick();
        assertEquals(0, marquee.offset(), "the new selection starts at the start of its name");

        now.set(2500 * MS + 1150 * MS);
        marquee.tick();
        assertEquals(1, marquee.offset());
    }

    @Test
    void followingTheSameSelectionKeepsTheTimeline() {
        AtomicLong now = new AtomicLong();
        Marquee marquee = new Marquee(now::get);
        marquee.enable(true);
        marquee.fit(OVERFLOW);
        marquee.follow("same.java");
        now.set(1150 * MS);
        marquee.tick();

        marquee.follow("same.java");
        marquee.tick();

        assertEquals(1, marquee.offset());
    }

    @Test
    void aShrinkingOverflowKeepsTheOffsetInRange() {
        AtomicLong now = new AtomicLong();
        Marquee marquee = new Marquee(now::get);
        marquee.enable(true);
        marquee.fit(OVERFLOW);
        marquee.follow("name.java");
        now.set(2500 * MS);
        marquee.tick();

        marquee.fit(4);
        marquee.tick();

        assertEquals(4, marquee.offset());
    }

    @Test
    void theListenerRunsOnlyWhenTheOverflowChanges() {
        Marquee marquee = new Marquee(() -> 0L);
        AtomicInteger changes = new AtomicInteger();
        marquee.enable(true);
        marquee.onChange(changes::incrementAndGet);

        marquee.fit(5);
        marquee.fit(5);
        marquee.fit(0);

        assertEquals(2, changes.get());
    }

    @Test
    void theWindowCutsTheTextByDisplayColumns() {
        assertEquals("cde", Marquee.window("abcdef", 2, 3));
        assertEquals("abc", Marquee.window("abc", 0, 10));
        assertEquals("", Marquee.window("abc", 1, 0));
        assertEquals(" 本", Marquee.window("日本語", 1, 4), "a wide glyph cut at the start leaves a blank cell");
    }
}
