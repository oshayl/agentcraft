"""A plainer character than the hand-drawn six.

The original cast are hand-authored pixel grids, face by face. These four are built from a
colour scheme instead: skin, hair, a garment ramp, trousers and shoes, with the face the only
grid anybody has to read. They are flat-shaded where the six are modelled, which is obvious
side by side - but they are real, distinct, in the pipeline, and they validate, which beats six
more copies of somebody else's skin.
"""
from __future__ import annotations

from chars.base import H, P, Skin, T, legend_hair, legend_skin


def plain(ramp_id, *, hair, skin_tone, slim, trousers="ramps.graphite", shoes="ramps.ink", eye="#2B2A28"):
    sk = Skin(slim=slim)
    cloth = T("cast_ramps", ramp_id)
    sktone = T("skin_tones", skin_tone)
    hr = T("hair", hair)
    tr = P(trousers + "[2]")
    sh = P(shoes + "[1]")

    # ---- head: skin all round, hair over the top and back, a face on the front ----
    for f in ("top", "bottom", "right", "front", "left", "back"):
        sk.fill("head", "base", f, sktone[1])
    sk.fill("head", "base", "top", hr[1])
    sk.fill("head", "base", "back", hr[1])
    L = {}
    L.update(legend_skin(skin_tone))
    L.update(legend_hair(hair))
    L.update({"e": H(eye), "w": P("ramps.cream[1]"), "m": sktone[3]})
    sk.grid("head", "base", "front",
            ["HHHHHHHH",
             "HHHHHHHH",
             "HGGGGGGH",
             "SSSSSSSS",
             "SweSSewS",
             "SSSSSSSS",
             "SSSmmSSS",
             "dSSSSSSd"], L)
    sk.grid("head", "base", "right",
            ["HHHHHHHH",
             "HHHHHHHH",
             "HHGGSSSS",
             "HGSSSSSS",
             "GSSSSSSS",
             "GSSSSSSS",
             "GSSSSSSd",
             "dsSSSSdd"], L)
    sk.mirror_face(("head", "base", "right"), ("head", "base", "left"), flip=True)

    # ---- body: the garment, with a lighter yoke across the shoulders ----
    for f in ("top", "bottom", "right", "front", "left", "back"):
        sk.fill("body", "base", f, cloth[2])
    for f in ("front", "back"):
        x, y, w, h = sk.rect("body", "base", f)
        px = sk.img.load()
        for i in range(w):
            px[x + i, y] = cloth[1]
            px[x + i, y + 1] = cloth[1]
    sk.fill("body", "base", "top", cloth[0])

    # ---- arms: sleeve in the garment, a hand at the cuff ----
    for part in ("right_arm", "left_arm"):
        for f in ("top", "bottom", "right", "front", "left", "back"):
            sk.fill(part, "base", f, cloth[3])
        sk.fill(part, "base", "bottom", sktone[1])
        for f in ("right", "front", "left", "back"):
            x, y, w, h = sk.rect(part, "base", f)
            px = sk.img.load()
            for i in range(w):
                px[x + i, y + h - 1] = sktone[1]
                px[x + i, y + h - 2] = sktone[2]

    # ---- legs: trousers, shoes at the foot ----
    for part in ("right_leg", "left_leg"):
        for f in ("top", "bottom", "right", "front", "left", "back"):
            sk.fill(part, "base", f, tr)
        sk.fill(part, "base", "bottom", sh)
        for f in ("right", "front", "left", "back"):
            x, y, w, h = sk.rect(part, "base", f)
            px = sk.img.load()
            for i in range(w):
                px[x + i, y + h - 1] = sh
                px[x + i, y + h - 2] = sh

    return sk
