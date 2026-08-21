#!/usr/bin/env python3
"""Generate the warm welcome banner (wizard.bmp) for the Inno Setup installer."""
from PIL import Image, ImageDraw, ImageFilter

W, H = 164, 314  # Inno Setup wizard image size (164x314)

img = Image.new("RGB", (W, H))
d = ImageDraw.Draw(img)

# Warm vertical gradient (deep indigo → warm amber)
for y in range(H):
    t = y / (H - 1)
    r = int(20 + (255 - 20) * t)
    g = int(20 + (180 - 20) * t)
    b = int(60 + (80 - 60) * t)
    d.line([(0, y), (W, y)], fill=(r, g, b))

# Soft glow orb near the top (a "mind palace" beacon)
glow = Image.new("RGB", (W, H), (0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse([W//2 - 40, 40, W//2 + 40, 120], fill=(255, 220, 140))
glow = glow.filter(ImageFilter.GaussianBlur(18))
img = Image.blend(img, glow, 0.55)

# Title text
d = ImageDraw.Draw(img)
d.text((W//2, 150), "MIND", fill=(255, 255, 255), anchor="mm")
d.text((W//2, 170), "PALACE", fill=(255, 220, 140), anchor="mm")

img.save("installer/wizard.bmp", "BMP")
print("wrote installer/wizard.bmp", img.size)
