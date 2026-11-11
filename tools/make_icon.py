"""The app icon from Philibert's own icon (brand/round3/philibert-icon.svg): the cyan boombox with its
glow on the navy tile, as an Android adaptive icon (background + foreground layers, 108 dp, the
boombox inside the 66 dp safe zone) rendered with Edge like the desktop icons.
python tools/make_icon.py   (Pillow + Microsoft Edge)"""
import subprocess
import tempfile
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent.parent
RES = HERE / "app" / "src" / "main" / "res"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
DENS = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

BOOMBOX = '''
    <path d="M62 62 V46 Q62 34 74 34 H126 Q138 34 138 46 V62" fill="none" stroke="#00e5ff" stroke-width="10" stroke-linecap="round"/>
    <rect x="26" y="60" width="148" height="100" rx="16" fill="#00e5ff"/>
    <circle cx="70" cy="104" r="25" fill="#0f172a"/><circle cx="70" cy="104" r="17" fill="#00e5ff"/><circle cx="70" cy="104" r="6" fill="#0f172a"/>
    <circle cx="130" cy="104" r="25" fill="#0f172a"/><circle cx="130" cy="104" r="17" fill="#00e5ff"/><circle cx="130" cy="104" r="6" fill="#0f172a"/>
    <path d="M48 143 H86 L93 134 L100 150 L107 138 L112 143 H152" fill="none" stroke="#0f172a" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="M100 150 L107 138 L112 143" fill="none" stroke="#ff2e88" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>
    <rect x="44" y="160" width="20" height="10" rx="3" fill="#00e5ff"/><rect x="136" y="160" width="20" height="10" rx="3" fill="#00e5ff"/>'''

# the boombox (x 26-174, y 34-170) at 0.31 scale, centred (a little low, like the desktop icon)
PLACE = "translate(23 24.4) scale(0.31)"

BACK = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <defs>
    <linearGradient id="tile" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1e3054"/><stop offset="1" stop-color="#0b1222"/></linearGradient>
    <radialGradient id="halo" cx="0.5" cy="0.53" r="0.42">
      <stop offset="0" stop-color="#00e5ff" stop-opacity="0.32"/><stop offset="1" stop-color="#00e5ff" stop-opacity="0"/></radialGradient>
  </defs>
  <rect width="108" height="108" fill="url(#tile)"/>
  <circle cx="54" cy="57" r="46" fill="url(#halo)"/>
</svg>'''

FORE = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <defs><filter id="glow" x="-30%" y="-30%" width="160%" height="160%">
    <feGaussianBlur stdDeviation="4" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>
  <g transform="''' + PLACE + '''" filter="url(#glow)">''' + BOOMBOX + '''
  </g>
</svg>'''


def render(svg_text, name, px):
    tmp = Path(tempfile.gettempdir()) / "philibert-phone-icon"
    tmp.mkdir(exist_ok=True)
    svg = tmp / f"{name}.svg"
    svg.write_text(svg_text, encoding="utf-8")
    big = 864
    html = tmp / f"{name}.html"
    html.write_text(f"<html><body style='margin:0;background:transparent'><img src='{svg.as_uri()}' width='{big}' "
                    f"height='{big}' style='display:block'></body></html>", encoding="utf-8")
    shot = tmp / f"{name}_{big}.png"
    shot.unlink(missing_ok=True)
    if True:
        subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
                        f"--user-data-dir={tmp}\\edge", f"--window-size={big},{big}", f"--screenshot={shot}", html.as_uri()],
                       capture_output=True, timeout=90)
    return Image.open(shot).convert("RGBA").resize((px, px), Image.LANCZOS)


for d, px in DENS.items():
    out = RES / f"mipmap-{d}"
    out.mkdir(parents=True, exist_ok=True)
    back, fore = render(BACK, "back", px), render(FORE, "fore", px)
    back.save(out / "ic_launcher_background.png")
    fore.save(out / "ic_launcher_foreground.png")
    # the legacy square icon (older launchers, the share sheet): both layers, the middle 72/108
    full = Image.alpha_composite(back, fore)
    m = round(px * 18 / 108)
    full.crop((m, m, px - m, px - m)).resize((round(px * 48 / 108),) * 2, Image.LANCZOS).save(out / "ic_launcher.png")
print("icons ->", RES)
