import numpy as np, wave
R = 44100
def T(d): return np.arange(int(R*d))/R
def env(n, a, k):            # attack seconds, exponential decay rate
    t = np.arange(n)/R
    return np.minimum(1, t/max(a,1e-4)) * np.exp(-t*k)
def bell(f, d, k=9, vol=1.0):  # soft bell: fundamental + octave + fifth partial
    t = T(d); ph = 2*np.pi*f*t
    w = np.sin(ph)*0.7 + np.sin(2*ph)*0.18 + np.sin(3*ph)*0.07
    return w*env(len(t), 0.003, k)*vol
def mix(parts, total):
    out = np.zeros(int(R*total))
    for start, sig in parts:
        s = int(R*start); e = min(len(out), s+len(sig)); out[s:e] += sig[:e-s]
    return out
def save(name, x, peak=0.6):
    x = x/ max(1e-9, np.max(np.abs(x))) * peak
    fade = int(R*0.01); x[-fade:] *= np.linspace(1,0,fade)
    with wave.open(name, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(R)
        w.writeframes((x*32767).astype('<i2').tobytes())

# 1. flower pickup: quick two-note "bling" (E6 -> B6)
save('1-flower.wav', mix([(0, bell(1318.5, .25, 14)), (.055, bell(1975.5, .35, 11))], .42), .45)

# 2. bouquet (full trail of 5): quick rising five-note run
notes = [1046.5, 1174.7, 1318.5, 1568.0, 2093.0]
save('2-bouquet.wav', mix([(i*.06, bell(f, .5 if i == 4 else .25, 7 if i == 4 else 14)) for i, f in enumerate(notes)], .8), .5)

# 3. golden item: shimmering sparkle, detuned bells with a high glitter
gl = [(0, bell(1568, .9, 4.5)), (0, bell(1572, .9, 4.5)), (.09, bell(2093, .8, 5)), (.09, bell(2099, .8, 5)),
      (.18, bell(3136, .6, 6, .6))]
for i in range(8): gl.append((.22 + i*.045, bell(4186*(1+0.06*np.sin(i)), .12, 30, .25)))
save('3-golden.wav', mix(gl, 1.1), .5)

# 4. jump: soft upward "whoop" (sine sweep 280 -> 620 Hz)
t = T(.2); f = 280 + (620-280)*(t/.2)**0.6
w = np.sin(2*np.pi*np.cumsum(f)/R) * env(len(t), .01, 14)
save('4-jump.wav', w, .35)

# 5. snail: subtle "slowing down" - a soft tone that sags in pitch like a tape winding down
t = T(.75); f = 520*np.exp(-t*1.9) + 120
vib = 1 + 0.015*np.sin(2*np.pi*6*t)
ph = 2*np.pi*np.cumsum(f*vib)/R
w = (np.sin(ph)*0.8 + np.sin(2*ph)*0.15) * np.minimum(1, t/.03) * np.exp(-t*2.2)
save('5-snail.wav', w, .28)

# 6. crash: low thump + short crunchy noise burst
t = T(.45); f = 110*np.exp(-t*6) + 38
thump = np.sin(2*np.pi*np.cumsum(f)/R) * np.exp(-t*7)
rng = np.random.default_rng(3); n = rng.standard_normal(len(t))
k = np.ones(18)/18; n = np.convolve(n, k, 'same')                  # soften the noise (low-pass)
crunch = n * np.exp(-t*16) * 1.6
save('6-crash.wav', thump + crunch, .7)

# 7. revive: short brass fanfare "ta-ta-ta-taaa" (G4 C5 E5 G5)
def brass(f, d, vib=False):
    t = T(d); fv = f*(1 + (0.008*np.sin(2*np.pi*5.5*t)*np.minimum(1, t/.15) if vib else 0))
    ph = 2*np.pi*np.cumsum(fv)/R
    bright = np.minimum(1, t/.04)                                  # brass "blat" opens up
    w = sum((1/h) * (bright if h > 2 else 1) * np.sin(h*ph) for h in range(1, 9))
    a = np.minimum(1, t/.02) * np.where(t > d-.06, np.maximum(0, (d-t)/.06), 1)
    return w*a
fan = [(0, brass(392, .13)), (.15, brass(523.25, .13)), (.3, brass(659.25, .13)), (.45, brass(784, .75, True))]
fan += [(.45, brass(392, .75)*0.35), (.45, brass(523.25, .75)*0.3)]  # chord under the last note
save('7-revive-trumpet.wav', mix(fan, 1.25), .55)

# 8. existing high-score chime, for comparison
save('8-highscore-chime.wav', mix([(i*.085, bell(f, .45 if i == 3 else .19, 6 if i == 3 else 14)) for i, f in enumerate([1046.5, 1318.5, 1568, 2093])], .75), .5)

# sampler: all of them in a row with gaps
import glob
seq = []
for fn in sorted(glob.glob('[1-8]-*.wav')):
    with wave.open(fn) as w: seq.append(np.frombuffer(w.readframes(w.getnframes()), '<i2'))
gap = np.zeros(int(R*.7), dtype='<i2')
allx = np.concatenate([np.concatenate([s, gap]) for s in seq])
with wave.open('0-all-sounds.wav', 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(R); w.writeframes(allx.tobytes())
