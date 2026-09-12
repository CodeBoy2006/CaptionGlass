// Renders the README overview animation: the setup flow, live bilingual captions over another
// player, the overlay gestures, the display styles and the offline statement, as numbered PNG
// frames drawn with CoreGraphics and CoreText.
//
//   swiftc -O scripts/preview-animation.swift -o /tmp/preview-animation
//   /tmp/preview-animation /tmp/frames                 # every frame
//   /tmp/preview-animation /tmp/frames --at 7.4        # one frame, for iterating
//   ffmpeg -y -framerate 20 -i /tmp/frames/f%04d.png \
//     -vf "scale=700:493:flags=lanczos,split[a][b];[a]palettegen=max_colors=180:stats_mode=diff[p];\
//          [b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
//     -loop 0 docs/assets/captionglass-overview.gif
//
// The mock UI follows app/src/main/java/com/captionglass/app: CaptionPalette colours, the obsidian
// plate with its hairline and handle, the status chip, the follow-latest pill and the home layout.
// It is an illustration of designed behaviour, not a screen recording. macOS only (CoreText fonts).
import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

// ============================================================ configuration
let CW: CGFloat = 880          // canvas points
let CH: CGFloat = 620
let SS: CGFloat = 2            // supersample factor
let FPS: Double = 20
let DUR: Double = 16.45

// Phone screen in world coordinates (origin = screen top-left, y down).
let SW: CGFloat = 258
let SH: CGFloat = 552
let BEZEL: CGFloat = 9

var ctx: CGContext! = nil

// ============================================================ colour
func rgb(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: a)
}
func fade(_ c: CGColor, _ a: CGFloat) -> CGColor { c.copy(alpha: (c.alpha) * a)! }
func mix(_ a: CGColor, _ b: CGColor, _ t: CGFloat) -> CGColor {
    let x = a.components!, y = b.components!
    let k = max(0, min(1, t))
    return CGColor(red: x[0] + (y[0] - x[0]) * k, green: x[1] + (y[1] - x[1]) * k,
                   blue: x[2] + (y[2] - x[2]) * k, alpha: x[3] + (y[3] - x[3]) * k)
}

enum P {  // product palette
    static let stageBg = rgb(0x06090A)
    static let plateTop = rgb(0x0A0F0D)
    static let plateBottom = rgb(0x060908)
    static let translation = rgb(0xFFFFFF)
    static let source = rgb(0xD9E3DD)
    static let provisional = rgb(0xA3B3AB)
    static let muted = rgb(0x85928B)
    static let accent = rgb(0x8DEBC1)
    static let progress = rgb(0xCCE4D7)
    static let warning = rgb(0xFFD08A)
    // light Material scheme of the app
    static let bg = rgb(0xF6F8F6)
    static let onBg = rgb(0x171D1A)
    static let onVar = rgb(0x56615B)
    static let primary = rgb(0x1B6B50)
    static let primaryCt = rgb(0xD0EEDF)
    static let onPrimaryCt = rgb(0x002116)
    static let container = rgb(0xEAEFEB)
    static let containerHi = rgb(0xE4EAE6)
    static let outline = rgb(0xD6DDD8)
    static let card = rgb(0x0E1311)
    // video scene
    static let vidTop = rgb(0x16203A)
    static let vidBottom = rgb(0x080B16)
    static let vidWarm = rgb(0xF0B267)
}

// ============================================================ easing
func clamp01(_ v: Double) -> Double { v < 0 ? 0 : (v > 1 ? 1 : v) }
/// Normalised progress of [a,b] at time t.
func seg(_ t: Double, _ a: Double, _ b: Double) -> Double { b <= a ? (t >= b ? 1 : 0) : clamp01((t - a) / (b - a)) }
func easeOut(_ v: Double) -> Double { 1 - pow(1 - clamp01(v), 3) }
func easeOutQuint(_ v: Double) -> Double { 1 - pow(1 - clamp01(v), 5) }
func easeIn(_ v: Double) -> Double { pow(clamp01(v), 3) }
func easeInOut(_ v: Double) -> Double {
    let x = clamp01(v)
    return x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2
}
/// Overshoot settle used for plates and sheets.
func spring(_ v: Double, _ bounce: Double = 0.34) -> Double {
    let x = clamp01(v)
    if x >= 1 { return 1 }
    return 1 - exp(-7 * x) * cos(x * .pi * 2.6) * (1 - bounce * 0)
}
func lerp(_ a: CGFloat, _ b: CGFloat, _ t: Double) -> CGFloat { a + (b - a) * CGFloat(clamp01(t)) }
func mixf(_ a: CGFloat, _ b: CGFloat, _ t: Double) -> CGFloat { a + (b - a) * CGFloat(t) }
/// Smooth pulse that rises and falls inside a window.
func pulse(_ t: Double, _ a: Double, _ b: Double) -> Double {
    let x = seg(t, a, b)
    return sin(x * .pi)
}

// ============================================================ geometry
func rr(_ r: CGRect, _ radius: CGFloat) -> CGPath {
    CGPath(roundedRect: r, cornerWidth: min(radius, r.width / 2), cornerHeight: min(radius, r.height / 2), transform: nil)
}
func fillRR(_ r: CGRect, _ radius: CGFloat, _ color: CGColor) {
    ctx.setFillColor(color); ctx.addPath(rr(r, radius)); ctx.fillPath()
}
func strokeRR(_ r: CGRect, _ radius: CGFloat, _ color: CGColor, _ w: CGFloat) {
    ctx.setStrokeColor(color); ctx.setLineWidth(w); ctx.addPath(rr(r.insetBy(dx: w / 2, dy: w / 2), radius)); ctx.strokePath()
}
func fillCircle(_ c: CGPoint, _ r: CGFloat, _ color: CGColor) {
    ctx.setFillColor(color); ctx.fillEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
}
func strokeCircle(_ c: CGPoint, _ r: CGFloat, _ color: CGColor, _ w: CGFloat) {
    ctx.setStrokeColor(color); ctx.setLineWidth(w)
    ctx.strokeEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2))
}
/// Vertical two-stop gradient clipped to a path.
func gradientRR(_ r: CGRect, _ radius: CGFloat, _ top: CGColor, _ bottom: CGColor) {
    ctx.saveGState()
    ctx.addPath(rr(r, radius)); ctx.clip()
    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [top, bottom] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(g, start: CGPoint(x: r.midX, y: r.minY), end: CGPoint(x: r.midX, y: r.maxY), options: [])
    ctx.restoreGState()
}
func radialGlow(_ c: CGPoint, _ radius: CGFloat, _ color: CGColor, _ inner: CGFloat = 0) {
    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                       colors: [color, fade(color, 0)] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(g, startCenter: c, startRadius: inner, endCenter: c, endRadius: radius, options: [])
}
func line(_ a: CGPoint, _ b: CGPoint, _ color: CGColor, _ w: CGFloat, cap: CGLineCap = .round) {
    ctx.setStrokeColor(color); ctx.setLineWidth(w); ctx.setLineCap(cap)
    ctx.move(to: a); ctx.addLine(to: b); ctx.strokePath(); ctx.setLineCap(.butt)
}
func clipped(_ path: CGPath, _ body: () -> Void) {
    ctx.saveGState(); ctx.addPath(path); ctx.clip(); body(); ctx.restoreGState()
}

// ============================================================ type
func fkey(_ s: CFString) -> NSAttributedString.Key { NSAttributedString.Key(s as String) }
enum Weight { case regular, medium, semibold, bold }
func uiFont(_ size: CGFloat, _ w: Weight = .regular) -> CTFont {
    let (trait, cjk): (CGFloat, String) = {
        switch w {
        case .regular: return (0.0, "PingFangSC-Regular")
        case .medium: return (0.23, "PingFangSC-Medium")
        case .semibold: return (0.3, "PingFangSC-Semibold")
        case .bold: return (0.4, "PingFangSC-Semibold")
        }
    }()
    let base = CTFontDescriptorCreateWithAttributes([
        kCTFontFamilyNameAttribute: ".AppleSystemUIFont",
        kCTFontTraitsAttribute: [kCTFontWeightTrait: trait]] as CFDictionary)
    let fallback = CTFontDescriptorCreateWithAttributes([kCTFontNameAttribute: cjk] as CFDictionary)
    let d = CTFontDescriptorCreateCopyWithAttributes(base, [kCTFontCascadeListAttribute: [fallback]] as CFDictionary)
    return CTFontCreateWithFontDescriptor(d, size, nil)
}

/// A laid-out run of text; lines keep absolute string indices so a reveal can cross line breaks.
struct TextBlock {
    var lines: [(line: CTLine, start: Int, end: Int, width: CGFloat)] = []
    var font: CTFont
    var lineHeight: CGFloat
    var count: Int
    var width: CGFloat = 0
    var height: CGFloat { CGFloat(lines.count) * lineHeight }
    var ascent: CGFloat
}

func layout(_ s: String, _ font: CTFont, width: CGFloat, tracking: CGFloat = 0, lineHeight: CGFloat? = nil) -> TextBlock {
    var attrs: [NSAttributedString.Key: Any] = [fkey(kCTFontAttributeName): font,
                                                fkey(kCTForegroundColorFromContextAttributeName): kCFBooleanTrue as Any]
    if tracking != 0 { attrs[fkey(kCTKernAttributeName)] = tracking }
    let attributed = NSAttributedString(string: s, attributes: attrs)
    let typesetter = CTTypesetterCreateWithAttributedString(attributed)
    let total = attributed.length
    var out = TextBlock(font: font, lineHeight: lineHeight ?? (CTFontGetAscent(font) + CTFontGetDescent(font) + 2),
                        count: total, ascent: CTFontGetAscent(font))
    var start = 0
    while start < total {
        var n = CTTypesetterSuggestLineBreak(typesetter, start, Double(width))
        if n <= 0 { n = total - start }
        let l = CTTypesetterCreateLine(typesetter, CFRange(location: start, length: n))
        let w = CGFloat(CTLineGetTypographicBounds(l, nil, nil, nil))
        out.lines.append((l, start, start + n, w))
        out.width = max(out.width, w)
        start += n
    }
    return out
}

