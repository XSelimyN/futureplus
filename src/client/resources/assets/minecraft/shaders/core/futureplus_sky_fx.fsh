#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Futureplus skybox shaders. Parameters come in through TextureMat:
// TextureMat[0] = (time, design, -, -), TextureMat[1] = color (that color in solid color mode, the palette color in shader mode)
in vec3 vDir;
out vec4 fragColor;

// ===================== Futureplus sky shaders =====================
// skyColor(d, t, p, c): d = view direction (unit), t = time (s), p.y = design (0..14, -1 = solid color),
// c.rgb = solid color. All in one function; fed through TextureMat on the Minecraft side.

const float PI = 3.14159265;
const float TAU = 6.28318531;

float hash11(float n) { return fract(sin(n) * 43758.5453123); }
float hash31(vec3 p) { p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3)); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
vec3 hash33(vec3 p) {
    p = vec3(dot(p, vec3(127.1, 311.7, 74.7)), dot(p, vec3(269.5, 183.3, 246.1)), dot(p, vec3(113.5, 271.9, 124.6)));
    return fract(sin(p) * 43758.5453123);
}

float vnoise(vec3 x) {
    vec3 i = floor(x), f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(i), hash31(i + vec3(1, 0, 0)), f.x),
                   mix(hash31(i + vec3(0, 1, 0)), hash31(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash31(i + vec3(0, 0, 1)), hash31(i + vec3(1, 0, 1)), f.x),
                   mix(hash31(i + vec3(0, 1, 1)), hash31(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { s += a * vnoise(p); p = p * 2.02 + vec3(1.7, 9.2, 3.1); a *= 0.5; }
    return s;
}

float fbm3(vec3 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 3; i++) { s += a * vnoise(p); p = p * 2.03 + vec3(5.1, 1.3, 7.7); a *= 0.5; }
    return s;
}

float PXA = 0.001;   // angular size of a pixel (computed at the start of skyColor)

// sine-free hash (well behaved with large numbers on the GPU too, cheap)
vec3 hash33b(vec3 p3) {
    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yxx) * p3.zyx);
}

// fast star field: a 2D grid on the cube faces, ONE cell per pixel (the old one looked at 27 cells).
// The star stays inside its cell (edge margin), its size adapts to the pixel -> no flicker, no vanishing.
vec3 stars(vec3 d, float scale, float density, float t) {
    vec3 a = abs(d); vec2 uv; float fid;
    if (a.x >= a.y && a.x >= a.z) { uv = d.yz / a.x; fid = d.x > 0.0 ? 1.0 : 2.0; }
    else if (a.y >= a.z)          { uv = d.xz / a.y; fid = d.y > 0.0 ? 3.0 : 4.0; }
    else                          { uv = d.xy / a.z; fid = d.z > 0.0 ? 5.0 : 6.0; }
    vec2 g = uv * scale * 0.85;
    float pw = min(PXA * scale * 0.85 / max(max(a.x, a.y), a.z), 0.5); // pixel size (in cell units)
    vec2 id = floor(g), f = g - id;
    vec3 h = hash33b(vec3(id, fid * 131.0 + scale));
    if (h.x > density) return vec3(0.0);
    vec3 h2 = hash33b(h * 91.7 + 3.1);
    vec2 sp = 0.2 + 0.6 * h2.xy;
    float size = 0.03 + 0.06 * h.y * h.y;
    float r = max(size, pw * 0.75);
    float dist = length(f - sp);
    float tw = 0.75 + 0.25 * sin(t * (1.0 + 3.0 * h.z) + h.y * 40.0);
    float b = smoothstep(r, 0.0, dist) * (size * size) / (r * r) * (0.35 + 1.4 * h.z * h.z * h.z) * tw;
    vec3 tint = mix(vec3(1.0, 0.85, 0.75), vec3(0.75, 0.85, 1.0), h2.z);
    return tint * b * 1.6;
}

vec3 starfield(vec3 d, float t) {
    return stars(d, 90.0, 0.10, t) + stars(d, 200.0, 0.12, t) * 0.6;
}

mat3 basisTowards(vec3 f) {
    f = normalize(f);
    vec3 r = normalize(cross(vec3(0.0, 1.0, 0.0), f));
    vec3 u = cross(f, r);
    return mat3(r, u, f);
}

vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }


// ---------------------------------------------------------------- palet (tek renkten uyumlu tonlar)
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

vec3 P_DEEP, P_DARK, P_MID, P_BRIGHT, P_ACC, P_ACC2, P_GLOW, P_BASED, P_BASEM;

float hueMix(float a, float b, float t) {                     // blend the short way around the color wheel
    float d = fract(b - a + 0.5) - 0.5;
    return fract(a + d * t);
}

// Builds a matching palette from the picked color: highlights in the picked color, shadows shift toward blue-purple
// (the painters' "warm light, cool shadow" rule), accent an adjacent hue, second accent the complementary color.
void makePalette(vec3 base) {
    vec3 h = rgb2hsv(max(base, vec3(1e-3)));
    float H = h.x, S = clamp(h.y, 0.35, 1.0);
    float shadowHue = 0.70;                                    // mavi-mor
    P_DEEP   = hsv2rgb(vec3(hueMix(H, shadowHue, 0.75), 0.75, 0.045));
    P_DARK   = hsv2rgb(vec3(hueMix(H, shadowHue, 0.45), 0.70, 0.20));
    P_MID    = hsv2rgb(vec3(hueMix(H, shadowHue, 0.12), S, 0.62));
    P_BRIGHT = hsv2rgb(vec3(H, S * 0.8, 1.0));
    P_ACC    = hsv2rgb(vec3(H + 0.045, min(1.0, S * 1.05), 1.0));
    P_ACC2   = hsv2rgb(vec3(hueMix(H, H + 0.5, 0.6), 0.55, 0.8));
    P_GLOW   = mix(P_BRIGHT, vec3(1.0), 0.3);
    P_BASED  = hsv2rgb(vec3(H, S, 0.20));                       // a darker shade of the same hue (nebula body)
    P_BASEM  = hsv2rgb(vec3(H, S * 0.95, 0.55));
}

vec3 palStars(vec3 d, float t) {                             // stars are lightly tinted by the palette too
    vec3 s = starfield(d, t);
    return s * mix(vec3(1.0), P_GLOW, 0.35);
}

