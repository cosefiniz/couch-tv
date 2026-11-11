# Generates vector icons, banner and launcher icons for the TV browser.
# Run: python -I scripts/make_res.py  (from the tv-browser folder)
import os
from PIL import Image, ImageDraw, ImageFont

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

ICONS = {
    "ic_back": "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
    "ic_forward": "M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z",
    "ic_reload": "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
    "ic_home": "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z",
    "ic_star": "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z",
    "ic_star_border": "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
    "ic_zoom_in": "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14zM12,10h-2v2H9v-2H7V9h2V7h1v2h2v1z",
    "ic_zoom_out": "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14zM7,9h5v1H7z",
    "ic_menu": "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z",
    "ic_pointer": "M7,2l12,11.2l-5.8,0.5l3.3,7.3l-2.2,1l-3.2,-7.4L7,18.5V2z",
    "ic_desktop": "M21,2H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h7v2H8v2h8v-2h-2v-2h7c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM21,16H3V4h18v12z",
    "ic_phone": "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z",
}

VEC = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="26dp" android:height="26dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="{p}" />
</vector>
"""


def font(size):
    for name in ("segoeuib.ttf", "arialbd.ttf"):
        path = os.path.join("C:/Windows/Fonts", name)
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def globe(draw, cx, cy, r, width):
    c = (255, 255, 255)
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), outline=c, width=width)
    draw.ellipse((cx - r * 0.45, cy - r, cx + r * 0.45, cy + r), outline=c, width=width)
    draw.line((cx, cy - r, cx, cy + r), fill=c, width=width)
    draw.line((cx - r, cy, cx + r, cy), fill=c, width=width)
    for f in (-0.55, 0.55):
        y = cy + r * f
        half = (r * r - (r * f) ** 2) ** 0.5
        draw.line((cx - half, y, cx + half, y), fill=c, width=width)


def gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        col = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = col
    return img


def banner():
    s = 4  # supersample
    w, h = 320 * s, 180 * s
    img = gradient(w, h, (32, 92, 200), (14, 34, 82))
    d = ImageDraw.Draw(img)
    globe(d, 70 * s, 90 * s, 42 * s, 5 * s)
    d.text((128 * s, 58 * s), "TV", font=font(34 * s), fill=(255, 255, 255))
    d.text((128 * s, 96 * s), "Tarayıcı", font=font(30 * s), fill=(255, 255, 255))
    img = img.resize((320, 180), Image.LANCZOS)
    img.save(os.path.join(RES, "drawable-xhdpi", "banner.png"))


def icon(size, folder):
    s = 4
    w = size * s
    img = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    bg = gradient(w, w, (40, 110, 225), (16, 44, 110)).convert("RGBA")
    mask = Image.new("L", (w, w), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w - 1, w - 1), fill=255)
    img.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(img)
    globe(d, w / 2, w / 2, w * 0.3, max(2, int(w * 0.04)))
    img = img.resize((size, size), Image.LANCZOS)
    img.save(os.path.join(RES, folder, "ic_launcher.png"))


if __name__ == "__main__":
    for name, p in ICONS.items():
        with open(os.path.join(RES, "drawable", name + ".xml"), "w", encoding="utf-8") as f:
            f.write(VEC.format(p=p))
    banner()
    icon(96, "mipmap-xhdpi")
    icon(144, "mipmap-xxhdpi")
    print("ok")