/// Word boundaries (indices just past each space) for a spoken-word reveal.
func wordStops(_ s: String) -> [Int] {
    var stops: [Int] = []
    let chars = Array(s.utf16)
    for (i, c) in chars.enumerated() where c == 32 { stops.append(i + 1) }
    stops.append(chars.count)
    return stops
}

/// Draws a block, optionally revealing only the first `reveal` characters with the next unit fading in.
func draw(_ b: TextBlock, at origin: CGPoint, color: CGColor, alpha: CGFloat = 1,
          reveal: Double = -1, stops: [Int]? = nil, align: CGFloat = 0, shadow: Bool = false) {
    guard alpha > 0.004 else { return }
    var solid = b.count
    var partialFrom = b.count, partialTo = b.count, partialAlpha: CGFloat = 0
    if reveal >= 0 {
        if let stops = stops {   // word-wise
            var prev = 0
            var next = stops.last ?? 0
            for s in stops { if Double(s) <= reveal { prev = s } else { next = s; break } }
            solid = prev
            if prev < (stops.last ?? 0) {
                partialFrom = prev; partialTo = next
                partialAlpha = CGFloat(clamp01((reveal - Double(prev)) / Double(max(1, next - prev))))
            }
        } else {
            solid = min(b.count, Int(reveal))
            partialFrom = solid; partialTo = min(b.count, solid + 1)
            partialAlpha = CGFloat(reveal - floor(reveal))
        }
    }
    ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
    var y = origin.y + b.ascent
    for l in b.lines {
        let x = origin.x + (b.width - l.width) * align
        func paint(_ from: Int, _ to: Int, _ a: CGFloat) {
            guard a > 0.004, to > from else { return }
            let lo = max(from, l.start), hi = min(to, l.end)
            guard hi > lo else { return }
            ctx.saveGState()
            let x0 = x + CGFloat(CTLineGetOffsetForStringIndex(l.line, lo, nil))
            let x1 = x + CGFloat(CTLineGetOffsetForStringIndex(l.line, hi, nil))
            ctx.clip(to: CGRect(x: x0 - 0.5, y: y - b.ascent - 4, width: x1 - x0 + 1, height: b.lineHeight + 8))
            if shadow {
                ctx.setShadow(offset: CGSize(width: 0, height: 1.2), blur: 3.2, color: rgb(0x000000, 0.85 * a * alpha))
            }
            ctx.setFillColor(fade(color, a * alpha))
            ctx.textPosition = CGPoint(x: x, y: y)
            CTLineDraw(l.line, ctx)
            ctx.restoreGState()
        }
        paint(0, solid, 1)
        paint(partialFrom, partialTo, partialAlpha)
        y += b.lineHeight
    }
}

/// One-line convenience.
@discardableResult
func text(_ s: String, _ size: CGFloat, _ w: Weight = .regular, _ color: CGColor, at p: CGPoint,
          alpha: CGFloat = 1, align: CGFloat = 0, tracking: CGFloat = 0, shadow: Bool = false) -> CGFloat {
    let f = uiFont(size, w)
    let b = layout(s, f, width: 10000, tracking: tracking)
    let x = p.x - b.width * align
    draw(b, at: CGPoint(x: x, y: p.y), color: color, alpha: alpha, shadow: shadow)
    return b.width
}
func measure(_ s: String, _ size: CGFloat, _ w: Weight = .regular, tracking: CGFloat = 0) -> CGFloat {
    layout(s, uiFont(size, w), width: 10000, tracking: tracking).width
}
// ============================================================ icons (stroke units, centred on p, box size s)
func iconStroke(_ color: CGColor, _ w: CGFloat) { ctx.setStrokeColor(color); ctx.setLineWidth(w); ctx.setLineCap(.round); ctx.setLineJoin(.round) }