float tri(float x) { return abs(fract(x) - 0.5) * 2.0; }

// ---------------------------------------------------------------- 0: Shooting stars (liked, enriched)
vec3 dMeteors(vec3 d, float t) {
    vec3 col = mix(P_DEEP, P_DARK * 0.8, pow(1.0 - max(d.y, 0.0), 3.0));
    col += palStars(d, t * 2.0) * 1.15;
    // slowly flowing milky way band
    vec3 bn = normalize(vec3(0.3, 1.0, 0.2));
    float band = exp(-pow(dot(d, bn), 2.0) * 7.0);
    float n = fbm(d * 3.0 + vec3(t * 0.05, 0.0, t * 0.035));
    col += mix(P_MID, P_ACC2, n) * smoothstep(0.35, 0.75, n) * band * 0.55;
    col += P_GLOW * pow(band, 3.0) * smoothstep(0.55, 0.85, fbm(d * 7.0 + t * 0.01)) * 0.25;
    // shooting stars
    for (int i = 0; i < 10; i++) {
        float fi = float(i);
        float per = 1.1 + fi * 0.23;
        float tt = t + fi * 0.91;
        float k = floor(tt / per);
        float ph = fract(tt / per);
        if (ph > 0.28) continue;
        float s = ph / 0.28;
        vec3 a0 = normalize(vec3(hash11(k + fi * 13.0) * 2.0 - 1.0, 0.25 + 0.6 * hash11(k * 3.0 + fi), hash11(k * 7.0 + fi) * 2.0 - 1.0));
        vec3 dirM = normalize(cross(a0, normalize(vec3(0.3, -1.0, 0.2) + 0.3 * vec3(hash11(k), 0.0, hash11(k + 5.0)))));
        float len = 0.55 + 0.4 * hash11(k * 11.0 + fi);
        vec3 head = normalize(a0 + dirM * s * len);
        vec3 tail = normalize(a0 + dirM * max(0.0, s - 0.2) * len);
        vec3 ab = head - tail, ap = d - tail;
        float h = clamp(dot(ap, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
        float dist = length(ap - ab * h);
        float fade = sin(s * 3.14159);
        col += mix(P_ACC, vec3(1.0), h) * exp(-dist * 700.0) * h * fade * 2.2;
        col += P_GLOW * exp(-length(d - head) * 220.0) * fade * 1.5;
    }
    return col;
}

// ---------------------------------------------------------------- 1: Sea of clouds (liked, tied to the palette, more vivid)
vec3 dCloudSea(vec3 d, float t) {
    vec3 sun = normalize(vec3(0.0, 0.07 + 0.02 * sin(t * 0.05), 1.0));
    float sd = max(dot(d, sun), 0.0);
    vec3 horizon = mix(P_ACC, vec3(1.0, 0.85, 0.6), 0.35);
    vec3 col = mix(horizon, P_MID * 0.9, smoothstep(0.0, 0.45, d.y));
    col = mix(col, P_DARK, smoothstep(0.4, 1.0, d.y));
    col += P_GLOW * pow(sd, 10.0) * 0.55 + vec3(1.0, 0.95, 0.85) * pow(sd, 500.0) * 3.0;
    if (d.y > 0.02) {
        vec2 p = d.xz / d.y * 0.8;
        vec2 drift = vec2(t * 0.2, t * 0.07);
        float n = fbm(vec3(p + drift, t * 0.02));
        float n2 = fbm(vec3(p * 2.3 + drift * 1.7, 4.0 + t * 0.03));
        float c = smoothstep(0.42, 0.75, n * 0.75 + n2 * 0.35) * smoothstep(0.02, 0.15, d.y) * (1.0 - smoothstep(0.55, 0.95, d.y));
        vec3 lit = mix(P_MID * 0.7, P_GLOW, pow(sd, 3.0) * 0.8 + 0.25);
        col = mix(col, lit, c * 0.85);
        col += P_GLOW * c * pow(sd, 20.0) * 0.8;            // cloud edges near the sun glow
    }
    if (d.y < 0.0) {
        vec2 p = d.xz / max(-d.y, 0.01) * 0.5;
        float n = fbm(vec3(p + vec2(t * 0.3, t * 0.08), 3.0 + t * 0.05));
        vec3 sea = mix(P_MID * 0.6, mix(horizon, vec3(1.0), 0.2), pow(sd, 4.0) * 0.7 + n * 0.35);
        col = mix(col, sea, smoothstep(0.0, -0.03, d.y));
    }
    return col;
}

// ---------------------------------------------------------------- 2: Vortex nebula (flowing, swirling nebula)
vec3 dNebula(vec3 d, float t) {
    vec3 q = d * 1.8;
    float ang = t * 0.06;
    mat2 R = mat2(cos(ang), -sin(ang), sin(ang), cos(ang));
    q.xz = R * q.xz;
    vec3 w = vec3(fbm(q + vec3(0.0, t * 0.08, 0.0)), fbm(q + vec3(5.2, 1.3, t * 0.07)), fbm(q + vec3(2.1, t * 0.06, 7.4)));
    float n = fbm(q + w * 3.0);
    float m = fbm(q * 2.0 + w * 3.5 + vec3(t * 0.05));
    vec3 col = P_DEEP;
    col = mix(col, P_DARK * 1.6, smoothstep(0.3, 0.6, n));
    col = mix(col, P_MID, smoothstep(0.48, 0.78, n));
    col = mix(col, P_ACC2, smoothstep(0.58, 0.88, m) * 0.6);
    col += P_GLOW * pow(smoothstep(0.62, 0.95, n), 3.0) * 0.9;
    col *= 0.55 + 0.6 * smoothstep(0.25, 0.7, fbm(d * 5.0 + w + t * 0.03));
    return col + palStars(d, t * 2.0) * 0.8;
}

// ---------------------------------------------------------------- 3: Aurora (rippling curtains)
vec3 dAurora(vec3 d, float t) {
    vec3 col = mix(P_DEEP, P_DARK * 0.6, 1.0 - abs(d.y));
    col += palStars(d, t * 2.0) * 0.8;
    if (d.y > -0.02) {
        float a = atan(d.z, d.x);
        vec3 acc = vec3(0.0);
        for (int l = 0; l < 5; l++) {
            float fl = float(l);
            float base = 0.07 + 0.065 * fl + 0.05 * sin(a * 2.0 + t * (0.35 + 0.1 * fl) + fl * 1.7);
            float h = d.y - base;
            if (h < 0.0) continue;
            float wave = sin(a * (3.0 + fl) + t * (0.6 + 0.15 * fl)) * 2.0 + fbm3(vec3(a * 2.5, fl, t * 0.25)) * 5.0;
            float rays = 0.5 + 0.5 * sin(a * (90.0 + fl * 25.0) + wave * 4.0 + t * 1.5);
            rays = mix(rays, 1.0, 0.4);
            float flick = 0.75 + 0.25 * sin(t * 2.3 + a * 7.0 + fl);
            float prof = exp(-h * (5.0 + 1.5 * fl)) * smoothstep(0.0, 0.012, h);
            vec3 c1 = mix(P_BRIGHT, P_ACC2, clamp(h * 2.5 + fl * 0.12, 0.0, 1.0));
            acc += c1 * prof * rays * flick * (0.6 - fl * 0.08);
        }
        col += acc * 1.3;
        col += P_MID * exp(-abs(d.y - 0.05) * 18.0) * 0.12;   // reflection on the horizon
    }
    return col;
}

// ---------------------------------------------------------------- 4: Rotating star trails (visible rotation)
vec3 dTrails(vec3 d, float t) {
    vec3 pole = normalize(vec3(0.0, 0.8, 0.6));
    mat3 B = basisTowards(pole);
    vec3 l = transpose(B) * d;
    float rho = acos(clamp(l.z, -1.0, 1.0));
    float th = atan(l.y, l.x);
    vec3 col = mix(P_DEEP, P_DARK * 0.7, 1.0 - abs(d.y));
    float glow = exp(-abs(d.y - 0.01) * (d.y > 0.0 ? 9.0 : 30.0));
    col += P_ACC * glow * 0.6 + P_GLOW * glow * glow * 0.25;
    float rings = 420.0;
    float ri = floor(rho * rings);
    float acc = 0.0;
    vec3 tint = vec3(0.0);
    for (int k = -1; k <= 1; k++) {
        float id = ri + float(k);
        float h1 = hash11(id * 1.37), h2 = hash11(id * 7.11 + 3.0), h3 = hash11(id * 3.3 + 9.0);
        if (h1 > 0.6) continue;
        float center = (id + 0.5) / rings;
        float w = exp(-pow((rho - center) * rings * 1.7, 2.0));
        float head = h2 * TAU + t * 0.35;                    // the head of the trail turns
        float len = 0.5 + h3 * 1.6;
        float dt = mod(head - th, TAU);
        float tr = dt < len ? pow(1.0 - dt / len, 1.5) : 0.0;
        float v = w * tr * (0.2 + 1.1 * h3 * h3);
        acc += v;
        tint += mix(P_GLOW, P_ACC2, h1) * v;
    }
    col += (tint + vec3(acc) * 0.4) * smoothstep(-0.05, 0.06, d.y);
    return col;
}

// ---------------------------------------------------------------- 5: Moonlit clouds (night, drifting clouds)
vec3 dNightClouds(vec3 d, float t) {
    vec3 moon = normalize(vec3(-0.4, 0.45, 1.0));
    float md = max(dot(d, moon), 0.0);
    vec3 col = mix(P_DARK * 0.8, P_DEEP, smoothstep(0.0, 0.7, d.y));
    col += palStars(d, t * 2.0) * smoothstep(0.05, 0.3, d.y);
    col += P_GLOW * pow(md, 30.0) * 0.4 + vec3(1.0) * smoothstep(0.9993, 0.9996, md) * 1.2;
    if (d.y > 0.0) {
        vec2 p = d.xz / (d.y + 0.08) * 0.6;
        vec2 drift = vec2(t * 0.22, t * 0.08);
        float n = fbm(vec3(p + drift, t * 0.04));
        float n2 = fbm(vec3(p * 2.7 + drift * 1.5, 9.0));
        float c = smoothstep(0.45, 0.8, n * 0.8 + n2 * 0.3);
        vec3 cc = mix(P_DARK * 1.5, P_GLOW * 0.9, pow(md, 6.0) * 0.9 + 0.08);
        col = mix(col, cc, c * 0.8);
        col += P_GLOW * c * pow(md, 40.0) * 0.6;              // silver lining at the moon's edge
    }
    return col;
}

// ---------------------------------------------------------------- 6: Galaxy swirl (a rotating spiral covering the sky)
vec3 dGalaxy(vec3 d, float t) {
    vec3 c = normalize(vec3(0.0, 0.85, 0.5));
    mat3 B = basisTowards(c);
    vec3 l = transpose(B) * d;
    vec2 uv = l.xy / (l.z + 1.3) * 2.2;
    float r = length(uv);
    float a = atan(uv.y, uv.x) - t * 0.12;
    float arms = pow(0.5 + 0.5 * cos(2.0 * a + 3.2 * log(r + 0.05) + fbm3(vec3(uv * 4.0, t * 0.1)) * 1.8), 2.0);
    float disk = exp(-r * 1.2) * (0.2 + arms) * (0.6 + 0.7 * fbm3(vec3(uv * 10.0 + t * 0.05, 2.0)));
    vec3 col = P_DEEP + palStars(d, t * 2.0) * 0.7;
    col += mix(P_MID, P_ACC2, smoothstep(0.2, 1.2, r)) * disk * 1.5;
    col += P_GLOW * exp(-r * r * 30.0) * 0.8 + P_BRIGHT * exp(-r * 4.0) * 0.25;
    col *= 1.0 - 0.45 * smoothstep(0.55, 0.85, fbm3(vec3(uv * 7.0, 5.0 + t * 0.05))) * exp(-r);
    return col;
}

// ---------------------------------------------------------------- 8: Black hole (Schwarzschild ray tracing)
// Rays bend by the real gravity equation (x'' = -1.5 h² x / r^5, rs = 1):
//  - shadow ~2.6 rs (inside the photon sphere), a thin photon ring around it
//  - the back half of the disk shows bent over and under the shadow (like in Interstellar)
//  - Doppler: the side turning toward us is brighter/hotter, the receding side dimmer; gravitational redshift on the inside
//  - background stars bend from lensing (Einstein ring)
vec3 bhRamp(float x) {                                            // observed temperature -> color (tied to the palette)
    vec3 c = mix(P_BASED * 0.6, P_ACC2 * 0.8, smoothstep(0.05, 0.35, x));
    c = mix(c, P_ACC, smoothstep(0.30, 0.70, x));
    c = mix(c, P_GLOW, smoothstep(0.65, 1.05, x));
    return mix(c, vec3(1.0), smoothstep(1.0, 1.5, x));
}

vec3 bhBackground(vec3 d, float t) {
    vec3 col = P_DEEP * 0.7 + stars(d, 150.0, 0.30, t) * 0.8 + stars(d, 60.0, 0.06, t);
    float n = vnoise(d * 2.5) * 0.65 + vnoise(d * 6.0) * 0.35;
    return col + P_DARK * 0.35 * smoothstep(0.45, 0.85, n);
}

vec3 dBlackHole(vec3 d, float t) {
    const float D = 15.0;
    vec3 bh = normalize(vec3(0.45, 0.30, 1.0));
    float cs = dot(d, bh);
    float ang = acos(clamp(cs, -1.0, 1.0));
    if (ang > 1.2) {                                              // far: weak field approximation (2 rs / b)
        float b = D * sin(min(ang, 1.5707));
        vec3 perp = normalize(d - bh * cs);
        float a = 2.0 / b * smoothstep(-0.2, 0.4, cs);
        return bhBackground(normalize(d - perp * a), t);
    }
    vec3 upP = normalize(vec3(0.0, 1.0, 0.0) - bh * bh.y);
    vec3 n = normalize(upP - bh * 0.16);                          // disk normal: we see it almost edge on (~82°)
    vec3 ex = normalize(cross(n, vec3(0.0, 0.0, 1.0))), ez = cross(ex, n);
    vec3 p = -bh * D, v = d;
    vec3 L = cross(p, v); float h2 = dot(L, L);
    vec3 col = vec3(0.0); float trans = 1.0;
    float s0 = dot(p, n);
    for (int i = 0; i < 140; i++) {
        float r = length(p);
        if (r < 1.0) { trans = 0.0; break; }                      // event horizon: light doesn't come back
        float dt = clamp(0.10 * r, 0.03, 2.0);
        vec3 pp = p;
        v += -1.5 * h2 * p / (r * r * r * r * r) * dt;
        p += v * dt;
        float s1 = dot(p, n);
        if (s0 * s1 < 0.0) {                                      // crossed the disk plane
            vec3 hit = mix(pp, p, s0 / (s0 - s1));
            float hr = length(hit);
            if (hr > 2.8 && hr < 11.0) {
                float beta = sqrt(0.5 / (hr - 1.0));              // circular orbit speed
                float gam = inversesqrt(1.0 - beta * beta);
                vec3 tang = normalize(cross(n, hit));
                float g = sqrt(1.0 - 1.0 / hr) / (gam * (1.0 - beta * dot(tang, -normalize(v))));
                float F = pow(hr, -3.0) * max(1.0 - sqrt(3.0 / hr), 0.0) / 0.0021;   // Novikov-Thorne flux
                float phi = atan(dot(hit, ex), dot(hit, ez)) - t * 2.2 * beta / hr;  // the inside spins faster
                float turb = vnoise(vec3(hr * 4.0, cos(phi) * 2.5, sin(phi) * 2.5) + vec3(0.0, 0.0, t * 0.05)) * 0.6
                           + vnoise(vec3(hr * 11.0, cos(phi) * 6.0, sin(phi) * 6.0)) * 0.4;
                float edge = smoothstep(2.8, 3.3, hr) * (1.0 - smoothstep(7.5, 11.0, hr));
                float a = edge * (0.78 + 0.22 * turb);
                float temp = pow(F, 0.25) * g;
                vec3 e = bhRamp(temp * (0.8 + 0.4 * turb)) * F * g * g * g * (0.5 + 0.8 * turb) * 1.6;
                col += trans * e * a;
                trans *= 1.0 - a * 0.97;
            }
        }
        s0 = s1;
        if (r > D * 1.2 && dot(p, v) > 0.0) break;
        if (trans < 0.01) break;
    }
    return col + trans * bhBackground(normalize(v), t);
}

// ---------------------------------------------------------------- 8: Silk waves (flowing ribbons of light)
vec3 dSilk(vec3 d, float t) {
    vec3 col = mix(P_DEEP, P_DARK, smoothstep(-0.2, 0.8, d.y) * 0.8);
    float a = atan(d.z, d.x);
    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        float y0 = 0.12 + fi * 0.11;
        float wave = y0 + 0.06 * sin(a * (2.0 + fi * 0.5) + t * (0.5 + fi * 0.13) + fi)
                        + 0.03 * sin(a * (5.0 + fi) - t * (0.8 + fi * 0.1));
        float dist = d.y - wave;
        float thick = 0.012 + 0.01 * sin(a * 3.0 + t + fi);
        float band = exp(-dist * dist / (thick * thick));
        float sheen = 0.6 + 0.4 * sin(a * 20.0 - t * 2.5 + fi * 2.0);
        vec3 c = mix(P_BRIGHT, i % 2 == 0 ? P_ACC : P_ACC2, 0.5 + 0.5 * sin(a * 2.0 + fi + t * 0.3));
        col += c * band * sheen * 0.7;
        col += c * exp(-abs(dist) * 25.0) * 0.08;
    }
    return col + palStars(d, t * 2.0) * 0.5 * smoothstep(0.2, 0.7, d.y);
}

// ---------------------------------------------------------------- 9: Light rays (through the clouds)
vec3 dGodRays(vec3 d, float t) {
    vec3 sun = normalize(vec3(0.2, 0.55, 1.0));
    float sd = max(dot(d, sun), 0.0);
    vec3 col = mix(P_MID * 0.8, P_DARK, smoothstep(0.0, 0.9, d.y));
    col = mix(mix(P_ACC, vec3(1.0), 0.2), col, smoothstep(-0.02, 0.25, d.y));
    if (d.y < 0.0) col = mix(col, P_DARK * 1.2 + P_ACC * 0.15, smoothstep(0.0, -0.08, d.y));
    // clouds that cut the rays
    float cl = 0.0;
    if (d.y > 0.0) {
        vec2 p = d.xz / (d.y + 0.1) * 0.7 + vec2(t * 0.08, t * 0.03);
        cl = smoothstep(0.45, 0.75, fbm(vec3(p, t * 0.03)));
    }
    // light shafts: intensity varying with the angle around the sun
    vec3 rel = normalize(d - sun * dot(d, sun));
    mat3 B = basisTowards(sun);
    vec3 lr = transpose(B) * rel;
    float ra = atan(lr.y, lr.x);
    float rays = fbm3(vec3(ra * 4.0, t * 0.15, 1.0)) * (0.6 + 0.4 * sin(ra * 18.0 + t * 0.4));
    float beam = pow(sd, 3.0) * rays;
    col += P_GLOW * beam * 0.9;
    col = mix(col, mix(P_DARK * 1.4, P_GLOW, pow(sd, 6.0)), cl * 0.75);
    col += vec3(1.0, 0.95, 0.85) * pow(sd, 300.0) * 2.5;
    return col;
}

// ---------------------------------------------------------------- 10: Glitter dust (drifting specks of light)
vec3 dSparkles(vec3 d, float t) {
    vec3 col = mix(P_DEEP, P_DARK, 0.5 + 0.5 * d.y);
    col += P_MID * smoothstep(0.5, 0.85, fbm(d * 2.0 + vec3(0.0, t * 0.03, 0.0))) * 0.35;
    for (int layer = 0; layer < 3; layer++) {
        float L = float(layer);
        float scale = 14.0 + L * 10.0;
        vec3 p = d * scale + vec3(t * (0.4 + L * 0.2), t * (0.7 + 0.3 * L), 0.0);   // drifts up
        vec3 id = floor(p) + step(0.5, fract(p)) - 1.0;             // the nearest 2x2x2 cells (8 instead of 27)
        for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 0; z <= 1; z++) {
            vec3 c = id + vec3(x, y, z);
            vec3 h = hash33(c + L * 31.0);
            if (h.x > 0.35) continue;
            vec3 sp = c + 0.5 + 0.35 * sin(h * 6.28 + t * (0.5 + h.y));
            float dist = length(p - sp);
            float tw = 0.5 + 0.5 * sin(t * (2.0 + 3.0 * h.z) + h.y * 30.0);
            float b = exp(-dist * dist * 60.0) * 1.4 + exp(-dist * 9.0) * 0.12 * (1.0 - smoothstep(0.5, 0.9, dist));
            col += mix(P_GLOW, h.z > 0.5 ? P_ACC : P_ACC2, h.y) * b * tw * (0.6 - L * 0.12);
        }
    }
    return col;
}

