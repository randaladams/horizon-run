from PIL import Image, ImageDraw, ImageFont, ImageFilter
import math, random
random.seed(7)
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

def lerp(a, b, t): return tuple(int(a[i] + (b[i]-a[i])*t) for i in range(3))
def sky(img, top, mid, bot, horizon):
    d = ImageDraw.Draw(img); w, h = img.size
    for y in range(h):
        t = y / max(1, horizon)
        c = lerp(top, mid, min(1, t*1.4)) if t < 0.7 else lerp(mid, bot, min(1, (t-0.7)/0.3))
        d.line([(0, y), (w, y)], fill=c)

def planet(img, cx, cy, r, stripes=True):
    d = ImageDraw.Draw(img)
    cols = [(79,154,63), (91,169,71)]
    # concentric arcs give the striped curved ground
    for i in range(14, -1, -1):
        rr = r + i * r * 0.035
        d.ellipse([cx-rr*1.9, cy-rr*0.55, cx+rr*1.9, cy+rr*0.55], fill=cols[i % 2] if stripes else cols[0])

def glow_line(img, cx, cy, r):
    g = Image.new("RGBA", img.size, (0,0,0,0)); d = ImageDraw.Draw(g)
    rr = r
    d.ellipse([cx-rr*1.9, cy-rr*0.55, cx+rr*1.9, cy+rr*0.55], outline=(255,255,255,170), width=max(3, int(r*0.03)))
    g = g.filter(ImageFilter.GaussianBlur(r*0.02))
    img.alpha_composite(g)

def pine(d, x, base, s, col=(46,107,58)):
    d.rectangle([x-0.07*s, base-0.4*s, x+0.07*s, base], fill=(107,68,35))
    for i, (w, y0, y1, c) in enumerate([(0.5, -0.3, -1.3, (36,90,48)), (0.4, -0.8, -1.8, (46,107,58)), (0.28, -1.3, -2.25, (58,127,69))]):
        d.polygon([(x-w*s, base+y0*s), (x+w*s, base+y0*s), (x, base+y1*s)], fill=c)

def rock(d, x, base, s):
    pts = [(x-0.6*s, base), (x-0.5*s, base-0.35*s), (x-0.15*s, base-0.55*s), (x+0.3*s, base-0.5*s), (x+0.6*s, base-0.15*s), (x+0.65*s, base)]
    d.polygon(pts, fill=(126,130,134))
    d.polygon([(x-0.45*s, base-0.05*s), (x-0.4*s, base-0.33*s), (x-0.12*s, base-0.5*s), (x+0.05*s, base-0.3*s), (x, base-0.05*s)], fill=(164,168,172))

def flower(img, x, y, s, col):
    g = Image.new("RGBA", img.size, (0,0,0,0)); d = ImageDraw.Draw(g)
    d.ellipse([x-s*1.6, y-s*1.6, x+s*1.6, y+s*1.6], fill=col+(90,))
    g = g.filter(ImageFilter.GaussianBlur(s*0.5)); img.alpha_composite(g)
    d = ImageDraw.Draw(img)
    d.line([(x, y), (x, y+s*2.4)], fill=(62,143,58), width=max(2, int(s*0.25)))
    for i in range(6):
        a = i*math.pi/3
        px, py = x+math.cos(a)*s*0.6, y+math.sin(a)*s*0.6
        d.ellipse([px-s*0.45, py-s*0.45, px+s*0.45, py+s*0.45], fill=col)
    d.ellipse([x-s*0.35, y-s*0.35, x+s*0.35, y+s*0.35], fill=(255,225,90))

def ringed_planet(img, x, y, r):
    d = ImageDraw.Draw(img)
    d.ellipse([x-r, y-r, x+r, y+r], fill=(233,217,182))
    ring = Image.new("RGBA", img.size, (0,0,0,0)); rd = ImageDraw.Draw(ring)
    rd.ellipse([x-r*1.9, y-r*0.42, x+r*1.9, y+r*0.42], outline=(243,230,200,190), width=max(2, int(r*0.12)))
    img.alpha_composite(ring.rotate(18, center=(x, y)))