func iconPower(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.6) {
    iconStroke(c, w)
    let r = s * 0.34
    ctx.addArc(center: p, radius: r, startAngle: -.pi / 2.6, endAngle: .pi + .pi / 2.6, clockwise: false)
    ctx.strokePath()
    ctx.move(to: CGPoint(x: p.x, y: p.y - s * 0.44)); ctx.addLine(to: CGPoint(x: p.x, y: p.y - s * 0.04)); ctx.strokePath()
}
func iconStop(_ p: CGPoint, _ s: CGFloat, _ c: CGColor) {
    ctx.setFillColor(c); ctx.addPath(rr(CGRect(x: p.x - s * 0.3, y: p.y - s * 0.3, width: s * 0.6, height: s * 0.6), s * 0.14)); ctx.fillPath()
}
func iconCheck(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.7, _ progress: Double = 1) {
    guard progress > 0 else { return }
    iconStroke(c, w)
    let a = CGPoint(x: p.x - s * 0.30, y: p.y + s * 0.02)
    let b = CGPoint(x: p.x - s * 0.08, y: p.y + s * 0.24)
    let d = CGPoint(x: p.x + s * 0.32, y: p.y - s * 0.24)
    let t = CGFloat(easeOut(progress))
    ctx.move(to: a)
    if t < 0.4 {
        let k = t / 0.4
        ctx.addLine(to: CGPoint(x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k))
    } else {
        ctx.addLine(to: b)
        let k = (t - 0.4) / 0.6
        ctx.addLine(to: CGPoint(x: b.x + (d.x - b.x) * k, y: b.y + (d.y - b.y) * k))
    }
    ctx.strokePath()
}
func iconCloudOff(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    ctx.addArc(center: CGPoint(x: p.x - s * 0.02, y: p.y - s * 0.02), radius: s * 0.26, startAngle: .pi, endAngle: 0, clockwise: false)
    ctx.addLine(to: CGPoint(x: p.x + s * 0.34, y: p.y + s * 0.2))
    ctx.addLine(to: CGPoint(x: p.x - s * 0.3, y: p.y + s * 0.2))
    ctx.addLine(to: CGPoint(x: p.x - s * 0.28, y: p.y - s * 0.02))
    ctx.strokePath()
    line(CGPoint(x: p.x - s * 0.42, y: p.y - s * 0.36), CGPoint(x: p.x + s * 0.42, y: p.y + s * 0.36), c, w)
}
func iconChevron(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.5, flip: Bool = false) {
    iconStroke(c, w)
    let d: CGFloat = flip ? -1 : 1
    ctx.move(to: CGPoint(x: p.x - s * 0.12 * d, y: p.y - s * 0.22))
    ctx.addLine(to: CGPoint(x: p.x + s * 0.14 * d, y: p.y))
    ctx.addLine(to: CGPoint(x: p.x - s * 0.12 * d, y: p.y + s * 0.22))
    ctx.strokePath()
}
func iconSwap(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.5) {
    iconStroke(c, w)
    let y1 = p.y - s * 0.15, y2 = p.y + s * 0.15
    ctx.move(to: CGPoint(x: p.x - s * 0.32, y: y1)); ctx.addLine(to: CGPoint(x: p.x + s * 0.32, y: y1)); ctx.strokePath()
    ctx.move(to: CGPoint(x: p.x + s * 0.14, y: y1 - s * 0.16)); ctx.addLine(to: CGPoint(x: p.x + s * 0.32, y: y1)); ctx.addLine(to: CGPoint(x: p.x + s * 0.14, y: y1 + s * 0.16)); ctx.strokePath()
    ctx.move(to: CGPoint(x: p.x + s * 0.32, y: y2)); ctx.addLine(to: CGPoint(x: p.x - s * 0.32, y: y2)); ctx.strokePath()
    ctx.move(to: CGPoint(x: p.x - s * 0.14, y: y2 - s * 0.16)); ctx.addLine(to: CGPoint(x: p.x - s * 0.32, y: y2)); ctx.addLine(to: CGPoint(x: p.x - s * 0.14, y: y2 + s * 0.16)); ctx.strokePath()
}
func iconBox(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    let h = s * 0.36
    ctx.move(to: CGPoint(x: p.x, y: p.y - h))
    ctx.addLine(to: CGPoint(x: p.x + h, y: p.y - h * 0.5))
    ctx.addLine(to: CGPoint(x: p.x + h, y: p.y + h * 0.5))
    ctx.addLine(to: CGPoint(x: p.x, y: p.y + h))
    ctx.addLine(to: CGPoint(x: p.x - h, y: p.y + h * 0.5))
    ctx.addLine(to: CGPoint(x: p.x - h, y: p.y - h * 0.5))
    ctx.closePath(); ctx.strokePath()
    ctx.move(to: CGPoint(x: p.x - h, y: p.y - h * 0.5)); ctx.addLine(to: CGPoint(x: p.x, y: p.y)); ctx.addLine(to: CGPoint(x: p.x + h, y: p.y - h * 0.5)); ctx.strokePath()
    ctx.move(to: CGPoint(x: p.x, y: p.y)); ctx.addLine(to: CGPoint(x: p.x, y: p.y + h)); ctx.strokePath()
}
func iconPip(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    ctx.addPath(rr(CGRect(x: p.x - s * 0.38, y: p.y - s * 0.30, width: s * 0.76, height: s * 0.60), s * 0.10)); ctx.strokePath()
    ctx.setFillColor(c)
    ctx.addPath(rr(CGRect(x: p.x - s * 0.02, y: p.y - s * 0.04, width: s * 0.32, height: s * 0.26), s * 0.05)); ctx.fillPath()
}
func iconFolder(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.6) {
    iconStroke(c, w)
    ctx.move(to: CGPoint(x: p.x - s * 0.36, y: p.y + s * 0.26))
    ctx.addLine(to: CGPoint(x: p.x - s * 0.36, y: p.y - s * 0.24))
    ctx.addLine(to: CGPoint(x: p.x - s * 0.08, y: p.y - s * 0.24))
    ctx.addLine(to: CGPoint(x: p.x + s * 0.02, y: p.y - s * 0.10))
    ctx.addLine(to: CGPoint(x: p.x + s * 0.36, y: p.y - s * 0.10))
    ctx.addLine(to: CGPoint(x: p.x + s * 0.36, y: p.y + s * 0.26))
    ctx.closePath(); ctx.strokePath()
}
func iconSearch(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    ctx.strokeEllipse(in: CGRect(x: p.x - s * 0.34, y: p.y - s * 0.34, width: s * 0.52, height: s * 0.52))
    ctx.move(to: CGPoint(x: p.x + s * 0.16, y: p.y + s * 0.16)); ctx.addLine(to: CGPoint(x: p.x + s * 0.36, y: p.y + s * 0.36)); ctx.strokePath()
}
func iconPlay(_ p: CGPoint, _ s: CGFloat, _ c: CGColor) {
    ctx.setFillColor(c)
    ctx.move(to: CGPoint(x: p.x - s * 0.26, y: p.y - s * 0.34))
    ctx.addLine(to: CGPoint(x: p.x + s * 0.34, y: p.y))
    ctx.addLine(to: CGPoint(x: p.x - s * 0.26, y: p.y + s * 0.34))
    ctx.closePath(); ctx.fillPath()
}
func iconPause(_ p: CGPoint, _ s: CGFloat, _ c: CGColor) {
    ctx.setFillColor(c)
    ctx.addPath(rr(CGRect(x: p.x - s * 0.28, y: p.y - s * 0.32, width: s * 0.19, height: s * 0.64), s * 0.06))
    ctx.addPath(rr(CGRect(x: p.x + s * 0.09, y: p.y - s * 0.32, width: s * 0.19, height: s * 0.64), s * 0.06))
    ctx.fillPath()
}
func iconCaption(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    ctx.addPath(rr(CGRect(x: p.x - s * 0.40, y: p.y - s * 0.30, width: s * 0.80, height: s * 0.60), s * 0.12)); ctx.strokePath()
    ctx.setFillColor(c)
    ctx.addPath(rr(CGRect(x: p.x - s * 0.24, y: p.y - s * 0.10, width: s * 0.48, height: s * 0.08), s * 0.04))
    ctx.addPath(rr(CGRect(x: p.x - s * 0.24, y: p.y + s * 0.06, width: s * 0.30, height: s * 0.08), s * 0.04))
    ctx.fillPath()
}
func iconList(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    ctx.addPath(rr(CGRect(x: p.x - s * 0.32, y: p.y - s * 0.36, width: s * 0.64, height: s * 0.72), s * 0.10)); ctx.strokePath()
    ctx.setFillColor(c)
    for (i, wd) in [CGFloat(0.36), 0.30, 0.20].enumerated() {
        ctx.addPath(rr(CGRect(x: p.x - s * 0.18, y: p.y - s * 0.20 + CGFloat(i) * s * 0.17, width: s * wd, height: s * 0.07), s * 0.035))
    }
    ctx.fillPath()
}
func iconGear(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    let outer = s * 0.36, inner = s * 0.26
    var path = CGMutablePath()
    for i in 0..<12 {
        let a = Double(i) * .pi / 6
        let r = i % 2 == 0 ? outer : inner
        let pt = CGPoint(x: p.x + r * CGFloat(cos(a)), y: p.y + r * CGFloat(sin(a)))
        if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
    }
    path.closeSubpath()
    ctx.addPath(path); ctx.strokePath()
    strokeCircle(p, s * 0.12, c, w)
}
func iconMic(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    ctx.addPath(rr(CGRect(x: p.x - s * 0.13, y: p.y - s * 0.38, width: s * 0.26, height: s * 0.46), s * 0.13)); ctx.strokePath()
    ctx.addArc(center: p, radius: s * 0.26, startAngle: 0, endAngle: .pi, clockwise: false); ctx.strokePath()
    ctx.move(to: CGPoint(x: p.x, y: p.y + s * 0.26)); ctx.addLine(to: CGPoint(x: p.x, y: p.y + s * 0.38)); ctx.strokePath()
}
func iconCast(_ p: CGPoint, _ s: CGFloat, _ c: CGColor, _ w: CGFloat = 1.4) {
    iconStroke(c, w)
    ctx.addPath(rr(CGRect(x: p.x - s * 0.38, y: p.y - s * 0.32, width: s * 0.76, height: s * 0.56), s * 0.10)); ctx.strokePath()
    ctx.setFillColor(c); fillCircle(CGPoint(x: p.x, y: p.y - s * 0.04), s * 0.07, c)
    ctx.addArc(center: CGPoint(x: p.x, y: p.y - s * 0.04), radius: s * 0.18, startAngle: -.pi * 0.85, endAngle: -.pi * 0.15, clockwise: true)
    ctx.strokePath()
}
/// The product mark: a caption plate with its drag handle.
func drawMark(_ p: CGPoint, _ s: CGFloat, _ tint: CGColor, _ barTop: CGColor, _ barBottom: CGColor, alpha: CGFloat = 1) {
    let plate = CGRect(x: p.x - s * 0.5, y: p.y - s * 0.30, width: s, height: s * 0.66)
    gradientRR(plate, s * 0.22, fade(mix(tint, rgb(0xFFFFFF), 0.14), alpha), fade(mix(tint, rgb(0x000000), 0.22), alpha))
    strokeRR(plate, s * 0.22, fade(rgb(0xFFFFFF, 0.20), alpha), s * 0.02)
    let pill = CGRect(x: p.x - s * 0.17, y: p.y - s * 0.46, width: s * 0.34, height: s * 0.12)
    fillRR(pill, s * 0.06, fade(mix(tint, rgb(0xFFFFFF), 0.22), alpha))
    fillRR(CGRect(x: plate.minX + s * 0.14, y: plate.minY + s * 0.16, width: s * 0.72, height: s * 0.115), s * 0.06, fade(barTop, alpha))
    fillRR(CGRect(x: plate.minX + s * 0.14, y: plate.minY + s * 0.36, width: s * 0.50, height: s * 0.115), s * 0.06, fade(barBottom, alpha))
}
/// Animated level meter used for the listening state.
func drawMeter(_ p: CGPoint, _ h: CGFloat, _ c: CGColor, _ phase: Double, alpha: CGFloat = 1, bars: Int = 4, gap: CGFloat = 2.2, wBar: CGFloat = 1.8) {
    let total = CGFloat(bars) * wBar + CGFloat(bars - 1) * gap
    var x = p.x - total / 2
    for i in 0..<bars {
        let k = sin(phase * 2.6 + Double(i) * 1.7) * 0.5 + 0.5
        let bh = h * (0.32 + 0.68 * CGFloat(k))
        fillRR(CGRect(x: x, y: p.y - bh / 2, width: wBar, height: bh), wBar / 2, fade(c, alpha))
        x += wBar + gap
    }
}
// ============================================================ layout cache
var blockCache: [String: TextBlock] = [:]
func block(_ s: String, _ size: CGFloat, _ w: Weight, width: CGFloat, lineHeight: CGFloat) -> TextBlock {
    let key = "\(size)|\(w)|\(width)|\(lineHeight)|\(s)"
    if let b = blockCache[key] { return b }
    let b = layout(s, uiFont(size, w), width: width, lineHeight: lineHeight)
    blockCache[key] = b
    return b
}

// ============================================================ device
let screenRect = CGRect(x: 0, y: 0, width: SW, height: SH)
let deviceRect = screenRect.insetBy(dx: -BEZEL, dy: -BEZEL)

func drawDevice(_ body: () -> Void) {
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: 26), blur: 60, color: rgb(0x000000, 0.62))
    fillRR(deviceRect, 34, rgb(0x0D100F))
    ctx.restoreGState()
    gradientRR(deviceRect, 34, rgb(0x323A36), rgb(0x141917))
    fillRR(deviceRect.insetBy(dx: 1.6, dy: 1.6), 32.6, rgb(0x0B0E0D))
    clipped(rr(screenRect, 26)) { body() }
    // inner edge of the glass
    strokeRR(screenRect.insetBy(dx: -0.6, dy: -0.6), 26.6, rgb(0xFFFFFF, 0.10), 1.2)
}

func statusBar(dark: Bool, alpha: CGFloat = 1) {
    fillCircle(CGPoint(x: SW / 2, y: 14), 4.2, rgb(0x050706))
    fillCircle(CGPoint(x: SW / 2, y: 14), 3.4, rgb(0x0B0F0D))
    let c = dark ? rgb(0xFFFFFF, 0.86) : P.onBg
    text("9:41", 10.5, .semibold, c, at: CGPoint(x: 18, y: 8), alpha: alpha)
    // signal, wifi, battery
    var x: CGFloat = SW - 56
    for i in 0..<4 {
        let h = 2.4 + CGFloat(i) * 1.5
        fillRR(CGRect(x: x, y: 16 - h, width: 1.8, height: h), 0.9, fade(c, alpha)); x += 2.9
    }
    x += 3
    ctx.saveGState(); iconStroke(fade(c, alpha), 1.3)
    ctx.addArc(center: CGPoint(x: x + 5, y: 16), radius: 6.4, startAngle: .pi * 1.25, endAngle: .pi * 1.75, clockwise: false); ctx.strokePath()
    ctx.addArc(center: CGPoint(x: x + 5, y: 16), radius: 3.4, startAngle: .pi * 1.25, endAngle: .pi * 1.75, clockwise: false); ctx.strokePath()
    ctx.restoreGState()
    fillCircle(CGPoint(x: x + 5, y: 14.6), 1.1, fade(c, alpha))
    x += 14
    strokeRR(CGRect(x: x, y: 9, width: 15, height: 7.6), 2.2, fade(c, alpha * 0.6), 1)
    fillRR(CGRect(x: x + 1.6, y: 10.6, width: 9, height: 4.4), 1.2, fade(c, alpha))
}