// ---------------------------------------------------------------- 11: Nebula storm (colored lightning)
vec3 dStorm(vec3 d, float t) {
    vec3 col = P_DEEP;
    vec3 q = d * 2.2;
    vec3 w = vec3(fbm(q + vec3(0.0, t * 0.1, 0.0)), fbm(q + vec3(4.0, t * 0.08, 2.0)), 0.0);
    float n = fbm(q + w * 2.5 + vec3(t * 0.05));
    col = mix(col, P_DARK * 1.5, smoothstep(0.3, 0.6, n));
    col = mix(col, P_MID * 0.8, smoothstep(0.5, 0.8, n));
    // lightning flashes
    float ft = floor(t * 1.3);
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        float k = ft + fi * 17.0;
        float on = step(0.4, hash11(k)) * exp(-fract(t * 1.3) * 4.0);
        vec3 c = normalize(vec3(hash11(k + 1.0) * 2.0 - 1.0, 0.2 + 0.6 * hash11(k + 2.0), hash11(k + 3.0) * 2.0 - 1.0));
        float near = exp(-length(d - c) * 4.0);
        col += P_GLOW * near * on * (0.4 + n) * 1.3;
        // forking line
        mat3 B = basisTowards(c);
        vec3 l = transpose(B) * d;
        if (l.z > 0.0) {
            vec2 uv = l.xy / l.z;
            float bx = (fbm3(vec3(uv.y * 6.0, k, 1.0)) - 0.5) * 0.6;
            float bolt = exp(-abs(uv.x - bx) * 90.0) * step(-0.6, uv.y) * step(uv.y, 0.15);
            col += mix(P_GLOW, vec3(1.0), 0.5) * bolt * on * 2.0;
        }
    }
    return col + palStars(d, t * 2.0) * 0.4;
}

