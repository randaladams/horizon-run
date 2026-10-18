package com.horizonrun.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.speech.tts.TextToSpeech;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/**
 * Horizon Run: a first-person runner on a small curved planet.
 *
 * World coordinates: x = sideways (metres), z = distance ahead of the player (metres),
 * height = metres above the ground. The ground falls away from the camera like the
 * surface of a sphere, with the sideways curvature exaggerated so the horizon is a
 * visible arc that bends down from the centre toward the screen edges. Objects rise
 * up over that arc as you run toward them.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable, SensorEventListener {

    // ---- world tuning -------------------------------------------------------------
    private static final float EYE = 1.7f;          // camera height
    private static final float RADIUS = 1500f;      // planet radius (forward)
    private static final float SIDE_CURVE = 18f;    // sideways curvature exaggeration
    private static final float SPAWN_Z = 170f;      // where new obstacles are created
    private static final float BAND = 5f;           // ground stripe length
    private static final float BAND_MAX_Z = 95f;    // farthest ground stripe drawn
    private static final float NEAR_Z = 0.35f;      // near clipping distance
    private static final float HIT_Z = 0.5f;        // collision plane
    private static final float PLAYER_R = 0.35f;    // player half-width

    private static final float BASE_SPEED = 14f;    // m/s at the start of a run
    private static final float SPEED_GAIN = 0.30f;  // m/s gained per second of running
    private static final float MAX_BASE = 55f;      // cap for the time-based speed
    private static final float BOOST_GAIN = 12f;    // extra m/s per second while holding
    private static final float MAX_BOOST = 45f;
    private static final float BOOST_DECAY = 25f;   // how fast the extra speed fades

    // ---- object types -------------------------------------------------------------
    private static final int PINE = 0, OAK = 1, ROCK = 2, DEER = 3, BEAR = 4, FOX = 5, BRANCH = 6;
    private static final int GRASS = 7, FLOWER = 8;
    private static final int SNAIL = 9;          // slow-down pickup
    private static final int BLOOM = 10, GOLD_BLOOM = 11;   // score flowers

    private static final class Obj {
        int type;
        float x, z, size, vx, radius;
        float x2;            // branch: tip position (x is the trunk)
        float[] verts;       // rock outline
        int color;
        int trail = -1;      // which flower trail this bloom belongs to
        boolean judged;      // collision / near-miss already evaluated

        boolean solid() { return type < GRASS; }
        boolean decor() { return type == GRASS || type == FLOWER; }
    }

    private static final int MENU = 0, RUN = 1, CRASH = 2, TUTORIAL = 3;

    // ---- threading / system -------------------------------------------------------
    private final SurfaceHolder holder;
    private Thread thread;
    private volatile boolean running;
    private boolean surfaceReady, resumed;

    private final SensorManager sensorManager;
    private final Sensor tiltSensor;
    private final boolean gravitySensor;
    private volatile float tiltRaw;          // m/s^2 toward the right side of the screen
    private float accelFiltered;
    private int rotation = Surface.ROTATION_90;

    private volatile boolean touching;
    private volatile boolean tapped;

    private final SharedPreferences prefs;
    private final Random rnd = new Random();

    // ---- game state ---------------------------------------------------------------
    private int state = MENU;
    private final ArrayList<Obj> objs = new ArrayList<>();
    private final ArrayList<Obj> drawList = new ArrayList<>();
    private float px;            // player sideways position
    private float distance;      // metres run
    private float elapsed;       // seconds run
    private float speed = BASE_SPEED, boost;
    private float steer, latVel, roll;
    private float spawnAcc, aimAcc, decorAcc, rowAcc, nextRow = 220f;
    private static final float FIELD = 60f;   // obstacles fill +/- this many metres sideways
    private float bobPhase;
    private float shake, crashTimer, flash;
    private int bonus, best;
    private int bestAtStart;        // best score when this run began
    private boolean passedBest;     // already beat the old best during this run
    private boolean newHigh;        // the finished run set a new high score
    private float goldFlash;
    private float msgTimer;
    private String msg = "";
    private float menuTime;
    private float baseTime;          // drives the base speed; snails knock it back
    // score flowers
    private static final int FLOWER_PTS = 20, BOUQUET_PTS = 100, GOLD_PTS = 150, TRAIL_LEN = 5;
    private float flowerTimer, nextFlower = 3f;
    private int trailId, trailGot, trailMissed, activeTrail = -1;
    private int flowersRun;
    private String popText = "";
    private float popTimer;
    private int popColor;
    private float pinkFlash;
    private float pickupAcc, nextPickup = 300f;
    private float blueFlash, animTime;
    private float resetConfirm;      // >0 while waiting for the second tap
    private volatile boolean resetTap;

    // ---- tutorial ---------------------------------------------------------------
    private volatile boolean tutStart, tutSkip;
    private int tutStep;
    private float tutTimer;          // time in the current step
    private float tutHold;           // how long the step's condition has been met
    private float tutWait = -1f;     // >=0 while showing "Nice!" before the next step
    private float tutDelay;          // countdown before spawning the step's object
    private float tutMsgTimer;
    private String tutMsg = "";
    private boolean tutMsgGood;
    private boolean tutSprinted;
    private Obj tutObj;
    private float sceneryAcc;
    private static final int TUT_STEPS = 10;
    private static final float SNAIL_SLOW = 25f;   // seconds of speed build-up removed

    // ---- rendering ----------------------------------------------------------------
    private float W = 1, H = 1, cx, cy, f, uMax;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sky = new Paint();
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private float fogT;
    private final float[] cloudX = new float[7], cloudY = new float[7], cloudS = new float[7];
    private final float[] starX = new float[60], starY = new float[60];

    // ---- light / dark theme -------------------------------------------------------
    private boolean dark;
    private volatile boolean themeDirty = true;
    private volatile boolean toggleTheme;
    private int HAZE, GROUND_A, GROUND_B;
    private int starCol, cloudCol, planetCol, ringCol, glowCol, bgCol;
    private int[] skyCols;

    private TextToSpeech tts;
    private boolean ttsReady;

    private void applyTheme() {
        if (dark) {
            HAZE = 0xFF22335A;
            GROUND_A = 0xFF1E3B2A;
            GROUND_B = 0xFF234530;
            skyCols = new int[]{0xFF02040C, 0xFF07112A, 0xFF13244A, HAZE};
            starCol = 0xE6FFFFFF;
            cloudCol = 0x3A8FA6D6;
            planetCol = 0xFFD8DDE8;
            ringCol = 0x99C9D3E8;
            glowCol = 0xFF7FA8FF;
            bgCol = 0xFF02040C;
        } else {
            HAZE = 0xFFBFDDF5;
            GROUND_A = 0xFF4F9A3F;
            GROUND_B = 0xFF5BA947;
            skyCols = new int[]{0xFF0E2457, 0xFF2F64B8, 0xFF8EC6F2, HAZE};
            starCol = 0x66FFFFFF;
            cloudCol = 0xD9FFFFFF;
            planetCol = 0xFFE9D9B6;
            ringCol = 0xAAF3E6C8;
            glowCol = 0xFFFFFFFF;
            bgCol = 0xFF0E2457;
        }
        if (H > 1) {
            sky.setShader(new LinearGradient(0, 0, 0, horizonY(0f) + H * 0.05f,
                    skyCols, new float[]{0f, 0.45f, 0.85f, 1f}, Shader.TileMode.CLAMP));
        }
        themeDirty = false;
    }

    /** Theme toggle button (top-right corner on the menu and crash screens). */
    private float themeBtnX() { return W - Math.max(W * 0.035f, H * 0.05f) - H * 0.055f; }
    private float themeBtnY() { return Math.max(W * 0.035f, H * 0.05f) + H * 0.055f; }
    private float themeBtnR() { return H * 0.055f; }

    public GameView(Context ctx) {
        super(ctx);
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);

        prefs = ctx.getSharedPreferences("horizon_run", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        // Start in the phone's own light/dark setting until the player picks one.
        int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = prefs.getBoolean("dark", night == Configuration.UI_MODE_NIGHT_YES);
        applyTheme();

        try {
            tts = new TextToSpeech(ctx, status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.getDefault());
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) { }

        sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) : null;
        if (g != null) {
            tiltSensor = g;
            gravitySensor = true;
        } else {
            tiltSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
            gravitySensor = false;
        }

        txt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        txt.setColor(Color.WHITE);
        txt.setShadowLayer(6f, 0f, 3f, 0x99000000);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < cloudX.length; i++) {
            cloudX[i] = rnd.nextFloat();
            cloudY[i] = 0.04f + rnd.nextFloat() * 0.16f;
            cloudS[i] = 0.6f + rnd.nextFloat() * 0.8f;
        }
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rnd.nextFloat();
            starY[i] = rnd.nextFloat() * 0.18f;
        }
        resetRun();
    }

    // ================================================================================
    // Lifecycle
    // ================================================================================

    public void resume() {
        resumed = true;
        if (tiltSensor != null) {
            sensorManager.registerListener(this, tiltSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        startLoop();
    }

    public void pause() {
        resumed = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        touching = false;
        stopLoop();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        startLoop();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        W = width;
        H = height;
        cx = W * 0.5f;
        cy = H * 0.30f;
        f = W * 0.5f;                      // roughly a 90 degree field of view
        uMax = 0.75f * (W * 0.5f) / f + 0.45f;
        themeDirty = true;
        Display d = getDisplay();
        if (d != null) rotation = d.getRotation();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopLoop();
    }

    private void startLoop() {
        if (!surfaceReady || !resumed || running) return;
        running = true;
        thread = new Thread(this, "game-loop");
        thread.start();
    }

    private void stopLoop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min((now - last) / 1e9f, 0.05f);
            last = now;
            update(dt);

            Canvas c = null;
            try {
                c = holder.lockHardwareCanvas();
            } catch (Exception e) {
                try { c = holder.lockCanvas(); } catch (Exception ignored) { }
            }
            if (c == null) {
                try { Thread.sleep(8); } catch (InterruptedException ignored) { }
                continue;
            }
            try {
                render(c);
            } finally {
                try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
            }
        }
    }

    // ================================================================================
    // Input
    // ================================================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (state == TUTORIAL) {
                    if (inRect(e, resetBtnL(), resetBtnT(), resetBtnR(), resetBtnB())) {
                        tutSkip = true;
                        return true;
                    }
                }
                if (state == MENU && inRect(e, howBtnL(), howBtnT(), howBtnR(), howBtnB())) {
                    tutStart = true;
                    return true;
                }
                if (state == MENU || state == CRASH) {
                    float dx = e.getX() - themeBtnX(), dy = e.getY() - themeBtnY();
                    float r = themeBtnR() * 1.8f;
                    if (dx * dx + dy * dy < r * r) {
                        toggleTheme = true;
                        return true;
                    }
                    if (inRect(e, resetBtnL(), resetBtnT(), resetBtnR(), resetBtnB())) {
                        resetTap = true;
                        return true;
                    }
                }
                touching = true;
                tapped = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                touching = true;
                tapped = true;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                touching = e.getPointerCount() > 1;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float ax = e.values[0], ay = e.values[1];
        // Convert device axes to "tilt toward the right of the screen" for landscape.
        float right;
        switch (rotation) {
            case Surface.ROTATION_90:  right = ay;  break;
            case Surface.ROTATION_270: right = -ay; break;
            case Surface.ROTATION_180: right = ax;  break;
            default:                   right = -ax; break;
        }
        if (gravitySensor) {
            tiltRaw = right;
        } else {
            accelFiltered += (right - accelFiltered) * 0.2f;
            tiltRaw = accelFiltered;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ================================================================================
    // Game logic
    // ================================================================================

    private void resetRun() {
        objs.clear();
        px = 0;
        distance = 0;
        elapsed = 0;
        baseTime = 0;
        pickupAcc = 0;
        nextPickup = 7f + rnd.nextFloat() * 3f;   // seconds
        blueFlash = 0;
        speed = BASE_SPEED;
        boost = 0;
        latVel = 0;
        bonus = 0;
        spawnAcc = 0;
        decorAcc = 0;
        rowAcc = 0;
        nextRow = 200f + rnd.nextFloat() * 120f;
        shake = 0;
        flash = 0;
        msgTimer = 0;
        aimAcc = 0;
        passedBest = false;
        newHigh = false;
        goldFlash = 0;
        bestAtStart = best;
        flowerTimer = 0;
        nextFlower = 3f + rnd.nextFloat() * 2f;
        activeTrail = -1;
        flowersRun = 0;
        popTimer = 0;
        pinkFlash = 0;
        float g0 = 12f * (48f / (2f * FIELD));
        for (float z = 45f; z < SPAWN_Z; z += g0) spawnObstacle(z, false);
        for (float z = 70f; z < SPAWN_Z; z += 36f) spawnObstacle(z, true);
        for (float z = 2f; z < 80f; z += 1f) { spawnDecor(z); spawnDecor(z); }
    }

    private void update(float dt) {
        // Steering input, with a small dead zone so a slightly crooked grip doesn't drift.
        float t = tiltRaw;
        float dead = 0.45f;
        float s = Math.abs(t) < dead ? 0f : (t - Math.signum(t) * dead) / 5.0f;
        s = clamp(s, -1f, 1f);
        steer += (s - steer) * Math.min(1f, dt * 12f);

        animTime += dt;
        if (resetConfirm > 0f) resetConfirm -= dt;
        if (resetTap) {
            resetTap = false;
            if (resetConfirm > 0f) {
                best = 0;
                bestAtStart = 0;
                newHigh = false;
                prefs.edit().putInt("best", 0).apply();
                resetConfirm = 0f;
                msg = "BEST SCORE RESET";
                msgTimer = 1.5f;
            } else {
                resetConfirm = 2.5f;
            }
        }
        if (msgTimer > 0 && state != RUN) msgTimer -= dt;

        if (toggleTheme) {
            toggleTheme = false;
            dark = !dark;
            prefs.edit().putBoolean("dark", dark).apply();
            themeDirty = true;
        }

        if (tutStart) {
            tutStart = false;
            if (state == MENU) startTutorial();
        }
        if (state == TUTORIAL) {
            updateTutorial(dt);
            tapped = false;
            return;
        }

        if (state == MENU) {
            menuTime += dt;
            // gentle idle jog in the background
            float dz = 6f * dt;
            advanceWorld(dz, dt, false);
            px += steer * 6f * dt;
            roll += (-steer * 5f - roll) * Math.min(1f, dt * 6f);
            if (tapped && menuTime > 0.3f) {
                tapped = false;
                resetRun();
                state = RUN;
            }
            tapped = false;
            return;
        }

        if (state == CRASH) {
            crashTimer += dt;
            shake = Math.max(0f, shake - dt * 1.6f);
            flash = Math.max(0f, flash - dt * 2f);
            if (tapped && crashTimer > 0.9f) {
                tapped = false;
                resetRun();
                state = RUN;
            }
            tapped = false;
            return;
        }

        // ---- RUN ----
        tapped = false;
        elapsed += dt;
        baseTime += dt;
        float base = Math.min(BASE_SPEED + baseTime * SPEED_GAIN, MAX_BASE);
        if (touching) boost = Math.min(boost + BOOST_GAIN * dt, MAX_BOOST);
        else boost = Math.max(0f, boost - BOOST_DECAY * dt);
        float target = base + boost;
        speed += (target - speed) * Math.min(1f, dt * 3f);

        // Sideways speed stays human: it no longer scales up with sprinting.
        float latMax = 7f + speed * 0.06f;
        latVel += (steer * latMax - latVel) * Math.min(1f, dt * 8f);
        px += latVel * dt;
        roll += (-steer * 5f - roll) * Math.min(1f, dt * 6f);

        float dz = speed * dt;
        distance += dz;
        bobPhase += dz * 0.55f;
        advanceWorld(dz, dt, true);
        if (state != RUN) return;

        // High score passed mid-run: flash gold once.
        if (!passedBest && bestAtStart > 0 && score() > bestAtStart) {
            passedBest = true;
            goldFlash = 1f;
            msg = "NEW HIGH SCORE!";
            msgTimer = 2.0f;
            speak("New high score!");
        }
        goldFlash = Math.max(0f, goldFlash - dt * 1.5f);

        // Spawning. Obstacles fill a wide field across where you will be when they
        // arrive, so steering hard to one side just runs you into a different part of
        // the forest instead of out of it.
        float gap = Math.max(4.4f, 12f - elapsed * 0.055f);
        spawnAcc += dz;
        float fieldGap = gap * (48f / (2f * FIELD));
        while (spawnAcc >= fieldGap) {
            spawnAcc -= fieldGap;
            spawnObstacle(SPAWN_Z - spawnAcc, false);
        }
        // Plus a steady stream aimed straight at your predicted path.
        aimAcc += dz;
        float aimGap = Math.max(13f, 36f - elapsed * 0.15f);
        while (aimAcc >= aimGap) {
            aimAcc -= aimGap;
            spawnObstacle(SPAWN_Z - aimAcc, true);
        }
        // Score flowers: a curving trail most of the time, now and then a golden one.
        flowerTimer += dt;
        if (flowerTimer >= nextFlower) {
            flowerTimer = 0;
            nextFlower = 3.5f + rnd.nextFloat() * 3f;
            if (rnd.nextFloat() < 0.18f && elapsed > 10f) spawnGoldBloom(SPAWN_Z);
            else spawnFlowerTrail(SPAWN_Z);
        }
        if (popTimer > 0) popTimer -= dt;
        pinkFlash = Math.max(0f, pinkFlash - dt * 2.5f);

        // Slow-down snails, on a timer that speeds up as the base speed climbs:
        // every 5-9 s at the start, every ~3-5 s near top speed.
        pickupAcc += dt;
        if (pickupAcc >= nextPickup && elapsed > 6f) {
            pickupAcc = 0;
            float fast = clamp((base - BASE_SPEED) / (MAX_BASE - BASE_SPEED), 0f, 1f);
            nextPickup = (5f + rnd.nextFloat() * 4f) * (1f - 0.45f * fast);
            spawnSnail(SPAWN_Z);
        }
        blueFlash = Math.max(0f, blueFlash - dt * 1.5f);
        rowAcc += dz;
        if (elapsed > 15f && rowAcc > nextRow) {
            rowAcc = 0;
            nextRow = 160f + rnd.nextFloat() * 200f - Math.min(elapsed, 120f) * 0.5f;
            spawnRow(SPAWN_Z);
        }
        decorAcc += dz;
        while (decorAcc >= 1f) {
            decorAcc -= 1f;
            spawnDecor(80f - decorAcc);
            spawnDecor(80f - decorAcc);
        }
        if (msgTimer > 0) msgTimer -= dt;
    }

    /** Where the player will be sideways when something now at distance z reaches them. */
    private float predictX(float z) {
        float eta = speed > 1f ? Math.min(z / speed, 5f) : 0f;
        return px + latVel * eta;
    }

    private void speak(String s) {
        if (tts != null && ttsReady) {
            try { tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "hr"); } catch (Exception ignored) { }
        }
    }

    public void release() {
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
    }

    /** Moves every object toward the player and handles collisions. */
    private void advanceWorld(float dz, float dt, boolean collide) {
        for (int i = objs.size() - 1; i >= 0; i--) {
            Obj o = objs.get(i);
            float prevZ = o.z;
            o.z -= dz;
            o.x += o.vx * dt;
            if (o.type == BRANCH) o.x2 += o.vx * dt;

            if (collide && o.solid() && !o.judged && prevZ >= HIT_Z && o.z < HIT_Z) {
                o.judged = true;
                float gapToPlayer = clearance(o);
                if (gapToPlayer < 0f) {
                    crash();
                    return;
                } else if (gapToPlayer < 0.7f) {
                    bonus += 25;
                    msg = "CLOSE CALL  +25";
                    msgTimer = 1.0f;
                }
            }

            if (collide && o.type == SNAIL && !o.judged && prevZ >= HIT_Z && o.z < HIT_Z) {
                o.judged = true;
                if (Math.abs(o.x - px) < 1.3f + PLAYER_R) {
                    collectSnail();
                    objs.remove(i);
                    continue;
                }
            }

            if (collide && (o.type == BLOOM || o.type == GOLD_BLOOM)
                    && !o.judged && prevZ >= HIT_Z && o.z < HIT_Z) {
                o.judged = true;
                if (Math.abs(o.x - px) < 1.0f + PLAYER_R) {
                    collectFlower(o);
                    objs.remove(i);
                    continue;
                } else if (o.type == BLOOM && o.trail == activeTrail) {
                    trailMissed++;
                }
            }

            boolean gone = o.z < (o.solid() ? -1f : NEAR_Z)
                    || Math.abs(o.x - px) > FIELD + 50f;
            if (o.decor() && !gone && o.z > 0) {
                // keep the grass field around the player when steering far sideways
                if (Math.abs(o.x - px) > 38f) gone = true;
            }
            if (gone) objs.remove(i);
        }
        // in the menu, keep the scene populated
        if (state == MENU) {
            spawnAcc += dz;
            float g = 12f * (48f / (2f * FIELD));
            while (spawnAcc >= g) { spawnAcc -= g; spawnObstacle(SPAWN_Z, false); }
            decorAcc += dz;
            while (decorAcc >= 1f) { decorAcc -= 1f; spawnDecor(80f); spawnDecor(80f); }
        }
    }

    /** Distance between the player's body and the obstacle edge; negative means a hit. */
    private float clearance(Obj o) {
        if (o.type == BRANCH) {
            float lo = Math.min(o.x, o.x2) - 0.4f;
            float hi = Math.max(o.x, o.x2) + 0.3f;
            if (px >= lo && px <= hi) return -1f;
            return Math.min(Math.abs(px - lo), Math.abs(px - hi)) - PLAYER_R;
        }
        return Math.abs(o.x - px) - (o.radius + PLAYER_R);
    }

    private void crash() {
        state = CRASH;
        crashTimer = 0;
        shake = 1f;
        flash = 1f;
        boost = 0;
        int score = score();
        newHigh = score > bestAtStart && bestAtStart > 0;
        if (score > best) {
            best = score;
            prefs.edit().putInt("best", best).apply();
        }
        if (newHigh) speak("New high score! " + score + " meters.");
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) { }
    }

    private int score() { return (int) distance + bonus; }

    // ---- spawning -----------------------------------------------------------------

    private void spawnObstacle(float z, boolean aimed) {
        float r = rnd.nextFloat();
        int type;
        if (r < 0.30f) type = PINE;
        else if (r < 0.48f) type = OAK;
        else if (r < 0.68f) type = ROCK;
        else if (r < 0.78f) type = DEER;
        else if (r < 0.84f) type = BEAR;
        else if (r < 0.90f) type = FOX;
        else type = (state == RUN && elapsed > 8f) ? BRANCH : ROCK;

        float center = predictX(z);
        for (int attempt = 0; attempt < 6; attempt++) {
            float x;
            if (aimed) {
                // right where the player is heading
                x = center + (rnd.nextFloat() * 2f - 1f) * 2.5f;
            } else {
                x = center + (rnd.nextFloat() * 2f - 1f) * FIELD;
            }
            Obj o = make(type, x, z);
            if (type == BRANCH || !crowded(o)) {
                objs.add(o);
                return;
            }
        }
    }

    private void spawnRow(float z) {
        float center = predictX(z);
        float gapX = center + (rnd.nextFloat() * 2f - 1f) * 7f;
        int kind = rnd.nextBoolean() ? PINE : ROCK;
        for (float x = center - 26f; x <= center + 26f; x += 2.6f) {
            if (Math.abs(x - gapX) < 2.4f) continue;
            Obj o = make(kind, x + (rnd.nextFloat() - 0.5f) * 0.3f, z + (rnd.nextFloat() - 0.5f) * 1.5f);
            if (kind == PINE) { o.size = 1f; o.radius = 1.05f; }
            else { o.size = 1.2f; o.radius = 1.05f; }
            objs.add(o);
        }
    }

    private boolean crowded(Obj n) {
        for (int i = 0; i < objs.size(); i++) {
            Obj o = objs.get(i);
            if (!o.solid()) continue;
            if (Math.abs(o.z - n.z) < 6f && Math.abs(o.x - n.x) < o.radius + n.radius + 1.5f) return true;
        }
        return false;
    }

    private Obj make(int type, float x, float z) {
        Obj o = new Obj();
        o.type = type;
        o.x = x;
        o.z = z;
        switch (type) {
            case PINE:
                o.size = 0.8f + rnd.nextFloat() * 0.6f;
                o.radius = 1.05f * o.size;
                o.color = rnd.nextInt(3);
                break;
            case OAK:
                o.size = 0.85f + rnd.nextFloat() * 0.6f;
                o.radius = 0.45f * o.size;
                o.color = rnd.nextInt(3);
                break;
            case ROCK: {
                o.size = 0.7f + rnd.nextFloat() * 0.9f;
                o.radius = 0.9f * o.size;
                int n = 9;
                o.verts = new float[n * 2];
                for (int i = 0; i < n; i++) {
                    double a = Math.PI + Math.PI * i / (n - 1);   // upper half outline
                    float rr = 0.8f + rnd.nextFloat() * 0.3f;
                    o.verts[i * 2] = (float) Math.cos(a) * rr;
                    o.verts[i * 2 + 1] = (float) Math.sin(a) * rr * 0.75f;
                }
                break;
            }
            case DEER: {
                float dir = rnd.nextBoolean() ? 1f : -1f;
                o.vx = dir * (2.5f + rnd.nextFloat() * 2.5f);
                o.x = x - dir * (4f + rnd.nextFloat() * 8f);
                o.size = 1f;
                o.radius = 0.85f;
                break;
            }
            case BEAR: {
                float dir = rnd.nextBoolean() ? 1f : -1f;
                o.vx = dir * (0.8f + rnd.nextFloat() * 1.4f);
                o.x = x - dir * (1f + rnd.nextFloat() * 3f);
                o.size = 1f;
                o.radius = 1.0f;
                break;
            }
            case FOX: {
                float dir = rnd.nextBoolean() ? 1f : -1f;
                o.vx = dir * (3f + rnd.nextFloat() * 3f);
                o.x = x - dir * (5f + rnd.nextFloat() * 10f);
                o.size = 1f;
                o.radius = 0.5f;
                break;
            }
            case BRANCH: {
                // Limb length 3.2-6.4 m (20% shorter than the first version).
                // The trunk is placed so the limb hangs over the requested spot x.
                float side = rnd.nextBoolean() ? 1f : -1f;
                float len = 3.2f + rnd.nextFloat() * 3.2f;
                o.x = x + side * (0.8f + rnd.nextFloat() * (len - 1.3f));
                o.x2 = o.x - side * len;
                o.size = 1f;
                o.radius = 0.4f;
                break;
            }
            default:
                break;
        }
        return o;
    }

    private void spawnDecor(float z) {
        Obj o = new Obj();
        o.type = rnd.nextFloat() < 0.18f ? FLOWER : GRASS;
        o.x = px + (rnd.nextFloat() * 2f - 1f) * 36f;
        o.z = z - rnd.nextFloat();
        o.size = 0.7f + rnd.nextFloat() * 0.7f;
        o.color = rnd.nextInt(4);
        objs.add(o);
    }

    // ================================================================================
    // Projection
    // ================================================================================

    private float drop(float dx, float z) {
        return (SIDE_CURVE * dx * dx + z * z) / (2f * RADIUS);
    }

    private float eye() {
        return EYE + ((state == RUN || state == TUTORIAL) ? (float) Math.sin(bobPhase) * 0.05f : 0f);
    }

    private float projX(float dx, float z) { return cx + f * dx / z; }

    private float projY(float dx, float height, float z) {
        return cy + f * (eye() - height + drop(dx, z)) / z;
    }

    /** Screen y of the planet's horizon for a given screen-space direction u = dx / z. */
    private float horizonY(float u) {
        return cy + f * (float) Math.sqrt(2f * EYE * (1f + SIDE_CURVE * u * u) / RADIUS);
    }

    /** Distance to the horizon in direction u. */
    private float horizonDist(float u) {
        return (float) Math.sqrt(2f * RADIUS * EYE / (1f + SIDE_CURVE * u * u));
    }

    // ================================================================================
    // Rendering
    // ================================================================================

    private void render(Canvas c) {
        if (themeDirty) applyTheme();
        c.drawColor(bgCol);
        c.save();
        if (shake > 0f) {
            float a = shake * H * 0.03f;
            c.translate((rnd.nextFloat() - 0.5f) * a, (rnd.nextFloat() - 0.5f) * a);
        }
        float pivotY = H * 0.5f;
        c.rotate(roll, cx, pivotY);
        c.scale(1.1f, 1.1f, cx, pivotY);

        drawSky(c);
        drawScene(c);
        c.restore();

        if (state == RUN && boost > 1f) drawSpeedLines(c);
        if (flash > 0f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (flash * 140), 255, 40, 30));
            c.drawRect(0, 0, W, H, p);
        }
        drawHud(c);
    }

    private void drawSky(Canvas c) {
        c.drawRect(-W * 0.3f, -H * 0.3f, W * 1.3f, H * 1.2f, sky);

        // faint stars high up
        p.setStyle(Paint.Style.FILL);
        p.setColor(starCol);
        float sr = Math.max(1.5f, H * 0.0025f);
        for (int i = 0; i < starX.length; i++) {
            float x = wrap(starX[i] * W * 1.6f - px * 0.6f, W * 1.6f) - W * 0.3f;
            c.drawCircle(x, starY[i] * H * (dark ? 2.2f : 1f) - H * 0.05f, sr * (dark && (i % 5 == 0) ? 1.6f : 1f), p);
        }

        // a big ringed neighbour planet hanging in the sky
        float plx = wrap(W * 0.78f - px * 1.2f, W * 2.4f) - W * 0.7f;
        float ply = H * 0.11f;
        float plr = H * 0.075f;
        p.setColor(planetCol);
        c.drawCircle(plx, ply, plr, p);
        p.setColor(0x33A0784A);
        c.drawCircle(plx + plr * 0.25f, ply + plr * 0.2f, plr * 0.8f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(plr * 0.12f);
        p.setColor(ringCol);
        c.save();
        c.rotate(-18f, plx, ply);
        rect.set(plx - plr * 1.9f, ply - plr * 0.42f, plx + plr * 1.9f, ply + plr * 0.42f);
        c.drawOval(rect, p);
        c.restore();
        p.setStyle(Paint.Style.FILL);
        p.setColor(planetCol);
        rect.set(plx - plr, ply - plr, plx + plr, ply);
        c.drawArc(rect, 180f, 180f, true, p);   // planet body in front of the far side of the ring

        // drifting clouds with a little parallax
        p.setColor(cloudCol);
        for (int i = 0; i < cloudX.length; i++) {
            float span = W * 2f;
            float x = wrap(cloudX[i] * span - px * 3f - distance * 0.15f, span) - W * 0.5f;
            float y = cloudY[i] * H + H * 0.02f;
            float s = cloudS[i] * H * 0.06f;
            rect.set(x - s * 2.2f, y - s * 0.5f, x + s * 2.2f, y + s * 0.6f);
            c.drawOval(rect, p);
            c.drawCircle(x - s * 0.7f, y - s * 0.3f, s * 0.8f, p);
            c.drawCircle(x + s * 0.6f, y - s * 0.4f, s * 1.0f, p);
        }

        // glowing atmosphere hugging the curved horizon
        int steps = 48;
        path.reset();
        for (int i = 0; i <= steps; i++) {
            float u = -uMax + 2f * uMax * i / steps;
            float x = cx + f * u;
            float y = horizonY(u);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        glow.setColor((glowCol & 0x00FFFFFF) | 0x30000000);
        glow.setStrokeWidth(H * 0.10f);
        c.drawPath(path, glow);
        glow.setColor((glowCol & 0x00FFFFFF) | 0x50000000);
        glow.setStrokeWidth(H * 0.045f);
        c.drawPath(path, glow);
        glow.setColor((glowCol & 0x00FFFFFF) | 0x90000000);
        glow.setStrokeWidth(H * 0.012f);
        c.drawPath(path, glow);
    }

    /** Draws ground stripes and objects far-to-near so nearer things cover farther ones. */
    private void drawScene(Canvas c) {
        drawList.clear();
        for (int i = 0; i < objs.size(); i++) {
            Obj o = objs.get(i);
            if (o.z <= NEAR_Z) continue;
            if (o.decor() && o.z > 55f) continue;
            float u = (o.x - px) / o.z;
            if (Math.abs(u) > uMax + 0.6f && o.type != BRANCH) continue;
            drawList.add(o);
        }
        // insertion sort by z descending (list is nearly sorted frame to frame)
        for (int i = 1; i < drawList.size(); i++) {
            Obj k = drawList.get(i);
            int j = i - 1;
            while (j >= 0 && drawList.get(j).z < k.z) {
                drawList.set(j + 1, drawList.get(j));
                j--;
            }
            drawList.set(j + 1, k);
        }

        int oi = 0;
        int n = drawList.size();
        // objects beyond every ground stripe
        while (oi < n && drawList.get(oi).z >= BAND_MAX_Z) drawObj(c, drawList.get(oi++));

        float mod = distance % BAND;
        float hi = BAND_MAX_Z;
        float lo = (float) Math.floor((hi + mod) / BAND) * BAND - mod;
        if (lo >= hi - 0.01f) lo -= BAND;

        while (hi > NEAR_Z) {
            float bandLo = Math.max(lo, NEAR_Z * 0.6f);
            int start = oi;
            while (oi < n && drawList.get(oi).z >= bandLo) oi++;
            // objects sitting past the horizon are partly hidden by this stripe
            for (int k = start; k < oi; k++) {
                Obj o = drawList.get(k);
                if (o.z > horizonDist((o.x - px) / o.z)) drawObj(c, o);
            }
            float zMid = (hi + bandLo) * 0.5f;
            int stripe = (int) Math.floor((distance + zMid) / BAND);
            int col = (stripe & 1) == 0 ? GROUND_A : GROUND_B;
            drawBand(c, hi, bandLo, hazeMix(col, fogFor(zMid)));
            for (int k = start; k < oi; k++) {
                Obj o = drawList.get(k);
                if (o.z <= horizonDist((o.x - px) / o.z)) drawObj(c, o);
            }
            hi = bandLo;
            lo = hi - BAND;
        }
        while (oi < n) drawObj(c, drawList.get(oi++));
    }

    private void drawBand(Canvas c, float zFar, float zNear, int color) {
        int steps = 40;
        float e = eye();
        path.reset();
        for (int i = 0; i <= steps; i++) {
            float u = -uMax + 2f * uMax * i / steps;
            float y = cy + f * (e / zFar + zFar * (1f + SIDE_CURVE * u * u) / (2f * RADIUS));
            float x = cx + f * u;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        for (int i = steps; i >= 0; i--) {
            float u = -uMax + 2f * uMax * i / steps;
            float y = cy + f * (e / zNear + zNear * (1f + SIDE_CURVE * u * u) / (2f * RADIUS));
            y = Math.min(y, H * 4f);
            path.lineTo(cx + f * u, y);
        }
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private float fogFor(float z) {
        return clamp((z - 30f) / 130f, 0f, 0.85f);
    }

    private int fog(int color) { return fog(color, fogT); }

    /** Blend toward the horizon haze without the night tint (ground is already themed). */
    private int hazeMix(int color, float t) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int hr = Color.red(HAZE), hg = Color.green(HAZE), hb = Color.blue(HAZE);
        return Color.rgb((int) (r + (hr - r) * t), (int) (g + (hg - g) * t), (int) (b + (hb - b) * t));
    }

    private int fog(int color, float t) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        if (dark) {          // moonlit: darker and cooler
            r = (int) (r * 0.42f);
            g = (int) (g * 0.48f);
            b = (int) (b * 0.62f + 12);
        }
        int hr = Color.red(HAZE), hg = Color.green(HAZE), hb = Color.blue(HAZE);
        return Color.argb(Color.alpha(color),
                (int) (r + (hr - r) * t), (int) (g + (hg - g) * t), (int) (b + (hb - b) * t));
    }

    private void drawObj(Canvas c, Obj o) {
        fogT = fogFor(o.z);
        float dx = o.x - px;
        if (o.type == BRANCH) { drawBranch(c, o); return; }

        float sx = projX(dx, o.z);
        float sy = projY(dx, 0f, o.z);
        float s = f / o.z;
        float lean = (float) Math.toDegrees(Math.atan(SIDE_CURVE * dx / RADIUS));

        c.save();
        c.translate(sx, sy);
        c.rotate(lean);
        c.scale(s, s);
        p.setStyle(Paint.Style.FILL);

        if (o.solid()) {
            // soft contact shadow
            p.setColor(Color.argb((int) (70 * (1f - fogT)), 0, 0, 0));
            float sw = o.type == PINE ? 1.3f * o.size : o.type == OAK ? 1.6f * o.size : o.radius + 0.3f;
            rect.set(-sw, -0.12f, sw, 0.12f);
            c.drawOval(rect, p);
        }

        switch (o.type) {
            case PINE:   drawPine(c, o); break;
            case OAK:    drawOak(c, o); break;
            case ROCK:   drawRock(c, o); break;
            case DEER:   drawDeer(c, o); break;
            case BEAR:   drawBear(c, o); break;
            case FOX:    drawFox(c, o); break;
            case GRASS:  drawGrass(c, o); break;
            case FLOWER: drawFlower(c, o); break;
            case SNAIL:  drawSnail(c, o); break;
            case BLOOM:
            case GOLD_BLOOM: drawBloom(c, o); break;
            default: break;
        }
        c.restore();
    }

    private static final int[][] PINE_COLS = {
            {0xFF245A30, 0xFF2E6B3A, 0xFF3A7F45},
            {0xFF1F5130, 0xFF2A6538, 0xFF377A43},
            {0xFF2B6233, 0xFF367540, 0xFF46894D}};

    private void drawPine(Canvas c, Obj o) {
        float k = o.size;
        p.setColor(fog(0xFF6B4423));
        c.drawRect(-0.22f * k, -1.3f * k, 0.22f * k, 0f, p);
        int[] cols = PINE_COLS[o.color % 3];
        tri(c, -1.6f * k, -1.0f * k, 1.6f * k, -1.0f * k, 0f, -4.2f * k, fog(cols[0]));
        tri(c, -1.25f * k, -2.6f * k, 1.25f * k, -2.6f * k, 0f, -5.8f * k, fog(cols[1]));
        tri(c, -0.85f * k, -4.2f * k, 0.85f * k, -4.2f * k, 0f, -7.2f * k, fog(cols[2]));
    }

    private static final int[] OAK_COLS = {0xFF3F8F3A, 0xFF4E9A35, 0xFF5E8F2E};

    private void drawOak(Canvas c, Obj o) {
        float k = o.size;
        p.setColor(fog(0xFF7A5230));
        path.reset();
        path.moveTo(-0.32f * k, 0f);
        path.lineTo(0.32f * k, 0f);
        path.lineTo(0.2f * k, -3.4f * k);
        path.lineTo(-0.2f * k, -3.4f * k);
        path.close();
        c.drawPath(path, p);
        int base = OAK_COLS[o.color % 3];
        p.setColor(fog(darker(base, 0.8f)));
        c.drawCircle(-1.25f * k, -3.7f * k, 1.35f * k, p);
        c.drawCircle(1.25f * k, -3.7f * k, 1.35f * k, p);
        p.setColor(fog(base));
        c.drawCircle(0f, -4.6f * k, 1.9f * k, p);
        p.setColor(fog(lighter(base, 0.25f)));
        c.drawCircle(-0.5f * k, -5.1f * k, 0.9f * k, p);
    }

    private void drawRock(Canvas c, Obj o) {
        float k = o.size;
        float[] v = o.verts;
        path.reset();
        path.moveTo(v[0] * k, 0.05f);
        for (int i = 0; i < v.length; i += 2) path.lineTo(v[i] * k, v[i + 1] * k);
        path.lineTo(v[v.length - 2] * k, 0.05f);
        path.close();
        p.setColor(fog(0xFF7E8286));
        c.drawPath(path, p);
        // lit face
        path.reset();
        path.moveTo(v[0] * k * 0.55f - 0.1f * k, -0.05f);
        for (int i = 0; i < v.length / 2 + 1; i += 2) path.lineTo(v[i] * k * 0.7f - 0.1f * k, v[i + 1] * k * 0.8f);
        path.lineTo(0f, -0.05f);
        path.close();
        p.setColor(fog(0xFFA4A8AC));
        c.drawPath(path, p);
    }

    private void drawDeer(Canvas c, Obj o) {
        float dir = o.vx >= 0 ? 1f : -1f;
        float gait = (float) Math.sin((distance + o.x * 3f) * 0.9f) * 0.12f;
        c.scale(dir, 1f);
        int body = fog(0xFF9B6A3C);
        int dark = fog(0xFF6E4A28);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.09f);
        p.setColor(dark);
        c.drawLine(-0.5f, -0.95f, -0.5f - gait, 0f, p);
        c.drawLine(0.45f, -0.95f, 0.45f + gait, 0f, p);
        p.setColor(body);
        c.drawLine(-0.35f, -0.95f, -0.35f + gait, 0f, p);
        c.drawLine(0.6f, -0.95f, 0.6f - gait, 0f, p);
        p.setStyle(Paint.Style.FILL);
        rect.set(-0.8f, -1.35f, 0.8f, -0.8f);
        c.drawOval(rect, p);
        path.reset();
        path.moveTo(0.45f, -1.25f);
        path.lineTo(0.75f, -1.95f);
        path.lineTo(0.98f, -1.85f);
        path.lineTo(0.78f, -1.05f);
        path.close();
        c.drawPath(path, p);
        rect.set(0.72f, -2.05f, 1.22f, -1.78f);
        c.drawOval(rect, p);
        p.setColor(fog(0xFFF2E6D0));
        c.drawCircle(-0.8f, -1.2f, 0.11f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.05f);
        p.setColor(fog(0xFFE0CFA6));
        c.drawLine(0.82f, -2.0f, 0.7f, -2.45f, p);
        c.drawLine(0.75f, -2.25f, 0.55f, -2.4f, p);
        c.drawLine(0.92f, -2.0f, 1.0f, -2.45f, p);
        c.drawLine(0.97f, -2.25f, 1.15f, -2.38f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawBear(Canvas c, Obj o) {
        float dir = o.vx >= 0 ? 1f : -1f;
        c.scale(dir, 1f);
        float gait = (float) Math.sin((distance + o.x * 3f) * 0.6f) * 0.1f;
        int fur = fog(0xFF4A2F1F);
        p.setColor(fog(0xFF3A2416));
        c.drawRect(-0.75f + gait, -0.5f, -0.45f + gait, 0f, p);
        c.drawRect(0.45f - gait, -0.5f, 0.75f - gait, 0f, p);
        p.setColor(fur);
        c.drawRect(-0.55f - gait, -0.5f, -0.25f - gait, 0f, p);
        c.drawRect(0.6f + gait, -0.5f, 0.9f + gait, 0f, p);
        rect.set(-1.1f, -1.45f, 1.0f, -0.35f);
        c.drawOval(rect, p);
        c.drawCircle(1.05f, -1.15f, 0.4f, p);
        c.drawCircle(0.88f, -1.52f, 0.13f, p);
        c.drawCircle(1.2f, -1.5f, 0.13f, p);
        p.setColor(fog(0xFF8A6A50));
        rect.set(1.2f, -1.15f, 1.55f, -0.93f);
        c.drawOval(rect, p);
        p.setColor(0xFF111111);
        c.drawCircle(1.53f, -1.06f, 0.06f, p);
    }

    private void drawFox(Canvas c, Obj o) {
        float dir = o.vx >= 0 ? 1f : -1f;
        c.scale(dir, 1f);
        float gait = (float) Math.sin((distance + o.x * 3f) * 1.4f) * 0.1f;
        int fur = fog(0xFFD9732B);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(0.06f);
        p.setColor(fog(0xFF2A1A10));
        c.drawLine(-0.3f, -0.4f, -0.3f - gait, 0f, p);
        c.drawLine(0.3f, -0.4f, 0.3f + gait, 0f, p);
        c.drawLine(-0.2f, -0.4f, -0.2f + gait, 0f, p);
        c.drawLine(0.4f, -0.4f, 0.4f - gait, 0f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(fur);
        rect.set(-0.45f, -0.62f, 0.5f, -0.35f);
        c.drawOval(rect, p);
        // tail
        path.reset();
        path.moveTo(-0.4f, -0.52f);
        path.quadTo(-0.9f, -0.75f, -1.05f, -0.45f);
        path.quadTo(-0.8f, -0.4f, -0.4f, -0.42f);
        path.close();
        c.drawPath(path, p);
        p.setColor(fog(0xFFF5EEE4));
        c.drawCircle(-1.0f, -0.47f, 0.07f, p);
        // head
        p.setColor(fur);
        path.reset();
        path.moveTo(0.4f, -0.7f);
        path.lineTo(0.78f, -0.55f);
        path.lineTo(0.42f, -0.45f);
        path.close();
        c.drawPath(path, p);
        tri(c, 0.4f, -0.68f, 0.52f, -0.7f, 0.44f, -0.88f, fur);
    }

    private void drawGrass(Canvas c, Obj o) {
        float k = o.size;
        int[] cols = {0xFF3D7D32, 0xFF467F2E, 0xFF35702C, 0xFF4C8A34};
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(0.04f * k);
        p.setColor(fog(cols[o.color % 4]));
        c.drawLine(0f, 0f, -0.12f * k, -0.32f * k, p);
        c.drawLine(0f, 0f, 0.02f * k, -0.4f * k, p);
        c.drawLine(0f, 0f, 0.14f * k, -0.3f * k, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawFlower(Canvas c, Obj o) {
        int[] cols = {0xFFFFE15A, 0xFFFFFFFF, 0xFFE86AA6, 0xFFB98CFF};
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.03f);
        p.setColor(fog(0xFF3D7D32));
        c.drawLine(0f, 0f, 0f, -0.25f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(fog(cols[o.color % 4]));
        c.drawCircle(0f, -0.28f, 0.07f * o.size, p);
    }

    /** A tree at the side with a low limb reaching across your path at head height. */
    private void drawBranch(Canvas c, Obj o) {
        float z = o.z;
        float dT = o.x - px, dE = o.x2 - px;
        float s = f / z;
        p.setStyle(Paint.Style.FILL);

        // shadow
        p.setColor(Color.argb((int) (60 * (1f - fogT)), 0, 0, 0));
        path.reset();
        path.moveTo(projX(dT, z), projY(dT, 0f, z) - 0.1f * s);
        path.lineTo(projX(dE, z), projY(dE, 0f, z) - 0.1f * s);
        path.lineTo(projX(dE, z), projY(dE, 0f, z) + 0.1f * s);
        path.lineTo(projX(dT, z), projY(dT, 0f, z) + 0.1f * s);
        path.close();
        c.drawPath(path, p);

        // trunk
        p.setColor(fog(0xFF5E3B1E));
        path.reset();
        path.moveTo(projX(dT - 0.45f, z), projY(dT - 0.45f, 0f, z));
        path.lineTo(projX(dT + 0.45f, z), projY(dT + 0.45f, 0f, z));
        path.lineTo(projX(dT + 0.32f, z), projY(dT + 0.32f, 9f, z));
        path.lineTo(projX(dT - 0.32f, z), projY(dT - 0.32f, 9f, z));
        path.close();
        c.drawPath(path, p);

        // the low limb, sagging toward its tip
        p.setColor(fog(0xFF6E4726));
        path.reset();
        int seg = 8;
        for (int i = 0; i <= seg; i++) {
            float t = (float) i / seg;
            float d = dT + (dE - dT) * t;
            float hgt = 2.05f - 0.55f * t * t;
            float th = 0.17f - 0.1f * t;
            float x = projX(d, z), y = projY(d, hgt + th, z);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        for (int i = seg; i >= 0; i--) {
            float t = (float) i / seg;
            float d = dT + (dE - dT) * t;
            float hgt = 2.05f - 0.55f * t * t;
            float th = 0.17f - 0.1f * t;
            path.lineTo(projX(d, z), projY(d, hgt - th, z));
        }
        path.close();
        c.drawPath(path, p);

        // leaves hanging along the limb
        for (int i = 2; i <= seg; i++) {
            float t = (float) i / seg;
            float d = dT + (dE - dT) * t;
            float hgt = 2.05f - 0.55f * t * t - 0.15f;
            p.setColor(fog((i & 1) == 0 ? 0xFF3E8A3A : 0xFF4C9B42));
            c.drawCircle(projX(d, z), projY(d, hgt, z), 0.42f * s, p);
        }
        // crown
        p.setColor(fog(0xFF3A7F38));
        c.drawCircle(projX(dT, z), projY(dT, 9.5f, z), 2.6f * s, p);
        p.setColor(fog(0xFF4C9442));
        c.drawCircle(projX(dT - 0.8f, z), projY(dT - 0.8f, 8.3f, z), 1.8f * s, p);
        c.drawCircle(projX(dT + 0.9f, z), projY(dT + 0.9f, 8.6f, z), 1.7f * s, p);
    }

    private void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    private void drawSpeedLines(Canvas c) {
        float a = clamp(boost / MAX_BOOST, 0f, 1f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(2f, H * 0.004f));
        p.setColor(Color.argb((int) (40 + 110 * a), 255, 255, 255));
        float vy = horizonY(0f);
        int count = 6 + (int) (14 * a);
        for (int i = 0; i < count; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            float r1 = W * (0.32f + rnd.nextFloat() * 0.2f);
            float r2 = r1 + W * (0.06f + 0.12f * a);
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            c.drawLine(cx + ca * r1, vy + sa * r1, cx + ca * r2, vy + sa * r2, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawHud(Canvas c) {
        float pad = Math.max(W * 0.035f, H * 0.05f);
        if (state == TUTORIAL) drawTutorialHud(c);
        txt.setTextAlign(Paint.Align.LEFT);

        if (state == RUN || state == CRASH) {
            txt.setTextSize(H * 0.075f);
            c.drawText(score() + " m", pad, pad + H * 0.06f, txt);
            txt.setTextSize(H * 0.035f);
            txt.setColor(0xCCFFFFFF);
            c.drawText("BEST " + best, pad, pad + H * 0.11f, txt);
            txt.setColor(0xFFFF9FD0);
            c.drawText("\u273F " + flowersRun, pad, pad + H * 0.155f, txt);
            txt.setColor(Color.WHITE);

            txt.setTextAlign(Paint.Align.RIGHT);
            txt.setTextSize(H * 0.06f);
            c.drawText(Math.round(speed * 3.6f) + " km/h", W - pad, pad + H * 0.055f, txt);

            // sprint meter
            float bw = W * 0.18f, bh = H * 0.014f;
            float bx = W - pad - bw, by = pad + H * 0.085f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55000000);
            rect.set(bx, by, bx + bw, by + bh);
            c.drawRoundRect(rect, bh, bh, p);
            p.setColor(touching ? 0xFFFFC94A : 0xFFFFE9A8);
            rect.set(bx, by, bx + bw * clamp(boost / MAX_BOOST, 0f, 1f), by + bh);
            c.drawRoundRect(rect, bh, bh, p);
            txt.setTextSize(H * 0.03f);
            txt.setColor(0xCCFFFFFF);
            c.drawText("SPRINT", W - pad, by + bh + H * 0.04f, txt);
            txt.setColor(Color.WHITE);

            if (msgTimer > 0 && state == RUN) {
                txt.setTextAlign(Paint.Align.CENTER);
                txt.setTextSize(H * 0.055f);
                txt.setColor(Color.argb((int) (255 * clamp(msgTimer, 0f, 1f)), 255, 236, 140));
                c.drawText(msg, cx, H * 0.2f, txt);
                txt.setColor(Color.WHITE);
            }
        }

        if (state == MENU) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x66000000);
            c.drawRect(0, 0, W, H, p);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * 0.13f);
            c.drawText("HORIZON RUN", cx, H * 0.36f, txt);
            txt.setTextSize(H * 0.045f);
            c.drawText("Tilt your phone left and right to steer", cx, H * 0.52f, txt);
            c.drawText("Hold a finger on the screen to sprint", cx, H * 0.6f, txt);
            c.drawText("Dodge obstacles  \u2022  glowing snails slow you down  \u2022  flowers score points", cx, H * 0.68f, txt);
            txt.setTextSize(H * 0.032f);
            txt.setColor(0xCCFFFFFF);
            c.drawText("Tap the sun / moon to switch light and dark mode", cx, H * 0.745f, txt);
            txt.setColor(Color.WHITE);
            float pulse = 0.6f + 0.4f * (float) Math.sin(menuTime * 4f);
            txt.setColor(Color.argb((int) (255 * pulse), 255, 220, 120));
            txt.setTextSize(H * 0.06f);
            c.drawText("TAP TO START", cx, H * 0.84f, txt);
            txt.setColor(Color.WHITE);
            if (best > 0) {
                txt.setTextSize(H * 0.035f);
                c.drawText("Best: " + best + " m", cx, H * 0.93f, txt);
            }
            if (tiltSensor == null) {
                txt.setColor(0xFFFF8A80);
                txt.setTextSize(H * 0.035f);
                c.drawText("No tilt sensor found on this device", cx, H * 0.44f, txt);
                txt.setColor(Color.WHITE);
            }
        }

        if (state == CRASH && crashTimer > 0.5f) {
            float a = clamp((crashTimer - 0.5f) * 3f, 0f, 1f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (120 * a), 0, 0, 0));
            c.drawRect(0, 0, W, H, p);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.argb((int) (255 * a), 255, 255, 255));
            if (newHigh) {
                txt.setTextSize(H * 0.07f);
                c.drawText("WIPEOUT", cx, H * 0.27f, txt);
                float pulse = 1f + 0.06f * (float) Math.sin(crashTimer * 6f);
                txt.setColor(Color.argb((int) (255 * a), 255, 212, 70));
                txt.setTextSize(H * 0.12f * pulse);
                c.drawText("NEW HIGH SCORE!", cx, H * 0.43f, txt);
                txt.setColor(Color.argb((int) (255 * a), 255, 255, 255));
                txt.setTextSize(H * 0.075f);
                c.drawText(score() + " m", cx, H * 0.56f, txt);
                txt.setTextSize(H * 0.038f);
                c.drawText("Previous best: " + bestAtStart + " m   \u2022   Flowers: " + flowersRun, cx, H * 0.64f, txt);
            } else {
                txt.setTextSize(H * 0.12f);
                c.drawText("WIPEOUT", cx, H * 0.4f, txt);
                txt.setTextSize(H * 0.06f);
                c.drawText(score() + " m", cx, H * 0.53f, txt);
                txt.setTextSize(H * 0.04f);
                c.drawText("Best: " + best + " m   \u2022   Flowers: " + flowersRun, cx, H * 0.61f, txt);
            }
            if (crashTimer > 0.9f) {
                float pulse = 0.6f + 0.4f * (float) Math.sin(crashTimer * 4f);
                txt.setColor(Color.argb((int) (255 * pulse * a), 255, 220, 120));
                txt.setTextSize(H * 0.05f);
                c.drawText("TAP TO RUN AGAIN", cx, H * 0.8f, txt);
            }
            txt.setColor(Color.WHITE);
        }

        if (state == MENU || (state == CRASH && crashTimer > 0.5f)) {
            drawThemeButton(c);
            drawResetButton(c);
        }
        if (state == MENU) drawHowButton(c);

        // soft blue wash when a snail slows you down
        if (blueFlash > 0f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (blueFlash * blueFlash * 120), 90, 200, 255));
            c.drawRect(0, 0, W, H, p);
        }

        drawPickupFx(c);

        // gold flash when the old high score is beaten mid-run
        if (goldFlash > 0f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (goldFlash * goldFlash * 150), 255, 205, 60));
            c.drawRect(0, 0, W, H, p);
        }
    }

    // ================================================================================
    // Tutorial: a safe practice track that walks through every control and obstacle
    // ================================================================================

    private static final String[] TUT_TITLE = {
            "Tilt your phone LEFT",
            "Now tilt RIGHT",
            "Hold a finger anywhere to SPRINT",
            "Lift your finger to ease off",
            "Steer around the rock",
            "Low branches hang at head height",
            "Animals move!",
            "Run through the glowing snail",
            "Speed & score",
            "Options"};
    private static final String[] TUT_BODY = {
            "Hold it like a steering wheel and tip the left side down.",
            "The more you tilt, the faster you slide sideways.",
            "Keep holding — the longer you hold, the faster you go.",
            "You drop back to your base speed.",
            "Obstacles rise over the curved horizon and grow as they get close.",
            "You can't duck — steer past the end of the limb.",
            "Watch which way the deer runs and pass behind it.",
            "Snails knock your base speed back down. Steer into it!",
            "", ""};
    private static final String[][] TUT_CARD = {
            {},{},{},{},{},{},{},{},
            {"Your score is the distance you run, in metres.",
             "Your base speed keeps rising the longer you survive.",
             "Sprinting adds speed on top — let go to fall back to base speed.",
             "Flowers +20 each, +100 for a full trail of 5. Golden flowers +150.",
             "Near misses earn +25. Snails give +10 and slow you down.",
             "Pass your best score and the screen flashes gold."},
            {"Sun / moon button (start screen): light or dark mode.",
             "Reset Best Score (bottom-left, tap twice): clears your record.",
             "How To Play (bottom-right): replay this tutorial any time.",
             "Hold the phone level to run straight.",
             "Snails turn up more often as you get faster — use them!"}};

    private static final float TUT_BASE = 13f;

    private void startTutorial() {
        objs.clear();
        px = 0;
        distance = 0;
        speed = TUT_BASE;
        boost = 0;
        latVel = 0;
        baseTime = 0;
        flash = 0;
        shake = 0;
        blueFlash = 0;
        goldFlash = 0;
        msgTimer = 0;
        sceneryAcc = 0;
        decorAcc = 0;
        tutStep = 0;
        enterTutStep();
        for (float z = 2f; z < 80f; z += 1f) { spawnDecor(z); spawnDecor(z); }
        for (float z = 20f; z < SPAWN_Z; z += 7f) spawnScenery(z);
        state = TUTORIAL;
    }

    private void enterTutStep() {
        tutTimer = 0;
        tutHold = 0;
        tutWait = -1f;
        tutObj = null;
        tutDelay = 0.8f;
        tutSprinted = false;
    }

    private void finishTutorial() {
        prefs.edit().putBoolean("tutSeen", true).apply();
        resetRun();
        state = MENU;
        menuTime = 0;
    }

    /** Trees and rocks well off to the sides so the practice track isn't empty. */
    private void spawnScenery(float z) {
        int[] kinds = {PINE, PINE, OAK, ROCK};
        float side = rnd.nextBoolean() ? 1f : -1f;
        Obj o = make(kinds[rnd.nextInt(kinds.length)], px + side * (16f + rnd.nextFloat() * 45f), z);
        objs.add(o);
    }

    private void tutSay(String m, boolean good, float time) {
        tutMsg = m;
        tutMsgGood = good;
        tutMsgTimer = time;
    }

    private void tutSuccess() {
        tutSay(tutStep < 7 ? "NICE!" : "GOT IT!", true, 1.1f);
        tutWait = 1.1f;
    }

    private void updateTutorial(float dt) {
        if (tutSkip) {
            tutSkip = false;
            finishTutorial();
            return;
        }
        tutTimer += dt;
        if (tutMsgTimer > 0) tutMsgTimer -= dt;
        flash = Math.max(0f, flash - dt * 2f);
        shake = Math.max(0f, shake - dt * 1.6f);
        blueFlash = Math.max(0f, blueFlash - dt * 1.5f);

        // same running physics as a real run, at a gentle fixed base speed
        if (touching) boost = Math.min(boost + BOOST_GAIN * dt, MAX_BOOST);
        else boost = Math.max(0f, boost - BOOST_DECAY * dt);
        speed += (TUT_BASE + boost - speed) * Math.min(1f, dt * 3f);
        float latMax = 7f + speed * 0.06f;
        latVel += (steer * latMax - latVel) * Math.min(1f, dt * 8f);
        px += latVel * dt;
        roll += (-steer * 5f - roll) * Math.min(1f, dt * 6f);
        float dz = speed * dt;
        distance += dz;
        bobPhase += dz * 0.55f;
        advanceWorld(dz, dt, false);

        sceneryAcc += dz;
        while (sceneryAcc >= 7f) { sceneryAcc -= 7f; spawnScenery(SPAWN_Z); }
        decorAcc += dz;
        while (decorAcc >= 1f) { decorAcc -= 1f; spawnDecor(80f); spawnDecor(80f); }

        // waiting to move on after a success
        if (tutWait >= 0f) {
            tutWait -= dt;
            if (tutWait < 0f) {
                tutStep++;
                enterTutStep();
            }
            return;
        }

        switch (tutStep) {
            case 0:   // tilt left
                tutHold = steer < -0.4f ? tutHold + dt : 0f;
                if (tutHold > 0.5f) tutSuccess();
                break;
            case 1:   // tilt right
                tutHold = steer > 0.4f ? tutHold + dt : 0f;
                if (tutHold > 0.5f) tutSuccess();
                break;
            case 2:   // sprint
                if (boost >= 18f) tutSuccess();
                break;
            case 3:   // release
                if (!touching && boost < 2f && tutTimer > 0.4f) tutSuccess();
                break;
            case 4: case 5: case 6: case 7:
                updateTutChallenge(dt);
                break;
            default:  // info cards
                if (tapped && tutTimer > 0.5f) {
                    if (tutStep >= TUT_STEPS - 1) finishTutorial();
                    else { tutStep++; enterTutStep(); }
                }
                break;
        }
    }

    private void updateTutChallenge(float dt) {
        if (tutObj == null) {
            tutDelay -= dt;
            if (tutDelay > 0f) return;
            float z = 85f;
            // clear any scenery that drifted into the lane
            for (int i = objs.size() - 1; i >= 0; i--) {
                Obj b = objs.get(i);
                if (b.solid() && Math.abs(b.x - px) < 14f) objs.remove(i);
            }
            switch (tutStep) {
                case 4:
                    tutObj = make(ROCK, px, z);
                    tutObj.size = 1.5f;
                    tutObj.radius = 1.35f;
                    objs.add(tutObj);
                    break;
                case 5:
                    tutObj = make(BRANCH, px, z);
                    objs.add(tutObj);
                    break;
                case 6: {
                    tutObj = make(DEER, px, z);
                    // make sure it crosses right in front of the player
                    float dir = Math.signum(tutObj.vx);
                    float eta = z / Math.max(speed, 1f);
                    tutObj.x = px - tutObj.vx * eta + dir * 0.2f;
                    objs.add(tutObj);
                    break;
                }
                default:
                    spawnSnail(z);
                    tutObj = objs.get(objs.size() - 1);
                    break;
            }
            return;
        }

        if (!tutObj.judged && tutObj.z < HIT_Z) {
            tutObj.judged = true;
            boolean ok;
            if (tutObj.type == SNAIL) {
                ok = Math.abs(tutObj.x - px) < 1.3f + PLAYER_R;
                if (ok) {
                    collectSnail();
                    objs.remove(tutObj);
                }
            } else {
                ok = clearance(tutObj) >= 0f;
            }
            if (ok) {
                tutSuccess();
            } else {
                if (tutObj.type == SNAIL) {
                    tutSay("Missed it — steer right into it. Try again!", false, 2.2f);
                } else {
                    tutSay("Ouch! That would have been a crash. Try again.", false, 2.2f);
                    flash = 0.7f;
                    shake = 0.5f;
                    try {
                        Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                        if (v != null && v.hasVibrator()) {
                            v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                    } catch (Exception ignored) { }
                }
                tutObj = null;
                tutDelay = 1.6f;
            }
        }
    }

    private boolean inRect(MotionEvent e, float l, float t, float r, float b) {
        float m = H * 0.03f;
        return e.getX() >= l - m && e.getX() <= r + m && e.getY() >= t - m && e.getY() <= b + m;
    }

    // "How to play" button, bottom-right of the start screen
    private float howBtnR() { return W - Math.max(W * 0.035f, H * 0.05f); }
    private float howBtnL() { return howBtnR() - W * 0.2f; }
    private float howBtnT() { return resetBtnT(); }
    private float howBtnB() { return resetBtnB(); }

    private void pill(Canvas c, float l, float t, float r, float b, int fill, int stroke, String label) {
        rect.set(l, t, r, b);
        float rr = rect.height() * 0.5f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawRoundRect(rect, rr, rr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, H * 0.004f));
        p.setColor(stroke);
        c.drawRoundRect(rect, rr, rr, p);
        p.setStyle(Paint.Style.FILL);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(H * 0.032f);
        txt.setColor(Color.WHITE);
        c.drawText(label, rect.centerX(), rect.centerY() + H * 0.011f, txt);
    }

    private void drawHowButton(Canvas c) {
        boolean seen = prefs.getBoolean("tutSeen", false);
        int stroke = seen ? 0x99FFFFFF
                : Color.argb((int) (160 + 95 * (0.5f + 0.5f * (float) Math.sin(animTime * 4f))), 255, 212, 70);
        pill(c, howBtnL(), howBtnT(), howBtnR(), howBtnB(), 0x66000000, stroke, "HOW TO PLAY");
    }

    private void drawTutorialHud(Canvas c) {
        float pad = Math.max(W * 0.035f, H * 0.05f);
        boolean card = tutStep >= 8;

        if (!card) {
            // instruction panel at the top
            float pw = W * 0.72f;
            rect.set(cx - pw / 2, H * 0.035f, cx + pw / 2, H * 0.255f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x99000000);
            c.drawRoundRect(rect, H * 0.03f, H * 0.03f, p);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(0xFFFFD45A);
            txt.setTextSize(H * 0.03f);
            c.drawText("PRACTICE  •  STEP " + (tutStep + 1) + " OF " + TUT_STEPS, cx, H * 0.08f, txt);
            txt.setColor(Color.WHITE);
            txt.setTextSize(H * 0.06f);
            c.drawText(TUT_TITLE[tutStep], cx, H * 0.155f, txt);
            txt.setTextSize(H * 0.035f);
            txt.setColor(0xDDFFFFFF);
            c.drawText(TUT_BODY[tutStep], cx, H * 0.215f, txt);
            txt.setColor(Color.WHITE);

            if (tutStep <= 1 && tutWait < 0f) drawTiltHint(c, tutStep == 0 ? -1f : 1f);
            if (tutStep == 2 || tutStep == 3) drawSprintHint(c, tutStep == 2);
        } else {
            // full information card
            float pw = W * 0.74f, ph = H * 0.74f;
            rect.set(cx - pw / 2, H * 0.08f, cx + pw / 2, H * 0.08f + ph);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC000000);
            c.drawRoundRect(rect, H * 0.04f, H * 0.04f, p);
            float left = rect.left + pw * 0.07f;
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(0xFFFFD45A);
            txt.setTextSize(H * 0.03f);
            c.drawText("STEP " + (tutStep + 1) + " OF " + TUT_STEPS, cx, rect.top + H * 0.07f, txt);
            txt.setColor(Color.WHITE);
            txt.setTextSize(H * 0.07f);
            c.drawText(TUT_TITLE[tutStep], cx, rect.top + H * 0.15f, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setTextSize(H * 0.038f);
            String[] lines = TUT_CARD[tutStep];
            for (int i = 0; i < lines.length; i++) {
                float y = rect.top + H * 0.25f + i * H * 0.075f;
                p.setColor(0xFFFFD45A);
                c.drawCircle(left, y - H * 0.012f, H * 0.009f, p);
                c.drawText(lines[i], left + H * 0.035f, y, txt);
            }
            float pulse = 0.6f + 0.4f * (float) Math.sin(animTime * 4f);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.argb((int) (255 * pulse), 255, 220, 120));
            txt.setTextSize(H * 0.045f);
            c.drawText(tutStep >= TUT_STEPS - 1 ? "TAP TO FINISH" : "TAP TO CONTINUE",
                    cx, rect.bottom - H * 0.05f, txt);
            txt.setColor(Color.WHITE);
        }

        // speed + sprint meter, as in a real run
        txt.setTextAlign(Paint.Align.RIGHT);
        txt.setTextSize(H * 0.045f);
        if (!card) c.drawText(Math.round(speed * 3.6f) + " km/h", W - pad, H * 0.33f, txt);

        // feedback
        if (tutMsgTimer > 0f) {
            float a = clamp(tutMsgTimer * 2f, 0f, 1f);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(tutMsgGood ? H * 0.11f : H * 0.05f);
            txt.setColor(tutMsgGood ? Color.argb((int) (255 * a), 140, 255, 150)
                                    : Color.argb((int) (255 * a), 255, 150, 140));
            c.drawText(tutMsg, cx, H * 0.5f, txt);
            txt.setColor(Color.WHITE);
        }

        pill(c, resetBtnL(), resetBtnT(), resetBtnR(), resetBtnB(), 0x66000000, 0x99FFFFFF, "SKIP TUTORIAL");
    }

    /** A little phone that rocks the way you should tilt, plus big arrows. */
    private void drawTiltHint(Canvas c, float dir) {
        float x = cx, y = H * 0.6f;
        float wobble = (float) Math.sin(animTime * 3f);
        float ang = dir * (14f + 10f * (0.5f + 0.5f * wobble));
        float pw = H * 0.34f, ph = H * 0.17f;
        c.save();
        c.rotate(ang, x, y);
        rect.set(x - pw / 2, y - ph / 2, x + pw / 2, y + ph / 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xAA101828);
        c.drawRoundRect(rect, ph * 0.18f, ph * 0.18f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(3f, H * 0.006f));
        p.setColor(0xEEFFFFFF);
        c.drawRoundRect(rect, ph * 0.18f, ph * 0.18f, p);
        c.restore();

        // chevrons pointing the way
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(4f, H * 0.012f));
        for (int i = 0; i < 3; i++) {
            float phase = (animTime * 2f + i * 0.33f) % 1f;
            p.setColor(Color.argb((int) (230 * (1f - Math.abs(phase - 0.5f) * 2f)), 255, 212, 70));
            float ax = x + dir * (pw * 0.75f + i * H * 0.07f);
            float s = H * 0.04f;
            c.drawLine(ax - dir * s, y - s, ax, y, p);
            c.drawLine(ax, y, ax - dir * s, y + s, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** Pulsing fingertip and the sprint meter. */
    private void drawSprintHint(Canvas c, boolean hold) {
        float x = cx, y = H * 0.62f;
        if (hold) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 5f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(3f, H * 0.006f));
            p.setColor(Color.argb((int) (120 + 120 * pulse), 255, 212, 70));
            c.drawCircle(x, y, H * (0.06f + 0.03f * pulse), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(touching ? 0xDDFFD45A : 0x88FFFFFF);
            c.drawCircle(x, y, H * 0.045f, p);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * 0.035f);
            c.drawText(touching ? "KEEP HOLDING..." : "TOUCH & HOLD", x, y + H * 0.12f, txt);
        }
        float bw = W * 0.34f, bh = H * 0.028f;
        float bx = cx - bw / 2, by = H * 0.8f;
        rect.set(bx, by, bx + bw, by + bh);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x77000000);
        c.drawRoundRect(rect, bh, bh, p);
        p.setColor(touching ? 0xFFFFC94A : 0xFFFFE9A8);
        rect.set(bx, by, bx + bw * clamp(boost / MAX_BOOST, 0f, 1f), by + bh);
        c.drawRoundRect(rect, bh, bh, p);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(H * 0.03f);
        c.drawText("SPRINT METER", cx, by - H * 0.02f, txt);
    }

    // ---- reset-best button (bottom-left of the menu and crash screens) ------------
    private float resetBtnL() { return Math.max(W * 0.035f, H * 0.05f); }
    private float resetBtnR() { return resetBtnL() + W * 0.2f; }
    private float resetBtnB() { return H - Math.max(W * 0.02f, H * 0.04f); }
    private float resetBtnT() { return resetBtnB() - H * 0.09f; }

    private void drawResetButton(Canvas c) {
        boolean confirm = resetConfirm > 0f;
        rect.set(resetBtnL(), resetBtnT(), resetBtnR(), resetBtnB());
        p.setStyle(Paint.Style.FILL);
        p.setColor(confirm ? 0xCCB3261E : 0x66000000);
        float rr = rect.height() * 0.5f;
        c.drawRoundRect(rect, rr, rr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, H * 0.003f));
        p.setColor(0x99FFFFFF);
        c.drawRoundRect(rect, rr, rr, p);
        p.setStyle(Paint.Style.FILL);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(H * 0.032f);
        txt.setColor(Color.WHITE);
        c.drawText(confirm ? "TAP AGAIN TO RESET" : "RESET BEST SCORE",
                rect.centerX(), rect.centerY() + H * 0.011f, txt);
        if (msgTimer > 0 && state != RUN && msg.equals("BEST SCORE RESET")) {
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(0xFFFFE98C);
            c.drawText("Best score reset", resetBtnL(), resetBtnT() - H * 0.025f, txt);
            txt.setColor(Color.WHITE);
        }
    }

    // ---- snail pickup ---------------------------------------------------------------
    private void spawnSnail(float z) {
        Obj o = new Obj();
        o.type = SNAIL;
        o.z = z;
        // close enough to reach, but you have to steer for it
        float side = rnd.nextBoolean() ? 1f : -1f;
        o.x = predictX(z) + side * (2.5f + rnd.nextFloat() * 6f);
        o.size = 1f;
        o.radius = 1.3f;
        // keep it clear of obstacles so grabbing it is fair
        for (int i = objs.size() - 1; i >= 0; i--) {
            Obj b = objs.get(i);
            if (b.solid() && Math.abs(b.z - z) < 8f) {
                float lo = b.type == BRANCH ? Math.min(b.x, b.x2) : b.x - b.radius;
                float hi = b.type == BRANCH ? Math.max(b.x, b.x2) : b.x + b.radius;
                if (o.x > lo - 3f && o.x < hi + 3f) objs.remove(i);
            }
        }
        objs.add(o);
    }

    private void collectSnail() {
        float before = Math.min(BASE_SPEED + baseTime * SPEED_GAIN, MAX_BASE);
        baseTime = Math.max(0f, Math.min(baseTime, (MAX_BASE - BASE_SPEED) / SPEED_GAIN) - SNAIL_SLOW);
        float after = Math.min(BASE_SPEED + baseTime * SPEED_GAIN, MAX_BASE);
        int kmh = Math.round((before - after) * 3.6f);
        bonus += 10;
        msg = "SLOW DOWN  -" + kmh + " km/h";
        msgTimer = 1.6f;
        blueFlash = 1f;
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) { }
    }

    /** A giant snail with a glowing spiral shell, bobbing so it reads as a pickup. */
    private void drawSnail(Canvas c, Obj o) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 4f + o.x);
        float bob = (float) Math.sin(animTime * 3f + o.x) * 0.08f;
        // glow halo
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb((int) (50 + 60 * pulse), 120, 230, 255));
        c.drawCircle(0f, -0.9f + bob, 1.75f + 0.15f * pulse, p);
        p.setColor(Color.argb((int) (40 + 50 * pulse), 200, 250, 255));
        c.drawCircle(0f, -0.9f + bob, 1.25f, p);

        c.translate(0f, bob);
        // body (a long soft foot with the head raised at the front)
        int bodyCol = fog(0xFFCFD98A);
        p.setColor(bodyCol);
        path.reset();
        path.moveTo(-1.35f, 0f);
        path.quadTo(-1.4f, -0.3f, -0.9f, -0.32f);
        path.lineTo(0.75f, -0.32f);
        path.quadTo(0.95f, -0.9f, 1.25f, -0.95f);
        path.quadTo(1.55f, -0.9f, 1.5f, -0.5f);
        path.quadTo(1.45f, 0f, 1.1f, 0f);
        path.close();
        c.drawPath(path, p);
        // eye stalks
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(0.07f);
        c.drawLine(1.2f, -0.9f, 1.05f, -1.45f, p);
        c.drawLine(1.35f, -0.9f, 1.5f, -1.42f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(fog(0xFF222222));
        c.drawCircle(1.05f, -1.48f, 0.09f, p);
        c.drawCircle(1.5f, -1.45f, 0.09f, p);
        p.setColor(fog(0xFF8A9150));
        c.drawCircle(1.33f, -0.62f, 0.05f, p);   // little smile dot

        // shell
        float sx = -0.15f, sy = -0.95f, sr = 0.72f;
        p.setColor(fog(0xFFD98A3A));
        c.drawCircle(sx, sy, sr, p);
        p.setColor(fog(0xFFF0B060));
        c.drawCircle(sx - 0.12f, sy - 0.12f, sr * 0.62f, p);
        // glowing spiral
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.09f);
        p.setColor(Color.argb(255, 120 + (int) (100 * pulse), 230, 255));
        path.reset();
        int n = 48;
        for (int i = 0; i <= n; i++) {
            float t = (float) i / n;
            double a = t * Math.PI * 2 * 2.6;
            float r = sr * 0.88f * (1f - t * 0.92f);
            float x = sx + (float) Math.cos(a) * r, y = sy + (float) Math.sin(a) * r;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawPickupFx(Canvas c) {
        if (pinkFlash > 0f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (pinkFlash * pinkFlash * 70), 255, 140, 200));
            c.drawRect(0, 0, W, H, p);
        }
        if (popTimer > 0f && state == RUN) {
            float t = 1f - popTimer / 1.0f;          // 0 -> 1 over the pop's life
            float a = clamp(popTimer * 2.5f, 0f, 1f);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * (0.07f + 0.02f * (1f - t)));
            txt.setColor((popColor & 0x00FFFFFF) | ((int) (255 * a) << 24));
            c.drawText(popText, cx, H * (0.46f - 0.08f * t), txt);
            txt.setColor(Color.WHITE);
        }
    }

    // ---- score flowers ------------------------------------------------------------

    /** Five flowers in a gentle curve across the field, starting off to one side. */
    private void spawnFlowerTrail(float z) {
        trailId++;
        activeTrail = trailId;
        trailGot = 0;
        trailMissed = 0;
        float side = rnd.nextBoolean() ? 1f : -1f;
        float x0 = predictX(z) + side * (2f + rnd.nextFloat() * 6f);
        float drift = -side * (0.6f + rnd.nextFloat() * 1.2f);   // sweeps back across
        float bend = (rnd.nextFloat() - 0.5f) * 0.5f;
        int col = rnd.nextInt(4);
        for (int k = 0; k < TRAIL_LEN; k++) {
            Obj o = new Obj();
            o.type = BLOOM;
            o.z = z + k * 5.5f;
            o.x = x0 + drift * k + bend * k * k;
            o.size = 1f;
            o.radius = 1.0f;
            o.color = col;
            o.trail = trailId;
            clearAround(o.x, o.z, 2.2f, 4f);
            objs.add(o);
        }
    }

    /** A single golden flower worth a lot, placed near your path. */
    private void spawnGoldBloom(float z) {
        Obj o = new Obj();
        o.type = GOLD_BLOOM;
        o.z = z;
        o.x = predictX(z) + (rnd.nextFloat() * 2f - 1f) * 7f;
        o.size = 1.15f;
        o.radius = 1.0f;
        clearAround(o.x, o.z, 2.0f, 3f);
        objs.add(o);
    }

    /** Remove obstacles that would sit right on top of a pickup. */
    private void clearAround(float x, float z, float dx, float dz) {
        for (int i = objs.size() - 1; i >= 0; i--) {
            Obj b = objs.get(i);
            if (!b.solid() || Math.abs(b.z - z) > dz) continue;
            float lo = b.type == BRANCH ? Math.min(b.x, b.x2) : b.x - b.radius;
            float hi = b.type == BRANCH ? Math.max(b.x, b.x2) : b.x + b.radius;
            if (x > lo - dx && x < hi + dx) objs.remove(i);
        }
    }

    private void collectFlower(Obj o) {
        flowersRun++;
        pinkFlash = 1f;
        if (o.type == GOLD_BLOOM) {
            bonus += GOLD_PTS;
            popText = "GOLDEN FLOWER  +" + GOLD_PTS;
            popColor = 0xFFFFD45A;
            popTimer = 1.3f;
            goldFlash = Math.max(goldFlash, 0.6f);
            buzz(70);
            return;
        }
        bonus += FLOWER_PTS;
        popText = "+" + FLOWER_PTS;
        popColor = 0xFFFFA8D8;
        popTimer = 1.0f;
        if (o.trail == activeTrail) {
            trailGot++;
            if (trailGot == TRAIL_LEN && trailMissed == 0) {
                bonus += BOUQUET_PTS;
                popText = "BOUQUET!  +" + (FLOWER_PTS + BOUQUET_PTS);
                popColor = 0xFFFFD45A;
                popTimer = 1.4f;
                buzz(60);
            }
        }
    }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) { }
    }

    private static final int[] BLOOM_COLS = {0xFFFF6FAE, 0xFFB47CFF, 0xFFFF9A3C, 0xFF5FC8FF};

    /** A big glowing flower on a stem; gold ones sparkle. */
    private void drawBloom(Canvas c, Obj o) {
        boolean gold = o.type == GOLD_BLOOM;
        float k = o.size;
        float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 5f + o.z * 0.3f);
        float sway = (float) Math.sin(animTime * 2.2f + o.x) * 4f;
        int petal = gold ? 0xFFFFCF3A : BLOOM_COLS[o.color % BLOOM_COLS.length];
        float hy = -1.05f * k;   // flower head height

        // halo
        p.setStyle(Paint.Style.FILL);
        p.setColor((petal & 0x00FFFFFF) | ((int) ((gold ? 90 : 55) + 50 * pulse) << 24));
        c.drawCircle(0f, hy, (gold ? 0.95f : 0.7f) * k + 0.08f * pulse, p);

        c.save();
        c.rotate(sway, 0f, 0f);
        // stem and leaves
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(0.06f * k);
        p.setColor(fog(0xFF3E8F3A));
        c.drawLine(0f, 0f, 0f, hy, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(fog(0xFF4FA444));
        rect.set(0f, -0.45f * k, 0.32f * k, -0.33f * k);
        c.drawOval(rect, p);
        rect.set(-0.3f * k, -0.62f * k, 0f, -0.5f * k);
        c.drawOval(rect, p);

        // petals
        int petals = gold ? 8 : 6;
        p.setColor(fog(petal));
        float spin = gold ? animTime * 40f : 0f;
        for (int i = 0; i < petals; i++) {
            c.save();
            c.rotate(spin + i * 360f / petals, 0f, hy);
            rect.set(-0.12f * k, hy - 0.42f * k, 0.12f * k, hy - 0.06f * k);
            c.drawOval(rect, p);
            c.restore();
        }
        p.setColor(fog(gold ? 0xFFFFF4B0 : 0xFFFFE15A));
        c.drawCircle(0f, hy, 0.13f * k, p);
        c.restore();

        if (gold) {
            // twinkling sparkles
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.04f);
            for (int i = 0; i < 4; i++) {
                float ph = (animTime * 1.5f + i * 0.25f) % 1f;
                float ang = (float) (i * Math.PI / 2 + animTime);
                float r = 0.75f + 0.35f * ph;
                float sx = (float) Math.cos(ang) * r, sy = hy + (float) Math.sin(ang) * r;
                float sz = 0.12f * (1f - Math.abs(ph - 0.5f) * 2f);
                p.setColor(Color.argb((int) (255 * (1f - ph)), 255, 250, 210));
                c.drawLine(sx - sz, sy, sx + sz, sy, p);
                c.drawLine(sx, sy - sz, sx, sy + sz, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
    }

    /** Sun (light mode) or moon (dark mode); tap to switch. */
    private void drawThemeButton(Canvas c) {
        float x = themeBtnX(), y = themeBtnY(), r = themeBtnR();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        c.drawCircle(x, y, r * 1.45f, p);
        if (dark) {
            p.setColor(0xFFE8ECF5);
            c.drawCircle(x, y, r * 0.75f, p);
            p.setColor(0xFF1A2440);
            c.drawCircle(x + r * 0.38f, y - r * 0.25f, r * 0.62f, p);
        } else {
            p.setColor(0xFFFFD45A);
            c.drawCircle(x, y, r * 0.5f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(r * 0.14f);
            for (int i = 0; i < 8; i++) {
                double ang = Math.PI * i / 4;
                float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
                c.drawLine(x + ca * r * 0.72f, y + sa * r * 0.72f, x + ca * r * 0.98f, y + sa * r * 0.98f, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(H * 0.028f);
        txt.setColor(0xDDFFFFFF);
        c.drawText(dark ? "DARK" : "LIGHT", x, y + r * 1.45f + H * 0.04f, txt);
        txt.setColor(Color.WHITE);
    }

    // ---- small helpers ------------------------------------------------------------

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float wrap(float v, float span) {
        float r = v % span;
        return r < 0 ? r + span : r;
    }

    private static int darker(int c, float k) {
        return Color.rgb((int) (Color.red(c) * k), (int) (Color.green(c) * k), (int) (Color.blue(c) * k));
    }

    private static int lighter(int c, float k) {
        return Color.rgb(Color.red(c) + (int) ((255 - Color.red(c)) * k),
                Color.green(c) + (int) ((255 - Color.green(c)) * k),
                Color.blue(c) + (int) ((255 - Color.blue(c)) * k));
    }
}
