package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunCompletion;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunOutcome;

class RunMotionTest {

    @Test
    void idleAndStoppedStatesStayStillWithoutSchedulingAnimationTicks() {
        AtomicLong now = new AtomicLong();
        RunMotion motion = new RunMotion(now::get, false);
        assertEquals(RunMotionState.IDLE, motion.state());
        assertEquals(">", motion.glyph(true));
        assertFalse(motion.animating());

        motion.start();
        motion.completed(new RunCompletion(RunOutcome.CANCELLED, Optional.empty()));
        String stopped = motion.glyph(true);
        now.addAndGet(10_000_000_000L);
        assertEquals(RunMotionState.STOPPED, motion.state());
        assertEquals("#", stopped);
        assertEquals(stopped, motion.glyph(true));
        assertFalse(motion.animating());
    }

    @Test
    void derivesSpinnerAndElapsedTimeFromTheInjectedClock() {
        AtomicLong now = new AtomicLong();
        RunMotion motion = new RunMotion(now::get, false);
        motion.start();
        assertEquals(Duration.ofMillis(80), motion.tickRate());
        String firstFrame = motion.glyph(false);
        now.set(80_000_000);
        assertNotEquals(firstFrame, motion.glyph(false));
        motion.running();
        assertEquals(Duration.ofMillis(120), motion.tickRate());
        String runningFrame = motion.glyph(false);
        now.addAndGet(120_000_000);
        assertNotEquals(runningFrame, motion.glyph(false));
        now.addAndGet(42_000_000_000L);
        assertEquals(42_200, motion.elapsedMillis());
    }

    @Test
    void samplesOutputLinesOncePerSecondIntoTwelveBucketsAndFreezesWhenStopping() {
        AtomicLong now = new AtomicLong();
        RunMotion motion = new RunMotion(now::get, false);
        motion.start();
        motion.running();
        motion.outputLine();
        motion.outputLine();
        assertArrayEquals(new int[12], motion.outputSamples());
        now.set(1_000_000_000L);
        motion.tick();
        assertEquals(2, motion.outputSamples()[11]);
        assertEquals(12, motion.outputSamples().length);
        String pulse = motion.outputPulse(true);
        assertEquals(12, pulse.length());

        motion.outputLine();
        String stoppingPulse = motion.outputPulse(true);
        motion.stopping();
        now.addAndGet(1_000_000_000L);
        motion.tick();
        assertEquals(2, motion.outputSamples()[11]);
        assertEquals(stoppingPulse, motion.outputPulse(true), "stopping freezes the last output pulse");
    }

    @Test
    void reducedMotionKeepsActiveFrameStaticAndOnlyReportsElapsedTime() {
        AtomicLong now = new AtomicLong();
        RunMotion motion = new RunMotion(now::get, true);
        motion.start();
        String frame = motion.glyph(false);
        now.set(9_000_000_000L);
        assertEquals(frame, motion.glyph(false));
        assertEquals(9_000, motion.elapsedMillis());
        assertEquals("", motion.outputPulse(false));
        assertEquals(Duration.ofSeconds(1), motion.tickRate());
        assertTrue(motion.reduced());
    }

    @Test
    void failedAndSuccessfulEffectsHaveTheirSpecifiedDurations() {
        AtomicLong now = new AtomicLong();
        RunMotion motion = new RunMotion(now::get, false);
        motion.start();
        motion.completed(new RunCompletion(RunOutcome.EXITED, Optional.of(0)));
        assertEquals(0.0, motion.effectProgress());
        now.addAndGet(300_000_000L);
        assertEquals(0.5, motion.effectProgress());
        assertTrue(motion.animating());
        now.addAndGet(300_000_000L);
        assertFalse(motion.animating());

        motion.start();
        motion.completed(new RunCompletion(RunOutcome.EXITED, Optional.of(1)));
        now.addAndGet(399_999_999L);
        assertTrue(motion.animating());
        now.addAndGet(1);
        assertFalse(motion.animating());
    }

    @Test
    void lifecycleGlyphsAndOutputPulseRespectTheAsciiFace() {
        AtomicLong now = new AtomicLong();
        RunMotion motion = new RunMotion(now::get, false);
        assertEquals(">", motion.glyph(true));
        motion.start();
        assertEquals("|", motion.glyph(true));
        motion.outputLine();
        now.set(1_000_000_000L);
        motion.tick();
        String pulse = motion.outputPulse(true);
        assertEquals(12, pulse.length());
        assertTrue(pulse.chars().allMatch(character -> "_.-^".indexOf(character) >= 0), pulse);
    }
}
