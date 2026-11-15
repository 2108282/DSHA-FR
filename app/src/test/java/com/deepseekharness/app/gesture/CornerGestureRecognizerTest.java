package com.deepseekharness.app.gesture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CornerGestureRecognizerTest {
    private static final float EPSILON = 0.000001f;

    @Test public void startsIdleAndMovesCannotStartGesture() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        assertIdle(recognizer);
        assertEquals(0f, recognizer.move(0f, 199f), 0f);
        assertIdle(recognizer);
    }

    @Test public void acceptsExactInnerHotZoneEdgesAndRejectsAdjacentFloats() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        assertTrue(recognizer.begin(48f, 175f, 300f, 200f));
        assertTrue(recognizer.isLeft());
        assertFalse(recognizer.begin(Math.nextUp(48f), 175f, 300f, 200f));
        assertFalse(recognizer.begin(48f, Math.nextDown(175f), 300f, 200f));
        assertTrue(recognizer.begin(252f, 175f, 300f, 200f));
        assertFalse(recognizer.isLeft());
        assertFalse(recognizer.begin(Math.nextDown(252f), 175f, 300f, 200f));
        assertTrue(recognizer.begin(0f, Math.nextDown(200f), 300f, 200f));
        assertTrue(recognizer.begin(Math.nextDown(300f), 199f, 300f, 200f));
    }

    @Test public void appliesDensityToBothHotZoneDimensionsAndThreshold() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(2f);
        assertTrue(recognizer.begin(96f, 350f, 600f, 400f));
        assertEquals(0.5f, recognizer.move(151f, 350f), EPSILON);
        assertFalse(recognizer.isTriggered());
        assertEquals(1f, recognizer.move(206f, 350f), 0f);
        assertTrue(recognizer.isTriggered());
        assertFalse(recognizer.begin(Math.nextUp(96f), 350f, 600f, 400f));
        assertFalse(recognizer.begin(96f, Math.nextDown(350f), 600f, 400f));
        assertTrue(recognizer.begin(504f, 350f, 600f, 400f));
        assertFalse(recognizer.isLeft());
    }

    @Test public void supportsFractionalDensityAndCustomBottomHeight() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1.5f, 20.5f);
        assertTrue(recognizer.begin(72f, 269.25f, 450f, 300f));
        assertFalse(recognizer.begin(72f, Math.nextDown(269.25f), 450f, 300f));
        assertTrue(recognizer.begin(72f, 269.25f, 450f, 300f));
        assertEquals(1f, recognizer.move(154.5f, 269.25f), 0f);
        assertTrue(recognizer.isTriggered());
        CornerGestureRecognizer defaults = new CornerGestureRecognizer(1.5f);
        assertTrue(defaults.begin(72f, 262.5f, 450f, 300f));
    }

    @Test public void acceptsZeroCoordinatesWithoutUsingThemAsSentinels() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        assertTrue(recognizer.begin(0f, 0f, 200f, 20f));
        assertTrue(recognizer.isTracking());
        assertTrue(recognizer.isLeft());
        assertEquals(0f, recognizer.move(0f, 0f), 0f);
        assertEquals(1f, recognizer.move(55f, 0f), 0f);
        assertTrue(recognizer.isTriggered());
    }

    @Test public void doesNotTrackDownOutsideHotZoneEvenWhenMoveEntersIt() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        assertFalse(recognizer.begin(150f, 199f, 300f, 200f));
        assertEquals(0f, recognizer.move(0f, 199f), 0f);
        assertIdle(recognizer);
        assertFalse(recognizer.begin(0f, 100f, 300f, 200f));
        assertEquals(0f, recognizer.move(0f, 199f), 0f);
        assertIdle(recognizer);
    }

    @Test public void exact55DpTriggersOnMoveAndNotBefore() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        assertTrue(recognizer.begin(0f, 199f, 300f, 200f));
        recognizer.move(Math.nextDown(55f), 199f);
        assertFalse(recognizer.isTriggered());
        assertEquals(1f, recognizer.move(55f, 199f), 0f);
        assertTrue(recognizer.isTriggered());
        assertTrue(recognizer.isTracking());
    }

    @Test public void usesHypotenuseAndAllowsAnyDirection() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        assertTrue(recognizer.begin(0f, 199f, 300f, 200f));
        assertEquals(1f, recognizer.move(33f, 155f), 0f);
        assertTrue(recognizer.isTriggered()); // hypot(33, 44) = 55
        assertTrue(recognizer.begin(299f, 175f, 300f, 200f));
        assertEquals(1f, recognizer.move(244f, 175f), 0f); // horizontal
        assertTrue(recognizer.begin(48f, 175f, 300f, 200f));
        assertEquals(1f, recognizer.move(48f, 120f), 0f); // vertical
        assertTrue(recognizer.begin(0f, 0f, 300f, 20f));
        assertEquals(1f, recognizer.move(55f, 0f), 0f); // no inward-angle restriction
    }

    @Test public void movementBacktracksWithoutAccumulatingTravelDistance() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        recognizer.begin(0f, 199f, 300f, 200f);
        assertEquals(40f / 55f, recognizer.move(40f, 199f), EPSILON);
        assertEquals(10f / 55f, recognizer.move(10f, 199f), EPSILON);
        assertEquals(40f / 55f, recognizer.move(40f, 199f), EPSILON);
        assertEquals(0f, recognizer.move(0f, 199f), 0f);
        assertFalse(recognizer.isTriggered());
    }

    @Test public void latchedTriggerHasExactlyOneRisingEdgeDespiteBacktracking() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        recognizer.begin(0f, 199f, 300f, 200f);
        int triggerCount = 0;
        float[][] moves = {{40f, 199f}, {55f, 199f}, {0f, 199f}, {100f, 100f}, {55f, 199f}};
        for (float[] move : moves) {
            boolean previouslyTriggered = recognizer.isTriggered();
            float progress = recognizer.move(move[0], move[1]);
            if (!previouslyTriggered && recognizer.isTriggered()) triggerCount++;
            if (recognizer.isTriggered()) assertEquals(1f, progress, 0f);
        }
        assertEquals(1, triggerCount);
        assertTrue(recognizer.isTracking());
        assertTrue(recognizer.isLeft());
    }

    @Test public void endClearsStateAndNewGestureCanTriggerOnOppositeSide() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        recognizer.begin(0f, 199f, 300f, 200f);
        recognizer.move(55f, 199f);
        recognizer.end();
        assertIdle(recognizer);
        assertEquals(0f, recognizer.move(100f, 199f), 0f);
        assertTrue(recognizer.begin(299f, 199f, 300f, 200f));
        assertFalse(recognizer.isLeft());
        assertFalse(recognizer.isTriggered());
        assertEquals(1f, recognizer.move(244f, 199f), 0f);
        assertTrue(recognizer.isTriggered());
    }

    @Test public void endDoesNotTriggerSubThresholdGesture() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        recognizer.begin(0f, 199f, 300f, 200f);
        recognizer.move(54f, 199f);
        recognizer.end();
        assertIdle(recognizer);
    }

    @Test public void cancelAndResetDiscardLatchedAndUntriggeredGestures() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        recognizer.begin(0f, 199f, 300f, 200f);
        recognizer.move(20f, 199f);
        recognizer.cancel(); // caller uses this for an additional pointer
        assertIdle(recognizer);
        assertEquals(0f, recognizer.move(100f, 199f), 0f);
        recognizer.begin(0f, 199f, 300f, 200f);
        recognizer.move(55f, 199f);
        recognizer.cancel();
        assertIdle(recognizer);
        recognizer.begin(0f, 199f, 300f, 200f);
        recognizer.move(55f, 199f);
        recognizer.reset();
        assertIdle(recognizer);
        recognizer.cancel();
        recognizer.end();
        recognizer.reset();
        assertIdle(recognizer);
    }

    @Test public void everyBeginReplacesPreviousGestureIncludingFailedBegins() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        recognizer.begin(0f, 199f, 300f, 200f);
        recognizer.move(55f, 199f);
        assertTrue(recognizer.begin(299f, 199f, 300f, 200f));
        assertFalse(recognizer.isTriggered());
        assertFalse(recognizer.isLeft());
        assertEquals(1f / 55f, recognizer.move(298f, 199f), EPSILON);
        assertFalse(recognizer.begin(150f, 199f, 300f, 200f));
        assertIdle(recognizer);
    }

    @Test public void rejectsOffScreenAndNonFiniteDownCoordinates() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        float[][] invalid = {
                {-1f, 199f}, {300f, 199f}, {0f, -1f}, {0f, 200f},
                {Float.NaN, 199f}, {0f, Float.NaN},
                {Float.POSITIVE_INFINITY, 199f}, {0f, Float.NEGATIVE_INFINITY}
        };
        for (float[] point : invalid) {
            assertFalse(recognizer.begin(point[0], point[1], 300f, 200f));
            assertIdle(recognizer);
        }
    }

    @Test public void invalidMoveCancelsAndLaterValidMoveCannotResume() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        float[][] invalid = {
                {-0.01f, 199f}, {300f, 199f}, {0f, -0.01f}, {0f, 200f},
                {Float.NaN, 199f}, {0f, Float.NaN},
                {Float.POSITIVE_INFINITY, 199f}, {0f, Float.NEGATIVE_INFINITY}
        };
        for (float[] point : invalid) {
            recognizer.begin(0f, 199f, 300f, 200f);
            assertEquals(0f, recognizer.move(point[0], point[1]), 0f);
            assertIdle(recognizer);
            assertEquals(0f, recognizer.move(55f, 199f), 0f);
            assertIdle(recognizer);
        }
        recognizer.begin(0f, 199f, 300f, 200f);
        recognizer.move(55f, 199f);
        assertEquals(0f, recognizer.move(-1f, 199f), 0f);
        assertIdle(recognizer);
    }

    @Test public void rejectsInvalidScreenDimensions() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        float[] invalid = {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float dimension : invalid) {
            assertFalse(recognizer.begin(0f, 0f, dimension, 200f));
            assertFalse(recognizer.begin(0f, 0f, 300f, dimension));
            assertIdle(recognizer);
        }
    }

    @Test public void overlappingCornerZonesSelectNearestEdgeAndMidpointSelectsLeft() {
        CornerGestureRecognizer recognizer = new CornerGestureRecognizer(1f);
        assertTrue(recognizer.begin(25f, 199f, 60f, 200f));
        assertTrue(recognizer.isLeft());
        assertTrue(recognizer.begin(30f, 199f, 60f, 200f));
        assertTrue(recognizer.isLeft());
        assertTrue(recognizer.begin(35f, 199f, 60f, 200f));
        assertFalse(recognizer.isLeft());
    }

    @Test public void rejectsInvalidConfigurationIncludingPixelOverflow() {
        float[] invalid = {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> new CornerGestureRecognizer(value));
            assertThrows(IllegalArgumentException.class, () -> new CornerGestureRecognizer(1f, value));
        }
        assertThrows(IllegalArgumentException.class, () -> new CornerGestureRecognizer(Float.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> new CornerGestureRecognizer(2f, Float.MAX_VALUE));
    }

    private static void assertIdle(CornerGestureRecognizer recognizer) {
        assertFalse(recognizer.isTracking());
        assertFalse(recognizer.isTriggered());
        assertFalse(recognizer.isLeft());
    }
}
