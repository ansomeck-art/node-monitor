import AppKit
import Foundation

// Project-local icon artwork, drawn from paths with no external assets.
let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
func render(_ pixels: Int) throws -> Data {
    let image = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    let context = NSGraphicsContext(bitmapImageRep: image)!
    NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = context
    defer { NSGraphicsContext.restoreGraphicsState() }
    context.cgContext.scaleBy(x: CGFloat(pixels) / 1024, y: CGFloat(pixels) / 1024)
    let outline = NSBezierPath(roundedRect: NSRect(x: 72, y: 72, width: 880, height: 880), xRadius: 202, yRadius: 202)
    NSGradient(starting: NSColor(red: 0.08, green: 0.20, blue: 0.32, alpha: 1), ending: NSColor(red: 0.025, green: 0.065, blue: 0.13, alpha: 1))!.draw(in: outline, angle: -90)
    for y in [560.0, 370.0] {
        let rack = NSBezierPath(roundedRect: NSRect(x: 247, y: y, width: 530, height: 145), xRadius: 35, yRadius: 35)
        NSColor.white.withAlphaComponent(0.1).setFill(); rack.fill()
        NSColor(red: 0.35, green: 0.83, blue: 0.95, alpha: 0.85).setStroke(); rack.lineWidth = 7; rack.stroke()
        NSColor(red: 0.3, green: 0.92, blue: 0.85, alpha: 1).setFill()
        NSBezierPath(ovalIn: NSRect(x: 286, y: y + 57, width: 30, height: 30)).fill()
        for x in [645.0, 681.0, 717.0] {
            NSColor.white.withAlphaComponent(0.6).setFill()
            NSBezierPath(roundedRect: NSRect(x: x, y: y + 48, width: 12, height: 48), xRadius: 6, yRadius: 6).fill()
        }
    }
    let line = NSBezierPath(); line.move(to: NSPoint(x: 233, y: 255))
    for point in [NSPoint(x: 365, y: 255), NSPoint(x: 421, y: 302), NSPoint(x: 482, y: 194), NSPoint(x: 557, y: 320), NSPoint(x: 612, y: 255), NSPoint(x: 791, y: 255)] { line.line(to: point) }
    NSColor(red: 0.33, green: 0.95, blue: 0.83, alpha: 1).setStroke(); line.lineWidth = 18; line.lineCapStyle = .round; line.lineJoinStyle = .round; line.stroke()
    return image.representation(using: .png, properties: [:])!
}
for size in [16, 32, 128, 256, 512] {
    try render(size).write(to: directory.appendingPathComponent("icon_\(size)x\(size).png"))
    try render(size * 2).write(to: directory.appendingPathComponent("icon_\(size)x\(size)@2x.png"))
}