// ---------------------------------------------------------------- 12: Synthwave (tied to the palette, scrolling grid)
vec3 dSynth(vec3 d, float t) {
    vec3 col = mix(P_ACC, P_DARK, smoothstep(-0.02, 0.55, d.y));
    col = mix(col, P_DEEP, smoothstep(0.45, 1.0, d.y));
    col += palStars(d, t * 2.0) * smoothstep(0.25, 0.6, d.y);
    vec3 sd = normalize(vec3(0.0, 0.16, 1.0));
    float a = acos(clamp(dot(d, sd), -1.0, 1.0));
    float R = 0.3;
    if (a < R && d.y > -0.01) {
        float v = (d.y - sd.y) / R;
        vec3 sc = mix(P_ACC, mix(P_GLOW, vec3(1.0, 0.95, 0.6), 0.5), clamp(v * 0.5 + 0.5, 0.0, 1.0));
        float stripes = step(0.0, sin((v - t * 0.15) * 34.0) + (v + 0.2) * 3.0);
        col = mix(col, sc * 1.3, stripes * smoothstep(R, R - 0.004, a));
    }
    col += P_ACC * exp(-a * 4.0) * 0.4;
    if (d.y < 0.0) {
        vec2 g = d.xz / max(-d.y, 0.02);
        g.y += t * 3.0;
        vec2 gg = g * 0.5;
        vec2 fw = fwidth(gg) + 1e-4;
        vec2 gl = abs(fract(gg) - 0.5) / fw;
        float line = 1.0 - smoothstep(0.5, 1.5, min(gl.x, gl.y));
        line *= smoothstep(-0.005, -0.06, d.y);
        col = mix(P_DEEP, P_BRIGHT, line * exp(d.y * 3.0));
        col += P_ACC * exp(d.y * 40.0) * 0.4;
    }
    return col;
}

