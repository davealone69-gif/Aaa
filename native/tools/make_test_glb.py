#!/usr/bin/env python3
"""
Builds a dependency-free test GLB that exercises every path in
GltfAvatarLoader / PbrPipeline / GltfTextures / GltfAnimation:

  - GLB 2.0 chunk structure (JSON + BIN)
  - FLOAT / UBYTE / USHORT / UINT accessors, normalized-free
  - indices as UNSIGNED_INT (body), UNSIGNED_SHORT (head/visor/eyes)
  - interleaved attributes with byteStride (eyes mesh)
  - skinning: 4 joints, inverseBindMatrices, 2-influence vertices
  - morph targets: blink (eyes), smile (head) + mesh.weights defaults
  - animation: linear rotation channels, STEP blink weights, linear morph weights
  - materials: base color + metallicRoughness + normal + occlusion textures,
    emissive texture (data URI image), alphaMode MASK, untextured fallback
  - images both as embedded bufferViews (PNG) and a base64 data URI

Output: app/src/main/assets/avatars/my_avatar.glb
"""
import base64
import io
import json
import math
import os
import struct
import sys

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..",
                   "app", "src", "main", "assets", "avatars", "my_avatar.glb")

# ---------------------------------------------------------------- geometry

def box(w, h, d, cx=0.0, cy=0.0, cz=0.0):
    """Axis-aligned box; 24 verts (flat per-face normals), 36 indices, UVs 0..1."""
    x0, x1 = cx - w / 2, cx + w / 2
    y0, y1 = cy - h / 2, cy + h / 2
    z0, z1 = cz - d / 2, cz + d / 2
    # (corner indices 0..7), face definitions (v0..v3, normal, u-axis)
    corners = [
        (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
    ]
    # face: (quad corners ccw viewed from outside, normal)
    faces = [
        ((4, 5, 6, 7), (0, 0, 1)),   # +Z
        ((1, 0, 3, 2), (0, 0, -1)),  # -Z
        ((5, 1, 2, 6), (1, 0, 0)),   # +X
        ((0, 4, 7, 3), (-1, 0, 0)),  # -X
        ((3, 7, 6, 2), (0, 1, 0)),   # +Y
        ((0, 1, 5, 4), (0, -1, 0)),  # -Y
    ]
    pos, nrm, uv, idx = [], [], [], []
    for (a, b, c, dq), n in faces:
        base = len(pos) // 3
        for corner in (a, b, c, dq):
            pos.append(corners[corner][0])
            pos.append(corners[corner][1])
            pos.append(corners[corner][2])
            nrm.extend(n)
        uv += [0, 0, 1, 0, 1, 1, 0, 1]
        idx += [base, base + 1, base + 2, base, base + 2, base + 3]
    return (np.array(pos, dtype=np.float32),
            np.array(nrm, dtype=np.float32),
            np.array(uv, dtype=np.float32),
            np.array(idx, dtype=np.uint32))


def quad(w, h, cx=0.0, cy=0.0, cz=0.0):
    """Front-facing (+Z) quad; 4 verts, 6 indices (flat, like box())."""
    x0, x1 = cx - w / 2, cx + w / 2
    y0, y1 = cy - h / 2, cy + h / 2
    pos = [v for corner in
           [(x0, y0, cz), (x1, y0, cz), (x1, y1, cz), (x0, y1, cz)]
           for v in corner]
    nrm = [0, 0, 1] * 4
    uv = [0, 0, 1, 0, 1, 1, 0, 1]
    idx = [0, 1, 2, 0, 2, 3]
    return (np.array(pos, dtype=np.float32),
            np.array(nrm, dtype=np.float32),
            np.array(uv, dtype=np.float32),
            np.array(idx, dtype=np.uint32))


def compute_tangents(pos, nrm, uv, idx):
    """Same algorithm as MeshGeometry.generateTangents (per-vertex averaged)."""
    n = len(pos) // 3
    tan = np.zeros((n, 4), dtype=np.float32)
    bitan = np.zeros((n, 3), dtype=np.float32)
    for t in range(0, len(idx), 3):
        i0, i1, i2 = int(idx[t]), int(idx[t + 1]), int(idx[t + 2])
        e1 = pos[i1 * 3:i1 * 3 + 3] - pos[i0 * 3:i0 * 3 + 3]
        e2 = pos[i2 * 3:i2 * 3 + 3] - pos[i0 * 3:i0 * 3 + 3]
        du1 = uv[i1 * 2] - uv[i0 * 2]
        dv1 = uv[i1 * 2 + 1] - uv[i0 * 2 + 1]
        du2 = uv[i2 * 2] - uv[i0 * 2]
        dv2 = uv[i2 * 2 + 1] - uv[i0 * 2 + 1]
        det = du1 * dv2 - dv1 * du2
        if abs(det) < 1e-12:
            continue
        r = 1.0 / det
        t = (e1 * dv2 - e2 * dv1) * r
        b = (e2 * du1 - e1 * du2) * r
        for i in (i0, i1, i2):
            tan[i, :3] += t
            bitan[i] += b
    out = []
    for i in range(n):
        nrm_i = nrm[i * 3:i * 3 + 3]
        t = tan[i, :3]
        t = t - nrm_i * np.dot(nrm_i, t)
        ln = np.linalg.norm(t)
        t = t / ln if ln > 1e-12 else np.array([1.0, 0.0, 0.0])
        w = float(np.dot(np.cross(nrm_i, t), bitan[i]))
        out.extend([t[0], t[1], t[2], 1.0 if w >= 0 else -1.0])
    return np.array(out, dtype=np.float32)


# ---------------------------------------------------------------- textures

def tex_base_color(size=64):
    img = np.zeros((size, size, 3), dtype=np.uint8)
    for y in range(size):
        for x in range(size):
            # navy/teal fabric with a soft checker and noise
            c = (0.10, 0.16, 0.22)
            if ((x // 8) + (y // 8)) % 2 == 0:
                c = (0.13, 0.21, 0.28)
            n = 0.02 * math.sin(x * 12.9898 + y * 78.233)
            img[y, x] = (
                int(255 * min(1, c[0] + n)),
                int(255 * min(1, c[1] + n)),
                int(255 * min(1, c[2] + n)),
            )
    return Image.fromarray(img, "RGB")


def tex_normal(size=64):
    # diagonal ridge "knit" normal map
    img = np.zeros((size, size, 3), dtype=np.uint8)
    for y in range(size):
        for x in range(size):
            h = 0.5 + 0.5 * math.sin((x + y) * 0.6) * 0.35
            nx = math.cos((x + y) * 0.6) * 0.12
            ny = math.cos((x + y) * 0.6) * 0.12
            nz = math.sqrt(max(1 - nx * nx - ny * ny, 0.0))
            img[y, x] = (int(127.5 + nx * 127), int(127.5 + ny * 127), int(127.5 + nz * 127))
    return Image.fromarray(img, "RGB")


def tex_mr(size=64):
    # B = metallic, G = roughness, R unused (255)
    img = np.zeros((size, size, 3), dtype=np.uint8)
    img[:, :, 0] = 255
    img[:, :, 1] = 184   # roughness 0.72
    img[:, :, 2] = 16    # metallic 0.06
    return Image.fromarray(img, "RGB")


def tex_occlusion(size=64):
    img = np.ones((size, size), dtype=np.uint8) * 255
    for y in range(size):
        for x in range(size):
            d = math.hypot((x - size / 2) / (size / 2), (y - size / 2) / (size / 2))
            img[y, x] = int(255 * (1.0 - 0.25 * min(1, d)))
    return Image.fromarray(np.stack([img] * 3, axis=-1), "RGB")


def tex_emissive(size=64):
    # glowing band: dark orange, bright center stripe
    img = np.zeros((size, size, 3), dtype=np.uint8)
    for y in range(size):
        for x in range(size):
            d = abs(y - size / 2) / (size / 2)
            glow = math.exp(-d * 4.0)
            img[y, x] = (int(90 * glow), int(28 * glow), int(10 * glow))
    return Image.fromarray(img, "RGB")


# ---------------------------------------------------------------- assembly

def build():
    # ---- geometry -----------------------------------------------------
    body_parts = []
    body_parts.append(box(0.34, 0.74, 0.22, cy=0.37))           # torso
    body_parts.append(box(0.09, 0.50, 0.11, cx=-0.215, cy=0.80))  # left arm
    body_parts.append(box(0.09, 0.50, 0.11, cx=0.215, cy=0.80))   # right arm
    body_parts.append(box(0.13, 0.62, 0.13, cx=-0.09, cy=-0.31))  # left leg
    body_parts.append(box(0.13, 0.62, 0.13, cx=0.09, cy=-0.31))   # right leg
    body_parts.append(box(0.09, 0.10, 0.09, cy=0.79))             # neck

    def merge(parts):
        pos, nrm, uv, tan, idx = [], [], [], [], []
        for p, n, u, i in parts:
            base = len(pos) // 3
            pos.extend(p.tolist()); nrm.extend(n.tolist()); uv.extend(u.tolist())
            idx.extend((i + base).tolist())
        pos = np.array(pos, dtype=np.float32)
        nrm = np.array(nrm, dtype=np.float32)
        uv = np.array(uv, dtype=np.float32)
        idx = np.array(idx, dtype=np.uint32)
        return pos, nrm, uv, compute_tangents(pos, nrm, uv, idx), idx

    body_pos, body_nrm, body_uv, body_tan, body_idx = merge(body_parts)
    body_vcount = len(body_pos) // 3

    # head: box + mouth quad
    head_parts = [box(0.24, 0.30, 0.22, cy=0.15), quad(0.07, 0.03, cy=0.075, cz=0.111)]
    head_pos, head_nrm, head_uv, head_tan, head_idx = merge(head_parts)
    head_vcount = len(head_pos) // 3
    mouth_base = 24  # first quad vertex index

    visor_pos, visor_nrm, visor_uv, visor_tan, visor_idx = merge(
        [box(0.26, 0.035, 0.02, cy=0.205, cz=0.116)])
    visor_vcount = len(visor_pos) // 3

    eyes_l = quad(0.05, 0.045, cx=-0.075, cy=0.20)
    eyes_r = quad(0.05, 0.045, cx=0.075, cy=0.20)
    eyes_pos, eyes_nrm, eyes_uv, eyes_tan, eyes_idx = merge([eyes_l, eyes_r])
    eyes_vcount = len(eyes_pos) // 3

    # ---- skinning data --------------------------------------------------
    # joints: 0 root, 1 torso, 2 neck, 3 head
    def skin_weights(joint, count, special=None):
        j = np.zeros((count, 4), dtype=np.uint8)
        w = np.zeros((count, 4), dtype=np.float32)
        j[:, 0] = joint; w[:, 0] = 1.0
        if special:
            for idx, (j0, j1, w0, w1) in special.items():
                j[idx] = (j0, j1, 0, 0)
                w[idx] = (w0, w1, 0, 0)
        return j, w

    body_j, body_w = skin_weights(1, body_vcount)
    head_j, head_w = skin_weights(3, head_vcount,
        special={i: (3, 2, 0.85, 0.15) for i in range(mouth_base, mouth_base + 4)})
    visor_j, visor_w = skin_weights(3, visor_vcount)
    eyes_j, eyes_w = skin_weights(3, eyes_vcount)

    # ---- morph targets ----------------------------------------------------
    # smile: mouth quad corners up
    smile = np.zeros((head_vcount, 3), dtype=np.float32)
    for i, dy in ((mouth_base + 0, 0.012), (mouth_base + 1, 0.012),
                  (mouth_base + 2, 0.004), (mouth_base + 3, 0.004)):
        smile[i, 1] = dy
    # blink: top edge of each eye quad drops
    blink = np.zeros((eyes_vcount, 3), dtype=np.float32)
    for i in (2, 3, 6, 7):   # top-row verts of both quads
        blink[i, 1] = -0.030

    # ---- nodes -------------------------------------------------------------
    nodes = [
        {"name": "root",  "children": [1]},
        {"name": "torso", "mesh": 0, "skin": 0, "translation": [0, 0.95, 0], "children": [2]},
        {"name": "neck",  "translation": [0, 0.37, 0], "children": [3]},
        {"name": "head",  "mesh": 1, "skin": 0, "translation": [0, 0.10, 0], "children": [4]},
        {"name": "eyes",  "mesh": 2, "skin": 0, "translation": [0, 0.14, 0.115]},
    ]
    scenes = [{"nodes": [0]}]

    # ---- animation -----------------------------------------------------------
    def z_quat(angle):
        return [0.0, 0.0, math.sin(angle / 2), math.cos(angle / 2)]

    anim_times = [0.0, 0.75, 1.5, 2.25, 3.0]
    torso_rot = [z_quat(a) for a in (0.07, -0.02, -0.07, -0.02, 0.07)]
    head_rot = [z_quat(a) for a in (-0.05, 0.03, 0.05, 0.03, -0.05)]
    blink_times = [0.0, 0.6, 1.2, 2.4, 3.0]
    blink_w = [0.0, 0.0, 1.0, 1.0, 0.0]

    # placeholders replaced with real accessor indices below (after all
    # mesh accessors are allocated)
    animations = [{
        "name": "idle",
        "samplers": [
            {"input": -1, "output": -1, "interpolation": "LINEAR"},
            {"input": -1, "output": -1, "interpolation": "LINEAR"},
            {"input": -1, "output": -1, "interpolation": "STEP"},
            {"input": -1, "output": -1, "interpolation": "LINEAR"},
        ],
        "channels": [
            {"sampler": 0, "target": {"node": 1, "path": "rotation"}},
            {"sampler": 1, "target": {"node": 3, "path": "rotation"}},
            {"sampler": 2, "target": {"node": 4, "path": "weights"}},
            {"sampler": 3, "target": {"node": 3, "path": "weights"}},
        ],
    }]

    # ---- GLB buffer -----------------------------------------------------------
    buf = bytearray()
    accessors = []   # (type, compType, count, components, data)
    buffer_views = []  # (bufferOffset, byteLength, byteStride or None)
    anim_accessors = []  # accessor indices used by the animation

    def align(n):
        while len(buf) % n != 0:
            buf.append(0)

    def add_buffer_view(byte_length, byte_stride=None):
        align(4)
        buffer_views.append({"buffer": 0, "byteOffset": len(buf),
                             "byteLength": byte_length,
                             **({"byteStride": byte_stride} if byte_stride else {})})
        return len(buffer_views) - 1

    def add_accessor(type_, comp_type, count, components, data, stride=None):
        align(comp_type_size(comp_type))
        view_index = add_buffer_view(data.size * comp_type_size(comp_type), stride)
        buf.extend(data.tobytes())
        accessors.append({
            "bufferView": view_index,
            "byteOffset": 0,
            "componentType": comp_type,
            "count": count,
            "type": type_,
        })
        return len(accessors) - 1

    def comp_type_size(ct):
        return {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}[ct]

    def add_float(name, data, components):
        return add_accessor("VEC%d" % components, 5126, data.size // components, components,
                            np.ascontiguousarray(data, dtype=np.float32))

    def add_ubyte(data, components):
        return add_accessor("VEC%d" % components, 5121, data.size // components, components,
                            np.ascontiguousarray(data, dtype=np.uint8))

    def add_indices(data, dtype):
        ct = {np.uint16: 5123, np.uint32: 5125}[dtype]
        return add_accessor("SCALAR", ct, data.size, 1,
                            np.ascontiguousarray(data, dtype=dtype))

    # --- body mesh (UNSIGNED_INT indices, separate views) ---
    b_pos = add_float("POSITION", body_pos, 3)
    b_nrm = add_float("NORMAL", body_nrm, 3)
    b_uv = add_float("TEXCOORD_0", body_uv, 2)
    b_tan = add_float("TANGENT", body_tan, 4)
    b_j = add_ubyte(body_j, 4)
    b_w = add_float("WEIGHTS_0", body_w, 4)
    b_i = add_indices(body_idx, np.uint32)

    # --- head mesh (UNSIGNED_SHORT indices) ---
    h_pos = add_float("POSITION", head_pos, 3)
    h_nrm = add_float("NORMAL", head_nrm, 3)
    h_uv = add_float("TEXCOORD_0", head_uv, 2)
    h_tan = add_float("TANGENT", head_tan, 4)
    h_j = add_ubyte(head_j, 4)
    h_w = add_float("WEIGHTS_0", head_w, 4)
    h_i = add_indices(head_idx.astype(np.uint16), np.uint16)
    h_smile = add_float("POSITION", smile, 3)

    # --- visor primitive (UNSIGNED_SHORT indices) ---
    v_pos = add_float("POSITION", visor_pos, 3)
    v_nrm = add_float("NORMAL", visor_nrm, 3)
    v_uv = add_float("TEXCOORD_0", visor_uv, 2)
    v_tan = add_float("TANGENT", visor_tan, 4)
    v_j = add_ubyte(visor_j, 4)
    v_w = add_float("WEIGHTS_0", visor_w, 4)
    v_i = add_indices(visor_idx.astype(np.uint16), np.uint16)

    # --- eyes mesh (interleaved, byteStride 32, UNSIGNED_SHORT indices) ---
    e_count = eyes_vcount
    interleaved = np.zeros((e_count, 8), dtype=np.float32)
    interleaved[:, 0:3] = eyes_pos.reshape(e_count, 3)
    interleaved[:, 3:6] = eyes_nrm.reshape(e_count, 3)
    interleaved[:, 6:8] = eyes_uv.reshape(e_count, 2)
    e_view_index = add_buffer_view(e_count * 32, 32)
    buf.extend(interleaved.tobytes())
    e_pos = len(accessors); accessors.append({"bufferView": e_view_index, "byteOffset": 0,
        "componentType": 5126, "count": e_count, "type": "VEC3"})
    e_nrm = len(accessors); accessors.append({"bufferView": e_view_index, "byteOffset": 12,
        "componentType": 5126, "count": e_count, "type": "VEC3"})
    e_uv = len(accessors); accessors.append({"bufferView": e_view_index, "byteOffset": 24,
        "componentType": 5126, "count": e_count, "type": "VEC2"})
    e_j = add_ubyte(eyes_j, 4)
    e_w = add_float("WEIGHTS_0", eyes_w, 4)
    e_i = add_indices(eyes_idx.astype(np.uint16), np.uint16)
    e_blink = add_float("POSITION", blink, 3)

    # --- skin ---
    ibm = np.zeros((4, 16), dtype=np.float32)
    for i, t in enumerate([(0, 0, 0), (0, -0.95, 0), (0, -1.32, 0), (0, -1.42, 0)]):
        ibm[i][0] = 1; ibm[i][5] = 1; ibm[i][10] = 1; ibm[i][15] = 1
        ibm[i][12], ibm[i][13], ibm[i][14] = t
    skin_ibm = add_accessor("MAT4", 5126, 4, 16, ibm)

    # --- animation accessors ---
    def add_scalar_times(times):
        return add_accessor("SCALAR", 5126, len(times), 1, np.array(times, dtype=np.float32))

    def add_quats(quats):
        return add_accessor("VEC4", 5126, len(quats), 4, np.array(quats, dtype=np.float32))

    def add_weights(w):
        return add_accessor("SCALAR", 5126, len(w), 1, np.array(w, dtype=np.float32))

    a_torso_in = add_scalar_times(anim_times)
    a_torso_out = add_quats(torso_rot)
    a_head_in = add_scalar_times(anim_times)
    a_head_out = add_quats(head_rot)
    a_blink_in = add_scalar_times(blink_times)
    a_blink_out = add_weights(blink_w)
    a_smile_in = add_scalar_times([0.0, 3.0])
    a_smile_out = add_weights([0.15, 0.60])

    # wire the real accessor indices into the animation samplers
    anim_samplers = [
        (a_torso_in, a_torso_out),
        (a_head_in, a_head_out),
        (a_blink_in, a_blink_out),
        (a_smile_in, a_smile_out),
    ]
    for s, (i, o) in enumerate(anim_samplers):
        animations[0]["samplers"][s]["input"] = i
        animations[0]["samplers"][s]["output"] = o

    # --- images (PNG) ---
    images = []
    textures = []
    image_specs = [tex_base_color(), tex_normal(), tex_mr(), tex_occlusion(), tex_emissive()]

    for idx, img in enumerate(image_specs):
        png = io.BytesIO()
        img.save(png, format="PNG")
        raw = png.getvalue()
        if idx == 4:
            # exercise the data-URI image path
            uri = "data:image/png;base64," + base64.b64encode(raw).decode()
            images.append({"uri": uri, "mimeType": "image/png"})
        else:
            align(4)
            off = len(buf)
            buffer_views.append({"buffer": 0, "byteOffset": off, "byteLength": len(raw)})
            buf.extend(raw)
            images.append({"bufferView": len(buffer_views) - 1, "mimeType": "image/png"})
        textures.append({"source": idx, "sampler": 0})

    # --- materials ---
    materials = [
        {  # 0 outfit
            "name": "outfit",
            "pbrMetallicRoughness": {
                "baseColorFactor": [1, 1, 1, 1],
                "metallicFactor": 1.0,
                "roughnessFactor": 1.0,
                "baseColorTexture": {"index": 0},
                "metallicRoughnessTexture": {"index": 2},
            },
            "normalTexture": {"index": 1, "scale": 1.0},
            "occlusionTexture": {"index": 3, "strength": 0.85},
            "emissiveFactor": [0, 0, 0],
        },
        {  # 1 head
            "name": "head",
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.96, 0.80, 0.65, 1],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.78,
            },
            "normalTexture": {"index": 1, "scale": 0.8},
        },
        {  # 2 eyes
            "name": "eyes",
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.015, 0.015, 0.02, 1],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.25,
            },
        },
        {  # 3 visor (emissive, MASK)
            "name": "visor",
            "pbrMetallicRoughness": {
                "baseColorFactor": [1, 1, 1, 1],
                "metallicFactor": 0.1,
                "roughnessFactor": 0.4,
            },
            "emissiveFactor": [0.5, 0.16, 0.05],
            "emissiveTexture": {"index": 4},
            "alphaMode": "MASK",
            "doubleSided": True,
        },
    ]

    meshes = [
        {
            "name": "body",
            "primitives": [{
                "attributes": {"POSITION": b_pos, "NORMAL": b_nrm, "TEXCOORD_0": b_uv,
                               "TANGENT": b_tan, "JOINTS_0": b_j, "WEIGHTS_0": b_w},
                "indices": b_i,
                "material": 0,
            }],
        },
        {
            "name": "head",
            "weights": [0.0],
            "primitives": [
                {
                    "attributes": {"POSITION": h_pos, "NORMAL": h_nrm, "TEXCOORD_0": h_uv,
                                   "TANGENT": h_tan, "JOINTS_0": h_j, "WEIGHTS_0": h_w},
                    "indices": h_i,
                    "material": 1,
                    "targets": [{"POSITION": h_smile}],
                },
                {
                    "attributes": {"POSITION": v_pos, "NORMAL": v_nrm, "TEXCOORD_0": v_uv,
                                   "TANGENT": v_tan, "JOINTS_0": v_j, "WEIGHTS_0": v_w},
                    "indices": v_i,
                    "material": 3,
                },
            ],
        },
        {
            "name": "eyes",
            "weights": [0.0],
            "primitives": [{
                "attributes": {"POSITION": e_pos, "NORMAL": e_nrm, "TEXCOORD_0": e_uv,
                               "JOINTS_0": e_j, "WEIGHTS_0": e_w},
                "indices": e_i,
                "material": 2,
                "targets": [{"POSITION": e_blink}],
            }],
        },
    ]

    skins = [{
        "name": "avatar",
        "joints": [0, 1, 2, 3],
        "inverseBindMatrices": skin_ibm,
    }]

    samplers = [{
        "magFilter": 9729,
        "minFilter": 9987,   # LINEAR_MIPMAP_LINEAR
        "wrapS": 10497,      # REPEAT
        "wrapT": 10497,
    }]

    # ---- write GLB ----------------------------------------------------------
    gltf = {
        "asset": {"version": "2.0", "generator": "aura-avatarstudio-test"},
        "scene": 0,
        "scenes": scenes,
        "nodes": nodes,
        "meshes": meshes,
        "accessors": accessors,
        "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(buf)}],
        "materials": materials,
        "skins": skins,
        "images": images,
        "textures": textures,
        "samplers": samplers,
        "animations": animations,
    }

    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    while len(json_bytes) % 4 != 0:
        json_bytes += b" "
    while len(buf) % 4 != 0:
        buf.append(0)

    total = 12 + 8 + len(json_bytes) + 8 + len(buf)
    glb = bytearray()
    glb += struct.pack("<III", 0x46546C67, 2, total)
    glb += struct.pack("<II", len(json_bytes), 0x4E4F534A)
    glb += json_bytes
    glb += struct.pack("<II", len(buf), 0x004E4942)
    glb += buf

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(glb)
    print("wrote %s (%.1f KB, buffer %.1f KB)" % (OUT, len(glb) / 1024, len(buf) / 1024))
    return glb, gltf


