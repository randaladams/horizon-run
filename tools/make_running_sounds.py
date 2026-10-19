import numpy as np, wave
R = 44100
rng = np.random.default_rng(5)
def lp(x, a):
    y = np.zeros_like(x); acc = 0.0
    for i in range(len(x)):
        acc += a*(x[i]-acc); y[i] = acc
    return y
def save(name, x, peak):
    x = x / max(1e-9, np.max(np.abs(x))) * peak
    with wave.open(name, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(R); w.writeframes((x*32767).astype('<i2').tobytes())
# one soft footfall (same recipe as preview 9d)
n = int(R*.09); t = np.arange(n)/R
thump = np.sin(2*np.pi*(80*np.exp(-t*14)+50)*t) * np.exp(-t*38) * np.minimum(1, t/0.008)
grass = lp(rng.standard_normal(n), 0.09) * np.exp(-t*70) * 0.45
step = thump*0.8 + grass
step[-200:] *= np.linspace(1, 0, 200)
save('sfx_step.wav', step, 0.12)
# seamless wind loop (6 s) at a middle brightness; the game raises pitch and volume with speed
L = int(R*6); X = int(R*1)
noise = rng.standard_normal(L + X)
w = lp(lp(noise, 0.028), 0.028)
tt = np.arange(L + X)/R
w *= 1 + 0.2*np.sin(2*np.pi*tt/3.0)            # gentle gusts, 2 per loop
loop = w[:L].copy()
fade = np.linspace(0, 1, X)
loop[:X] = w[L:L+X]*(1-fade) + w[:X]*fade        # crossfade the tail into the head
save('sfx_wind.wav', loop, 0.1)