// ---------------------------------------------------------------- 13: Cosmic waves (rings spreading from the horizon)
vec3 dWaves(vec3 d, float t) {
    vec3 col = mix(P_DEEP, P_DARK, smoothstep(0.6, 0.0, d.y) * 0.7);
    col += palStars(d, t * 2.0) * 0.7;
    vec3 c = normalize(vec3(0.0, 0.02, 1.0));
    float a = acos(clamp(dot(d, c), -1.0, 1.0));
    float n = fbm3(d * 4.0 + t * 0.1);
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float r = fract(t * 0.12 + fi * 0.25) * 2.4;                 // expanding ring
        float w = exp(-pow((a - r + n * 0.08) * 14.0, 2.0)) * (1.0 - r / 2.4);
        col += mix(P_BRIGHT, i % 2 == 0 ? P_ACC : P_ACC2, fi / 3.0) * w * 0.9;
    }
    col += P_GLOW * exp(-a * 10.0) * (0.8 + 0.4 * sin(t * 2.0));
    col += P_MID * exp(-abs(d.y) * 20.0) * 0.25;
    return col;
}

// ---------------------------------------------------------------- 14: Star shower (stars drifting like snow)
vec3 dCaustics(vec3 d, float t) {
    vec3 col = mix(P_MID * 0.35, P_DEEP, smoothstep(-0.05, 0.8, d.y));
    col += P_ACC * exp(-abs(d.y) * 9.0) * 0.35;
    col += P_DARK * smoothstep(0.45, 0.8, fbm(d * 2.0 + vec3(t * 0.02, 0.0, 0.0))) * 0.8;
    float a = atan(d.z, d.x);
    for (int layer = 0; layer < 4; layer++) {
        float L = float(layer);
        float sc = 60.0 + L * 35.0;
        vec2 p = vec2(a * sc / 6.2832 * 6.0, d.y * sc * 0.5 + t * (1.2 + L * 0.6));   // drifts down
        vec2 id = floor(p);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            vec2 c = id + vec2(x, y);
            float h = hash11(dot(c, vec2(12.9898, 78.233)) + L * 7.0);
            if (h > 0.09) continue;
            float h2 = hash11(h * 91.0);
            vec2 sp = c + vec2(0.5 + 0.3 * sin(t * (0.8 + h2) + h * 20.0), 0.5);
            vec2 dd = (p - sp) * vec2(1.0, 0.7);
            float dist = length(dd);
            float tail = exp(-abs(dd.x) * 14.0) * smoothstep(0.0, 1.2, dd.y) * exp(-dd.y * 1.6) * step(0.0, dd.y);
            float b = exp(-dist * dist * 18.0) * 1.5 + tail * 0.45;
            col += mix(P_GLOW, h2 > 0.5 ? P_ACC : P_BRIGHT, h2) * b * (0.55 - L * 0.1);
        }
    }
    return col;
}