# ---------------------------------------------------------------- validation

def validate(glb, gltf):
    assert glb[:4] == b"glTF", "bad magic"
    (magic, version, length) = struct.unpack("<III", glb[:12])
    assert version == 2 and length == len(glb), "bad header"

    offset = 12
    chunks = {}
    while offset < len(glb):
        (clen, ctype) = struct.unpack("<II", glb[offset:offset + 8])
        chunks[ctype] = glb[offset + 8:offset + 8 + clen]
        offset += 8 + clen
    assert 0x4E4F534A in chunks and 0x004E4942 in chunks, "missing chunks"

    binary = chunks[0x004E4942]
    accs = gltf["accessors"]
    views = gltf["bufferViews"]
    n_checked = 0
    for a in accs:
        if a.get("bufferView") is None:
            continue
        v = views[a["bufferView"]]
        ct = a["componentType"]
        comp_size = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}[ct]
        comps = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}[a["type"]]
        stride = v.get("byteStride", comps * comp_size)
        assert stride >= comps * comp_size, "stride too small"
        end = a.get("byteOffset", 0) + (a["count"] - 1) * stride + comps * comp_size
        assert end <= v["byteLength"], "accessor %d overruns view" % n_checked
        n_checked += 1
    print("validated %d accessors within %d buffer views" % (n_checked, len(views)))

    # decode every embedded image and confirm PNG integrity
    for i, img in enumerate(gltf["images"]):
        if "bufferView" in img:
            v = views[img["bufferView"]]
            raw = bytes(binary[v["byteOffset"]:v["byteOffset"] + v["byteLength"]])
        else:
            raw = base64.b64decode(img["uri"].split(",")[1])
        im = Image.open(io.BytesIO(raw))
        im.load()
        print("  image %d: %dx%d %s" % (i, im.width, im.height, im.mode))

    # animation sanity
    anim = gltf["animations"][0]
    assert len(anim["channels"]) == 4
    print("animation: %d samplers, %d channels" % (
        len(anim["samplers"]), len(anim["channels"])))
    return True