func homeIndicator(_ c: CGColor, alpha: CGFloat = 1) {
    fillRR(CGRect(x: SW / 2 - 32, y: SH - 10, width: 64, height: 3.4), 1.7, fade(c, alpha))
}

// ============================================================ home screen
struct HomeState {
    var appear: Double = 1
    var target = "中文"
    var prevTarget = "日本語"
    var targetMorph: Double = 1
    var sheet: Double = 0
    var sheetPick: Double = 0        // 0 = 日本語 selected, 1 = 中文 selected
    var sheetFlash: Double = 0
    var modelProgress: Double = 0
    var modelReady: Double = 0
    var fabPress: Double = 0
    var fabRipple: Double = 0
    var ready: Double = 0            // FAB morphs import -> start
    var active: Double = 0           // idle -> listening
    var perms: [Double] = [0, 0, 0]
    var overlayOk: Double = 0
    var previewRev: Double = 1
    var phase: Double = 0
}

let langRows = ["中文", "English", "日本語", "한국어", "Español", "Deutsch"]

func drawHome(_ s: HomeState) {
    ctx.setFillColor(P.bg); ctx.fill(screenRect)
    statusBar(dark: false)
    let rise = { (i: Double) -> (CGFloat, CGFloat) in       // (dy, alpha) staggered entrance
        let p = easeOutQuint(seg(s.appear, i * 0.07, i * 0.07 + 0.55))
        return (CGFloat(1 - p) * 16, CGFloat(p))
    }

    // header
    var (dy, a) = rise(0)
    drawMark(CGPoint(x: 25, y: 42 + dy), 20, rgb(0x18795A), rgb(0xFFFFFF), rgb(0xA7F2D0), alpha: a)
    text("CaptionGlass", 15.5, .semibold, P.onBg, at: CGPoint(x: 40, y: 33 + dy), alpha: a)
    let chipW: CGFloat = 52
    let chip = CGRect(x: SW - 14 - chipW, y: 31 + dy, width: chipW, height: 22)
    fillRR(chip, 11, fade(P.container, a))
    iconCloudOff(CGPoint(x: chip.minX + 14, y: chip.midY), 11, fade(P.onVar, a), 1.2)
    text("离线", 10, .medium, P.onVar, at: CGPoint(x: chip.minX + 23, y: chip.midY - 6.5), alpha: a)

    // stage card
    (dy, a) = rise(1)
    let card = CGRect(x: 14, y: 64 + dy, width: SW - 28, height: 126)
    fillRR(card, 18, fade(P.card, a))
    let idle = CGFloat(1 - min(1, s.active * 2.2)), live = CGFloat(max(0, s.active * 2.2 - 1.2))
    // status line
    if idle > 0.01 {
        iconPower(CGPoint(x: card.minX + 20, y: card.minY + 20), 13, fade(P.muted, a * idle), 1.4)
        text("未开启", 10.5, .medium, P.muted, at: CGPoint(x: card.minX + 32, y: card.minY + 13.5), alpha: a * idle)
    }
    if live > 0.01 {
        drawMeter(CGPoint(x: card.minX + 20, y: card.minY + 20), 11, P.accent, s.phase, alpha: a * live)
        text("聆听", 10.5, .medium, P.accent, at: CGPoint(x: card.minX + 32, y: card.minY + 13.5), alpha: a * live)
    }
    // idle: the language pair as a quiet watermark
    let tgtA = a * idle
    if tgtA > 0.01 {
        let m = CGFloat(easeOut(s.targetMorph))
        text(s.prevTarget, 23, .semibold, rgb(0x3A443F), at: CGPoint(x: card.minX + 20, y: card.minY + 46 - 6 * m), alpha: tgtA * (1 - m))
        text(s.target, 23, .semibold, rgb(0x3A443F), at: CGPoint(x: card.minX + 20, y: card.minY + 46 + 6 * (1 - m)), alpha: tgtA * m)
        text("English", 13, .regular, rgb(0x2C3531), at: CGPoint(x: card.minX + 20, y: card.minY + 76), alpha: tgtA)
    }
    if live > 0.01 {   // a caption preview breathing inside the stage
        let zh = block("先从人们真实的观看方式说起。", 13, .medium, width: card.width - 40, lineHeight: 17)
        let en = block("Let's start with how people really watch.", 11, .regular, width: card.width - 40, lineHeight: 14.5)
        let rev = s.previewRev * Double(zh.count)
        draw(zh, at: CGPoint(x: card.minX + 20, y: card.minY + 52), color: P.translation, alpha: a * live, reveal: rev)
        draw(en, at: CGPoint(x: card.minX + 20, y: card.minY + 74), color: P.provisional, alpha: a * live * 0.9)
    }

    // language row
    (dy, a) = rise(2)
    func field(_ r: CGRect, _ label: String, _ value: String, _ emphasis: Bool, _ valueAlpha: CGFloat = 1) {
        fillRR(r, 14, fade(emphasis ? P.primaryCt : P.container, a))
        text(label, 9, .regular, P.onVar, at: CGPoint(x: r.minX + 13, y: r.minY + 8), alpha: a)
        text(value, 13, .medium, emphasis ? P.onPrimaryCt : P.onBg, at: CGPoint(x: r.minX + 13, y: r.minY + 19), alpha: a * valueAlpha)
    }
    let left = CGRect(x: 14, y: 200 + dy, width: 94, height: 42)
    let right = CGRect(x: SW - 14 - 94, y: 200 + dy, width: 94, height: 42)
    field(left, "听到的语言", "English", false)
    let m = CGFloat(easeOut(s.targetMorph))
    fillRR(right, 14, fade(P.primaryCt, a))
    text("字幕语言", 9, .regular, P.onVar, at: CGPoint(x: right.minX + 13, y: right.minY + 8), alpha: a)
    text(s.prevTarget, 13, .medium, P.onPrimaryCt, at: CGPoint(x: right.minX + 13, y: right.minY + 19 - 5 * m), alpha: a * (1 - m))
    text(s.target, 13, .medium, P.onPrimaryCt, at: CGPoint(x: right.minX + 13, y: right.minY + 19 + 5 * (1 - m)), alpha: a * m)
    let swapC = CGPoint(x: SW / 2, y: 221 + dy)
    fillCircle(swapC, 16, fade(P.primaryCt, a))
    iconSwap(swapC, 15, fade(P.primary, a), 1.4)

    // model row
    (dy, a) = rise(3)
    let row = CGRect(x: 14, y: 250 + dy, width: SW - 28, height: 44)
    fillRR(row, 14, fade(P.container, a))
    iconBox(CGPoint(x: row.minX + 20, y: row.midY), 15, fade(P.onBg, a), 1.3)
    text("X-ASR · 中英", 11.5, .medium, P.onBg, at: CGPoint(x: row.minX + 36, y: row.minY + 9), alpha: a)
    let pend = CGFloat(1 - s.modelReady)
    text("需导入识别或翻译模型", 9.5, .regular, P.onVar, at: CGPoint(x: row.minX + 36, y: row.minY + 24), alpha: a * pend)
    text("已就绪 · 615 MB", 9.5, .regular, P.primary, at: CGPoint(x: row.minX + 36, y: row.minY + 24), alpha: a * CGFloat(s.modelReady))
    let ring = CGPoint(x: row.maxX - 22, y: row.midY)
    if s.modelProgress > 0.001 && s.modelReady < 1 {
        strokeCircle(ring, 8, fade(P.outline, a), 2)
        ctx.saveGState(); iconStroke(fade(P.primary, a), 2)
        ctx.addArc(center: ring, radius: 8, startAngle: -.pi / 2, endAngle: -.pi / 2 + .pi * 2 * CGFloat(s.modelProgress), clockwise: false)
        ctx.strokePath(); ctx.restoreGState()
    } else if s.modelReady >= 1 {
        fillCircle(ring, 8.5, fade(P.primaryCt, a))
        iconCheck(ring, 13, fade(P.primary, a), 1.7, 1)
    } else {
        iconChevron(ring, 14, fade(P.onVar, a), 1.4)
    }

    // overlay permission chip
    (dy, a) = rise(4)
    let allowed = CGFloat(s.overlayOk)
    let oc = CGRect(x: 14, y: 302 + dy, width: 116, height: 28)
    fillRR(oc, 14, fade(mix(P.container, P.primaryCt, allowed), a))
    iconPip(CGPoint(x: oc.minX + 18, y: oc.midY), 13, fade(mix(P.onVar, P.primary, allowed), a), 1.3)
    text("悬浮窗未开启", 10, .medium, P.onVar, at: CGPoint(x: oc.minX + 30, y: oc.midY - 6.5), alpha: a * (1 - allowed))
    text("悬浮窗已允许", 10, .medium, P.primary, at: CGPoint(x: oc.minX + 30, y: oc.midY - 6.5), alpha: a * allowed)

    // permission ticks that appear while starting
    for (i, p) in s.perms.enumerated() where p > 0.001 {
        let w: CGFloat = 50, gap: CGFloat = 10
        let r = CGRect(x: SW / 2 - (w * 3 + gap * 2) / 2 + CGFloat(i) * (w + gap), y: 334, width: w, height: 28)
        let pa = CGFloat(easeOut(min(1, p * 2.4)))
        ctx.saveGState()
        ctx.translateBy(x: 0, y: (1 - pa) * 7)
        fillRR(r, 14, fade(P.primaryCt, pa * a))
        let glyph = CGPoint(x: r.minX + 16, y: r.midY)
        switch i {
        case 0: iconMic(glyph, 14, fade(P.primary, pa), 1.4)
        case 1: iconPip(glyph, 14, fade(P.primary, pa), 1.4)
        default: iconCast(glyph, 14, fade(P.primary, pa), 1.4)
        }
        iconCheck(CGPoint(x: r.maxX - 14, y: r.midY), 12, fade(P.primary, pa), 1.6, clamp01((p - 0.35) * 3))
        ctx.restoreGState()
    }

    // start button
    (dy, a) = rise(5)
    let fab = CGPoint(x: SW / 2, y: 398 + dy)
    let press = CGFloat(1 - 0.045 * s.fabPress)
    if s.fabRipple > 0.001 && s.fabRipple < 1 {
        let k = CGFloat(easeOut(s.fabRipple))
        strokeCircle(fab, 33 + k * 32, fade(P.primary, (1 - k) * 0.55 * a), 2.4)
    }
    let running = CGFloat(s.active)
    fillCircle(fab, 33 * press, fade(mix(P.primary, rgb(0x0F513A), running), a))
    let iconA = CGFloat(s.ready)
    iconFolder(fab, 26, fade(rgb(0xFFFFFF), a * (1 - iconA)), 1.7)
    if running < 0.5 {
        iconPower(fab, 26, fade(rgb(0xFFFFFF), a * iconA * (1 - running * 2)), 1.8)
    } else {
        iconStop(fab, 24, fade(rgb(0xFFFFFF), a * (running - 0.5) * 2))
    }
    let labels: [(String, CGFloat)] = [("导入识别模型", a * (1 - iconA)), ("开启字幕", a * iconA * (1 - running)), ("停止字幕", a * running)]
    for (l, al) in labels where al > 0.01 {
        text(l, 12, .semibold, P.onBg, at: CGPoint(x: SW / 2, y: 440 + dy), alpha: al, align: 0.5)
    }
    text("X-ASR · 中英 · 615 MB", 9.5, .regular, P.onVar, at: CGPoint(x: SW / 2, y: 458 + dy), alpha: a * 0.9, align: 0.5)

    // bottom navigation
    (dy, a) = rise(6)
    let navY: CGFloat = 508 + dy
    fillRR(CGRect(x: SW / 2 - 60 - 17, y: navY - 13, width: 34, height: 26), 13, fade(P.primaryCt, a))
    let items: [(CGFloat, String, (CGPoint, CGFloat, CGColor, CGFloat) -> Void, Bool)] = [
        (SW / 2 - 60, "字幕", iconCaption, true), (SW / 2, "记录", iconList, false), (SW / 2 + 60, "设置", iconGear, false)]
    for (x, label, glyph, on) in items {
        glyph(CGPoint(x: x, y: navY), 15, fade(on ? P.onPrimaryCt : P.onVar, a), 1.4)
        text(label, 9.5, on ? .medium : .regular, on ? P.onBg : P.onVar, at: CGPoint(x: x, y: navY + 13), alpha: a, align: 0.5)
    }
    homeIndicator(rgb(0x171D1A, 0.5), alpha: a)

    // language sheet
    if s.sheet > 0.001 {
        let p = CGFloat(s.sheet)
        ctx.setFillColor(rgb(0x000000, 0.42 * p)); ctx.fill(screenRect)
        let h: CGFloat = 322
        let top = SH - h * p
        let sheet = CGRect(x: 0, y: top, width: SW, height: h + 30)
        fillRR(sheet, 24, P.bg)
        fillRR(CGRect(x: SW / 2 - 16, y: top + 9, width: 32, height: 3.4), 1.7, P.outline)
        text("字幕语言", 13.5, .semibold, P.onBg, at: CGPoint(x: 18, y: top + 22), alpha: 1)
        let sf = CGRect(x: 14, y: top + 44, width: SW - 28, height: 30)
        fillRR(sf, 15, P.container)
        iconSearch(CGPoint(x: sf.minX + 17, y: sf.midY), 13, P.onVar, 1.3)
        text("搜索语言", 11, .regular, P.onVar, at: CGPoint(x: sf.minX + 29, y: sf.midY - 7), alpha: 0.9)
        for (i, l) in langRows.enumerated() {
            let r = CGRect(x: 10, y: top + 84 + CGFloat(i) * 36, width: SW - 20, height: 33)
            let chosen: CGFloat = i == 0 ? CGFloat(s.sheetPick) : (i == 2 ? CGFloat(1 - s.sheetPick) : 0)
            if chosen > 0.01 { fillRR(r, 12, fade(P.primaryCt, chosen)) }
            if i == 0 && s.sheetFlash > 0.001 {
                strokeRR(r, 12, fade(P.primary, CGFloat(1 - s.sheetFlash) * 0.7), 1.4)
            }
            text(l, 13, chosen > 0.5 ? .medium : .regular, mix(P.onBg, P.onPrimaryCt, chosen), at: CGPoint(x: r.minX + 12, y: r.midY - 8), alpha: 1)
            if chosen > 0.01 { iconCheck(CGPoint(x: r.maxX - 16, y: r.midY), 13, fade(P.primary, chosen), 1.6, 1) }
        }
    }
}
// ============================================================ the other app: a talk player
struct PlayerState {
    var progress: CGFloat = 0.39
    var playing: Double = 1
    var tap: Double = 0
    var phase: Double = 0
}

