import numpy as np, wave
R = 44100
def T(d): return np.arange(int(R*d))/R
def save(name, x, peak=0.6):
    x = x/max(1e-9, np.max(np.abs(x)))*peak
    f = int(R*.02); x[-f:] *= np.linspace(1, 0, f)
    with wave.open(name, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(R); w.writeframes((x*32767).astype('<i2').tobytes())
def mix(parts, total):
    out = np.zeros(int(R*total))
    for s, sig in parts:
        a = int(R*s); b = min(len(out), a+len(sig)); out[a:b] += sig[:b-a]
    return out
def lowpass(x, cutoff):            # one-pole low-pass with a time-varying cutoff (Hz array)
    y = np.zeros_like(x); a = 1 - np.exp(-2*np.pi*np.asarray(cutoff)/R)
    a = np.broadcast_to(a, x.shape); acc = 0.0
    for i in range(len(x)):
        acc += a[i]*(x[i]-acc); y[i] = acc
    return y
def reverb(x, wet=.25):            # a few soft echoes to give it a "hall"
    out = x.copy()
    for d, g in [(.031, .5), (.047, .4), (.071, .3), (.113, .22), (.167, .15)]:
        n = int(R*d); out[n:] += x[:-n]*g*wet*2
    return out
def brass(f, d, vib=0.0, scoop=True, loud=1.0):
    t = T(d)
    pitch = f*(1 - (0.05*np.exp(-t*40) if scoop else 0))                 # lips "scoop" up into the note
    pitch = pitch*(1 + vib*np.sin(2*np.pi*5.2*t)*np.minimum(1, t/.25))
    ph = 2*np.pi*np.cumsum(pitch)/R
    saw = sum(np.sin(h*ph)/h for h in range(1, 16))                       # rich, buzzy source
    amp = np.minimum(1, t/.025)*np.where(t > d-.08, np.maximum(0, (d-t)/.08), 1)
    amp = amp*(0.85 + 0.15*np.exp(-t*8))                                  # little accent at the start
    bright = 600 + 3800*amp*loud                                         # louder = brighter, like real brass
    breath = np.random.default_rng(int(f)).standard_normal(len(t))*0.03*np.exp(-t*30)
    return lowpass(saw*amp + breath, bright)

# A: "Ta-daaa!" - a short pickup note then a big held major chord
A = mix([(0, brass(523.25, .14)),
         (.16, brass(523.25, .9, .006)), (.16, brass(659.25, .9, .006)*.8),
         (.16, brass(783.99, .9, .006)*.8), (.16, brass(1046.5, .9, .006)*.5)], 1.3)
save('7a-revive-tada.wav', reverb(A), .55)

# B: classic bugle "Charge!" - short and punchy, last note held
mel = [(392.0, .11), (523.25, .11), (659.25, .11), (783.99, .22), (659.25, .11), (783.99, .7)]
parts, t0 = [], 0.0
for f, d in mel:
    parts.append((t0, brass(f, d+.02, .007 if d > .5 else 0))); t0 += d + .03
save('7b-revive-charge.wav', reverb(mix(parts, t0+.45)), .55)

# C: arcade "power-up" - a quick rising shimmer that blooms into a soft bright chord (no trumpet)
t = T(.55); f = 300*np.exp(t*3.4)
sweep = np.sin(2*np.pi*np.cumsum(f)/R)*np.minimum(1, t/.02)*(0.4+0.6*t/.55)
sweep += 0.5*np.sin(2*np.pi*np.cumsum(f*1.5)/R)*(t/.55)
def pad(fr, d):
    tt = T(d); ph = 2*np.pi*fr*tt
    return (np.sin(ph) + .3*np.sin(2*ph) + .1*np.sin(3*ph))*np.minimum(1, tt/.03)*np.exp(-tt*2.2)
C = mix([(0, sweep*.6), (.5, pad(1046.5, 1.0)), (.5, pad(1318.5, 1.0)*.8), (.5, pad(1568, 1.0)*.7), (.5, pad(2093, 1.0)*.4)], 1.6)
save('7c-revive-powerup.wav', reverb(C, .3), .5)