// ---- gradient noise (Perlin), for more "photographic" textures
vec3 ghash(vec3 p) {
    return -1.0 + 2.0 * hash33b(p + 0.5);
}
float gnoise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(mix(dot(ghash(i), f), dot(ghash(i + vec3(1, 0, 0)), f - vec3(1, 0, 0)), u.x),
                   mix(dot(ghash(i + vec3(0, 1, 0)), f - vec3(0, 1, 0)), dot(ghash(i + vec3(1, 1, 0)), f - vec3(1, 1, 0)), u.x), u.y),
               mix(mix(dot(ghash(i + vec3(0, 0, 1)), f - vec3(0, 0, 1)), dot(ghash(i + vec3(1, 0, 1)), f - vec3(1, 0, 1)), u.x),
                   mix(dot(ghash(i + vec3(0, 1, 1)), f - vec3(0, 1, 1)), dot(ghash(i + vec3(1, 1, 1)), f - vec3(1, 1, 1)), u.x), u.y), u.z);
}
float gfbm(vec3 p, int oct) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 8; i++) { if (i >= oct) break; s += a * gnoise(p); p = p * 2.03 + vec3(3.1, 1.7, 5.3); a *= 0.5; }
    return s;                                   // ~[-0.6, 0.6]
}
float gridged(vec3 p, int oct) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 8; i++) { if (i >= oct) break; float n = 1.0 - abs(gnoise(p) * 1.8); s += a * n * n; p = p * 2.07 + vec3(1.3, 4.7, 2.9); a *= 0.5; }
    return s;                                   // ~[0, 1]
}

// photographic star field: many dim + a few bright, lightly colored
vec3 photoStars(vec3 d, float t) {
    vec3 c = stars(d, 160.0, 0.22, t) * 0.55 + stars(d, 330.0, 0.25, t) * 0.35 + stars(d, 70.0, 0.05, t) * 1.2;
    return c;
}

// rotate the sky slowly around the world axis (like a timelapse)
vec3 skyRot(vec3 d, float a) {
    vec3 ax = normalize(vec3(0.0, 0.8, 0.6));
    float c = cos(a), s = sin(a);
    return d * c + cross(ax, d) * s + ax * dot(ax, d) * (1.0 - c);
}

// ---------------------------------------------------------------- 2: Long exposure star trails (like in the 3arthh4ck video)
// A real long exposure photo: the pole near the horizon, trails dense, thin, near white (lightly tinted by the palette color),
// orange city light on one side. The sky turns slowly around the pole.
vec4 hash42t(vec2 p) {
    vec4 p4 = fract(vec4(p.xyxy) * vec4(0.1031, 0.1030, 0.0973, 0.1099));
    p4 += dot(p4, p4.wzxy + 33.33);
    return fract((p4.xxyz + p4.yzzw) * p4.zywx);
}

vec3 trailTint(float h1, float h2) {
    vec3 base = rgb2hsv(P_BRIGHT);
    float hue = base.x + (h1 - 0.5) * 0.18;
    vec3 c = hsv2rgb(vec3(hue, 0.08 + 0.32 * h2 * h2, 1.0));    // most near white
    if (h2 > 0.93) c = vec3(1.0, 0.80, 0.58);                     // a warm star now and then
    return c;
}

