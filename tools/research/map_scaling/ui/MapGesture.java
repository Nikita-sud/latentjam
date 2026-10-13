/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Standalone shell-only input replay for reproducible map profiling. No app storage, permissions, or settings are modified. */
public final class MapGesture {
    private static final int WAIT_FOR_FINISH = 2;
    private static final long FRAME_MS = 16;
    private final Object inputManager;
    private final Method inject;
    private final Method setDisplayId;
    private final float centerX, centerY, initialSpan, maxSpan;
    private final int cycles;
    private final long phaseMs;
    private final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
    private long downTime;
    private int activePointers;
    private int attempts, accepted;
    private long lateFrames, maxLateMs;

    private MapGesture(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            throw new IllegalArgumentException("Usage: MapGesture centerX centerY initialSpan maxSpan cycles [phaseMs=1000]; spans are full finger separation in px; pan is +100 px X then return");
        }
        centerX = finite(args[0]);
        centerY = finite(args[1]);
        initialSpan = finite(args[2]);
        maxSpan = finite(args[3]);
        cycles = Integer.parseInt(args[4]);
        phaseMs = args.length == 6 ? Long.parseLong(args[5]) : 1000L;
        if (initialSpan < 20 || maxSpan <= initialSpan || cycles < 1 || cycles > 20 || phaseMs < 200 || phaseMs > 10000) {
            throw new IllegalArgumentException("Require initialSpan>=20, maxSpan>initialSpan, cycles=1..20, phaseMs=200..10000");
        }
        Object manager = null;
        Method method = null;
        Exception last = null;
        for (String name : new String[] {"android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager"}) {
            try {
                Class<?> type = Class.forName(name);
                Method get = type.getDeclaredMethod("getInstance");
                get.setAccessible(true);
                manager = get.invoke(null);
                method = type.getMethod("injectInputEvent", InputEvent.class, int.class);
                method.setAccessible(true);
                log("injector=" + name);
                break;
            } catch (Exception failure) {
                last = failure;
            }
        }
        if (manager == null || method == null) throw new IllegalStateException("Input injector unavailable", last);
        inputManager = manager;
        inject = method;
        Method display = null;
        try {
            display = InputEvent.class.getMethod("setDisplayId", int.class);
            display.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            log("display_id_method_unavailable=true; using MotionEvent default display");
        }
        setDisplayId = display;
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].pressure = 1f;
            coords[i].size = 0.1f;
        }
    }

    private static float finite(String text) {
        float value = Float.parseFloat(text);
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("Non-finite coordinate");
        return value;
    }

    private static void log(String message) {
        System.out.println("wall_ms=" + System.currentTimeMillis() + " uptime_ms=" + SystemClock.uptimeMillis() + " " + message);
        System.out.flush();
    }

    private void position(float span, float pan) {
        coords[0].x = centerX + pan - span / 2f;
        coords[1].x = centerX + pan + span / 2f;
        coords[0].y = coords[1].y = centerY;
    }

    private void send(int action, int count) throws Exception {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                count, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        attempts++;
        try {
            if (setDisplayId != null) setDisplayId.invoke(event, 0);
            Object result;
            try {
                result = inject.invoke(inputManager, event, WAIT_FOR_FINISH);
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException("Injection threw at action=" + action + " count=" + count, failure.getCause());
            }
            if (!Boolean.TRUE.equals(result)) {
                throw new IllegalStateException("Injection returned false at action=" + action + " count=" + count + " attempt=" + attempts);
            }
            accepted++;
        } finally {
            event.recycle();
        }
    }

    private void phase(String name, float fromSpan, float toSpan, float fromPan, float toPan) throws Exception {
        long start = SystemClock.uptimeMillis();
        int steps = Math.max(1, (int) ((phaseMs + FRAME_MS - 1) / FRAME_MS));
        log("phase=" + name + " begin=true steps=" + steps + " events=" + accepted);
        for (int i = 1; i <= steps; i++) {
            long due = start + phaseMs * i / steps;
            long sleep = due - SystemClock.uptimeMillis();
            if (sleep > 0) SystemClock.sleep(sleep);
            long late = Math.max(0, SystemClock.uptimeMillis() - due);
            if (late > FRAME_MS) lateFrames++;
            maxLateMs = Math.max(maxLateMs, late);
            float fraction = (float) i / steps;
            position(fromSpan + fraction * (toSpan - fromSpan), fromPan + fraction * (toPan - fromPan));
            send(MotionEvent.ACTION_MOVE, 2);
        }
        log("phase=" + name + " end=true elapsed_ms=" + (SystemClock.uptimeMillis() - start) + " events=" + accepted);
    }

    private void releaseAfterFailure() {
        if (activePointers == 0) return;
        // CANCEL releases the complete gesture, including both pointers. A failed injector can
        // prevent even CANCEL delivery; report that truthfully instead of hiding the failure.
        try {
            send(MotionEvent.ACTION_CANCEL, activePointers);
            activePointers = 0;
            log("cleanup_cancel_accepted=true events=" + accepted);
        } catch (Exception failure) {
            log("cleanup_cancel_accepted=false error=" + failure);
            // Best-effort UP fallback; each returned boolean is still checked by send().
            if (activePointers == 2) {
                try {
                    send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
                    activePointers = 1;
                } catch (Exception secondary) {
                    log("cleanup_pointer_up_failed=" + secondary);
                }
            }
            try {
                send(MotionEvent.ACTION_UP, 1);
                activePointers = 0;
                log("cleanup_up_accepted=true");
            } catch (Exception secondary) {
                log("cleanup_up_failed=" + secondary);
            }
        }
    }

    private void run() throws Exception {
        long start = SystemClock.uptimeMillis();
        boolean completed = false;
        log("begin=true center_x=" + centerX + " center_y=" + centerY + " initial_span=" + initialSpan
                + " max_span=" + maxSpan + " nominal_scale=" + maxSpan / initialSpan
                + " cycles=" + cycles + " phase_ms=" + phaseMs + " pan_px=100 cadence_ms=" + FRAME_MS);
        try {
            for (int cycle = 1; cycle <= cycles; cycle++) {
                position(initialSpan, 0);
                downTime = SystemClock.uptimeMillis();
                // Track possible active pointers before dispatch, so even a false result or
                // partial failure triggers cleanup for every pointer that may have reached UI.
                activePointers = 1;
                send(MotionEvent.ACTION_DOWN, 1);
                SystemClock.sleep(FRAME_MS);
                activePointers = 2;
                send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
                phase("cycle" + cycle + "_zoom_in", initialSpan, maxSpan, 0, 0);
                phase("cycle" + cycle + "_pan", maxSpan, maxSpan, 0, 100);
                phase("cycle" + cycle + "_return", maxSpan, maxSpan, 100, 0);
                phase("cycle" + cycle + "_zoom_out", maxSpan, initialSpan, 0, 0);
                send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
                activePointers = 1;
                send(MotionEvent.ACTION_UP, 1);
                activePointers = 0;
                log("cycle=" + cycle + " complete=true events=" + accepted);
                if (cycle < cycles) SystemClock.sleep(250);
            }
            completed = true;
        } finally {
            releaseAfterFailure();
            log("complete=" + completed + " elapsed_ms="
                    + (SystemClock.uptimeMillis() - start) + " attempted_events=" + attempts
                    + " accepted_events=" + accepted + " late_frames_gt_16ms=" + lateFrames
                    + " max_lateness_ms=" + maxLateMs);
        }
    }

    public static void main(String[] args) {
        try {
            new MapGesture(args).run();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}