func drawPlayer(_ s: PlayerState) {
    // scene
    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [P.vidTop, P.vidBottom] as CFArray, locations: [0, 1])!
    ctx.saveGState(); ctx.clip(to: screenRect)
    ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: 0), end: CGPoint(x: 0, y: SH), options: [])
    radialGlow(CGPoint(x: 168, y: 286), 210, rgb(0xF0B267, 0.13))
    radialGlow(CGPoint(x: 60, y: 170), 150, rgb(0x4C6BE0, 0.12))
    // stage floor
    ctx.saveGState()
    ctx.translateBy(x: 150, y: 430); ctx.scaleBy(x: 1, y: 0.16); ctx.translateBy(x: -150, y: -430)
    radialGlow(CGPoint(x: 150, y: 430), 190, rgb(0xF0B267, 0.10))
    ctx.restoreGState()
    // speaker silhouette
    let bodyDark = rgb(0x060A15)
    let head = CGPoint(x: 176, y: 272)
    let body = CGMutablePath()
    body.move(to: CGPoint(x: 128, y: 452))
    body.addCurve(to: CGPoint(x: 152, y: 322), control1: CGPoint(x: 132, y: 392), control2: CGPoint(x: 140, y: 336))
    body.addCurve(to: CGPoint(x: 200, y: 322), control1: CGPoint(x: 166, y: 306), control2: CGPoint(x: 190, y: 306))
    body.addCurve(to: CGPoint(x: 226, y: 452), control1: CGPoint(x: 214, y: 336), control2: CGPoint(x: 222, y: 392))
    body.closeSubpath()
    ctx.setFillColor(bodyDark); ctx.addPath(body); ctx.fillPath()
    fillCircle(head, 23, bodyDark)
    // warm rim on the right edge
    ctx.saveGState()
    ctx.clip(to: CGRect(x: 186, y: 230, width: 80, height: 240))
    iconStroke(rgb(0xF6C888, 0.5), 2)
    ctx.addPath(body); ctx.strokePath()
    ctx.strokeEllipse(in: CGRect(x: head.x - 23, y: head.y - 23, width: 46, height: 46))
    ctx.restoreGState()
    // slide panel
    let slide = CGRect(x: 24, y: 138, width: 104, height: 80)
    fillRR(slide, 7, rgb(0xE8EEFF, 0.07))
    strokeRR(slide, 7, rgb(0xFFFFFF, 0.13), 1)
    fillRR(CGRect(x: slide.minX + 12, y: slide.minY + 13, width: 52, height: 5), 2.5, rgb(0xFFFFFF, 0.5))
    for i in 0..<2 {
        fillRR(CGRect(x: slide.minX + 12, y: slide.minY + 26 + CGFloat(i) * 9, width: i == 0 ? 68 : 44, height: 3.4), 1.7, rgb(0xFFFFFF, 0.22))
    }
    let bars: [(CGFloat, CGColor)] = [(16, rgb(0x8DEBC1, 0.75)), (26, rgb(0xF0B267, 0.8)), (21, rgb(0xFFFFFF, 0.45)), (32, rgb(0x8DEBC1, 0.45))]
    for (i, b) in bars.enumerated() {
        fillRR(CGRect(x: slide.minX + 13 + CGFloat(i) * 13, y: slide.maxY - 12 - b.0, width: 8, height: b.0), 2, b.1)
    }
    // dust
    for (x, y, r, a) in [(CGFloat(52), CGFloat(300), CGFloat(2.4), CGFloat(0.10)), (78, 366, 1.6, 0.08), (222, 214, 2, 0.09), (206, 392, 1.4, 0.07), (40, 250, 1.2, 0.07)] {
        fillCircle(CGPoint(x: x, y: y), r, rgb(0xFFFFFF, a))
    }
    // vignette
    ctx.saveGState()
    let vg = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [rgb(0x000000, 0), rgb(0x000000, 0.55)] as CFArray, locations: [0.55, 1])!
    ctx.drawRadialGradient(vg, startCenter: CGPoint(x: SW / 2, y: SH * 0.45), startRadius: 40,
                           endCenter: CGPoint(x: SW / 2, y: SH * 0.45), endRadius: 360, options: [.drawsAfterEndLocation])
    ctx.restoreGState()
    ctx.restoreGState()

    // chrome
    statusBar(dark: true, alpha: 0.9)
    iconChevron(CGPoint(x: 22, y: 42), 16, rgb(0xFFFFFF, 0.8), 1.6, flip: true)
    text("How subtitles should feel", 10.5, .medium, rgb(0xFFFFFF, 0.62), at: CGPoint(x: 36, y: 36))
    drawMeter(CGPoint(x: SW - 46, y: 42), 11, rgb(0xFFFFFF, CGFloat(0.30 + 0.30 * s.playing)), s.phase, bars: 4, gap: 2, wBar: 1.7)
    iconCast(CGPoint(x: SW - 22, y: 42), 15, rgb(0xFFFFFF, 0.55), 1.3)

    let cy: CGFloat = 476
    if s.tap > 0.001 && s.tap < 1 {
        let k = CGFloat(easeOut(s.tap))
        strokeCircle(CGPoint(x: SW / 2, y: cy), 17 + k * 22, rgb(0xFFFFFF, (1 - k) * 0.35), 1.6)
    }
    fillCircle(CGPoint(x: SW / 2, y: cy), 17, rgb(0xFFFFFF, 0.16))
    if s.playing > 0.5 { iconPause(CGPoint(x: SW / 2, y: cy), 17, rgb(0xFFFFFF, 0.92)) }
    else { iconPlay(CGPoint(x: SW / 2 + 1, y: cy), 17, rgb(0xFFFFFF, 0.92)) }
    for (x, flip) in [(CGFloat(SW / 2 - 42), true), (SW / 2 + 42, false)] {
        iconChevron(CGPoint(x: x - (flip ? 2 : -2), y: cy), 13, rgb(0xFFFFFF, 0.45), 1.5, flip: flip)
        iconChevron(CGPoint(x: x + (flip ? 3 : -3), y: cy), 13, rgb(0xFFFFFF, 0.45), 1.5, flip: flip)
    }
    let track = CGRect(x: 18, y: 505, width: SW - 36, height: 2.6)
    fillRR(track, 1.3, rgb(0xFFFFFF, 0.20))
    fillRR(CGRect(x: track.minX, y: track.minY, width: track.width * s.progress, height: track.height), 1.3, rgb(0xFFFFFF, 0.88))
    fillCircle(CGPoint(x: track.minX + track.width * s.progress, y: track.midY), 3.6, rgb(0xFFFFFF, 0.95))
    text("07:1\(Int(s.progress * 20) % 10)", 8.5, .regular, rgb(0xFFFFFF, 0.55), at: CGPoint(x: 18, y: 513))
    text("18:42", 8.5, .regular, rgb(0xFFFFFF, 0.55), at: CGPoint(x: SW - 18, y: 513), align: 1)
    homeIndicator(rgb(0xFFFFFF, 0.55))
}