# ---------------- 512 x 512 icon ----------------
S = 512
icon = Image.new("RGBA", (S, S))
sky(icon, (14,36,87), (47,100,184), (190,221,245), int(S*0.62))
ringed_planet(icon, 380, 120, 46)
planet(icon, S/2, S*1.18, S*0.62)
glow_line(icon, S/2, S*1.18, S*0.62 + 14*S*0.62*0.035)
d = ImageDraw.Draw(icon)
pine(d, 256, 360, 120)
rock(d, 395, 405, 70)
pine(d, 110, 395, 55)
flower(icon, 175, 430, 14, (255,111,174))
flower(icon, 330, 455, 11, (180,124,255))
icon.convert("RGB").save("icon-512.png")

# ---------------- 1024 x 500 feature graphic ----------------
W, H = 1024, 500
fg = Image.new("RGBA", (W, H))
sky(fg, (14,36,87), (47,100,184), (190,221,245), int(H*0.66))
for i in range(40):
    d = ImageDraw.Draw(fg); x, y = random.randint(0, W), random.randint(0, 140)
    d.ellipse([x-1.5, y-1.5, x+1.5, y+1.5], fill=(255,255,255,120))
ringed_planet(fg, 915, 78, 40)
planet(fg, W/2, H*1.21, H*0.72)
glow_line(fg, W/2, H*1.21, H*0.72 + 14*H*0.72*0.035)
d = ImageDraw.Draw(fg)
for x, b, s in [(120, 400, 70), (210, 380, 50), (820, 395, 75), (905, 412, 55), (560, 352, 34), (470, 356, 26)]:
    pine(d, x, b, s)
rock(d, 690, 380, 40)
rock(d, 330, 420, 55)
for x, y, c in [(400, 405, (255,111,174)), (450, 395, (255,111,174)), (500, 390, (255,111,174)), (560, 395, (255,111,174)), (610, 405, (255,111,174))]:
    flower(fg, x, y, 9, c)
# title
title = ImageFont.truetype(BOLD, 96); sub = ImageFont.truetype(BOLD, 30)
t = "HORIZON RUN"
tw = d.textlength(t, font=title)
shadow = Image.new("RGBA", fg.size, (0,0,0,0)); sd = ImageDraw.Draw(shadow)
sd.text(((W-tw)/2+4, 120+6), t, font=title, fill=(0,0,0,150))
fg.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(6)))
d = ImageDraw.Draw(fg)
d.text(((W-tw)/2, 120), t, font=title, fill=(255,255,255))
st = "Tilt. Jump. Run to the horizon."
sw = d.textlength(st, font=sub)
d.text(((W-sw)/2+2, 238+2), st, font=sub, fill=(0,0,0,140))
d.text(((W-sw)/2, 238), st, font=sub, fill=(255,233,160))
fg.convert("RGB").save("feature-graphic-1024x500.png")

# ---------------- 320 x 180 Fire TV home-screen banner ----------------
# Drawn at 4x and scaled down. Title only: a tagline is unreadable at TV-tile size.
W, H = 1280, 720
tv = Image.new("RGBA", (W, H))
sky(tv, (14,36,87), (47,100,184), (190,221,245), int(H*0.64))
for i in range(50):
    d = ImageDraw.Draw(tv); x, y = random.randint(0, W), random.randint(0, 220)
    d.ellipse([x-2.5, y-2.5, x+2.5, y+2.5], fill=(255,255,255,120))
ringed_planet(tv, 1130, 110, 56)
planet(tv, W/2, H*1.25, H*0.78)
glow_line(tv, W/2, H*1.25, H*0.78 + 14*H*0.78*0.035)
d = ImageDraw.Draw(tv)
for x, b, s in [(130, 640, 120), (260, 600, 80), (1040, 625, 125), (1160, 650, 90), (700, 545, 50), (580, 550, 40)]:
    pine(d, x, b, s)
rock(d, 860, 600, 60)
rock(d, 400, 660, 80)
for x in (500, 570, 640, 720, 790):
    flower(tv, x, 640 - abs(x - 645) * 0.1, 14, (255,111,174))
title = ImageFont.truetype(BOLD, 150)
t = "HORIZON RUN"
tw = d.textlength(t, font=title)
shadow = Image.new("RGBA", tv.size, (0,0,0,0)); sd = ImageDraw.Draw(shadow)
sd.text(((W-tw)/2+6, 215+9), t, font=title, fill=(0,0,0,160))
tv.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(9)))
d = ImageDraw.Draw(tv)
d.text(((W-tw)/2, 215), t, font=title, fill=(255,255,255))
tv.convert("RGB").resize((320, 180), Image.LANCZOS).save("tv-banner-320x180.png")
print("ok")
