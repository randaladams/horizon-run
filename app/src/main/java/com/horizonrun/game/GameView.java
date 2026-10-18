package com.horizonrun.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.SoundPool;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
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
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable, SensorEventListener,
        Ads.Listener, Billing.Listener {

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
        boolean wallPiece;   // part of a solid rock wall
        boolean judged;      // collision / near-miss already evaluated

        boolean solid() { return type < GRASS; }
        boolean decor() { return type == GRASS || type == FLOWER; }
    }

    private static final int MENU = 0, RUN = 1, CRASH = 2, TUTORIAL = 3, SCORES = 4, STATS = 5, UPGRADE = 6;

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
    private int trailId;
    // every trail on screen keeps its own tally: trail id -> {collected, missed}
    private final java.util.HashMap<Integer, int[]> trails = new java.util.HashMap<>();
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
    private static final int TUT_STEPS = 13;
    // tutorial step numbers
    private static final int T_LEFT = 0, T_RIGHT = 1, T_SPRINT = 2, T_RELEASE = 3, T_JUMP = 4,
            T_ROCK = 5, T_WALL = 6, T_BEAR = 7, T_BRANCH = 8, T_DEER = 9, T_SNAIL = 10, T_CARD = 11;
    private final ArrayList<Obj> tutWall = new ArrayList<>();
    private boolean tutJumped;

    // ---- jumping (tap the left half of the screen) ---------------------------------
    private static final float JUMP_V = 7.5f;     // take-off speed, m/s
    private static final float GRAVITY = 18.75f;  // gives a ~1.5 m, 0.8 s jump
    private volatile boolean jumpReq;
    private float jumpH, jumpV;
    private boolean airborne;
    private boolean highJumped;                   // second tap already used this jump
    private static final float HIGH_PEAK = 3.0f;  // a high jump tops out about 3 m up
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

    // ---- sound: a short synthesized chime (no audio files needed) ------------------
    private AudioTrack chime;
    private SoundPool sfx;
    private int sFlower, sBouquet, sGolden, sJump, sSnail, sCrash, sRevive, sChime;
    private boolean soundOn = true;
    private volatile boolean toggleSound;

    // ---- ads ----------------------------------------------------------------------
    private Ads ads;
    private volatile boolean adFinished, adWasRewarded, adRewardEarned;
    private boolean waitingForAd;          // game is paused behind a full-screen ad
    private boolean pendingStartAfterAd;   // interstitial before a new run
    private boolean continuedThisRun;
    private int gamesSinceAd;
    private static final int GAMES_PER_AD = 4;
    private float invuln;                  // seconds of no-crash after continuing
    private volatile boolean continueTap;

    // ---- scoreboard ---------------------------------------------------------------
    private static final int MAX_HISTORY = 10;
    private final ArrayList<long[]> history = new ArrayList<>();   // {time, score, flowers, secs, newBest, continued}
    private boolean runLogged;           // this run already has a row (updated after a continue)
    private boolean statsPending;        // the run ended but its stats aren't added yet
    private volatile boolean scoresReq, backReq, privacyReq;
    // back button: pause the run first, end it on a second press; exit needs two presses too
    private volatile boolean paused, backInRun;
    // real buttons instead of "tap anywhere"
    private volatile boolean exitDialog;
    private volatile boolean startReq, doneReq, resumeReq, endRunReq, pauseReq, exitReq;

    // ---- holiday themes -----------------------------------------------------------
    private int holiday = Holidays.NONE;    // theme in use
    private int holidayAuto = Holidays.NONE; // what today's date says
    private int holidayPreview = -1;         // -1 = follow the date (preview switch on the scoreboard)
    private volatile boolean cycleHoliday;
    private volatile long titleDownAt;
    private final float[] wx = new float[70], wy = new float[70], wsp = new float[70], wsz = new float[70];
    private final int[] wcol = new int[70];
    private int weatherFor = -1;
    // ground cover: 0 grass, 1 snow, 2 fallen leaves, 3 brown winter grass
    private int groundCover;
    private static final int COVER_GRASS = 0, COVER_SNOW = 1, COVER_LEAVES = 2, COVER_WINTER = 3;

    // ---- full version ---------------------------------------------------------------
    private Billing billing;
    private volatile boolean fullOwned;       // bought through Google Play
    private boolean testFull;                 // hidden test switch (test builds only)
    private volatile boolean upgradeReq, buyReq, statsReq, modeReq, themePickReq, toggleTestFull;
    private volatile String billingMsg = "";
    private volatile float billingMsgTimer;
    private int upgradeReturn = MENU;         // where "Not now" goes back to
    private boolean zenMode;                  // chosen on the start screen (full version)
    private boolean zenRun;                   // the current run is a zen run
    private int themePick = -1;               // -1 follows the calendar (full version picker)
    private volatile long bestDownAt;
    private Progress progress;
    // per-run counters for stats and badges
    private int runGold, runJumps, runHighJumps, runBears, runSnails, runClose;
    private float lastWallJump = -10f;
    private String badgeToast = "";
    private float badgeToastTimer;
    private static final String[] THEME_SHORT = {
            "Normal", "New Year", "Valentine", "St Patrick", "Easter",
            "July 4th", "Halloween", "Thanksgiving", "Christmas"};

    private boolean isFull() {
        return fullOwned || (BuildConfig.ALLOW_TEST_UNLOCK && testFull);
    }

    // ---- high-score line -----------------------------------------------------------
    private static final int BESTLINE = 12;
    private final Obj bestLine = new Obj();

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
        // Ground cover: snow when it snows, fallen leaves when leaves fall.
        // (Purely visual - crashes are worked out from positions, not the ground.)
        if (groundCover == COVER_SNOW) {
            GROUND_A = dark ? 0xFF5A6678 : 0xFFEAF1F8;
            GROUND_B = dark ? 0xFF525E70 : 0xFFDCE6F0;
        } else if (groundCover == COVER_LEAVES) {
            GROUND_A = dark ? 0xFF3E2A16 : 0xFFB8782E;
            GROUND_B = dark ? 0xFF4A3219 : 0xFFA3682A;
        } else if (groundCover == COVER_WINTER) {
            GROUND_A = dark ? 0xFF3A3424 : 0xFF9C8A5C;
            GROUND_B = dark ? 0xFF423B28 : 0xFF8E7C50;
        }
        int tint = dark ? Holidays.TINT_DARK[holiday] : Holidays.TINT_LIGHT[holiday];
        if (tint != 0) {
            HAZE = mixCol(HAZE, tint, dark ? 0.45f : 0.35f);
            skyCols = new int[]{mixCol(skyCols[0], tint, 0.15f), mixCol(skyCols[1], tint, 0.3f),
                    mixCol(skyCols[2], tint, 0.45f), HAZE};
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

        bestLine.type = BESTLINE;
        progress = new Progress(prefs);
        fullOwned = prefs.getBoolean("fullOwned", false);
        testFull = prefs.getBoolean("testFull", false);
        themePick = prefs.getInt("themePick", -1);
        zenMode = prefs.getBoolean("zenMode", false);
        refreshHoliday();
        loadHistory();
        loadSounds();

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
        if (state == RUN) paused = true;
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
                if (waitingForAd) return true;
                if (exitDialog && (state == MENU || state == CRASH)) {
                    // only the dialog's buttons respond; stray taps do nothing
                    if (inRect(e, dlgExitL(), dlgBtnT(), dlgExitR(), dlgBtnB())) exitReq = true;
                    else if (inRect(e, dlgStayL(), dlgBtnT(), dlgStayR(), dlgBtnB())) exitDialog = false;
                    else if (!isFull() && inRect(e, exitUpL(), exitUpT(), exitUpR(), exitUpB())) {
                        exitDialog = false;
                        upgradeReq = true;
                    }
                    return true;
                }
                if (state == RUN) {
                    if (paused) {
                        // pause menu: only RESUME and END RUN respond
                        if (inRect(e, pResumeL(), pBtnT(), pResumeR(), pBtnB())) resumeReq = true;
                        else if (inRect(e, pEndL(), pBtnT(), pEndR(), pBtnB())) endRunReq = true;
                        return true;
                    }
                    if (inPauseBtn(e.getX(), e.getY())) {
                        pauseReq = true;
                        return true;
                    }
                }
                if (state == MENU && inRect(e, startBtnL(), startBtnT(), startBtnR(), startBtnB())) {
                    startReq = true;
                    return true;
                }
                if (state == MENU && inRect(e, exitBtnL(), exitBtnT(), exitBtnR(), exitBtnB())) {
                    exitDialog = true;
                    return true;
                }
                if (state == CRASH && crashTimer > 0.9f
                        && inRect(e, howBtnL(), howBtnT(), howBtnR(), howBtnB())) {
                    doneReq = true;
                    return true;
                }
                if (state == SCORES) {
                    if (inScoresTitle(e)) titleDownAt = e.getEventTime();
                    if (BuildConfig.ALLOW_TEST_UNLOCK && inScoresBest(e)) bestDownAt = e.getEventTime();
                    if (inRect(e, howBtnL(), howBtnT(), howBtnR(), howBtnB())) backReq = true;
                    else if (inRect(e, resetBtnL(), resetBtnT(), resetBtnR(), resetBtnB())) resetTap = true;
                    else if (inRect(e, privBtnL(), resetBtnT(), privBtnR(), resetBtnB())) statsReq = true;
                    return true;
                }
                if (state == STATS) {
                    if (inRect(e, howBtnL(), howBtnT(), howBtnR(), howBtnB())) backReq = true;
                    else if (ads != null && ads.privacyOptionsRequired()
                            && inRect(e, privBtnL(), resetBtnT(), privBtnR(), resetBtnB())) privacyReq = true;
                    return true;
                }
                if (state == UPGRADE) {
                    if (inRect(e, howBtnL(), howBtnT(), howBtnR(), howBtnB())) backReq = true;
                    else if (!isFull() && inRect(e, buyBtnL(), buyBtnT(), buyBtnR(), buyBtnB())) buyReq = true;
                    return true;
                }
                if (state == MENU && inRect(e, howBtnL(), howBtnT(), howBtnR(), howBtnB())) {
                    tutStart = true;
                    return true;
                }
                if (state == MENU && inRect(e, modeBtnL(), howBtnT(), modeBtnL() + midBtnW(), howBtnB())) {
                    modeReq = true;
                    return true;
                }
                if (state == MENU && inRect(e, pickBtnL(), howBtnT(), pickBtnL() + midBtnW(), howBtnB())) {
                    themePickReq = true;
                    return true;
                }
                if (state == CRASH && canOfferContinue()
                        && inRect(e, contBtnL(), contBtnT(), contBtnR(), contBtnB())) {
                    continueTap = true;
                    return true;
                }
                if (state == MENU || (state == CRASH && crashTimer > 0.5f)) {
                    float dx = e.getX() - themeBtnX(), dy = e.getY() - themeBtnY();
                    float r = themeBtnR() * 1.8f;
                    if (dx * dx + dy * dy < r * r) {
                        toggleTheme = true;
                        return true;
                    }
                    float sx = e.getX() - soundBtnX();
                    if (sx * sx + dy * dy < r * r) {
                        toggleSound = true;
                        return true;
                    }
                    if (inRect(e, resetBtnL(), resetBtnT(), resetBtnR(), resetBtnB())) {
                        scoresReq = true;
                        return true;
                    }
                }
                pointerDown(e);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (state == RUN && (paused || inPauseBtn(e.getX(e.getActionIndex()), e.getY(e.getActionIndex())))) {
                    if (!paused) pauseReq = true;
                    return true;
                }
                pointerDown(e);
                break;
            case MotionEvent.ACTION_UP:
                // hidden theme preview: hold the scoreboard title for a moment
                if (state == SCORES && titleDownAt > 0 && inScoresTitle(e)
                        && e.getEventTime() - titleDownAt > 600) cycleHoliday = true;
                // hidden free/full switch for testing: hold "BEST" on the scoreboard
                if (BuildConfig.ALLOW_TEST_UNLOCK && state == SCORES && bestDownAt > 0 && inScoresBest(e)
                        && e.getEventTime() - bestDownAt > 600) toggleTestFull = true;
                titleDownAt = 0;
                bestDownAt = 0;
                break;
            default:
                break;
        }
        // Sprint while any finger is held on the right half of the screen.
        int action = e.getActionMasked();
        boolean lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL;
        boolean right = false;
        if (action != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (lifting && i == e.getActionIndex()) continue;
                if (e.getX(i) >= W * 0.5f) right = true;
            }
        }
        touching = right;
        return true;
    }

    private boolean inScoresTitle(MotionEvent e) {
        float pw = Math.min(W * 0.8f, H * 1.9f);
        return e.getX() > cx - pw / 2 && e.getX() < cx && e.getY() < H * 0.17f;
    }

    private boolean inScoresBest(MotionEvent e) {
        float pw = Math.min(W * 0.8f, H * 1.9f);
        return e.getX() >= cx && e.getX() < cx + pw / 2 && e.getY() < H * 0.17f;
    }

    private void pointerDown(MotionEvent e) {
        tapped = true;
        int idx = e.getActionIndex();
        if ((state == RUN || state == TUTORIAL) && e.getX(idx) < W * 0.5f) jumpReq = true;
    }

    /** Take off if on the ground, then simple ballistic flight. */
    private void updateJump(float dt) {
        if (jumpReq) {
            jumpReq = false;
            if (!airborne) {
                airborne = true;
                highJumped = false;
                jumpV = JUMP_V;
                play(sJump, 0.7f);
                if (state == TUTORIAL) tutJumped = true;
            } else if (!highJumped && jumpH < HIGH_PEAK - 0.3f) {
                // second tap in the air: kick up so the jump peaks at about 3 m
                highJumped = true;
                jumpV = (float) Math.sqrt(2f * GRAVITY * (HIGH_PEAK - jumpH));
                play(sJump, 0.8f, 1.35f);
            }
        }
        if (airborne) {
            jumpH += jumpV * dt;
            jumpV -= GRAVITY * dt;
            if (jumpH <= 0f) {
                jumpH = 0f;
                jumpV = 0f;
                airborne = false;
                shake = Math.max(shake, 0.12f);   // little landing bump
            }
        }
    }

    /** How high you must be to sail over this obstacle (huge = can't be jumped). */
    private float clearHeight(Obj o) {
        switch (o.type) {
            case ROCK: return 0.8f * o.size;
            case FOX:  return 0.75f;
            case BEAR: return 1.6f;    // needs a high jump (double tap)
            case DEER: return 2.2f;    // antlers too - high jump only
            default:   return 999f;
        }
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

    /** Works out today's theme (or the preview) and refreshes colours if it changed. */
    private void refreshHoliday() {
        try {
            holidayAuto = Holidays.forDate(java.time.LocalDate.now());
        } catch (Exception e) {
            holidayAuto = Holidays.NONE;
        }
        int h = holidayPreview >= 0 ? holidayPreview
                : (isFull() && themePick >= 0 ? themePick : holidayAuto);
        if (h != holiday) {
            holiday = h;
            themeDirty = true;
        }
        pickGroundCover();
    }

    /** Christmas is snowy, Thanksgiving leafy; New Year's is a coin toss each run. */
    private void pickGroundCover() {
        int cover;
        int w = Holidays.WEATHER[holiday];
        if (holiday == Holidays.NEW_YEAR) cover = rnd.nextBoolean() ? COVER_SNOW : COVER_WINTER;
        else if (w == 1) cover = COVER_SNOW;
        else if (w == 2) cover = COVER_LEAVES;
        else cover = COVER_GRASS;
        if (cover != groundCover) {
            groundCover = cover;
            themeDirty = true;
        }
    }

    private static int mixCol(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private void resetRun() {
        refreshHoliday();
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
        zenRun = zenMode && isFull();
        if (zenRun) bestAtStart = 0;      // zen runs don't chase the high score
        runGold = runJumps = runHighJumps = runBears = runSnails = runClose = 0;
        statsPending = false;
        continuedThisRun = false;
        runLogged = false;
        invuln = 0;
        jumpH = 0;
        jumpV = 0;
        airborne = false;
        jumpReq = false;
        flowerTimer = 0;
        nextFlower = 3f + rnd.nextFloat() * 2f;
        trails.clear();
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
                history.clear();
                progress.reset();
                prefs.edit().putInt("best", 0).putString("history", "").apply();
                resetConfirm = 0f;
                msg = "SCORES RESET";
                msgTimer = 1.5f;
            } else {
                resetConfirm = 2.5f;
            }
        }
        if (msgTimer > 0 && state != RUN) msgTimer -= dt;

        if (toggleSound) {
            toggleSound = false;
            soundOn = !soundOn;
            prefs.edit().putBoolean("sound", soundOn).apply();
            if (soundOn) play(sFlower, 0.8f);
        }
        if (toggleTheme) {
            toggleTheme = false;
            play(sFlower, 0.8f);
            dark = !dark;
            prefs.edit().putBoolean("dark", dark).apply();
            themeDirty = true;
        }

        // ---- ads closing ----
        if (adFinished) {
            adFinished = false;
            waitingForAd = false;
            if (adWasRewarded) {
                if (adRewardEarned && state == CRASH) resumeAfterCrash();
            } else if (pendingStartAfterAd) {
                pendingStartAfterAd = false;
                gamesSinceAd = 1;
                resetRun();
                state = RUN;
            }
        }
        if (continueTap) {
            continueTap = false;
            if (canOfferContinue()) {
                if (isFull()) {
                    resumeAfterCrash();          // full version: free revive, no ad
                } else {
                    waitingForAd = true;
                    ads.showRewarded();
                }
            }
        }
        if (waitingForAd) {
            tapped = false;
            return;
        }

        if (scoresReq) {
            scoresReq = false;
            if (state == MENU || state == CRASH) {
                state = SCORES;
                resetConfirm = 0f;
                menuTime = 0;
            }
        }
        if (backReq) {
            backReq = false;
            if (state == SCORES) {
                state = MENU;
                menuTime = 0;
                bestAtStart = best;
            } else if (state == STATS) {
                state = SCORES;
            } else if (state == UPGRADE) {
                state = upgradeReturn == SCORES ? SCORES : MENU;
                menuTime = 0;
            } else if (state == TUTORIAL) {
                tutSkip = true;
            }
        }
        if (billingMsgTimer > 0) billingMsgTimer -= dt;
        if (badgeToastTimer > 0) badgeToastTimer -= dt;
        if (toggleTestFull) {
            toggleTestFull = false;
            testFull = !testFull;
            prefs.edit().putBoolean("testFull", testFull).apply();
            billingMsg = "Test switch: now playing the " + (isFull() ? "FULL" : "FREE") + " version";
            billingMsgTimer = 3f;
            refreshHoliday();
        }
        if (upgradeReq) {
            upgradeReq = false;
            if (state == MENU || state == CRASH || state == SCORES) openUpgrade();
        }
        if (buyReq) {
            buyReq = false;
            if (billing != null) billing.buy();
            else onBillingMessage("The store isn't available right now.");
        }
        if (statsReq) {
            statsReq = false;
            if (state == SCORES) {
                if (isFull()) state = STATS;
                else openUpgrade();
            }
        }
        if (modeReq) {
            modeReq = false;
            if (state == MENU) {
                if (isFull()) {
                    zenMode = !zenMode;
                    prefs.edit().putBoolean("zenMode", zenMode).apply();
                    play(sFlower, 0.8f);
                } else openUpgrade();
            }
        }
        if (themePickReq) {
            themePickReq = false;
            if (state == MENU) {
                if (isFull()) {
                    // Auto (calendar) -> each holiday -> plain flowers -> Auto
                    if (themePick == -1) themePick = 1;
                    else if (themePick == Holidays.NONE) themePick = -1;
                    else themePick = (themePick + 1) % Holidays.COUNT;
                    prefs.edit().putInt("themePick", themePick).apply();
                    holidayPreview = -1;
                    refreshHoliday();
                    play(sFlower, 0.8f);
                } else openUpgrade();
            }
        }
        if (cycleHoliday) {
            cycleHoliday = false;
            // auto -> each holiday -> plain flowers -> back to auto
            if (holidayPreview == -1) holidayPreview = 1;
            else if (holidayPreview == Holidays.NONE) holidayPreview = -1;
            else holidayPreview = (holidayPreview + 1) % Holidays.COUNT;
            refreshHoliday();
        }
        updateWeather(dt);
        if (privacyReq) {
            privacyReq = false;
            if (ads != null) ads.showPrivacyOptions();
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

        if (state == MENU || state == SCORES || state == STATS || state == UPGRADE) {
            if (state != MENU) {
                // background keeps jogging; taps are handled by the buttons only
                menuTime += dt;
                advanceWorld(6f * dt, dt, false);
                tapped = false;
                return;
            }
            menuTime += dt;
            // gentle idle jog in the background
            float dz = 6f * dt;
            advanceWorld(dz, dt, false);
            px += steer * 6f * dt;
            roll += (-steer * 5f - roll) * Math.min(1f, dt * 6f);
            if (exitReq) {
                exitReq = false;
                closeApp();
            }
            if (startReq) {
                startReq = false;
                if (menuTime > 0.3f && !exitDialog) requestNewRun();
            }
            tapped = false;
            return;
        }

        if (state == CRASH) {
            crashTimer += dt;
            shake = Math.max(0f, shake - dt * 1.6f);
            flash = Math.max(0f, flash - dt * 2f);
            if (exitReq) {
                exitReq = false;
                finishRunStats();
                closeApp();
            }
            if (doneReq && !exitDialog) {
                // back to the start screen rather than straight into a new run
                doneReq = false;
                finishRunStats();
                resetRun();
                state = MENU;
                menuTime = 0;
            }
            tapped = false;
            return;
        }

        // ---- RUN ----
        if (backInRun || pauseReq) {
            backInRun = false;
            pauseReq = false;
            paused = true;
        }
        if (paused) {
            if (endRunReq) {
                endRunReq = false;
                resumeReq = false;
                quitRun();
                return;
            }
            if (resumeReq) {
                resumeReq = false;
                paused = false;
                jumpReq = false;
                touching = false;
            }
            tapped = false;
            return;
        }
        tapped = false;
        elapsed += dt;
        if (invuln > 0f) invuln -= dt;
        baseTime += dt;
        float base = zenRun ? Math.min(BASE_SPEED + baseTime * SPEED_GAIN * 0.5f, 30f)
                : Math.min(BASE_SPEED + baseTime * SPEED_GAIN, MAX_BASE);
        if (score() >= 1000 && !zenRun) badge(Progress.KM1);
        if (score() >= 5000 && !zenRun) badge(Progress.KM5);
        if (touching) boost = Math.min(boost + BOOST_GAIN * dt, MAX_BOOST);
        else boost = Math.max(0f, boost - BOOST_DECAY * dt);
        float target = base + boost;
        speed += (target - speed) * Math.min(1f, dt * 3f);

        // Sideways speed stays human: it no longer scales up with sprinting.
        float latMax = 7f + speed * 0.06f;
        latVel += (steer * latMax - latVel) * Math.min(1f, dt * 8f);
        px += latVel * dt;
        roll += (-steer * 5f - roll) * Math.min(1f, dt * 6f);

        updateJump(dt);
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
            playChime();
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

    public void release() {
        if (sfx != null) {
            try { sfx.release(); } catch (Exception ignored) { }
            sfx = null;
        }
        if (chime != null) {
            try { chime.release(); } catch (Exception ignored) { }
            chime = null;
        }
    }

    public void setAds(Ads a) { ads = a; }

    public void setBilling(Billing b) { billing = b; }

    /** Paid players never need the ads SDK started. */
    public boolean wantsAds() { return !isFull(); }

    @Override
    public void onFullVersionOwned(boolean owned) {
        boolean was = fullOwned;
        fullOwned = owned;
        prefs.edit().putBoolean("fullOwned", owned).apply();
        if (owned && !was) {
            billingMsg = "Thank you! The full version is unlocked.";
            billingMsgTimer = 4f;
            themeDirty = true;
        }
    }

    @Override
    public void onBillingMessage(String message) {
        billingMsg = message;
        billingMsgTimer = 4f;
    }

    /** Called by the ads code (main thread) when a full-screen ad closes. */
    @Override
    public void onAdFinished(boolean wasRewardedAd, boolean rewarded) {
        adWasRewarded = wasRewardedAd;
        adRewardEarned = rewarded;
        adFinished = true;
    }

    /** Android back button: returns true if the game used it. */
    public boolean handleBack() {
        if (state == SCORES || state == TUTORIAL || state == STATS || state == UPGRADE) {
            backReq = true;
            return true;
        }
        if (state == RUN) {
            backInRun = true;          // pauses the run (the pause menu has the buttons)
            return true;
        }
        // start screen or crash screen: open (or close) the "Leave Horizon Run?" box
        exitDialog = !exitDialog;
        return true;
    }

    private boolean exitPromptShowing() { return exitDialog; }

    private void closeApp() {
        exitDialog = false;
        final android.content.Context ctx = getContext();
        if (ctx instanceof android.app.Activity) {
            post(((android.app.Activity) ctx)::finish);
        }
    }

    /** Unlocks a badge; full-version players see a little banner. */
    private void badge(int id) {
        if (state != RUN || zenRun) return;
        if (progress.unlock(id) && isFull()) {
            badgeToast = Progress.BADGE_NAME[id];
            badgeToastTimer = 2.6f;
            play(sBouquet, 0.6f, 1.2f);
        }
    }

    /** Adds a finished run to the lifetime stats (once, when the player leaves it). */
    private void finishRunStats() {
        if (!statsPending || zenRun) { statsPending = false; return; }
        statsPending = false;
        progress.addRun(score(), (long) distance, flowersRun, runGold, runJumps, runHighJumps,
                runBears, runSnails, runClose, (long) elapsed);
        badge2(Progress.FIRST_RUN);
        if (progress.distance >= 100_000) badge2(Progress.GLOBETROTTER);
    }

    /** Badge check outside a run (after it ends). */
    private void badge2(int id) {
        if (progress.unlock(id) && isFull()) {
            badgeToast = Progress.BADGE_NAME[id];
            badgeToastTimer = 2.6f;
        }
    }

    private void openUpgrade() {
        upgradeReturn = state == SCORES ? SCORES : MENU;
        if (state == CRASH) {
            finishRunStats();
            resetRun();
        }
        state = UPGRADE;
        menuTime = 0;
    }

    /** Ends the current run on purpose (back button): saves it like a crash, no crash effects. */
    private void quitRun() {
        int score = score();
        newHigh = !zenRun && score > bestAtStart && bestAtStart > 0;
        statsPending = !zenRun;
        if (!zenRun && score > best) {
            best = score;
            prefs.edit().putInt("best", best).apply();
        }
        logRun();
        finishRunStats();
        paused = false;
        resetRun();
        state = MENU;
        menuTime = 0;
    }

    // ---- chime --------------------------------------------------------------------

    /** A bright rising four-note arpeggio, built once as raw audio. */
    private void buildChime() {
        try {
            int rate = 44100;
            float[] notes = {1046.5f, 1318.5f, 1568.0f, 2093.0f};   // C6 E6 G6 C7
            int step = (int) (rate * 0.085f);
            int tail = (int) (rate * 0.45f);
            int n = step * (notes.length - 1) + tail;
            short[] pcm = new short[n];
            for (int k = 0; k < notes.length; k++) {
                int start = k * step;
                int len = k == notes.length - 1 ? tail : (int) (step * 2.2f);
                for (int i = 0; i < len && start + i < n; i++) {
                    float t = (float) i / rate;
                    float env = (float) Math.exp(-t * (k == notes.length - 1 ? 6f : 14f));
                    float attack = Math.min(1f, i / (rate * 0.004f));
                    double ph = 2 * Math.PI * notes[k] * t;
                    float v = (float) (Math.sin(ph) * 0.7 + Math.sin(ph * 2) * 0.2 + Math.sin(ph * 3) * 0.08);
                    int sum = pcm[start + i] + (int) (v * env * attack * 9000);
                    pcm[start + i] = (short) Math.max(-32767, Math.min(32767, sum));
                }
            }
            chime = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            chime.write(pcm, 0, n);
        } catch (Exception e) {
            chime = null;
        }
    }

    private void loadSounds() {
        soundOn = prefs.getBoolean("sound", true);
        try {
            sfx = new SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
            Context ctx = getContext();
            sFlower = sfx.load(ctx, R.raw.sfx_flower, 1);
            sBouquet = sfx.load(ctx, R.raw.sfx_bouquet, 1);
            sGolden = sfx.load(ctx, R.raw.sfx_golden, 1);
            sJump = sfx.load(ctx, R.raw.sfx_jump, 1);
            sSnail = sfx.load(ctx, R.raw.sfx_snail, 1);
            sCrash = sfx.load(ctx, R.raw.sfx_crash, 1);
            sRevive = sfx.load(ctx, R.raw.sfx_revive, 1);
            sChime = sfx.load(ctx, R.raw.sfx_chime, 1);
        } catch (Exception e) {
            sfx = null;
        }
    }

    private void play(int id, float vol) { play(id, vol, 1f); }

    private void play(int id, float vol, float rate) {
        if (!soundOn || sfx == null || id == 0) return;
        try { sfx.play(id, vol, vol, 1, 0, rate); } catch (Exception ignored) { }
    }

    private void playChime() {
        play(sChime, 1f);
    }

    @SuppressWarnings("unused")
    private void playOldChime() {
        AudioTrack t = chime;
        if (t == null) return;
        try {
            if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) t.stop();
            t.reloadStaticData();
            t.play();
        } catch (Exception ignored) { }
    }

    // ---- scoreboard storage ---------------------------------------------------------

    private void loadHistory() {
        history.clear();
        String raw = prefs.getString("history", "");
        if (raw.isEmpty()) return;
        for (String row : raw.split(";")) {
            String[] f = row.split(",");
            if (f.length < 6) continue;
            try {
                long[] r = new long[7];
                for (int i = 0; i < Math.min(7, f.length); i++) r[i] = Long.parseLong(f[i]);
                history.add(r);
            } catch (NumberFormatException ignored) { }
        }
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
    }

    private void saveHistory() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            long[] r = history.get(i);
            if (i > 0) sb.append(';');
            for (int k = 0; k < r.length; k++) {
                if (k > 0) sb.append(',');
                sb.append(r[k]);
            }
        }
        prefs.edit().putString("history", sb.toString()).apply();
    }

    /** Newest run first. After a continue, the same row is updated instead of adding one. */
    private void logRun() {
        long[] r = {System.currentTimeMillis(), score(), flowersRun, (long) elapsed,
                newHigh ? 1 : 0, continuedThisRun ? 1 : 0, zenRun ? 1 : 0};
        if (runLogged && !history.isEmpty()) {
            history.set(0, r);
        } else {
            history.add(0, r);
            while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        }
        runLogged = true;
        saveHistory();
    }

    // ---- continue after a crash (rewarded ad) -----------------------------------------

    private boolean canOfferContinue() {
        if (state != CRASH || crashTimer <= 0.5f || continuedThisRun || waitingForAd) return false;
        return isFull() || (ads != null && ads.rewardedReady());
    }

    private void resumeAfterCrash() {
        continuedThisRun = true;
        statsPending = false;
        if (!isFull()) gamesSinceAd = 0;   // they just watched an ad
        // clear the way ahead so you don't crash straight away again
        for (int i = objs.size() - 1; i >= 0; i--) {
            Obj o = objs.get(i);
            if (o.solid() && o.z < 60f) objs.remove(i);
        }
        boost = 0;
        speed *= 0.8f;
        invuln = 2.5f;
        shake = 0;
        flash = 0;
        state = RUN;
        msg = "KEEP GOING!";
        play(sRevive, 1f);
        msgTimer = 1.5f;
    }

    /** Start a fresh run, first showing an interstitial if one is due. */
    private void requestNewRun() {
        // gamesSinceAd = games played since the last ad. After 4, the next start shows one.
        if (!isFull() && gamesSinceAd >= GAMES_PER_AD && ads != null && ads.interstitialReady()) {
            gamesSinceAd = 0;
            waitingForAd = true;
            pendingStartAfterAd = true;
            ads.showInterstitial();
            return;
        }
        gamesSinceAd++;
        resetRun();
        state = RUN;
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
                if (gapToPlayer < 0f && jumpH >= clearHeight(o)) {
                    boolean big = o.type == BEAR || o.type == DEER;
                    if (big) runHighJumps++; else runJumps++;
                    if (o.type == BEAR) {
                        runBears++;
                        badge(Progress.BEAR1);
                        if (progress.bears + runBears >= 10) badge(Progress.BEARS10);
                    }
                    if (o.wallPiece && animTime - lastWallJump > 1f) {
                        lastWallJump = animTime;
                        badge(Progress.WALL);
                    }
                    bonus += big ? 25 : 10;
                    msg = big ? "HIGH JUMP!  +25" : "JUMP!  +10";
                    msgTimer = 0.9f;
                } else if (gapToPlayer < 0f && (invuln > 0f || zenRun)) {
                    // shielded after continuing, or zen mode: run straight through
                } else if (gapToPlayer < 0f) {
                    crash();
                    return;
                } else if (gapToPlayer < 0.7f) {
                    runClose++;
                    if (runClose >= 10) badge(Progress.CLOSE10);
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
                } else if (o.type == BLOOM) {
                    int[] t = trails.get(o.trail);
                    if (t != null) {
                        t[1]++;
                        if (t[0] + t[1] >= TRAIL_LEN) trails.remove(o.trail);
                    }
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
        if (state == MENU || state == SCORES || state == STATS || state == UPGRADE) {
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
        if (zenRun) return;
        play(sCrash, 0.8f);
        statsPending = true;
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
        logRun();
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
        // About half the rock rows are solid walls: no way around, you have to jump.
        boolean wall = kind == ROCK && rnd.nextBoolean();
        if (wall) {
            msg = "ROCK WALL AHEAD \u2014 JUMP!";
            msgTimer = 1.6f;
        }
        float half = wall ? 70f : 26f;   // walls are too wide to steer around
        for (float x = center - half; x <= center + half; x += 2.4f) {
            if (!wall && Math.abs(x - gapX) < 2.4f) continue;
            Obj o = make(kind, x + (rnd.nextFloat() - 0.5f) * 0.3f, z + (rnd.nextFloat() - 0.5f) * 1.5f);
            o.wallPiece = wall;
            if (kind == PINE) { o.size = 1f; o.radius = 1.05f; }
            else { o.size = 1.15f; o.radius = 1.05f; }
            objs.add(o);
        }
    }

    private boolean crowded(Obj n) {
        for (int i = 0; i < objs.size(); i++) {
            Obj o = objs.get(i);
            boolean pickup = o.type == BLOOM || o.type == GOLD_BLOOM || o.type == SNAIL;
            if (!o.solid() && !pickup) continue;
            float r = pickup ? 1.2f : o.radius;
            if (Math.abs(o.z - n.z) < 6f && Math.abs(o.x - n.x) < r + n.radius + 1.5f) return true;
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
        return EYE + jumpH + ((state == RUN || state == TUTORIAL) && !airborne ? (float) Math.sin(bobPhase) * 0.05f : 0f);
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

        drawWeather(c);
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
        // the glowing line across the ground at your best score
        if (state == RUN && bestAtStart > 0 && !passedBest) {
            float z = bestAtStart - score() + 0.5f;
            if (z > NEAR_Z + 0.6f && z < SPAWN_Z) {
                bestLine.z = z;
                bestLine.x = px;
                drawList.add(bestLine);
            }
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
        if (o.type == BESTLINE) { drawBestLine(c, o); return; }

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
        if (snowy()) {
            int snow = fog(dark ? 0xFFB4C0D2 : 0xFFFFFFFF);
            tri(c, -0.56f * k, -2.92f * k, 0.56f * k, -2.92f * k, 0f, -4.2f * k, snow);
            tri(c, -0.44f * k, -4.44f * k, 0.44f * k, -4.44f * k, 0f, -5.8f * k, snow);
            tri(c, -0.34f * k, -6.0f * k, 0.34f * k, -6.0f * k, 0f, -7.2f * k, snow);
        }
    }

    private static final int[] AUTUMN_OAK = {0xFFD9772A, 0xFFC4462A, 0xFFE0A21E};

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
        int base = leafy() ? AUTUMN_OAK[o.color % 3] : OAK_COLS[o.color % 3];
        p.setColor(fog(darker(base, 0.8f)));
        c.drawCircle(-1.25f * k, -3.7f * k, 1.35f * k, p);
        c.drawCircle(1.25f * k, -3.7f * k, 1.35f * k, p);
        p.setColor(fog(base));
        c.drawCircle(0f, -4.6f * k, 1.9f * k, p);
        p.setColor(fog(lighter(base, 0.25f)));
        c.drawCircle(-0.5f * k, -5.1f * k, 0.9f * k, p);
        if (snowy()) {
            p.setColor(fog(dark ? 0xFFB4C0D2 : 0xFFFFFFFF));
            rect.set(-1.45f * k, -6.6f * k, 1.45f * k, -5.75f * k);
            c.drawOval(rect, p);
            rect.set(-2.45f * k, -5.15f * k, -0.25f * k, -4.65f * k);
            c.drawOval(rect, p);
            rect.set(0.25f * k, -5.15f * k, 2.45f * k, -4.65f * k);
            c.drawOval(rect, p);
        }
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
        if (snowy()) {
            // a cap of snow following the top of the rock
            path.reset();
            int n = v.length / 2;
            for (int i = 1; i < n - 1; i++) {
                float x = v[i * 2] * k, y = v[i * 2 + 1] * k;
                if (i == 1) path.moveTo(x, y); else path.lineTo(x, y);
            }
            for (int i = n - 2; i >= 1; i--) path.lineTo(v[i * 2] * k * 0.92f, v[i * 2 + 1] * k * 0.62f);
            path.close();
            p.setColor(fog(dark ? 0xFFB4C0D2 : 0xFFFFFFFF));
            c.drawPath(path, p);
        }
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

    private boolean snowy() { return groundCover == COVER_SNOW; }
    private boolean leafy() { return groundCover == COVER_LEAVES; }
    private boolean wintry() { return groundCover == COVER_WINTER; }

    private static final int[] DRY_GRASS = {0xFFB09A62, 0xFF9E8752, 0xFFC2AC70, 0xFF8C7646};

    /** A leaf lying flat on the ground (Thanksgiving). */
    private void drawFallenLeaf(Canvas c, Obj o) {
        float k = o.size;
        p.setStyle(Paint.Style.FILL);
        p.setColor(fog(LEAF_COLS[o.color % 4]));
        c.save();
        c.rotate((o.x * 53f) % 180f);
        rect.set(-0.2f * k, -0.06f * k, 0.2f * k, 0.06f * k);
        c.drawOval(rect, p);
        c.restore();
    }

    /** A small lump of snow (Christmas). */
    private void drawSnowBump(Canvas c, Obj o) {
        if (o.color % 2 == 0) return;          // keep it sparse
        float k = o.size;
        p.setStyle(Paint.Style.FILL);
        p.setColor(fog(dark ? 0xFF7C889A : 0xFFC8D6E6));
        rect.set(-0.3f * k, -0.12f * k, 0.3f * k, 0.03f * k);
        c.drawOval(rect, p);
        p.setColor(fog(dark ? 0xFF9AA6B8 : 0xFFFFFFFF));
        rect.set(-0.24f * k, -0.15f * k, 0.18f * k, -0.03f * k);
        c.drawOval(rect, p);
    }

    private void drawGrass(Canvas c, Obj o) {
        if (snowy()) { drawSnowBump(c, o); return; }
        if (leafy()) { drawFallenLeaf(c, o); return; }
        float k = o.size;
        int[] cols = wintry() ? DRY_GRASS : new int[]{0xFF3D7D32, 0xFF467F2E, 0xFF35702C, 0xFF4C8A34};
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
        if (snowy() || wintry()) return;
        if (leafy()) { drawFallenLeaf(c, o); return; }
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
            if (zenRun) {
                txt.setTextAlign(Paint.Align.CENTER);
                txt.setTextSize(H * 0.032f);
                txt.setColor(0xCC9CFFC8);
                c.drawText("ZEN MODE  \u2022  pause to finish", cx, pad + H * 0.02f, txt);
                txt.setColor(Color.WHITE);
                txt.setTextAlign(Paint.Align.LEFT);
            }
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

            if (state == RUN && elapsed < 6f) {
                float a = clamp((6f - elapsed) / 1.5f, 0f, 1f);
                txt.setTextSize(H * 0.035f);
                txt.setColor(Color.argb((int) (220 * a), 255, 255, 255));
                txt.setTextAlign(Paint.Align.LEFT);
                c.drawText("\u25C0 TAP: JUMP", pad, H - pad * 0.8f, txt);
                txt.setTextAlign(Paint.Align.RIGHT);
                c.drawText("HOLD: SPRINT \u25B6", W - pad, H - pad * 0.8f, txt);
                txt.setColor(Color.WHITE);
            }
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
            if (holiday != Holidays.NONE) {
                float bob = (float) Math.sin(menuTime * 2.5f) * H * 0.008f;
                txt.setTextSize(H * 0.065f);
                txt.setColor(Holidays.COLOR[holiday]);
                c.drawText(Holidays.BANNER[holiday], cx, H * 0.2f + bob, txt);
                txt.setColor(Color.WHITE);
            }
            // one line under the title: version + best score
            String bestTxt = best > 0 ? "Best: " + best + " m" : "";
            txt.setTextSize(H * 0.04f);
            if (isFull()) {
                String tag = "FULL VERSION";
                String sep = best > 0 ? "   \u2022   " : "";
                float wTag = txt.measureText(tag), wSep = txt.measureText(sep), wBest = txt.measureText(bestTxt);
                float x0 = cx - (wTag + wSep + wBest) / 2f;
                txt.setTextAlign(Paint.Align.LEFT);
                txt.setColor(0xFFFFD45A);
                c.drawText(tag, x0, H * 0.45f, txt);
                txt.setColor(Color.WHITE);
                c.drawText(sep + bestTxt, x0 + wTag, H * 0.45f, txt);
                txt.setTextAlign(Paint.Align.CENTER);
            } else if (best > 0) {
                c.drawText(bestTxt, cx, H * 0.45f, txt);
            }
            // a single, short controls reminder (the tutorial covers the rest)
            txt.setTextSize(H * 0.036f);
            txt.setColor(0xCCFFFFFF);
            c.drawText("Tilt to steer  \u2022  tap LEFT to jump  \u2022  hold RIGHT to sprint", cx, H * 0.535f, txt);
            txt.setColor(Color.WHITE);
            float pulse = 0.5f + 0.5f * (float) Math.sin(menuTime * 4f);
            pill(c, startBtnL(), startBtnT(), startBtnR(), startBtnB(), 0xEE1E7A3C,
                    Color.argb((int) (150 + 105 * pulse), 140, 255, 170), "");
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * 0.065f);
            txt.setColor(Color.WHITE);
            c.drawText(zenMode && isFull() ? "START ZEN RUN" : "START",
                    cx, (startBtnT() + startBtnB()) * 0.5f + H * 0.023f, txt);
            pill(c, exitBtnL(), exitBtnT(), exitBtnR(), exitBtnB(), 0x55000000, 0x66FFFFFF, "\u2715  EXIT");
            if (tiltSensor == null) {
                txt.setColor(0xFFFF8A80);
                txt.setTextSize(H * 0.035f);
                c.drawText("No tilt sensor found on this device", cx, H * 0.13f, txt);
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
                c.drawText("Previous best: " + bestAtStart + " m   \u2022   " + Holidays.ITEMS[holiday] + ": " + flowersRun, cx, H * 0.64f, txt);
            } else {
                txt.setTextSize(H * 0.12f);
                c.drawText("WIPEOUT", cx, H * 0.4f, txt);
                txt.setTextSize(H * 0.06f);
                c.drawText(score() + " m", cx, H * 0.53f, txt);
                txt.setTextSize(H * 0.04f);
                c.drawText("Best: " + best + " m   \u2022   " + Holidays.ITEMS[holiday] + ": " + flowersRun, cx, H * 0.61f, txt);
            }
            boolean offer = canOfferContinue();
            if (offer) drawContinueButton(c);
            if (crashTimer > 0.9f) {
                pill(c, howBtnL(), howBtnT(), howBtnR(), howBtnB(), 0xAA203050, 0xCCFFFFFF,
                        offer ? "NO THANKS" : "CONTINUE  \u25B8");
            }
            txt.setColor(Color.WHITE);
        }

        if (state == MENU || (state == CRASH && crashTimer > 0.5f)) {
            // drawn faint so they stay out of the way, but still work
            float bx0 = soundBtnX() - themeBtnR() * 2f, bx1 = themeBtnX() + themeBtnR() * 2f;
            int layer = c.saveLayerAlpha(bx0, 0f, bx1, themeBtnY() + themeBtnR() * 2.6f, 105);
            drawThemeButton(c);
            drawSoundButton(c);
            c.restoreToCount(layer);
            pill(c, resetBtnL(), resetBtnT(), resetBtnR(), resetBtnB(), 0x66000000, 0x99FFFFFF, "SCORES");
        }
        if (state == SCORES) drawScoreboard(c);
        if (state == STATS) drawStats(c);
        if (state == UPGRADE) drawUpgrade(c);
        drawToasts(c);

        // shield blink after continuing
        if (state == RUN && invuln > 0f) {
            float a = (0.5f + 0.5f * (float) Math.sin(animTime * 18f)) * clamp(invuln, 0f, 1f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(H * 0.025f);
            p.setColor(Color.argb((int) (160 * a), 120, 230, 255));
            c.drawRect(0, 0, W, H, p);
            p.setStyle(Paint.Style.FILL);
        }
        if (state == MENU) {
            drawHowButton(c);
            boolean full = isFull();
            pill2(c, modeBtnL(), howBtnT(), modeBtnL() + midBtnW(), howBtnB(), "MODE",
                    full ? (zenMode ? "ZEN (no crashes)" : "NORMAL") : "ZEN MODE", !full,
                    full && zenMode ? 0xAA2E7D5A : 0x66000000);
            pill2(c, pickBtnL(), howBtnT(), pickBtnL() + midBtnW(), howBtnB(), "THEME",
                    full ? (themePick < 0 ? "AUTO (" + THEME_SHORT[holidayAuto] + ")" : THEME_SHORT[themePick])
                         : "ANY HOLIDAY", !full, 0x66000000);
        }

        // soft blue wash when a snail slows you down
        if (blueFlash > 0f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (blueFlash * blueFlash * 120), 90, 200, 255));
            c.drawRect(0, 0, W, H, p);
        }

        drawPickupFx(c);

        if (state == RUN && !paused) drawPauseButton(c);
        if (state == RUN && paused) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x99000000);
            c.drawRect(0, 0, W, H, p);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.WHITE);
            txt.setTextSize(H * 0.12f);
            c.drawText("PAUSED", cx, H * 0.38f, txt);
            txt.setTextSize(H * 0.038f);
            txt.setColor(0xCCFFFFFF);
            c.drawText(score() + " m so far", cx, H * 0.46f, txt);
            float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 4f);
            pill(c, pResumeL(), pBtnT(), pResumeR(), pBtnB(), 0xEE1E7A3C,
                    Color.argb((int) (150 + 105 * pulse), 140, 255, 170), "\u25B6  RESUME");
            pill(c, pEndL(), pBtnT(), pEndR(), pBtnB(), 0xCC5A1E1E, 0xCCFFFFFF, "END RUN");
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * 0.028f);
            txt.setColor(0x99FFFFFF);
            c.drawText(zenRun ? "Ending a zen run just finishes it" : "Ending the run saves it to your scores",
                    cx, pBtnB() + H * 0.06f, txt);
            txt.setColor(Color.WHITE);
        }
        if ((state == MENU || state == CRASH) && exitPromptShowing()) {
            float bw = W * 0.5f, bh = isFull() ? H * 0.28f : H * 0.39f;
            rect.set(cx - bw / 2, DLG_TOP * H, cx + bw / 2, DLG_TOP * H + bh);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xEE101828);
            c.drawRoundRect(rect, H * 0.03f, H * 0.03f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, H * 0.004f));
            p.setColor(0x99FFFFFF);
            c.drawRoundRect(rect, H * 0.03f, H * 0.03f, p);
            p.setStyle(Paint.Style.FILL);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(Color.WHITE);
            txt.setTextSize(H * 0.055f);
            c.drawText("Leave Horizon Run?", cx, rect.top + H * 0.085f, txt);
            pill(c, dlgExitL(), dlgBtnT(), dlgExitR(), dlgBtnB(), 0xCC5A1E1E, 0xCCFFFFFF, "EXIT");
            pill(c, dlgStayL(), dlgBtnT(), dlgStayR(), dlgBtnB(), 0xEE1E7A3C, 0xCCFFFFFF, "STAY");
            if (!isFull()) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 4f);
                pill(c, exitUpL(), exitUpT(), exitUpR(), exitUpB(), 0xDD8A6A10,
                        Color.argb((int) (150 + 105 * pulse), 255, 212, 70),
                        "GO FULL VERSION \u2014 NO ADS  " + priceText());
            }
        }

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
            "Hold the RIGHT side to SPRINT",
            "Lift your finger to ease off",
            "Tap the LEFT side to JUMP",
            "Steer around the rock",
            "Rock wall! JUMP over it",
            "Bear! Tap TWICE to HIGH JUMP",
            "Low branches hang at head height",
            "Animals move!",
            "Run through the glowing snail",
            "Speed & score",
            "Options"};
    private static final String[] TUT_BODY = {
            "Hold it like a steering wheel and tip the left side down.",
            "The more you tilt, the faster you slide sideways.",
            "Keep holding \u2014 the longer you hold, the faster you go.",
            "You drop back to your base speed.",
            "A quick tap sends you about 1.5 m into the air.",
            "Obstacles rise over the curved horizon and grow as they get close.",
            "No way around \u2014 tap the left side just before you reach it.",
            "Tap once to jump, then tap again in the air to go twice as high.",
            "You can't duck — steer past the end of the limb.",
            "Watch which way the deer runs and pass behind it.",
            "Snails knock your base speed back down. Steer into it!",
            "", ""};
    private static final String[][] TUT_CARD = {
            {},{},{},{},{},{},{},{},{},{},{},
            {"Your score is the distance you run, in metres.",
             "Your base speed keeps rising the longer you survive.",
             "Sprinting adds speed on top — let go to fall back to base speed.",
             "Flowers +20 each, +100 for a full trail of 5. Golden flowers +150.",
             "Near misses +25, jumps +10, high jumps over bears/deer +25, snails +10.",
             "A gold line on the ground marks your best \u2014 cross it for a chime!",
             "Pass your best score and the screen flashes gold."},
            {"Sun / moon button (start screen): light or dark mode.",
             "Scores (bottom-left): your last 10 runs. Reset scores is in there.",
             "How To Play (bottom-right): replay this tutorial any time.",
             "Jump rocks and foxes; double-tap to high-jump bears and deer. Trees and branches: steer.",
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
        jumpH = 0;
        jumpV = 0;
        airborne = false;
        jumpReq = false;
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
        tutJumped = false;
        tutWall.clear();
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
        tutSay(tutStep < T_SNAIL ? "NICE!" : "GOT IT!", true, 1.1f);
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
        updateJump(dt);
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
            case T_LEFT:
                tutHold = steer < -0.4f ? tutHold + dt : 0f;
                if (tutHold > 0.5f) tutSuccess();
                break;
            case T_RIGHT:
                tutHold = steer > 0.4f ? tutHold + dt : 0f;
                if (tutHold > 0.5f) tutSuccess();
                break;
            case T_SPRINT:
                if (boost >= 18f) tutSuccess();
                break;
            case T_RELEASE:
                if (!touching && boost < 2f && tutTimer > 0.4f) tutSuccess();
                break;
            case T_JUMP:
                if (tutJumped && !airborne) tutSuccess();
                break;
            case T_ROCK: case T_WALL: case T_BEAR: case T_BRANCH: case T_DEER: case T_SNAIL:
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
                case T_ROCK:
                    tutObj = make(ROCK, px, z);
                    tutObj.size = 1.5f;
                    tutObj.radius = 1.35f;
                    objs.add(tutObj);
                    break;
                case T_WALL: {
                    // wide enough that steering around it isn't an option
                    tutWall.clear();
                    float best = 999f;
                    for (float x = px - 60f; x <= px + 60f; x += 2.4f) {
                        Obj r = make(ROCK, x, z);
                        r.size = 1.15f;
                        r.radius = 1.05f;
                        objs.add(r);
                        tutWall.add(r);
                        if (Math.abs(x - px) < best) { best = Math.abs(x - px); tutObj = r; }
                    }
                    break;
                }
                case T_BEAR:
                    // a bear standing right in your path
                    tutObj = make(BEAR, px, z);
                    tutObj.x = px;
                    tutObj.vx = 0.01f;
                    objs.add(tutObj);
                    break;
                case T_BRANCH:
                    tutObj = make(BRANCH, px, z);
                    objs.add(tutObj);
                    break;
                case T_DEER: {
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
            } else if (tutStep == T_WALL) {
                boolean blocked = false;
                for (int i = 0; i < tutWall.size(); i++) {
                    Obj r = tutWall.get(i);
                    r.judged = true;
                    if (clearance(r) < 0f && jumpH < clearHeight(r)) blocked = true;
                }
                ok = !blocked;
                if (!ok) {
                    tutSay(jumpH > 0f ? "Too early or too late \u2014 jump just before it!"
                                      : "Tap the LEFT side to jump over it!", false, 2.2f);
                }
            } else {
                ok = clearance(tutObj) >= 0f || jumpH >= clearHeight(tutObj);
            }
            if (ok) {
                tutSuccess();
            } else {
                if (tutObj.type == SNAIL) {
                    tutSay("Missed it — steer right into it. Try again!", false, 2.2f);
                } else {
                    play(sCrash, 0.5f);
                    if (tutStep == T_BEAR) {
                        tutSay(jumpH > 0f ? "Higher! Tap again while you're in the air."
                                          : "Tap the LEFT side twice to high jump!", false, 2.4f);
                    } else if (tutStep != T_WALL) {
                        tutSay("Ouch! That would have been a crash. Try again.", false, 2.2f);
                    }
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
        boolean card = tutStep >= T_CARD;

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

            if (tutStep <= T_RIGHT && tutWait < 0f) drawTiltHint(c, tutStep == T_LEFT ? -1f : 1f);
            if (tutStep == T_SPRINT || tutStep == T_RELEASE) drawSprintHint(c, tutStep == T_SPRINT);
            if ((tutStep == T_JUMP || tutStep == T_WALL || tutStep == T_BEAR) && tutWait < 0f) drawJumpHint(c);
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

    /** Tints one half of the screen to show where to touch. */
    private void halfHighlight(Canvas c, boolean left) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 4f);
        float l = left ? 0f : W * 0.5f, r = left ? W * 0.5f : W;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb((int) (25 + 30 * pulse), 255, 212, 70));
        c.drawRect(l, H * 0.28f, r, H, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(3f, H * 0.006f));
        p.setColor(Color.argb((int) (90 + 100 * pulse), 255, 212, 70));
        c.drawLine(W * 0.5f, H * 0.3f, W * 0.5f, H * 0.97f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Left-half highlight with a bouncing figure arrow: tap here to jump. */
    private void drawJumpHint(Canvas c) {
        halfHighlight(c, true);
        float x = W * 0.25f, y = H * 0.62f;
        float bounce = Math.abs((float) Math.sin(animTime * 3.5f)) * H * 0.06f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Math.max(4f, H * 0.012f));
        p.setColor(0xEEFFD45A);
        float s = H * 0.05f;
        float ay = y - bounce;
        c.drawLine(x - s, ay, x, ay - s, p);
        c.drawLine(x, ay - s, x + s, ay, p);
        c.drawLine(x, ay - s, x, ay + s * 1.2f, p);
        p.setStyle(Paint.Style.FILL);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(H * 0.04f);
        txt.setColor(Color.WHITE);
        c.drawText(tutStep == T_BEAR ? "TAP, TAP AGAIN!" : "TAP HERE TO JUMP", x, y + H * 0.13f, txt);
    }

    /** Pulsing fingertip and the sprint meter. */
    private void drawSprintHint(Canvas c, boolean hold) {
        float x = W * 0.75f, y = H * 0.62f;
        if (hold) {
            halfHighlight(c, false);
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
            c.drawText(touching ? "KEEP HOLDING..." : "HOLD HERE", x, y + H * 0.12f, txt);
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

    // ---- start / exit / pause buttons ------------------------------------------------
    private static final float DLG_TOP = 0.28f;
    private float startBtnL() { return cx - W * 0.17f; }
    private float startBtnR() { return cx + W * 0.17f; }
    private float startBtnT() { return H * 0.61f; }
    private float startBtnB() { return H * 0.74f; }
    private float exitBtnL() { return Math.max(W * 0.035f, H * 0.05f); }
    private float exitBtnR() { return exitBtnL() + W * 0.11f; }
    private float exitBtnT() { return Math.max(W * 0.02f, H * 0.04f); }
    private float exitBtnB() { return exitBtnT() + H * 0.085f; }
    private float dlgBtnT() { return DLG_TOP * H + H * 0.13f; }
    private float dlgBtnB() { return DLG_TOP * H + H * 0.225f; }
    private float dlgExitL() { return cx - W * 0.2f; }
    private float dlgExitR() { return cx - W * 0.02f; }
    private float dlgStayL() { return cx + W * 0.02f; }
    private float dlgStayR() { return cx + W * 0.2f; }
    private float pBtnT() { return H * 0.53f; }
    private float pBtnB() { return H * 0.64f; }
    private float pResumeL() { return cx - W * 0.22f; }
    private float pResumeR() { return cx - W * 0.02f; }
    private float pEndL() { return cx + W * 0.02f; }
    private float pEndR() { return cx + W * 0.22f; }
    private float pauseX() { return cx; }
    private float pauseY() { return H - Math.max(W * 0.02f, H * 0.04f) - H * 0.05f; }
    private float pauseR() { return H * 0.045f; }

    private boolean inPauseBtn(float x, float y) {
        float dx = x - pauseX(), dy = y - pauseY(), r = pauseR() * 1.9f;
        return dx * dx + dy * dy < r * r;
    }

    /** Faint pause button at the top centre during a run. */
    private void drawPauseButton(Canvas c) {
        float x = pauseX(), y = pauseY(), r = pauseR();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x44000000);
        c.drawCircle(x, y, r * 1.25f, p);
        p.setColor(0x99FFFFFF);
        c.drawRect(x - r * 0.42f, y - r * 0.5f, x - r * 0.14f, y + r * 0.5f, p);
        c.drawRect(x + r * 0.14f, y - r * 0.5f, x + r * 0.42f, y + r * 0.5f, p);
    }

    // ---- start-screen middle buttons, upgrade buttons ---------------------------------
    private float midBtnW() { return W * 0.2f; }
    private float midGap() { return (howBtnL() - resetBtnR() - 2 * midBtnW()) / 3f; }
    private float modeBtnL() { return resetBtnR() + midGap(); }
    private float pickBtnL() { return modeBtnL() + midBtnW() + midGap(); }
    private float exitUpL() { return cx - W * 0.21f; }
    private float exitUpR() { return cx + W * 0.21f; }
    private float exitUpT() { return DLG_TOP * H + H * 0.26f; }
    private float exitUpB() { return DLG_TOP * H + H * 0.345f; }
    private float buyBtnL() { return cx - W * 0.16f; }
    private float buyBtnR() { return cx + W * 0.16f; }
    private float buyBtnT() { return resetBtnT() - H * 0.02f; }
    private float buyBtnB() { return resetBtnB(); }

    private String priceText() {
        String p = billing != null ? billing.price() : null;
        return p != null ? p : "$1.99";
    }

    /** Two-line pill (small caption + value) with an optional gold "FULL" tag. */
    private void pill2(Canvas c, float l, float t, float r, float b, String top, String value,
                       boolean locked, int fill) {
        pill(c, l, t, r, b, fill, 0x99FFFFFF, "");
        txt.setTextAlign(Paint.Align.CENTER);
        float midY = (t + b) * 0.5f;
        if (top.isEmpty()) {
            txt.setTextSize(H * 0.03f);
            txt.setColor(Color.WHITE);
            c.drawText(value, (l + r) * 0.5f, midY + H * 0.011f, txt);
        } else {
            txt.setTextSize(H * 0.022f);
            txt.setColor(0xAAFFFFFF);
            c.drawText(top, (l + r) * 0.5f, midY - H * 0.008f, txt);
            txt.setTextSize(H * 0.028f);
            txt.setColor(Color.WHITE);
            c.drawText(value, (l + r) * 0.5f, midY + H * 0.026f, txt);
        }
        if (locked) {
            float tw = H * 0.085f, th = H * 0.034f;
            rect.set(r - tw * 0.85f, t - th * 0.55f, r + tw * 0.15f, t + th * 0.45f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFC23A);
            c.drawRoundRect(rect, th * 0.5f, th * 0.5f, p);
            txt.setTextSize(H * 0.02f);
            txt.setColor(0xFF3A2A00);
            c.drawText("FULL", rect.centerX(), rect.centerY() + H * 0.007f, txt);
        }
        txt.setColor(Color.WHITE);
    }

    /** Badge banner during a run + store messages anywhere. */
    private void drawToasts(Canvas c) {
        if (badgeToastTimer > 0f && isFull()) {
            float a = clamp(badgeToastTimer * 2f, 0f, 1f);
            float bw = W * 0.36f, bh = H * 0.1f, top = H * 0.06f;
            rect.set(cx - bw / 2, top, cx + bw / 2, top + bh);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (220 * a), 40, 30, 0));
            c.drawRoundRect(rect, bh * 0.5f, bh * 0.5f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, H * 0.004f));
            p.setColor(Color.argb((int) (255 * a), 255, 212, 70));
            c.drawRoundRect(rect, bh * 0.5f, bh * 0.5f, p);
            p.setStyle(Paint.Style.FILL);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * 0.024f);
            txt.setColor(Color.argb((int) (220 * a), 255, 212, 70));
            c.drawText("BADGE UNLOCKED", cx, top + bh * 0.38f, txt);
            txt.setTextSize(H * 0.038f);
            txt.setColor(Color.argb((int) (255 * a), 255, 255, 255));
            c.drawText(badgeToast, cx, top + bh * 0.8f, txt);
            txt.setColor(Color.WHITE);
        }
        if (billingMsgTimer > 0f && !billingMsg.isEmpty()) {
            float a = clamp(billingMsgTimer * 2f, 0f, 1f);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * 0.036f);
            float w = txt.measureText(billingMsg) + H * 0.08f;
            rect.set(cx - w / 2, H * 0.06f, cx + w / 2, H * 0.14f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (220 * a), 16, 24, 40));
            c.drawRoundRect(rect, H * 0.04f, H * 0.04f, p);
            txt.setColor(Color.argb((int) (255 * a), 255, 233, 140));
            c.drawText(billingMsg, cx, H * 0.113f, txt);
            txt.setColor(Color.WHITE);
        }
    }

    private void panel(Canvas c, float l, float t, float r, float b) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x99000000);
        c.drawRect(0, 0, W, H, p);
        rect.set(l, t, r, b);
        p.setColor(dark ? 0xE00A1226 : 0xE0142A4A);
        c.drawRoundRect(rect, H * 0.035f, H * 0.035f, p);
    }

    /** Full-version pitch with the buy button. */
    private void drawUpgrade(Canvas c) {
        float pw = Math.min(W * 0.7f, H * 1.7f);
        float l = cx - pw / 2, r = cx + pw / 2, t = H * 0.05f, b = buyBtnT() - H * 0.03f;
        panel(c, l, t, r, b);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setColor(0xFFFFD45A);
        txt.setTextSize(H * 0.03f);
        c.drawText("HORIZON RUN", cx, t + H * 0.07f, txt);
        txt.setColor(Color.WHITE);
        txt.setTextSize(H * 0.065f);
        c.drawText(isFull() ? "You have the Full Version!" : "Full Version", cx, t + H * 0.15f, txt);
        String[] perks = {
                "No ads \u2014 ever",
                "Free revive every run, no ad to watch",
                "Holiday themes any time of year",
                "Zen mode: no crashes, just run and collect",
                "Stats, all-time top 10 and 12 badges",
                "Keeps all your scores \u2014 nothing is erased"};
        txt.setTextAlign(Paint.Align.LEFT);
        txt.setTextSize(H * 0.038f);
        float x = l + pw * 0.14f;
        for (int i = 0; i < perks.length; i++) {
            float y = t + H * 0.25f + i * H * 0.075f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(H * 0.008f);
            p.setColor(0xFF8CFF9E);
            c.drawLine(x - H * 0.06f, y - H * 0.014f, x - H * 0.045f, y, p);
            c.drawLine(x - H * 0.045f, y, x - H * 0.018f, y - H * 0.032f, p);
            p.setStyle(Paint.Style.FILL);
            txt.setColor(Color.WHITE);
            c.drawText(perks[i], x, y, txt);
        }
        if (!isFull()) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 4f);
            pill(c, buyBtnL(), buyBtnT(), buyBtnR(), buyBtnB(), 0xEE1E7A3C,
                    Color.argb((int) (150 + 105 * pulse), 140, 255, 170), "UPGRADE  " + priceText());
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * 0.026f);
            txt.setColor(0xAAFFFFFF);
            c.drawText("One-time purchase through Google Play", cx, b - H * 0.02f, txt);
        }
        pill(c, howBtnL(), howBtnT(), howBtnR(), howBtnB(), 0x66000000, 0x99FFFFFF,
                isFull() ? "BACK" : "NOT NOW");
        txt.setColor(Color.WHITE);
    }

    private static String km(long metres) {
        return String.format(Locale.US, "%.1f km", metres / 1000.0);
    }

    /** Lifetime stats, all-time top 10 and badges (full version). */
    private void drawStats(Canvas c) {
        float l = W * 0.04f, r = W * 0.96f, t = H * 0.05f, b = resetBtnT() - H * 0.03f;
        panel(c, l, t, r, b);
        float w = r - l;
        float c1 = l + w * 0.03f, c2 = l + w * 0.36f, c3 = l + w * 0.58f;
        txt.setTextAlign(Paint.Align.LEFT);
        txt.setTextSize(H * 0.042f);
        txt.setColor(Color.WHITE);
        c.drawText("STATS", c1, t + H * 0.075f, txt);
        c.drawText("TOP 10", c2, t + H * 0.075f, txt);
        c.drawText("BADGES  " + progress.badgeCount() + "/" + Progress.BADGES, c3, t + H * 0.075f, txt);

        Progress g = progress;
        String[][] rows = {
                {"Runs", String.valueOf(g.runs)},
                {"Distance", km(g.distance)},
                {"Items collected", String.valueOf(g.items)},
                {"Golden items", String.valueOf(g.golden)},
                {"Jumps", String.valueOf(g.jumps)},
                {"High jumps", String.valueOf(g.highJumps)},
                {"Bears cleared", String.valueOf(g.bears)},
                {"Snails", String.valueOf(g.snails)},
                {"Close calls", String.valueOf(g.closeCalls)},
                {"Longest run", mmss(g.longestSecs)},
                {"Most items, one run", String.valueOf(g.mostItems)}};
        float rowH = (b - t - H * 0.14f) / rows.length;
        float fs = Math.min(H * 0.032f, rowH * 0.62f);
        txt.setTextSize(fs);
        for (int i = 0; i < rows.length; i++) {
            float y = t + H * 0.135f + i * rowH;
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(0xCCFFFFFF);
            c.drawText(rows[i][0], c1, y, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            txt.setColor(Color.WHITE);
            c.drawText(rows[i][1], c2 - w * 0.04f, y, txt);
        }

        float topRow = (b - t - H * 0.14f) / 10f;
        txt.setTextSize(Math.min(H * 0.032f, topRow * 0.62f));
        if (g.top.isEmpty()) {
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(0x99FFFFFF);
            c.drawText("No runs yet", c2, t + H * 0.135f, txt);
        }
        for (int i = 0; i < g.top.size(); i++) {
            float y = t + H * 0.135f + i * topRow;
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(i == 0 ? 0xFFFFD45A : 0xCCFFFFFF);
            c.drawText((i + 1) + ".", c2, y, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            c.drawText(g.top.get(i)[0] + " m", c3 - w * 0.04f, y, txt);
        }

        // badges: 2 columns x 6 rows
        float bw = (r - w * 0.03f - c3) / 2f;
        float bh = (b - t - H * 0.12f) / 6f;
        for (int i = 0; i < Progress.BADGES; i++) {
            float x = c3 + (i % 2) * bw, y = t + H * 0.11f + (i / 2) * bh;
            boolean got = g.has(i);
            float cr = Math.min(bh * 0.28f, H * 0.03f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(got ? 0xFFFFC23A : 0x33FFFFFF);
            c.drawCircle(x + cr, y + bh * 0.4f, cr, p);
            if (got) {
                starPath(x + cr, y + bh * 0.4f, cr * 0.7f, 5, 0.45f, 0f);
                p.setColor(0xFF5A3A00);
                c.drawPath(path, p);
            }
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setTextSize(Math.min(H * 0.028f, bh * 0.3f));
            txt.setColor(got ? Color.WHITE : 0x88FFFFFF);
            c.drawText(Progress.BADGE_NAME[i], x + cr * 2.5f, y + bh * 0.36f, txt);
            txt.setTextSize(Math.min(H * 0.021f, bh * 0.24f));
            txt.setColor(got ? 0xBBFFFFFF : 0x66FFFFFF);
            c.drawText(Progress.BADGE_DESC[i], x + cr * 2.5f, y + bh * 0.66f, txt);
        }
        txt.setColor(Color.WHITE);

        pill(c, howBtnL(), howBtnT(), howBtnR(), howBtnB(), 0x66000000, 0x99FFFFFF, "BACK");
        if (ads != null && ads.privacyOptionsRequired()) {
            pill(c, privBtnL(), resetBtnT(), privBtnR(), resetBtnB(), 0x66000000, 0x99FFFFFF, "PRIVACY");
        }
    }

    // continue button (crash screen) and privacy button (scoreboard)
    private float contBtnL() { return cx - W * 0.2f; }
    private float contBtnR() { return cx + W * 0.2f; }
    private float contBtnT() { return H * 0.69f; }
    private float contBtnB() { return H * 0.79f; }
    private float privBtnL() { return cx - W * 0.12f; }
    private float privBtnR() { return cx + W * 0.12f; }

    private void drawContinueButton(Canvas c) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 4f);
        pill(c, contBtnL(), contBtnT(), contBtnR(), contBtnB(), 0xDD1E7A3C,
                Color.argb((int) (150 + 105 * pulse), 140, 255, 170), "");
        // little play triangle + label
        float ty = (contBtnT() + contBtnB()) * 0.5f, tx = contBtnL() + H * 0.07f, ts = H * 0.022f;
        path.reset();
        path.moveTo(tx - ts * 0.6f, ty - ts);
        path.lineTo(tx + ts, ty);
        path.lineTo(tx - ts * 0.6f, ty + ts);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawPath(path, p);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(H * 0.04f);
        txt.setColor(Color.WHITE);
        c.drawText(isFull() ? "KEEP RUNNING (FREE)" : "WATCH AD TO KEEP RUNNING",
                cx + H * 0.03f, ty + H * 0.014f, txt);
    }

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("MMM d", Locale.getDefault());

    private static String when(long t) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(t);
        Date d = new Date(t);
        boolean sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR);
        int dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR);
        if (sameYear && dayDiff == 0) return "Today " + TIME_FMT.format(d);
        if (sameYear && dayDiff == 1) return "Yesterday " + TIME_FMT.format(d);
        return DATE_FMT.format(d) + "  " + TIME_FMT.format(d);
    }

    private static String mmss(long secs) {
        return (secs / 60) + ":" + (secs % 60 < 10 ? "0" : "") + (secs % 60);
    }

    /** Table of the last ten runs, newest first. */
    private void drawScoreboard(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x99000000);
        c.drawRect(0, 0, W, H, p);

        float pw = Math.min(W * 0.8f, H * 1.9f);
        float left = cx - pw / 2, right = cx + pw / 2;
        float top = H * 0.05f, bottom = resetBtnT() - H * 0.03f;
        rect.set(left, top, right, bottom);
        p.setColor(dark ? 0xE00A1226 : 0xE0142A4A);
        c.drawRoundRect(rect, H * 0.035f, H * 0.035f, p);

        txt.setTextAlign(Paint.Align.LEFT);
        txt.setColor(Color.WHITE);
        txt.setTextSize(H * 0.06f);
        c.drawText("LAST 10 RUNS", left + pw * 0.04f, top + H * 0.08f, txt);
        txt.setTextAlign(Paint.Align.RIGHT);
        txt.setTextSize(H * 0.04f);
        txt.setColor(0xFFFFD45A);
        c.drawText("BEST  " + best + " m", right - pw * 0.04f, top + H * 0.08f, txt);

        // columns: #  when  score  flowers  time
        float[] colX = {left + pw * 0.06f, left + pw * 0.12f, left + pw * 0.66f, left + pw * 0.8f, right - pw * 0.04f};
        float rowH = (bottom - top - H * 0.17f) / (MAX_HISTORY + 1);
        float y = top + H * 0.15f;
        txt.setTextSize(Math.min(H * 0.032f, rowH * 0.62f));
        txt.setColor(0xAAFFFFFF);
        txt.setTextAlign(Paint.Align.RIGHT);
        c.drawText("#", colX[0], y, txt);
        txt.setTextAlign(Paint.Align.LEFT);
        c.drawText("WHEN", colX[1], y, txt);
        txt.setTextAlign(Paint.Align.RIGHT);
        c.drawText("SCORE", colX[2], y, txt);
        c.drawText("\u273F", colX[3], y, txt);
        c.drawText("TIME", colX[4], y, txt);
        p.setColor(0x44FFFFFF);
        c.drawRect(left + pw * 0.03f, y + rowH * 0.3f, right - pw * 0.03f, y + rowH * 0.3f + Math.max(1f, H * 0.002f), p);

        if (history.isEmpty()) {
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(0xCCFFFFFF);
            txt.setTextSize(H * 0.045f);
            c.drawText("No runs yet \u2014 go run!", cx, (top + bottom) * 0.55f, txt);
        }
        for (int i = 0; i < history.size(); i++) {
            long[] r = history.get(i);
            float ry = y + rowH * (i + 1);
            boolean isBest = r[1] == best && best > 0 && !(r.length > 6 && r[6] == 1);
            if (i % 2 == 0) {
                p.setColor(0x14FFFFFF);
                c.drawRect(left + pw * 0.03f, ry - rowH * 0.72f, right - pw * 0.03f, ry + rowH * 0.28f, p);
            }
            int col = isBest ? 0xFFFFD45A : Color.WHITE;
            txt.setColor(col);
            txt.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(i + 1), colX[0], ry, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            String tag = (r[4] == 1 ? "  \u2605" : "") + (r[5] == 1 ? "  \u25B6" : "")
                    + (r.length > 6 && r[6] == 1 ? "  ZEN" : "");
            c.drawText(when(r[0]) + tag, colX[1], ry, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            c.drawText(r[1] + " m", colX[2], ry, txt);
            txt.setColor(isBest ? col : 0xFFFF9FD0);
            c.drawText(String.valueOf(r[2]), colX[3], ry, txt);
            txt.setColor(col);
            c.drawText(mmss(r[3]), colX[4], ry, txt);
        }
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(H * 0.026f);
        txt.setColor(0x99FFFFFF);
        c.drawText("\u2605 new high score     \u25B6 continued after an ad     gold = your best",
                cx, bottom - H * 0.025f, txt);
        if (holidayPreview >= 0) {
            txt.setColor(0xFFFFD45A);
            c.drawText("Theme preview: " + Holidays.NAME[holiday] + "  (hold the title to change)",
                    cx, top + H * 0.125f, txt);
        }
        txt.setColor(Color.WHITE);

        boolean confirm = resetConfirm > 0f;
        pill(c, resetBtnL(), resetBtnT(), resetBtnR(), resetBtnB(), confirm ? 0xCCB3261E : 0x66000000,
                0x99FFFFFF, confirm ? "TAP AGAIN TO RESET" : "RESET SCORES");
        pill(c, howBtnL(), howBtnT(), howBtnR(), howBtnB(), 0x66000000, 0x99FFFFFF, "BACK");
        pill2(c, privBtnL(), resetBtnT(), privBtnR(), resetBtnB(), "", "STATS & BADGES", !isFull(), 0x66000000);
        if (BuildConfig.ALLOW_TEST_UNLOCK) {
            txt.setTextAlign(Paint.Align.RIGHT);
            txt.setTextSize(H * 0.024f);
            txt.setColor(0x88FFFFFF);
            c.drawText("test build: hold BEST to switch \u2022 now " + (isFull() ? "FULL" : "FREE"),
                    right - pw * 0.04f, top + H * 0.125f, txt);
            txt.setColor(Color.WHITE);
        }
        if (msgTimer > 0 && msg.equals("SCORES RESET")) {
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(H * 0.05f);
            txt.setColor(0xFFFFE98C);
            c.drawText("Scores reset", cx, (top + bottom) * 0.5f, txt);
            txt.setColor(Color.WHITE);
        }
    }

    /** A glowing gold line across the ground at your best score, with posts and a banner. */
    private void drawBestLine(Canvas c, Obj o) {
        float z = o.z;
        float e = eye();
        float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 5f);
        int steps = 40;
        float zf = z + 0.5f, zn = Math.max(NEAR_Z + 0.05f, z - 0.5f);
        // ground stripe following the planet's curve
        path.reset();
        for (int i = 0; i <= steps; i++) {
            float u = -uMax + 2f * uMax * i / steps;
            float y = cy + f * (e / zf + zf * (1f + SIDE_CURVE * u * u) / (2f * RADIUS));
            if (i == 0) path.moveTo(cx + f * u, y); else path.lineTo(cx + f * u, y);
        }
        for (int i = steps; i >= 0; i--) {
            float u = -uMax + 2f * uMax * i / steps;
            float y = cy + f * (e / zn + zn * (1f + SIDE_CURVE * u * u) / (2f * RADIUS));
            path.lineTo(cx + f * u, Math.min(y, H * 4f));
        }
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb((int) (170 + 70 * pulse), 255, 205, 60));
        c.drawPath(path, p);

        // a soft curtain of light rising from the line
        float s = f / z;
        float gy = projY(0f, 0f, z), ty = projY(0f, 2.2f, z);
        p.setShader(new LinearGradient(0, gy, 0, ty,
                Color.argb((int) (90 + 50 * pulse), 255, 215, 90), 0x00FFD45A, Shader.TileMode.CLAMP));
        c.drawRect(-W * 0.2f, ty, W * 1.2f, gy, p);
        p.setShader(null);

        // glowing posts every 4 m
        float x0 = (float) Math.floor((px - 60f) / 4f) * 4f;
        for (float x = x0; x <= px + 60f; x += 4f) {
            float dx = x - px;
            if (Math.abs(dx / z) > uMax + 0.2f) continue;
            float bx = projX(dx, z), by = projY(dx, 0f, z), tyy = projY(dx, 1.3f, z);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Math.max(2f, 0.08f * s));
            p.setColor(0xFFFFE9A0);
            c.drawLine(bx, by, bx, tyy, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (150 + 100 * pulse), 255, 230, 120));
            c.drawCircle(bx, tyy, Math.max(2f, 0.16f * s), p);
        }

        // banner
        float ts = clamp(0.55f * s, H * 0.02f, H * 0.11f);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(ts);
        txt.setColor(0xFFFFE07A);
        c.drawText("BEST  " + bestAtStart, cx, projY(0f, 2.6f, z), txt);
        txt.setColor(Color.WHITE);
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
        play(sSnail, 0.8f);
        if (state == RUN) {
            runSnails++;
            if (runSnails >= 5 && !zenRun) badge(Progress.SNAILS5);
        }
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
        trails.put(trailId, new int[2]);
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
            popText = Holidays.GOLD[holiday] + "  +" + GOLD_PTS;
            popColor = 0xFFFFD45A;
            popTimer = 1.3f;
            goldFlash = Math.max(goldFlash, 0.6f);
            buzz(70);
            play(sGolden, 1f);
            runGold++;
            if (runGold >= 3 && !zenRun) badge(Progress.GOLD3);
            if (progress.items + flowersRun >= 500 && !zenRun) badge(Progress.FLORIST);
            return;
        }
        bonus += FLOWER_PTS;
        popText = "+" + FLOWER_PTS;
        popColor = 0xFFFFA8D8;
        popTimer = 1.0f;
        int[] t = trails.get(o.trail);
        if (t != null) {
            t[0]++;
            popText = "+" + FLOWER_PTS + "   " + t[0] + "/" + TRAIL_LEN;
            if (t[0] + t[1] >= TRAIL_LEN) trails.remove(o.trail);
            if (t[0] == TRAIL_LEN) {
                bonus += BOUQUET_PTS;
                popText = Holidays.TRAIL[holiday] + "  +" + (FLOWER_PTS + BOUQUET_PTS);
                popColor = 0xFFFFD45A;
                popTimer = 1.4f;
                buzz(60);
                play(sBouquet, 1f);
                if (!zenRun) badge(Progress.BOUQUET);
                return;
            }
        }
        play(sFlower, 0.8f);
        if (progress.items + flowersRun >= 500 && !zenRun) badge(Progress.FLORIST);
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
        if (holiday != Holidays.NONE) {
            drawHolidayItem(c, o);
            return;
        }
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

    // ================================================================================
    // Holiday collectibles (drawn in metres, ground at y = 0, up is negative y)
    // ================================================================================

    private static final int[][] HOL_COLS = {
            {0xFFFF6FAE, 0xFFB47CFF, 0xFFFF9A3C, 0xFF5FC8FF},   // (normal, unused here)
            {0xFF9B5CFF, 0xFFFF5FA2, 0xFF3FC1FF, 0xFFFFC23A},   // new year poppers
            {0xFFE8234A, 0xFFFF6FA0, 0xFFFF3D7F, 0xFFD81B60},   // hearts
            {0xFF2EAA3C, 0xFF46C350, 0xFF1F8F30, 0xFF5BD45E},   // shamrocks
            {0xFFFFA8C8, 0xFFA8D8FF, 0xFFC8F0A0, 0xFFE0C0FF},   // eggs
            {0xFFE53935, 0xFFF5F5F5, 0xFF1E5BD8, 0xFFE53935},   // stars
            {0xFFFF8A1E, 0xFFF57C00, 0xFFFF9A2E, 0xFFFB8C00},   // pumpkins
            {0xFFE8661E, 0xFFC62828, 0xFFE0A21E, 0xFF9E5A1E},   // leaves
            {0xFFD32F2F, 0xFF2E7D32, 0xFF1E5BD8, 0xFF8E3FC8}};  // presents

    private void drawHolidayItem(Canvas c, Obj o) {
        boolean gold = o.type == GOLD_BLOOM;
        float k = o.size;
        float pulse = 0.5f + 0.5f * (float) Math.sin(animTime * 5f + o.z * 0.3f);
        float bob = (float) Math.sin(animTime * 3f + o.x) * 0.07f;
        float y = -1.0f * k + bob;
        int main = gold ? 0xFFFFCF3A : HOL_COLS[holiday][o.color % 4];
        if (gold && holiday == Holidays.HALLOWEEN) main = 0xFF9CFF7A;   // eerie green glow

        // halo so it reads as a pickup, not an obstacle
        p.setStyle(Paint.Style.FILL);
        p.setColor((main & 0x00FFFFFF) | ((int) ((gold ? 95 : 60) + 50 * pulse) << 24));
        c.drawCircle(0f, y, (gold ? 0.9f : 0.62f) * k + 0.08f * pulse, p);

        c.save();
        c.translate(0f, y);
        c.scale(k, k);
        switch (holiday) {
            case Holidays.NEW_YEAR:     if (gold) drawChampagne(c); else drawPopper(c, main); break;
            case Holidays.VALENTINE:    drawHeart(c, gold ? 0xFFFFC83A : main, gold); break;
            case Holidays.ST_PATRICK:   if (gold) drawPotOfGold(c); else drawShamrock(c, main); break;
            case Holidays.EASTER:       drawEgg(c, gold ? 0xFFFFCF3A : main, o.color, gold); break;
            case Holidays.JULY_4:       if (gold) drawFirework(c); else drawStar(c, main); break;
            case Holidays.HALLOWEEN:    if (gold) drawSkull(c); else drawPumpkin(c, main, pulse); break;
            case Holidays.THANKSGIVING: if (gold) drawTurkey(c); else drawLeaf(c, main, o.x); break;
            case Holidays.CHRISTMAS:    if (gold) drawXmasTree(c); else drawPresent(c, main); break;
            default: break;
        }
        c.restore();

        if (gold) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.04f);
            for (int i = 0; i < 4; i++) {
                float ph = (animTime * 1.5f + i * 0.25f) % 1f;
                float ang = (float) (i * Math.PI / 2 + animTime);
                float r = 0.75f + 0.35f * ph;
                float sx = (float) Math.cos(ang) * r, sy = y + (float) Math.sin(ang) * r;
                float sz = 0.12f * (1f - Math.abs(ph - 0.5f) * 2f);
                p.setColor(Color.argb((int) (255 * (1f - ph)), 255, 250, 210));
                c.drawLine(sx - sz, sy, sx + sz, sy, p);
                c.drawLine(sx, sy - sz, sx, sy + sz, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
    }

    private void fill(int col) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(fog(col));
    }

    private void stroke(int col, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(w);
        p.setColor(fog(col));
    }

    private void starPath(float x, float y, float r, int points, float inner, float rot) {
        path.reset();
        for (int i = 0; i < points * 2; i++) {
            double a = rot + Math.PI * i / points - Math.PI / 2;
            float rr = (i % 2 == 0) ? r : r * inner;
            float qx = x + (float) Math.cos(a) * rr, qy = y + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(qx, qy); else path.lineTo(qx, qy);
        }
        path.close();
    }

    private void heartPath(float x, float y, float s) {
        path.reset();
        path.moveTo(x, y + s * 0.9f);
        path.cubicTo(x - s * 1.35f, y + s * 0.05f, x - s * 0.65f, y - s * 0.95f, x, y - s * 0.32f);
        path.cubicTo(x + s * 0.65f, y - s * 0.95f, x + s * 1.35f, y + s * 0.05f, x, y + s * 0.9f);
        path.close();
    }

    // ---- New Year's ----
    private void drawPopper(Canvas c, int col) {
        path.reset();                       // cone, open end up
        path.moveTo(-0.07f, 0.4f);
        path.lineTo(0.07f, 0.4f);
        path.lineTo(0.27f, -0.18f);
        path.lineTo(-0.27f, -0.18f);
        path.close();
        fill(col);
        c.drawPath(path, p);
        stroke(0xFFFFD45A, 0.05f);
        c.drawLine(-0.16f, 0.1f, 0.16f, 0.1f, p);
        c.drawLine(-0.21f, -0.05f, 0.21f, -0.05f, p);
        int[] cc = {0xFFFFD45A, 0xFFFF5FA2, 0xFF3FC1FF, 0xFF8CFF6A, 0xFFFFFFFF};
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 9; i++) {       // confetti bursting out
            float ph = (animTime * 0.9f + i * 0.11f) % 1f;
            float ang = (float) (-Math.PI / 2 + (i - 4) * 0.28f);
            float r = 0.15f + 0.45f * ph;
            float x = (float) Math.cos(ang) * r, y = -0.2f + (float) Math.sin(ang) * r;
            p.setColor(fog(cc[i % cc.length]));
            p.setAlpha((int) (255 * (1f - ph * 0.7f)));
            c.save();
            c.rotate(ph * 360f + i * 40f, x, y);
            c.drawRect(x - 0.045f, y - 0.025f, x + 0.045f, y + 0.025f, p);
            c.restore();
        }
        p.setAlpha(255);
    }

    private void drawChampagne(Canvas c) {
        fill(0xFF1F5A34);
        rect.set(-0.17f, -0.12f, 0.17f, 0.45f);
        c.drawRoundRect(rect, 0.08f, 0.08f, p);
        path.reset();                        // shoulders and neck
        path.moveTo(-0.17f, -0.05f);
        path.quadTo(-0.16f, -0.25f, -0.06f, -0.3f);
        path.lineTo(-0.06f, -0.5f);
        path.lineTo(0.06f, -0.5f);
        path.lineTo(0.06f, -0.3f);
        path.quadTo(0.16f, -0.25f, 0.17f, -0.05f);
        path.close();
        c.drawPath(path, p);
        fill(0xFFFFCF3A);                    // foil and label
        c.drawRect(-0.07f, -0.58f, 0.07f, -0.38f, p);
        c.drawRect(-0.17f, 0.05f, 0.17f, 0.25f, p);
        fill(0xFFFFF4C0);
        c.drawRect(-0.1f, 0.1f, 0.1f, 0.2f, p);
        p.setColor(fog(0xFFFFFFFF));         // bubbles
        for (int i = 0; i < 5; i++) {
            float ph = (animTime * 0.8f + i * 0.2f) % 1f;
            p.setAlpha((int) (220 * (1f - ph)));
            c.drawCircle((float) Math.sin(i * 2.1f + animTime) * 0.12f, -0.6f - ph * 0.5f, 0.035f + 0.02f * ph, p);
        }
        p.setAlpha(255);
    }

    // ---- Valentine's ----
    private void drawHeart(Canvas c, int col, boolean gold) {
        float beat = 1f + 0.07f * (float) Math.max(0, Math.sin(animTime * 6f));
        c.save();
        c.scale(beat, beat);
        heartPath(0f, 0f, 0.38f);
        fill(col);
        c.drawPath(path, p);
        fill(0x66FFFFFF);
        c.drawCircle(-0.15f, -0.13f, 0.07f, p);
        if (gold) {                          // ribbon bow
            stroke(0xFFE8234A, 0.06f);
            c.drawLine(-0.36f, 0.05f, 0.36f, 0.05f, p);
            fill(0xFFE8234A);
            rect.set(-0.2f, -0.06f, -0.02f, 0.16f);
            c.drawOval(rect, p);
            rect.set(0.02f, -0.06f, 0.2f, 0.16f);
            c.drawOval(rect, p);
        }
        c.restore();
    }

    // ---- St. Patrick's ----
    private void drawShamrock(Canvas c, int col) {
        stroke(0xFF1F7A2A, 0.06f);
        path.reset();
        path.moveTo(0f, 0.05f);
        path.quadTo(0.05f, 0.3f, 0.2f, 0.45f);
        c.drawPath(path, p);
        for (int i = 0; i < 3; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / 3;
            float lx = (float) Math.cos(a) * 0.17f, ly = (float) Math.sin(a) * 0.17f;
            heartPath(lx, ly, 0.18f);
            fill(col);
            c.save();
            c.rotate((float) Math.toDegrees(a) + 90f, lx, ly);
            c.drawPath(path, p);
            c.restore();
        }
        fill(0x44FFFFFF);
        c.drawCircle(0f, 0f, 0.05f, p);
    }

    private void drawPotOfGold(Canvas c) {
        fill(0xFFFFCF3A);                    // heaped coins
        for (int i = 0; i < 6; i++) c.drawCircle(-0.25f + i * 0.1f, -0.12f - (i % 2) * 0.05f, 0.09f, p);
        c.drawCircle(0f, -0.25f, 0.1f, p);
        fill(0xFF1E1E22);                    // pot
        rect.set(-0.38f, -0.15f, 0.38f, 0.45f);
        c.drawArc(rect, 0f, 180f, true, p);
        c.drawRect(-0.38f, -0.05f, 0.38f, 0.17f, p);
        rect.set(-0.42f, -0.12f, 0.42f, 0.0f);
        fill(0xFF33333A);
        c.drawRoundRect(rect, 0.05f, 0.05f, p);
        fill(0xFFFFF0A0);
        c.drawCircle(-0.12f, -0.17f, 0.03f, p);
    }

    // ---- Easter ----
    private void drawEgg(Canvas c, int col, int variant, boolean gold) {
        rect.set(-0.28f, -0.42f, 0.28f, 0.36f);
        fill(col);
        c.drawOval(rect, p);
        int band = gold ? 0xFFFFF4B0 : HOL_COLS[Holidays.EASTER][(variant + 1) % 4];
        stroke(band == col ? 0xFFFFFFFF : band, 0.05f);
        path.reset();
        for (int i = 0; i <= 8; i++) {
            float x = -0.26f + i * 0.065f, y = (i % 2 == 0) ? -0.05f : 0.05f;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, p);
        fill(0xFFFFFFFF);
        for (int i = 0; i < 3; i++) c.drawCircle(-0.12f + i * 0.12f, -0.22f, 0.035f, p);
        for (int i = 0; i < 2; i++) c.drawCircle(-0.06f + i * 0.12f, 0.18f, 0.035f, p);
        fill(0x55FFFFFF);
        rect.set(-0.18f, -0.34f, -0.06f, -0.16f);
        c.drawOval(rect, p);
    }

    // ---- Independence Day ----
    private void drawStar(Canvas c, int col) {
        c.save();
        c.rotate((float) Math.sin(animTime * 2f) * 12f);
        starPath(0f, 0f, 0.4f, 5, 0.45f, 0f);
        fill(col);
        c.drawPath(path, p);
        stroke(col == 0xFFF5F5F5 ? 0xFF1E5BD8 : 0xFFFFFFFF, 0.035f);
        c.drawPath(path, p);
        c.restore();
    }

    private void drawFirework(Canvas c) {
        int[] cols = {0xFFFF4040, 0xFFFFFFFF, 0xFF4A8CFF, 0xFFFFD45A};
        float ph = (animTime * 0.8f) % 1f;
        float r = 0.15f + 0.45f * ph;
        for (int i = 0; i < 14; i++) {
            double a = i * Math.PI * 2 / 14;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            stroke(cols[i % cols.length], 0.05f);
            p.setAlpha((int) (255 * (1f - ph * 0.6f)));
            c.drawLine(ca * r * 0.4f, sa * r * 0.4f, ca * r, sa * r, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(ca * r, sa * r, 0.045f, p);
        }
        p.setAlpha(255);
        fill(0xFFFFFFFF);
        c.drawCircle(0f, 0f, 0.08f, p);
    }

    // ---- Halloween ----
    private void drawPumpkin(Canvas c, int col, float pulse) {
        stroke(0xFF4E6B1E, 0.07f);           // stem
        c.drawLine(0f, -0.28f, 0.05f, -0.42f, p);
        fill(darker(col, 0.8f));
        rect.set(-0.42f, -0.3f, -0.02f, 0.3f);
        c.drawOval(rect, p);
        rect.set(0.02f, -0.3f, 0.42f, 0.3f);
        c.drawOval(rect, p);
        fill(col);
        rect.set(-0.25f, -0.32f, 0.25f, 0.32f);
        c.drawOval(rect, p);
        // glowing carved face, flickering like a candle
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(255, 200 + (int) (40 * pulse), 70));
        path.reset();
        path.moveTo(-0.2f, -0.02f); path.lineTo(-0.12f, -0.17f); path.lineTo(-0.04f, -0.02f); path.close();
        path.moveTo(0.04f, -0.02f); path.lineTo(0.12f, -0.17f); path.lineTo(0.2f, -0.02f); path.close();
        path.moveTo(-0.03f, 0.04f); path.lineTo(0.03f, 0.04f); path.lineTo(0f, -0.03f); path.close();
        c.drawPath(path, p);
        path.reset();
        path.moveTo(-0.24f, 0.09f);
        path.lineTo(-0.15f, 0.13f); path.lineTo(-0.1f, 0.08f); path.lineTo(-0.04f, 0.14f);
        path.lineTo(0.02f, 0.08f); path.lineTo(0.08f, 0.14f); path.lineTo(0.14f, 0.08f);
        path.lineTo(0.24f, 0.09f);
        path.quadTo(0f, 0.3f, -0.24f, 0.09f);
        path.close();
        c.drawPath(path, p);
    }

    private void drawSkull(Canvas c) {
        fill(0xFFF2EEDC);
        c.drawCircle(0f, -0.08f, 0.36f, p);
        rect.set(-0.2f, 0.1f, 0.2f, 0.38f);
        c.drawRoundRect(rect, 0.07f, 0.07f, p);
        fill(0xFF141414);
        c.drawCircle(-0.13f, -0.06f, 0.1f, p);
        c.drawCircle(0.13f, -0.06f, 0.1f, p);
        path.reset();
        path.moveTo(-0.04f, 0.12f); path.lineTo(0.04f, 0.12f); path.lineTo(0f, 0.04f); path.close();
        c.drawPath(path, p);
        float g = 0.5f + 0.5f * (float) Math.sin(animTime * 4f);   // glowing eyes
        p.setColor(Color.argb(255, 120 + (int) (80 * g), 255, 110));
        c.drawCircle(-0.13f, -0.06f, 0.035f + 0.015f * g, p);
        c.drawCircle(0.13f, -0.06f, 0.035f + 0.015f * g, p);
        stroke(0xFF8A8676, 0.025f);
        for (int i = -2; i <= 2; i++) c.drawLine(i * 0.065f, 0.22f, i * 0.065f, 0.36f, p);
        c.drawLine(-0.17f, 0.22f, 0.17f, 0.22f, p);
    }

    // ---- Thanksgiving ----
    private void drawLeaf(Canvas c, int col, float seed) {
        c.save();
        c.rotate((float) Math.sin(animTime * 1.7f + seed) * 25f + Math.abs(seed * 37f) % 40f - 20f);
        starPath(0f, -0.02f, 0.4f, 5, 0.55f, 0f);    // maple-like leaf
        fill(col);
        c.drawPath(path, p);
        stroke(darker(col, 0.7f), 0.03f);
        c.drawLine(0f, 0.35f, 0f, -0.3f, p);
        c.drawLine(0f, 0.05f, -0.24f, -0.12f, p);
        c.drawLine(0f, 0.05f, 0.24f, -0.12f, p);
        stroke(0xFF6B4423, 0.04f);
        c.drawLine(0f, 0.3f, 0.02f, 0.5f, p);
        c.restore();
    }

    private void drawTurkey(Canvas c) {
        int[] feathers = {0xFFFFCF3A, 0xFFE8A21E, 0xFFFFE07A, 0xFFD48A1E};
        for (int i = 0; i < 7; i++) {        // fanned tail
            c.save();
            c.rotate(-72f + i * 24f, 0f, 0.1f);
            fill(feathers[i % feathers.length]);
            rect.set(-0.09f, -0.48f, 0.09f, 0.1f);
            c.drawOval(rect, p);
            c.restore();
        }
        fill(0xFFB8862A);                    // body
        c.drawCircle(0f, 0.12f, 0.22f, p);
        fill(0xFFD8A23A);                    // head
        c.drawCircle(0f, -0.12f, 0.11f, p);
        fill(0xFF141414);
        c.drawCircle(-0.04f, -0.15f, 0.022f, p);
        c.drawCircle(0.04f, -0.15f, 0.022f, p);
        fill(0xFFFF9A1E);                    // beak
        path.reset();
        path.moveTo(-0.04f, -0.1f); path.lineTo(0.04f, -0.1f); path.lineTo(0f, -0.04f); path.close();
        c.drawPath(path, p);
        fill(0xFFE53935);                    // wattle
        rect.set(0.01f, -0.07f, 0.06f, 0.03f);
        c.drawOval(rect, p);
    }

    // ---- Christmas ----
    private void drawPresent(Canvas c, int col) {
        fill(col);
        c.drawRect(-0.32f, -0.22f, 0.32f, 0.36f, p);
        fill(lighter(col, 0.2f));
        c.drawRect(-0.36f, -0.32f, 0.36f, -0.18f, p);
        fill(0xFFFFD45A);                    // ribbon
        c.drawRect(-0.05f, -0.32f, 0.05f, 0.36f, p);
        c.drawRect(-0.32f, 0.02f, 0.32f, 0.1f, p);
        rect.set(-0.22f, -0.48f, -0.01f, -0.3f);   // bow
        c.drawOval(rect, p);
        rect.set(0.01f, -0.48f, 0.22f, -0.3f);
        c.drawOval(rect, p);
        fill(darker(0xFFFFD45A, 0.8f));
        c.drawCircle(0f, -0.33f, 0.05f, p);
    }

    private void drawXmasTree(Canvas c) {
        fill(0xFF6B4423);
        c.drawRect(-0.06f, 0.3f, 0.06f, 0.45f, p);
        int[] g = {0xFF1E6B34, 0xFF268040, 0xFF2E944A};
        float[][] tiers = {{0.42f, 0.32f, -0.05f}, {0.33f, 0.08f, -0.28f}, {0.24f, -0.15f, -0.5f}};
        for (int i = 0; i < 3; i++) {
            path.reset();
            path.moveTo(-tiers[i][0], tiers[i][1]);
            path.lineTo(tiers[i][0], tiers[i][1]);
            path.lineTo(0f, tiers[i][2]);
            path.close();
            fill(g[i]);
            c.drawPath(path, p);
        }
        int[] orn = {0xFFE53935, 0xFF4A8CFF, 0xFFFFD45A, 0xFFFF6FD8};
        float[][] pos = {{-0.2f, 0.22f}, {0.15f, 0.2f}, {-0.05f, 0.05f}, {0.14f, -0.06f}, {-0.12f, -0.13f}, {0.04f, -0.3f}};
        for (int i = 0; i < pos.length; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(animTime * 5f + i * 1.3f);
            p.setColor(fog(orn[i % orn.length]));
            p.setAlpha((int) (160 + 95 * tw));
            c.drawCircle(pos[i][0], pos[i][1], 0.045f, p);
        }
        p.setAlpha(255);
        starPath(0f, -0.56f, 0.12f, 5, 0.45f, animTime * 0.5f);
        fill(0xFFFFE07A);
        c.drawPath(path, p);
    }

    // ---- falling snow / leaves / confetti (screen space) ----------------------------

    private void initWeather() {
        weatherFor = holiday;
        for (int i = 0; i < wx.length; i++) {
            wx[i] = rnd.nextFloat();
            wy[i] = rnd.nextFloat();
            wsp[i] = 0.06f + rnd.nextFloat() * 0.1f;
            wsz[i] = 0.5f + rnd.nextFloat();
            wcol[i] = rnd.nextInt(4);
        }
    }

    private void updateWeather(float dt) {
        int kind = Holidays.WEATHER[holiday];
        if (kind == 0) return;
        if (weatherFor != holiday) initWeather();
        float fall = kind == 1 ? 1f : kind == 2 ? 0.8f : 1.3f;
        for (int i = 0; i < wx.length; i++) {
            wy[i] += wsp[i] * fall * dt;
            wx[i] += ((float) Math.sin(animTime * 1.3f + i) * 0.03f - latVel * 0.004f) * dt;
            if (wy[i] > 1.05f) { wy[i] = -0.05f; wx[i] = rnd.nextFloat(); }
            if (wx[i] < -0.05f) wx[i] += 1.1f;
            if (wx[i] > 1.05f) wx[i] -= 1.1f;
        }
    }

    private static final int[] LEAF_COLS = {0xFFE8661E, 0xFFC62828, 0xFFE0A21E, 0xFF9E5A1E};
    private static final int[] CONFETTI_JULY = {0xFFE53935, 0xFFFFFFFF, 0xFF1E5BD8, 0xFFFFFFFF};
    private static final int[] CONFETTI_NY = {0xFFFFD45A, 0xFFE0E0E0, 0xFFFF5FA2, 0xFF3FC1FF};

    private void drawWeather(Canvas c) {
        int kind = Holidays.WEATHER[holiday];
        if (kind == 0 || weatherFor != holiday) return;
        int count = kind == 1 ? 52 : kind == 2 ? 20 : 34;   // 25% fewer than before
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            float x = wx[i] * W, y = wy[i] * H, sz = wsz[i] * H;
            if (kind == 1) {
                p.setColor(0xD8FFFFFF);
                c.drawCircle(x, y, sz * 0.006f, p);
            } else if (kind == 2) {
                p.setColor(LEAF_COLS[wcol[i]]);
                c.save();
                c.rotate(animTime * 90f * (wsp[i] * 10f - 1f) + i * 40f, x, y);
                rect.set(x - sz * 0.018f, y - sz * 0.009f, x + sz * 0.018f, y + sz * 0.009f);
                c.drawOval(rect, p);
                c.restore();
            } else {
                p.setColor((holiday == Holidays.JULY_4 ? CONFETTI_JULY : CONFETTI_NY)[wcol[i]]);
                c.save();
                c.rotate(animTime * 200f * wsp[i] * 10f + i * 30f, x, y);
                c.drawRect(x - sz * 0.009f, y - sz * 0.005f, x + sz * 0.009f, y + sz * 0.005f, p);
                c.restore();
            }
        }
    }

    private float soundBtnX() { return themeBtnX() - themeBtnR() * 4.2f; }

    /** Speaker icon: tap to turn sound effects on or off. */
    private void drawSoundButton(Canvas c) {
        float x = soundBtnX(), y = themeBtnY(), r = themeBtnR();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        c.drawCircle(x, y, r * 1.45f, p);
        p.setColor(Color.WHITE);
        path.reset();                                    // speaker body + cone
        path.moveTo(x - r * 0.7f, y - r * 0.28f);
        path.lineTo(x - r * 0.35f, y - r * 0.28f);
        path.lineTo(x + r * 0.1f, y - r * 0.7f);
        path.lineTo(x + r * 0.1f, y + r * 0.7f);
        path.lineTo(x - r * 0.35f, y + r * 0.28f);
        path.lineTo(x - r * 0.7f, y + r * 0.28f);
        path.close();
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.13f);
        if (soundOn) {
            rect.set(x - r * 0.2f, y - r * 0.45f, x + r * 0.7f, y + r * 0.45f);
            c.drawArc(rect, -50f, 100f, false, p);
            rect.set(x - r * 0.45f, y - r * 0.8f, x + r * 1.0f, y + r * 0.8f);
            c.drawArc(rect, -50f, 100f, false, p);
        } else {
            p.setColor(0xFFFF8A80);
            c.drawLine(x + r * 0.35f, y - r * 0.35f, x + r * 0.95f, y + r * 0.35f, p);
            c.drawLine(x + r * 0.95f, y - r * 0.35f, x + r * 0.35f, y + r * 0.35f, p);
        }
        p.setStyle(Paint.Style.FILL);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(H * 0.028f);
        txt.setColor(0xDDFFFFFF);
        c.drawText(soundOn ? "SOUND" : "MUTED", x, y + r * 1.45f + H * 0.04f, txt);
        txt.setColor(Color.WHITE);
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