// ============================================================ the caption overlay
struct SegView {
    var zh: String
    var en: String
    var zhRev: Double = -1
    var enRev: Double = -1
    var confirm: Double = 1
    var trOpen: Double = 1
}
struct PlateState {
    var bottom: CGFloat = 444
    var alpha: CGFloat = 1
    var fill: CGFloat = 1
    var sweep: Double = -1
    var press: Double = 0
    var flash: Double = 0
    var blocking: Double = 0
    var scroll: CGFloat = 0
    var latest: Double = 0
    var visible: Int = 99
    var segs: [SegView] = []
    var phase: Double = 0
    var rise: Double = 1
    var forced: CGFloat = 0
}

let PLATE_X: CGFloat = 10
let PLATE_W: CGFloat = SW - 20
let PAD_X: CGFloat = 11
let PAD_Y: CGFloat = 9
let TEXT_W: CGFloat = PLATE_W - PAD_X * 2
let ZH_SIZE: CGFloat = 13, ZH_LH: CGFloat = 17.5
let EN_SIZE: CGFloat = 11, EN_LH: CGFloat = 14.5
let SEG_GAP: CGFloat = 9
let PLATE_MAX: CGFloat = 150

func segHeight(_ s: SegView) -> CGFloat {
    let zh = block(s.zh, ZH_SIZE, .medium, width: TEXT_W, lineHeight: ZH_LH)
    let en = block(s.en, EN_SIZE, .regular, width: TEXT_W, lineHeight: EN_LH)
    return (zh.height + 2) * CGFloat(s.trOpen) + en.height
}

func drawPlate(_ s: PlateState) {
    guard s.alpha > 0.004 else { return }
    let shown = Array(s.segs.suffix(s.visible))
    var contentH: CGFloat = 0
    for (i, v) in shown.enumerated() { contentH += segHeight(v) + (i == 0 ? 0 : SEG_GAP) }
    let h = s.forced > 0 ? s.forced : min(PLATE_MAX, contentH + PAD_Y * 2)
    let riseOffset = CGFloat(1 - spring(s.rise)) * 120
    let bottom = s.bottom + riseOffset
    let plate = CGRect(x: PLATE_X, y: bottom - h, width: PLATE_W, height: h)
    let alpha = s.alpha * CGFloat(clamp01(s.rise * 3))
    let radius: CGFloat = 13

    // obsidian fill and hairline
    if s.fill > 0.01 {
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: 6), blur: 18, color: rgb(0x000000, 0.45 * alpha * s.fill))
        fillRR(plate, radius, fade(P.plateTop, alpha * s.fill))
        ctx.restoreGState()
        gradientRR(plate, radius, fade(P.plateTop, alpha * s.fill), fade(P.plateBottom, alpha * s.fill))
        ctx.saveGState()
        ctx.addPath(rr(plate.insetBy(dx: 0.5, dy: 0.5), radius)); ctx.clip()
        let eg = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                            colors: [rgb(0xFFFFFF, 0.19 * alpha * s.fill), rgb(0xFFFFFF, 0.06 * alpha * s.fill)] as CFArray, locations: [0, 1])!
        ctx.saveGState()
        ctx.addPath(rr(plate.insetBy(dx: 0.5, dy: 0.5), radius)); ctx.setLineWidth(1); ctx.replacePathWithStrokedPath(); ctx.clip()
        ctx.drawLinearGradient(eg, start: CGPoint(x: 0, y: plate.minY), end: CGPoint(x: 0, y: plate.maxY), options: [])
        ctx.restoreGState()
        ctx.restoreGState()
    }
    // a light sweeping the edge while a translation is being produced
    if s.sweep >= 0 {
        let x = plate.minX - 50 + (plate.width + 100) * CGFloat(s.sweep.truncatingRemainder(dividingBy: 1))
        for (w, a) in [(CGFloat(52), CGFloat(0.13)), (30, 0.20), (14, 0.34)] {
            ctx.saveGState()
            ctx.clip(to: CGRect(x: x - w / 2, y: plate.minY - 4, width: w, height: plate.height + 8))
            strokeRR(plate.insetBy(dx: 0.5, dy: 0.5), radius, fade(P.accent, a * alpha), 1.3)
            ctx.restoreGState()
        }
    }

    // caption stack, bottom anchored, scrolled
    let content = plate.insetBy(dx: PAD_X, dy: PAD_Y)
    ctx.saveGState()
    ctx.clip(to: CGRect(x: plate.minX, y: plate.minY + 3, width: plate.width, height: plate.height - 6))
    var y = content.maxY + s.scroll
    for v in shown.reversed() {
        let zh = block(v.zh, ZH_SIZE, .medium, width: TEXT_W, lineHeight: ZH_LH)
        let en = block(v.en, EN_SIZE, .regular, width: TEXT_W, lineHeight: EN_LH)
        let open = CGFloat(easeOut(v.trOpen))
        let zhH = (zh.height + 2) * open
        let hSeg = zhH + en.height
        y -= hSeg
        if y < plate.minY - hSeg - 40 { break }
        if open > 0.01 {          // the translation line opens upward as it arrives
            ctx.saveGState()
            ctx.clip(to: CGRect(x: plate.minX, y: y, width: plate.width, height: zhH))
            draw(zh, at: CGPoint(x: content.minX, y: y + zhH - zh.height - 2), color: P.translation,
                 alpha: alpha, reveal: v.zhRev, shadow: s.fill < 0.5)
            ctx.restoreGState()
        }
        let srcColor = mix(P.provisional, P.source, CGFloat(v.confirm))
        draw(en, at: CGPoint(x: content.minX, y: y + zhH), color: srcColor, alpha: alpha,
             reveal: v.enRev, stops: v.enRev >= 0 ? wordStops(v.en) : nil, shadow: s.fill < 0.5)
        y -= SEG_GAP
    }
    ctx.restoreGState()
    // top fade so older lines leave softly
    if s.fill > 0.5 {
        ctx.saveGState()
        ctx.addPath(rr(plate, radius)); ctx.clip()
        let fg = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                            colors: [fade(P.plateTop, alpha * s.fill), fade(P.plateTop, 0)] as CFArray, locations: [0.30, 1])!
        ctx.drawLinearGradient(fg, start: CGPoint(x: 0, y: plate.minY), end: CGPoint(x: 0, y: plate.minY + 30), options: [])
        ctx.restoreGState()
    }

    // status chip above the plate
    let blocking = CGFloat(s.blocking)
    let label = s.blocking > 0.5 ? "字幕 · 穿透已开启" : "字幕 · 聆听"
    let lw = measure(label, 9.5, .medium)
    let chip = CGRect(x: plate.minX + 2, y: plate.minY - 27, width: lw + 30, height: 20)
    fillRR(chip, 10, fade(rgb(0x121815), 0.95 * alpha))
    strokeRR(chip, 10, fade(P.accent, (0.12 + 0.28 * blocking) * alpha), 1)
    drawMeter(CGPoint(x: chip.minX + 12, y: chip.midY), 9, mix(P.accent, P.warning, blocking), s.phase, alpha: alpha, bars: 3, gap: 2, wBar: 1.7)
    text(label, 9.5, .medium, mix(P.source, P.accent, blocking * 0.6), at: CGPoint(x: chip.minX + 21, y: chip.midY - 6), alpha: alpha)

    // follow-latest pill
    if s.latest > 0.01 {
        let a = CGFloat(easeOut(s.latest)) * alpha
        let w = measure("最新", 9.5, .medium) + 27
        let pill = CGRect(x: plate.maxX - w, y: plate.maxY + 6, width: w, height: 20)
        fillRR(pill, 10, fade(rgb(0x121815), 0.96 * a))
        strokeRR(pill, 10, fade(P.accent, 0.5 * a), 1)
        ctx.saveGState()
        ctx.translateBy(x: pill.minX + 11, y: pill.midY)
        ctx.rotate(by: .pi / 2)
        iconChevron(.zero, 11, fade(P.accent, a), 1.5)
        ctx.restoreGState()
        text("最新", 9.5, .medium, P.accent, at: CGPoint(x: pill.minX + 19, y: pill.midY - 6), alpha: a)
    }

    // handle riding the top edge
    let scale = 1 + 0.12 * CGFloat(s.press)
    let hw = 30 * scale, hh = 12 * scale
    let hc = CGPoint(x: plate.midX, y: plate.minY)
    let handle = CGRect(x: hc.x - hw / 2, y: hc.y - hh / 2, width: hw, height: hh)
    gradientRR(handle, hh / 2, fade(P.plateTop, alpha), fade(P.plateBottom, alpha))
    strokeRR(handle, hh / 2, fade(rgb(0xFFFFFF, 0.24), alpha), 1)
    let grip = mix(rgb(0xFFFFFF, 0.62), P.accent, max(blocking, CGFloat(s.flash)))
    fillRR(CGRect(x: hc.x - 7 * scale, y: hc.y - 1.1 * scale, width: 14 * scale, height: 2.2 * scale), 1.1, fade(grip, alpha))
    if s.flash > 0.01 {
        strokeCircle(hc, 12 + CGFloat(easeOut(s.flash)) * 16, fade(P.accent, CGFloat(1 - s.flash) * 0.5 * alpha), 1.4)
    }
}
// ============================================================ stage, camera, motifs
let CANVAS = CGRect(x: 0, y: 0, width: CW, height: CH)

