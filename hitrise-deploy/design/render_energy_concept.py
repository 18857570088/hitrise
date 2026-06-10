from __future__ import annotations

from pathlib import Path
import math

from PIL import Image, ImageDraw, ImageFilter, ImageFont


OUT_DIR = Path(__file__).resolve().parent
PNG = OUT_DIR / "hitrise_live_training_energy_concept.png"
GIF = OUT_DIR / "hitrise_live_training_energy_motion.gif"
W, H = 430, 900
S = 2

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\arial.ttf",
]
FONT_PATH = next((p for p in FONT_CANDIDATES if Path(p).exists()), None)


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    return ImageFont.truetype(FONT_PATH, size) if FONT_PATH else ImageFont.load_default()


F = {
    "tiny": font(18),
    "small": font(21),
    "body": font(24),
    "body_b": font(25),
    "title": font(35),
    "metric": font(38),
    "timer": font(70),
    "button": font(30),
}

COL = {
    "teal": (101, 207, 167),
    "teal_soft": (158, 223, 196),
    "neon": (52, 255, 145),
    "neon_soft": (155, 255, 201),
    "gold": (216, 186, 112),
    "gold_bright": (241, 212, 135),
    "platinum": (255, 243, 197),
    "amber": (217, 130, 59),
    "copper": (185, 83, 55),
    "red": (158, 47, 46),
    "deep_red": (112, 37, 36),
    "text": (245, 241, 230),
    "muted": (169, 178, 172),
}


def rr(draw: ImageDraw.ImageDraw, box, radius: int, fill, outline=None, width: int = 1) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def gradient_rect(size, top, bottom) -> Image.Image:
    w, h = size
    img = Image.new("RGB", size, top)
    pix = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
        for x in range(w):
            pix[x, y] = c
    return img


