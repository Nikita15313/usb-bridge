"""Render USB Bridge's code-native chain mark for Windows; requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "source/branding"
SCALE = 16
SIZE = 64 * SCALE

def box(coords):
    return tuple(round(v * SCALE) for v in coords)

def render():
    canvas = Image.new("RGBA", (SIZE, SIZE))
    ImageDraw.Draw(canvas).rounded_rectangle(box((0, 0, 64, 64)), 18 * SCALE, fill="#2F66FF")
    for left in (14, 33):
        ImageDraw.Draw(canvas).rounded_rectangle(
            box((left, 24, left + 17, 40)), round(5.5 * SCALE),
            outline="white", width=3 * SCALE)
    ImageDraw.Draw(canvas).line(box((27, 32, 37, 32)), fill="white", width=3 * SCALE)
    return canvas

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    master = render()
    master.resize((512, 512), Image.Resampling.LANCZOS).save(OUT / "usbbridge.png")
    # BMP entries keep small sizes readable by .NET2 System.Drawing.Icon on Win7.
    master.resize((256, 256), Image.Resampling.LANCZOS).save(
        OUT / "usbbridge.ico", format="ICO", bitmap_format="bmp",
        sizes=[(n, n) for n in (16, 20, 24, 32, 40, 48, 64, 128, 256)])
    print("Rendered shared USB Bridge PNG/ICO from chain geometry")