func drawStageBackground() {
    ctx.setFillColor(rgb(0x070A09)); ctx.fill(CANVAS)
    ctx.saveGState()
    radialGlow(CGPoint(x: CW / 2, y: CH * 0.46), 430, rgb(0x0F3A2C, 0.55))
    radialGlow(CGPoint(x: CW / 2, y: CH * 0.62), 240, rgb(0x11513A, 0.30))
    ctx.restoreGState()
}

func drawLockup(_ alpha: CGFloat) {
    guard alpha > 0.004 else { return }
    let y = CH - 44 + CGFloat(1 - alpha) * 8
    drawMark(CGPoint(x: 54, y: y), 26, rgb(0x1B7B5B), rgb(0xFFFFFF), rgb(0xA7F2D0), alpha: alpha)
    text("CaptionGlass", 17, .semibold, rgb(0xE6EFE9), at: CGPoint(x: 76, y: y - 11), alpha: alpha, tracking: 0.2)
}

/// Closing statement: sound comes in, nothing goes out.
func drawMotifs(_ t: Double) {
    let inA = CGFloat(easeOut(seg(t, 15.15, 15.70))) * CGFloat(1 - seg(t, 16.15, 16.40))
    guard inA > 0.004 else { return }
    // incoming sound on the left
    let src = CGPoint(x: 138, y: CH * 0.52)
    fillCircle(src, 4.5, fade(P.accent, 0.75 * inA))
    for i in 0..<3 {
        let cycle = (t * 0.75 + Double(i) * 0.33).truncatingRemainder(dividingBy: 1)
        let r = 18 + CGFloat(cycle) * 74
        let a = CGFloat(sin(cycle * .pi)) * 0.5 * inA
        ctx.saveGState(); iconStroke(fade(P.accent, a), 1.8)
        ctx.addArc(center: src, radius: r, startAngle: -.pi / 3.4, endAngle: .pi / 3.4, clockwise: false)
        ctx.strokePath(); ctx.restoreGState()
    }
    // nothing leaves on the right
    let cloud = CGPoint(x: CW - 142, y: CH * 0.46)
    let breathe = CGFloat(0.5 + 0.5 * sin(t * 2.2))
    strokeCircle(cloud, 32 + breathe * 3, fade(rgb(0x6C8C7E), 0.38 * inA), 1.3)
    iconCloudOff(cloud, 38, fade(rgb(0xD4E3DB), 0.75 * inA), 2.0)
}

typealias Cam = (x: CGFloat, y: CGFloat, s: CGFloat)
let camKeys: [(Double, Cam)] = [
    (0.0, (SW / 2, SH / 2, 1.0)),
    (4.55, (SW / 2, SH / 2, 1.0)),
    (5.20, (SW / 2, 408, 2.30)),
    (9.40, (SW / 2, 408, 2.30)),
    (9.95, (SW / 2, 392, 1.72)),
    (14.95, (SW / 2, 392, 1.72)),
    (15.60, (SW / 2, SH / 2, 1.0)),
    (99.0, (SW / 2, SH / 2, 1.0)),
]
func camAt(_ t: Double) -> Cam {
    var prev = camKeys[0]
    for k in camKeys {
        if t <= k.0 {
            let p = CGFloat(easeInOut(seg(t, prev.0, k.0)))
            let s = exp(log(prev.1.s) + (log(k.1.s) - log(prev.1.s)) * p)
            return (prev.1.x + (k.1.x - prev.1.x) * p, prev.1.y + (k.1.y - prev.1.y) * p, s)
        }
        prev = k
    }
    return prev.1
}
func inCamera(_ c: Cam, _ body: () -> Void) {
    ctx.saveGState()
    ctx.translateBy(x: CW / 2, y: CH / 2)
    ctx.scaleBy(x: c.s, y: c.s)
    ctx.translateBy(x: -c.x, y: -c.y)
    body()
    ctx.restoreGState()
}
func toCanvas(_ p: CGPoint, _ c: Cam) -> CGPoint {
    CGPoint(x: (p.x - c.x) * c.s + CW / 2, y: (p.y - c.y) * c.s + CH / 2)
}

func drawTouch(_ world: CGPoint, _ cam: Cam, alpha: Double, press: Double, ripple: Double) {
    guard alpha > 0.004 else { return }
    let p = toCanvas(world, cam)
    let a = CGFloat(alpha)
    if ripple > 0.001 && ripple < 1 {
        let k = CGFloat(easeOut(ripple))
        strokeCircle(p, (16 + k * 26) * (0.52 + 0.48 * cam.s), rgb(0xFFFFFF, (1 - k) * 0.45 * a), 1.8)
    }
    let r = (16 - CGFloat(press) * 2.4) * (0.52 + 0.48 * cam.s)
    strokeCircle(p, r + 0.8, rgb(0x000000, 0.28 * a), 3.2)
    fillCircle(p, r, rgb(0xFFFFFF, (0.16 + 0.12 * CGFloat(press)) * a))
    strokeCircle(p, r, rgb(0xFFFFFF, (0.72 + 0.20 * CGFloat(press)) * a), 1.6)
}

// ============================================================ script
struct SegTime { let a: Double; let b: Double; let confirm: Double; let c: Double; let d: Double }
let script: [(zh: String, en: String)] = [
    ("先从人们真实的观看方式说起。", "Let's start with how people really watch."),
    ("多数播放器只显示一种语言。", "Most players show only one language."),
    ("好的字幕，会留出阅读的时间。", "Good subtitles give you time to read."),
    ("而这一切都在你自己的设备上运行。", "And all of it runs on your own device."),
]
let times: [SegTime] = [
    SegTime(a: 5.30, b: 6.20, confirm: 6.26, c: 6.36, d: 7.10),
    SegTime(a: 7.40, b: 8.30, confirm: 8.36, c: 8.46, d: 9.22),
]

func plateHeight(_ segs: [SegView], _ visible: Int) -> CGFloat {
    let shown = Array(segs.suffix(visible))
    var h: CGFloat = 0
    for (i, v) in shown.enumerated() { h += segHeight(v) + (i == 0 ? 0 : SEG_GAP) }
    return min(PLATE_MAX, h + PAD_Y * 2)
}