def paste_rounded(base: Image.Image, box, fill_img: Image.Image, radius: int, outline=None) -> None:
    x1, y1, x2, y2 = box
    mask = Image.new("L", (x2 - x1, y2 - y1), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle((0, 0, x2 - x1, y2 - y1), radius=radius, fill=255)
    base.paste(fill_img.resize((x2 - x1, y2 - y1)), (x1, y1), mask)
    if outline:
        ImageDraw.Draw(base).rounded_rectangle(box, radius=radius, outline=outline, width=2)


def text_center(draw: ImageDraw.ImageDraw, box, text: str, fnt, fill) -> None:
    x1, y1, x2, y2 = box
    bbox = draw.textbbox((0, 0), text, font=fnt)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    draw.text((x1 + (x2 - x1 - tw) / 2, y1 + (y2 - y1 - th) / 2 - 2), text, font=fnt, fill=fill)


def draw_bar_gradient(draw_img: Image.Image, x: int, y: int, w: int, h: int, ratio: float) -> None:
    ratio = max(0, min(1, ratio))
    d = ImageDraw.Draw(draw_img, "RGBA")
    rr(d, (x, y, x + w, y + h), h // 2, (255, 255, 255, 22))
    gw = int(w * ratio)
    if gw <= 0:
        return
    grad = Image.new("RGBA", (gw, h), (0, 0, 0, 0))
    gp = grad.load()
    for xx in range(gw):
        t = xx / max(1, gw - 1)
        if t < 0.58:
            a, b, tt = COL["neon"], COL["gold_bright"], t / 0.58
        else:
            a, b, tt = COL["gold_bright"], COL["amber"], (t - 0.58) / 0.42
        c = tuple(int(a[i] * (1 - tt) + b[i] * tt) for i in range(3)) + (255,)
        for yy in range(h):
            gp[xx, yy] = c
    mask = Image.new("L", (gw, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, gw, h), radius=h // 2, fill=255)
    draw_img.paste(grad, (x, y), mask)


def draw_force_gradient_bar(base: Image.Image, x: int, y: int, w: int, h: int) -> None:
    grad = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    pix = grad.load()
    stops = [
        (0.0, COL["neon_soft"]),
        (0.35, COL["gold"]),
        (0.58, COL["amber"]),
        (0.78, COL["copper"]),
        (1.0, COL["deep_red"]),
    ]
    for yy in range(h):
        t = 1 - yy / max(1, h - 1)
        c = stops[-1][1] + (255,)
        for i in range(len(stops) - 1):
            if stops[i][0] <= t <= stops[i + 1][0]:
                tt = (t - stops[i][0]) / (stops[i + 1][0] - stops[i][0])
                a, b = stops[i][1], stops[i + 1][1]
                c = tuple(int(a[k] * (1 - tt) + b[k] * tt) for k in range(3)) + (255,)
                break
        for xx in range(w):
            pix[xx, yy] = c
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w, h), radius=10, fill=255)
    base.paste(grad, (x, y), mask)


def draw_mock(frame: int = 0) -> Image.Image:
    img = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 255))
    img.paste(gradient_rect((W * S, H * S), (2, 4, 3), (8, 12, 11)).convert("RGBA"), (0, 0))
    glow = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow, "RGBA")
    gd.ellipse((35 * S, -90 * S, 390 * S, 250 * S), fill=(216, 186, 112, 42))
    gd.ellipse((260 * S, 110 * S, 520 * S, 360 * S), fill=(52, 255, 145, 14))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(28 * S)))
    d = ImageDraw.Draw(img, "RGBA")
    rr(d, (14 * S, 14 * S, 416 * S, 886 * S), 34 * S, (5, 9, 8, 238), (255, 243, 197, 86), 2 * S)
    d.text((30 * S, 28 * S), "9:41", font=F["small"], fill=COL["text"])
    d.text((255 * S, 28 * S), "SENBALL#A · 86%", font=F["small"], fill=COL["neon_soft"])

    paste_rounded(
        img,
        (28 * S, 58 * S, 402 * S, 206 * S),
        gradient_rect((374 * S, 148 * S), (45, 50, 50), (9, 13, 12)).convert("RGBA"),
        18 * S,
        (216, 186, 112, 72),
    )
    d = ImageDraw.Draw(img, "RGBA")
    d.text((44 * S, 76 * S), "HitRise 实时训练", font=F["title"], fill=COL["text"])
    d.text((45 * S, 116 * S), "金属燃脂风 · 持续运动反馈", font=F["small"], fill=COL["muted"])
    rr(d, (326 * S, 72 * S, 386 * S, 122 * S), 14 * S, (255, 243, 197, 22), (255, 243, 197, 92), 2 * S)
    d.text((344 * S, 78 * S), "⚙", font=F["body"], fill=COL["platinum"])
    d.text((334 * S, 101 * S), "设置", font=F["tiny"], fill=COL["text"])
    rr(d, (44 * S, 140 * S, 386 * S, 190 * S), 15 * S, (55, 47, 31, 240), (216, 186, 112, 98), 2 * S)
    d.text((58 * S, 150 * S), "今日目标：500拳 · 已完成 186拳", font=F["body_b"], fill=COL["platinum"])
    d.text((58 * S, 174 * S), "目标进度 37% · 连续运动 06:18", font=F["tiny"], fill=(201, 208, 200))
    draw_bar_gradient(img, 238 * S, 174 * S, 136 * S, 11 * S, 0.37 + 0.04 * math.sin(frame / 6 * math.pi))

    paste_rounded(
        img,
        (28 * S, 218 * S, 402 * S, 786 * S),
        gradient_rect((374 * S, 568 * S), (33, 40, 41), (8, 12, 11)).convert("RGBA"),
        22 * S,
        (216, 186, 112, 48),
    )
    d = ImageDraw.Draw(img, "RGBA")
    d.text((44 * S, 236 * S), "实时训练", font=F["body_b"], fill=(226, 217, 195))
    rr(d, (158 * S, 232 * S, 280 * S, 266 * S), 17 * S, (255, 243, 197, 230), (217, 130, 59, 170), 1 * S)
    text_center(d, (158 * S, 232 * S, 280 * S, 266 * S), "第 2 / 3 回合", F["small"], (6, 17, 12))

    cx, cy, r = 215 * S, 365 * S, 78 * S
    progress = 0.72 + 0.025 * math.sin(frame / 6 * math.pi)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(13, 18, 19, 255), outline=(255, 243, 197, 58), width=2 * S)
    d.arc((cx - r, cy - r, cx + r, cy + r), start=-90, end=-90 + 360 * progress, fill=COL["gold"], width=12 * S)
    d.arc((cx - r, cy - r, cx + r, cy + r), start=-90 + 360 * 0.47, end=-90 + 360 * 0.52, fill=COL["neon"], width=12 * S)
    d.arc((cx - r, cy - r, cx + r, cy + r), start=-90 + 360 * 0.52, end=-90 + 360 * progress, fill=COL["teal"], width=12 * S)
    d.arc((cx - r + 8 * S, cy - r + 8 * S, cx + r - 8 * S, cy + r - 8 * S), start=-90, end=270, fill=(255, 255, 255, 25), width=2 * S)
    d.ellipse((cx - 61 * S, cy - 61 * S, cx + 61 * S, cy + 61 * S), fill=(11, 17, 16, 255))
    text_center(d, (cx - 70 * S, cy - 42 * S, cx + 70 * S, cy + 18 * S), "00:42", F["timer"], COL["text"])
    text_center(d, (cx - 70 * S, cy + 22 * S, cx + 70 * S, cy + 48 * S), "燃脂冲刺", F["small"], COL["muted"])
    alpha = int(150 + 70 * (0.5 + 0.5 * math.sin(frame / 3 * math.pi)))
    text_center(d, (108 * S, 515 * S, 322 * S, 544 * S), "3 · 2 · 1 · GO", F["body_b"], COL["platinum"] + (alpha,))

    labels = [
        ("186", "总击打数", COL["text"]),
        ("92", "BPM", COL["text"]),
        ("31.8", "卡路里 kcal", COL["platinum"]),
        ("4.1", "等效燃脂 g", COL["neon_soft"]),
    ]
    x0, y0, gap, mw, mh = 44, 548, 8, 81, 76
    for idx, (val, lbl, col) in enumerate(labels):
        x, y = (x0 + idx * (mw + gap)) * S, y0 * S
        rr(d, (x, y, x + mw * S, y + mh * S), 14 * S, (24, 30, 31, 245), (216, 186, 112, 62), 2 * S)
        text_center(d, (x, y + 9 * S, x + mw * S, y + 39 * S), val, F["metric"], col)
        text_center(d, (x, y + 47 * S, x + mw * S, y + 70 * S), lbl, F["tiny"], COL["muted"])

    rr(d, (44 * S, 638 * S, 386 * S, 746 * S), 16 * S, (21, 27, 28, 245), (216, 186, 112, 58), 2 * S)
    d.text((58 * S, 651 * S), "击打力度", font=F["small"], fill=(220, 212, 190))
    rr(d, (292 * S, 646 * S, 374 * S, 672 * S), 13 * S, (158, 47, 46, 58), (190, 78, 58, 88), 2 * S)
    text_center(d, (292 * S, 646 * S, 374 * S, 672 * S), "峰值1180N", F["tiny"], (255, 198, 188))
    heights = [28, 45, 36, 62, 78, 48, 83, 55, 88, 68, 94, 51, 73, 38]
    basey, bx, bw, bgap = 728 * S, 59 * S, 18 * S, 5 * S
    for idx, h in enumerate(heights):
        dyn = h + 5 * math.sin((frame + idx * 0.7) / 2)
        barh = int(max(18, min(98, dyn)) * 0.64 * S)
        draw_force_gradient_bar(img, bx + idx * (bw + bgap), basey - barh, bw, barh)
    text_center(d, (55 * S, 748 * S, 145 * S, 772 * S), "最小 420N", F["tiny"], COL["muted"])
    text_center(d, (165 * S, 748 * S, 255 * S, 772 * S), "最大 1180N", F["tiny"], COL["text"])
    text_center(d, (278 * S, 748 * S, 374 * S, 772 * S), "平均 760N", F["tiny"], COL["muted"])

    y = 802 * S
    rr(d, (28 * S, y, 204 * S, y + 52 * S), 16 * S, (255, 243, 197, 230), (217, 130, 59, 150), 2 * S)
    rr(d, (216 * S, y, 402 * S, y + 52 * S), 16 * S, (111, 37, 36, 230), (184, 59, 54, 140), 2 * S)
    text_center(d, (28 * S, y, 204 * S, y + 52 * S), "开始", F["button"], (6, 17, 12))
    text_center(d, (216 * S, y, 402 * S, y + 52 * S), "结束", F["button"], (255, 241, 236))
    text_center(d, (28 * S, 862 * S, 402 * S, 884 * S), "确认稿：正式开发会保留现有蓝牙、回合、战报和上传逻辑", F["tiny"], (220, 255, 233, 185))
    return img.resize((W, H), Image.Resampling.LANCZOS).convert("RGB")


def main() -> None:
    frames = [draw_mock(i) for i in range(12)]
    frames[4].save(PNG, quality=95)
    frames[0].save(GIF, save_all=True, append_images=frames[1:], duration=130, loop=0, optimize=True)
    print(PNG)
    print(GIF)


if __name__ == "__main__":
    main()