vec3 pTrails(vec3 d, float t) {
    vec3 pole = normalize(vec3(-0.80, 0.16, -0.58));              // pole near the horizon (not in the middle of the screen)
    mat3 B = basisTowards(pole); vec3 l = transpose(B) * d;
    float rho = acos(clamp(l.z, -1.0, 1.0));
    float th = mod(atan(l.y, l.x) + t * 0.03, TAU);               // the sky turns around the pole
    float px = PXA;

    // --- dark sky, light purple haze, orange city light
    float az = atan(d.z, d.x);
    float up = max(d.y, 0.0);
    vec3 col = vec3(0.006, 0.006, 0.012) + P_DEEP * 0.35;
    float haze = vnoise(d * 3.0) * 0.6 + vnoise(d * 7.0) * 0.4;
    col += P_MID * 0.05 * smoothstep(0.35, 0.9, haze) * (1.0 - up);
    float gAz = cos(az - 1.1);                                    // the direction the light comes from
    float glowSide = pow(max(gAz, 0.0), 6.0);
    vec3 orange = vec3(1.0, 0.36, 0.08);
    col += orange * glowSide * exp(-up * 4.0) * 0.55;
    col += vec3(1.0, 0.6, 0.3) * glowSide * exp(-up * 22.0) * 0.35;
    col += P_ACC2 * 0.06 * exp(-up * 6.0) * (1.0 - glowSide);

    // --- izler
    float SP = 0.0034;                                            // ring spacing (radians)
    float x = rho / SP; float ri = floor(x);
    float k2 = (x - ri) < 0.5 ? -1.0 : 1.0;                       // nearest neighbouring ring
    vec3 acc = vec3(0.0);
    for (int kk = 0; kk < 2; kk++) {
        float id = ri + (kk == 0 ? 0.0 : k2);
        float rk = (id + 0.5) * SP;
        float M = max(3.0, floor(sin(rk) * 46.0));
        float cw = TAU / M;
        float ci = floor(th / cw);
        for (int j = 0; j < 5; j++) {
            float cell = mod(ci + float(j), M);
            vec4 hh = hash42t(vec2(id, cell));
            if (hh.x > 0.46) continue;
            vec4 hb = hash42t(vec2(cell + 71.3, id * 1.37 + 9.1));
            float r0 = rk + (hh.y - 0.5) * SP * 0.7;
            float head = (cell + hh.z) * cw;
            float dt = mod(head - th, TAU);
            float len = 0.10 + 0.34 * hb.x * hb.x + 0.08 * hh.w;  // trails of different lengths
            if (dt > len) continue;
            float u = dt / len;
            float along = smoothstep(0.0, 0.04, u) * (1.0 - smoothstep(0.90, 1.0, u));
            if (hb.y > 0.93) along *= step(0.15, fract(u * (3.0 + 4.0 * hb.z)));   // kesik kesik izler
            float mag = 0.06 + 1.1 * pow(hh.w, 3.5) + 0.22 * hb.w * hb.w;
            float w = SP * (0.06 + 0.10 * hh.w * hh.w);
            float ww = max(w, px * 0.85);
            float dr = (rho - r0) / ww;
            acc += trailTint(hh.x / 0.46, hb.z) * exp(-dr * dr * 2.0) * (w / ww) * along * mag;
        }
    }
    float vis = smoothstep(-0.01, 0.05, d.y);
    col += acc * 1.7 * vis;
    col += stars(d, 260.0, 0.25, t) * 0.35 * vis;
    col *= 1.0 - 0.92 * smoothstep(0.0, -0.05, d.y);
    return col;
}

// ---------------------------------------------------------------- 3: Koyu bulutsu
vec3 pNebula(vec3 d, float t) {
    vec3 r = skyRot(d, t * 0.008);
    vec3 q = r * 1.6;
    vec3 w = vec3(gfbm(q + vec3(0.0, 0.0, t * 0.02), 3), gfbm(q + vec3(5.2, 1.3, 2.8 - t * 0.015), 3), gfbm(q + vec3(9.1, 4.4, 1.7), 3));
    float n = gfbm(q * 1.4 + w * 1.6, 5) + 0.5;                 // ana bulutsu
    float dust = gridged(q * 2.2 + w * 1.2 + 7.0, 4);            // dark dust veins
    float fine = gfbm(q * 6.0 + w * 2.0, 3) + 0.5;
    vec3 col = vec3(0.006, 0.004, 0.008);
    float body = smoothstep(0.35, 0.85, n) * (0.65 + 0.7 * fine);
    col += P_BASED * 1.5 * body;
    col += P_BASEM * pow(smoothstep(0.55, 0.95, n), 2.0) * 0.65;
    col += P_ACC * pow(smoothstep(0.7, 1.0, n * fine), 3.0) * 0.35;
    col *= 1.0 - 0.75 * smoothstep(0.45, 0.85, dust);            // toz yutar
    col += photoStars(r, t) * (0.35 + 0.65 * (1.0 - body));
    return col;
}

// ---------------------------------------------------------------- C: deep blue veil nebula (reference)
vec3 pVeil(vec3 d, float t) {
    vec3 r = skyRot(d, t * 0.01);
    vec3 q = r * 1.3;
    vec3 w = vec3(gfbm(q + vec3(1.0, t * 0.02, 0.0), 3), gfbm(q + vec3(4.0, 2.0, t * 0.018), 3), 0.0);
    float base = gfbm(q + w, 4) + 0.5;
    float wisps = gridged(q * 2.6 + w * 2.2, 5);
    float fil = pow(wisps, 3.5);
    vec3 col = mix(vec3(0.004, 0.006, 0.02), P_DARK * 1.4, smoothstep(0.2, 0.9, base));
    col += P_MID * fil * 0.45 * smoothstep(0.3, 0.8, base);
    col += P_GLOW * pow(fil, 2.5) * 0.18;
    col *= 0.75 + 0.5 * (gfbm(q * 4.0 + 3.0, 3) + 0.5);
    col += photoStars(r, t) * 0.8;
    return col;
}

// ---------------------------------------------------------------- D: Milky way (timelapse, rotating sky)
vec3 pMilkyWay(vec3 d, float t) {
    vec3 r = skyRot(d, t * 0.03);
    vec3 bn = normalize(vec3(1.0, 0.25, 0.15));
    float h = dot(r, bn);                                       // perpendicular distance to the band
    vec3 q = r * 2.0;
    float n = gfbm(q * 1.5, 5) + 0.5;
    float band = exp(-h * h * 9.0) * (0.55 + 0.9 * n);
    float core = exp(-pow(length(r - normalize(cross(bn, vec3(0.0, 0.0, 1.0))) * 0.9), 2.0) * 1.2);
    vec3 col = vec3(0.004, 0.005, 0.01);
    col += mix(P_BASED, P_BASEM, n) * band * 0.9;
    col += mix(P_GLOW, vec3(1.0, 0.85, 0.7), 0.4) * band * band * core * 0.6;
    float lane = smoothstep(0.5, 0.8, gridged(q * 2.5 + 3.0, 4)) * exp(-pow(h + 0.03 * gnoise(q * 3.0), 2.0) * 40.0);
    col *= 1.0 - 0.8 * lane;
    col += photoStars(r, t) * (1.0 + band * 1.5);
    float hz = exp(-abs(d.y) * 8.0);
    col += P_ACC * hz * 0.1;
    col *= 1.0 - 0.8 * smoothstep(0.0, -0.08, d.y);
    return col;
}