# ---------------------------------------------------------------- preview

def preview(gltf):
    import matplotlib
    matplotlib.use("Agg")
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection
    import matplotlib.pyplot as plt

    accs = gltf["accessors"]
    views = gltf["bufferViews"]
    meshes = gltf["meshes"]
    materials = gltf["materials"]

    fig = plt.figure(figsize=(6, 8), facecolor="#14161c")
    ax = fig.add_subplot(111, projection="3d")
    ax.set_facecolor("#14161c")

    COMP_SZ = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}
    FMT = {5121: "B", 5122: "h", 5123: "H", 5125: "I", 5126: "f"}

    def read(acc_idx, comps):
        a = accs[acc_idx]
        v = views[a["bufferView"]]
        ct = a["componentType"]
        size = FMT[ct]
        stride = v.get("byteStride", comps * COMP_SZ[ct])
        out = []
        for i in range(a["count"]):
            off = v["byteOffset"] + a.get("byteOffset", 0) + i * stride
            for c in range(comps):
                out.append(struct.unpack_from("<" + size, BUFFER_BYTES, off + c * COMP_SZ[ct])[0])
        return out

    colors = {0: "#4a7a8c", 1: "#f0c9a4", 2: "#0a0a12", 3: "#ff7a1f"}
    for mesh in meshes:
        for prim in mesh["primitives"]:
            pos = read(prim["attributes"]["POSITION"], 3)
            idx = read(prim["indices"], 1)
            mat_idx = prim["material"]
            tris = []
            for t in range(0, len(idx), 3):
                tri = []
                for i in idx[t:t + 3]:
                    tri.append((pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]))
                tris.append(tri)
            col = colors.get(mat_idx, "#888888")
            pc = Poly3DCollection(tris, facecolor=col, edgecolor="none", alpha=0.95)
            ax.add_collection3d(pc)

    ax.set_xlim(-0.5, 0.5); ax.set_ylim(-0.5, 0.5); ax.set_zlim(-0.6, 1.9)
    ax.view_init(elev=12, azim=-55)
    ax.set_axis_off()
    out_png = os.path.join(os.path.dirname(OUT), "avatar_preview.png")
    plt.tight_layout(pad=0)
    plt.savefig(out_png, facecolor="#14161c", bbox_inches="tight")
    print("preview ->", out_png)


if __name__ == "__main__":
    glb, gltf = build()

    # locate the BIN chunk for the preview reader
    off = 12
    BUFFER_BYTES = b""
    while off < len(glb):
        (clen, ctype) = struct.unpack("<II", glb[off:off + 8])
        if ctype == 0x004E4942:
            BUFFER_BYTES = glb[off + 8:off + 8 + clen]
            break
        off += 8 + clen

    validate(glb, gltf)
    preview(gltf)
