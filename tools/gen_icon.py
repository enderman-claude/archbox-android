"""usage: gen_icon.py <commit-sha> <target-sdk> <out.png>  -> 432x432 icon showing the short commit."""
import colorsys, sys
from PIL import Image, ImageDraw, ImageFont

sha, sdk, out = sys.argv[1], sys.argv[2], sys.argv[3]
short = sha[:7]
hue = int(sha[:6], 16) % 360 / 360          # background colour is derived from the commit
r, g, b = [int(c * 255) for c in colorsys.hsv_to_rgb(hue, 0.55, 0.45)]
S = 432
img = Image.new("RGB", (S, S), (r, g, b))
d = ImageDraw.Draw(img)

def font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationMono-Bold.ttf"):
        try: return ImageFont.truetype(p, size)
        except OSError: pass
    return ImageFont.load_default(size=size)

def centered(text, y, size, fill):
    f = font(size)
    w = d.textlength(text, font=f)
    d.text(((S - w) / 2, y), text, font=f, fill=fill)

centered(">_", 40, 120, (255, 255, 255))
centered(short, 200, 70, (255, 255, 255))
centered("SDK " + sdk, 300, 56, (255, 220, 120))
d.rounded_rectangle([6, 6, S - 7, S - 7], radius=60, outline=(255, 255, 255), width=6)
img.save(out)