// ---------------------------------------------------------------- E: Photographic spiral galaxy (slowly rotating)
vec3 pGalaxy(vec3 d, float t) {
    vec3 col = vec3(0.004, 0.005, 0.01) + photoStars(skyRot(d, t * 0.01), t);
    vec3 c = normalize(vec3(-0.5, 0.42, 0.8));
    mat3 B = basisTowards(c);
    vec3 l = transpose(B) * d;
    if (l.z <= 0.0) return col;
    vec2 uv = l.xy / l.z * 1.25;
    float ca = cos(0.5), sa = sin(0.5);
    uv = mat2(ca, -sa, sa, ca) * uv;
    uv.y /= 0.42;
    float r = length(uv);
    float a = atan(uv.y, uv.x) - t * 0.04;
    float warpA = a + 0.6 * gnoise(vec3(uv * 3.0, t * 0.02));
    float arms = pow(0.5 + 0.5 * cos(2.0 * warpA - 5.0 * log(r + 0.04)), 3.0);
    float clumps = gfbm(vec3(uv * 12.0, 1.0), 4) + 0.5;
    float disk = exp(-r * 2.6) * (0.25 + 1.2 * arms * (0.6 + 0.8 * clumps));
    col += mix(P_BASEM, P_GLOW * 0.9, smoothstep(1.0, 0.0, r)) * disk * 1.3;
    col += mix(P_GLOW, vec3(1.0, 0.9, 0.75), 0.6) * exp(-r * r * 60.0) * 1.3;
    float dust = smoothstep(0.55, 0.85, gridged(vec3(uv * 7.0, 4.0), 3)) * arms * exp(-r * 2.0);
    col *= 1.0 - 0.6 * dust;
    col += P_ACC * pow(clumps, 6.0) * arms * exp(-r * 3.0) * 0.6;   // star forming regions
    return col;
}

// ---------------------------------------------------------------- F: Nebula storm (light flashing inside the cloud)
vec3 pStorm(vec3 d, float t) {
    vec3 col = pNebula(d, t * 1.5);
    float ft = floor(t * 1.1);
    float on = step(0.35, hash11(ft)) * exp(-fract(t * 1.1) * 5.0);
    vec3 cdir = normalize(vec3(hash11(ft + 1.0) * 2.0 - 1.0, 0.15 + 0.6 * hash11(ft + 2.0), hash11(ft + 3.0) * 2.0 - 1.0));
    float near = exp(-length(d - cdir) * 3.5);
    vec3 r = skyRot(d, t * 0.012);
    float cloud = smoothstep(0.4, 0.9, gfbm(r * 2.2, 4) + 0.5);
    col += P_GLOW * near * on * (0.25 + cloud * 1.4) * 1.2;
    return col;
}

// ---------------------------------------------------------------- G: Sunset clouds (fast flowing, timelapse)
vec3 pSunset(vec3 d, float t) {
    vec3 sun = normalize(vec3(0.3, 0.04, 1.0));
    float sd = max(dot(d, sun), 0.0);
    vec3 col = mix(mix(P_ACC, vec3(1.0, 0.8, 0.55), 0.25), P_BASEM * 0.8, smoothstep(0.0, 0.35, d.y));
    col = mix(col, P_DARK * 0.9, smoothstep(0.3, 1.0, d.y));
    col += mix(P_GLOW, vec3(1.0, 0.85, 0.6), 0.4) * pow(sd, 6.0) * 0.6 + vec3(1.0, 0.95, 0.85) * pow(sd, 700.0) * 3.0;
    if (d.y > 0.01) {
        vec2 p = d.xz / (d.y + 0.05);
        vec2 drift = vec2(t * 0.35, t * 0.12);
        float n = gfbm(vec3(p * 0.6 + drift, t * 0.05), 5) + 0.5;
        float thin = gfbm(vec3(p * 1.8 + drift * 1.4, 3.0), 3) + 0.5;
        float c = smoothstep(0.45, 0.8, n) * smoothstep(0.01, 0.12, d.y);
        float under = pow(sd, 2.0);
        vec3 cc = mix(P_DARK * 1.2, mix(P_ACC, vec3(1.0, 0.75, 0.5), 0.4), under * 0.9 + 0.1) * (0.7 + 0.5 * thin);
        col = mix(col, cc, c * 0.9);
        col += P_GLOW * c * pow(sd, 12.0) * (1.0 - n) * 1.2;          // thin rim light
    }
    if (d.y < 0.0) col = mix(col, P_DARK * 0.5, smoothstep(0.0, -0.1, d.y));
    return col;
}

vec3 skyColor(vec3 d, float t, vec4 p, vec4 c) {
    PXA = max(length(fwidth(d)), 1e-5);
    int design = int(floor(p.y + 0.5));
    if (design < 0) return c.rgb;                       // solid color
    makePalette(c.rgb);
    vec3 col;
    if (design == 0) col = dMeteors(d, t);
    else if (design == 1) col = dCloudSea(d, t);
    else if (design == 2) col = pTrails(d, t);
    else if (design == 3) col = pNebula(d, t);
    else if (design == 4) col = pVeil(d, t);
    else if (design == 5) col = pMilkyWay(d, t);
    else if (design == 6) col = dAurora(d, t);
    else if (design == 7) col = dNightClouds(d, t);
    else if (design == 8) col = dBlackHole(d, t);
    else if (design == 9) col = pGalaxy(d, t);
    else if (design == 10) col = pStorm(d, t);
    else if (design == 11) col = pSunset(d, t);
    else if (design == 12) col = dSparkles(d, t);
    else if (design == 13) col = dSynth(d, t);
    else col = dGodRays(d, t);
    return aces(col * 1.1);
}


void main() {
    vec4 p = TextureMat[0];
    vec4 c = TextureMat[1];
    fragColor = vec4(skyColor(normalize(vDir), p.x, p, c), 1.0);
}