func renderFrame(_ t: Double) {
    // ---- caption model -------------------------------------------------
    var segs: [SegView] = [
        SegView(zh: script[0].zh, en: script[0].en),
        SegView(zh: script[1].zh, en: script[1].en),
    ]
    var shift: CGFloat = 0
    var sweep: Double = -1
    for (i, k) in times.enumerated() {
        guard t >= k.a - 0.02 else { break }
        let s = script[i + 2]
        let en = block(s.en, EN_SIZE, .regular, width: TEXT_W, lineHeight: EN_LH)
        let zh = block(s.zh, ZH_SIZE, .medium, width: TEXT_W, lineHeight: ZH_LH)
        var v = SegView(zh: s.zh, en: s.en)
        v.enRev = Double(en.count) * easeOut(seg(t, k.a, k.b)) * 1.02
        v.confirm = seg(t, k.confirm, k.confirm + 0.22)
        v.zhRev = t >= k.c ? Double(zh.count) * seg(t, k.c, k.d) * 1.02 : 0
        v.trOpen = seg(t, k.c - 0.05, k.c + 0.22)
        segs.append(v)
        shift = segHeight(v) * CGFloat(1 - easeOut(seg(t, k.a, k.a + 0.34)))
        if t >= k.c && t < k.d + 0.1 { sweep = (t - k.c) * 1.15 }
    }

    var plate = PlateState()
    plate.segs = segs
    plate.phase = t
    plate.rise = seg(t, 4.35, 4.95)
    plate.sweep = sweep
    plate.bottom = 444 - 60 * CGFloat(easeInOut(seg(t, 10.00, 10.55)))               // dragged up
    plate.scroll = shift + 104 * CGFloat(easeOut(seg(t, 12.80, 13.25))) * CGFloat(1 - spring(seg(t, 13.55, 13.95)))
    plate.latest = seg(t, 13.05, 13.30) * (1 - seg(t, 13.55, 13.75))
    plate.blocking = seg(t, 11.05, 11.18) * (1 - seg(t, 12.35, 12.45))
    plate.alpha = 1 - 0.18 * CGFloat(plate.blocking)
    plate.flash = pulse(t, 11.02, 11.42) * (t < 11.5 ? 1 : 0) + pulse(t, 12.32, 12.72) * (t > 12.2 ? 1 : 0)
    plate.press = min(1, seg(t, 9.88, 9.96) * (1 - seg(t, 10.48, 10.56))             // drag press
        + seg(t, 11.00, 11.06) * (1 - seg(t, 11.14, 11.20))                          // pass-through tap
        + seg(t, 12.30, 12.36) * (1 - seg(t, 12.44, 12.50)))                          // interaction tap
    plate.fill = 1 - CGFloat(easeInOut(seg(t, 14.38, 14.62))) * CGFloat(1 - easeInOut(seg(t, 14.85, 15.15)))
    // display-mode morph: the plate collapses to two segments, then one, then returns
    let hFull = plateHeight(segs, 99), hTwo = plateHeight(segs, 2), hOne = plateHeight(segs, 1)
    var forced = hFull
    forced = mixf(forced, hTwo, easeInOut(seg(t, 13.80, 14.05)))
    forced = mixf(forced, hOne, easeInOut(seg(t, 14.10, 14.32)))
    forced = mixf(forced, hFull, easeInOut(seg(t, 14.85, 15.20)))
    plate.forced = forced

    // ---- other app -----------------------------------------------------
    var player = PlayerState()
    player.phase = t
    let paused = t >= 11.63 && t < 12.05
    player.playing = paused ? 0 : 1
    let stop = min(t, 11.63), resume = max(0, t - 12.05)
    player.progress = 0.39 + CGFloat(max(0, stop - 4.5) + resume) * 0.0026
    player.tap = max(seg(t, 11.58, 12.02), seg(t, 12.00, 12.44))

    // ---- app -----------------------------------------------------------
    var home = HomeState()
    home.phase = t
    home.appear = seg(t, 0.18, 0.92)
    home.sheet = easeOut(seg(t, 1.20, 1.48)) - easeIn(seg(t, 1.95, 2.22))
    home.sheetPick = easeOut(seg(t, 1.78, 2.00))
    home.sheetFlash = seg(t, 1.76, 2.06)
    home.targetMorph = seg(t, 1.94, 2.24)
    home.modelProgress = easeInOut(seg(t, 2.46, 3.00))
    home.modelReady = t >= 3.04 ? 1 : 0
    home.ready = seg(t, 3.06, 3.22)
    home.fabPress = seg(t, 3.28, 3.34) * (1 - seg(t, 3.42, 3.50))
    home.fabRipple = seg(t, 3.30, 3.78)
    home.perms = [seg(t, 3.36, 3.80), seg(t, 3.50, 3.94), seg(t, 3.64, 4.08)]
    home.overlayOk = seg(t, 3.90, 4.00)
    home.active = seg(t, 3.78, 3.98)
    home.previewRev = clamp01((t - 3.94) * 2.6)

    // ---- compose --------------------------------------------------------
    let cam = camAt(t)
    drawStageBackground()
    drawLockup(CGFloat(easeOut(seg(t, 0.15, 0.85))) * CGFloat(1 - seg(t, 16.15, 16.40)))
    drawMotifs(t)

    inCamera(cam) {
        let entrance = spring(seg(t, 0.05, 1.00))
        ctx.saveGState()
        ctx.translateBy(x: 0, y: CGFloat(1 - entrance) * 120)
        ctx.setAlpha(CGFloat(clamp01(seg(t, 0.05, 0.40))))
        ctx.beginTransparencyLayer(auxiliaryInfo: nil)
        drawDevice {
            let swap = easeInOut(seg(t, 4.02, 4.58))
            if swap > 0.001 {
                ctx.saveGState()                       // the player settles in from slightly forward
                let z = CGFloat(1 + 0.035 * (1 - swap))
                ctx.translateBy(x: SW / 2, y: SH / 2); ctx.scaleBy(x: z, y: z); ctx.translateBy(x: -SW / 2, y: -SH / 2)
                drawPlayer(player)
                drawPlate(plate)
                ctx.restoreGState()
            }
            if swap < 0.999 {
                ctx.saveGState()
                ctx.setAlpha(CGFloat(1 - easeOut(seg(t, 4.02, 4.42))))
                ctx.beginTransparencyLayer(auxiliaryInfo: nil)
                let k = CGFloat(easeIn(seg(t, 4.02, 4.58)))
                ctx.translateBy(x: SW / 2, y: SH / 2)
                ctx.scaleBy(x: 1 - 0.09 * k, y: 1 - 0.09 * k)
                ctx.translateBy(x: -SW / 2, y: -SH / 2 + 150 * k)
                drawHome(home)
                ctx.endTransparencyLayer()
                ctx.restoreGState()
            }
        }
        ctx.endTransparencyLayer()
        ctx.restoreGState()
    }

    // ---- pointer ---------------------------------------------------------
    let plateTop = plate.bottom + CGFloat(1 - spring(plate.rise)) * 120 - plate.forced
    let handle = CGPoint(x: SW / 2, y: plateTop)
    let beats: [(Double, Double, CGPoint, Double, Double)] = [
        // (in, out, position, pressAt, rippleAt)
        (0.95, 1.20, CGPoint(x: 196, y: 221), 1.12, 1.12),
        (1.20, 2.12, CGPoint(x: 74, y: 331), 1.78, 1.78),
        (2.30, 2.60, CGPoint(x: 222, y: 272), 2.42, 2.42),
        (3.10, 3.70, CGPoint(x: SW / 2, y: 398), 3.28, 3.30),
        (9.70, 10.70, handle, 9.88, -1),
        (10.88, 11.34, handle, 11.02, 11.04),
        (11.36, 12.46, CGPoint(x: SW / 2, y: 476), 11.58, -1),
        (12.20, 12.54, handle, 12.32, 12.34),
        (12.54, 13.32, CGPoint(x: SW / 2, y: plateTop + 56), 12.70, -1),
        (13.36, 13.80, CGPoint(x: 214, y: plateTop + plate.forced + 16), 13.54, 13.56),
    ]
    var touch: CGPoint? = nil
    var tAlpha = 0.0, tPress = 0.0, tRipple = 0.0
    for (i, b) in beats.enumerated() {
        guard t >= b.0 - 0.24 && t <= b.1 + 0.2 else { continue }
        var p = b.2
        if i > 0, t < b.0 + 0.22 {            // glide from the previous target
            let prev = beats[i - 1]
            if b.0 - prev.1 < 0.5 {
                let g = CGFloat(easeInOut(seg(t, b.0 - 0.16, b.0 + 0.20)))
                p = CGPoint(x: prev.2.x + (b.2.x - prev.2.x) * g, y: prev.2.y + (b.2.y - prev.2.y) * g)
            }
        }
        touch = p
        tAlpha = easeOut(seg(t, b.0 - 0.22, b.0 + 0.02)) * (1 - easeIn(seg(t, b.1 - 0.14, b.1 + 0.16)))
        if b.3 > 0 { tPress = seg(t, b.3, b.3 + 0.07) * (1 - seg(t, b.3 + 0.16, b.3 + 0.24)) }
        if b.4 > 0 { tRipple = seg(t, b.4, b.4 + 0.50) }
    }
    if t >= 9.88 && t <= 10.58 {          // holding the handle: the plate follows the finger
        touch = CGPoint(x: SW / 2, y: plateTop)
        tPress = t < 10.48 ? 1 : Double(1 - seg(t, 10.48, 10.56))
    }
    if t >= 11.58 && t <= 12.44 {         // two taps land on the player underneath
        tPress = max(seg(t, 11.58, 11.63) * (1 - seg(t, 11.72, 11.78)), seg(t, 12.00, 12.05) * (1 - seg(t, 12.14, 12.20)))
    }
    if t >= 12.70 && t <= 13.40 {         // scrolling back through the transcript
        touch = CGPoint(x: SW / 2, y: plateTop + 56 + CGFloat(104 * easeOut(seg(t, 12.80, 13.25))))
        tPress = t < 13.26 ? 1 : Double(1 - seg(t, 13.26, 13.34))
    }
    if let p = touch { drawTouch(p, cam, alpha: tAlpha, press: tPress, ripple: tRipple) }

    // ---- film fades -------------------------------------------------------
    let fade = CGFloat(1 - easeOut(seg(t, 0.0, 0.28))) + CGFloat(easeIn(seg(t, 16.17, 16.45)))
    if fade > 0.002 { ctx.setFillColor(rgb(0x000000, min(1, fade))); ctx.fill(CANVAS) }
}

// ============================================================ main
let args = CommandLine.arguments
let outDir = args.count > 1 ? args[1] : "frames"
let single: Double? = args.count > 3 && args[2] == "--at" ? Double(args[3]) : nil
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

let pxW = Int(CW * SS), pxH = Int(CH * SS)
func newContext() -> CGContext {
    let c = CGContext(data: nil, width: pxW, height: pxH, bitsPerComponent: 8, bytesPerRow: 0,
                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    c.translateBy(x: 0, y: CGFloat(pxH))
    c.scaleBy(x: SS, y: -SS)
    c.setAllowsAntialiasing(true)
    c.interpolationQuality = .high
    return c
}
func write(_ image: CGImage, _ path: String) {
    let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
}

if let at = single {
    ctx = newContext()
    renderFrame(at)
    write(ctx.makeImage()!, "\(outDir)/single.png")
    print("frame at \(at)")
} else {
    let total = Int(DUR * FPS)
    for f in 0..<total {
        ctx = newContext()
        renderFrame(Double(f) / FPS)
        write(ctx.makeImage()!, String(format: "%@/f%04d.png", outDir, f))
        if f % 40 == 0 { FileHandle.standardError.write("\(f)/\(total)\n".data(using: .utf8)!) }
    }
    print("\(total) frames")
}
