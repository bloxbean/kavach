import AppKit
let size = 1024
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
NSColor(calibratedRed: 0.055, green: 0.071, blue: 0.067, alpha: 1).setFill(); NSBezierPath(rect: NSRect(x: 0,y: 0,width: size,height: size)).fill()
let lime = NSColor(calibratedRed: 0.79, green: 0.93, blue: 0.45, alpha: 1)
let shield = NSBezierPath(); shield.move(to: NSPoint(x: 512,y: 806)); shield.line(to: NSPoint(x: 760,y: 690)); shield.line(to: NSPoint(x: 732,y: 400)); shield.curve(to: NSPoint(x: 512,y: 204), controlPoint1: NSPoint(x: 705,y: 302), controlPoint2: NSPoint(x: 615,y: 242)); shield.curve(to: NSPoint(x: 292,y: 400), controlPoint1: NSPoint(x: 409,y: 242), controlPoint2: NSPoint(x: 319,y: 302)); shield.line(to: NSPoint(x: 264,y: 690)); shield.close(); lime.setFill(); shield.fill()
let check = NSBezierPath(); check.move(to: NSPoint(x: 390,y: 525)); check.line(to: NSPoint(x: 476,y: 430)); check.line(to: NSPoint(x: 637,y: 610)); check.lineWidth = 48; check.lineCapStyle = .round; check.lineJoinStyle = .round; NSColor(calibratedRed: 0.055, green: 0.071, blue: 0.067, alpha: 1).setStroke(); check.stroke()
NSGraphicsContext.restoreGraphicsState()
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "App/Assets.xcassets/AppIcon.appiconset/AppIcon.png"))
for (pixels, suffix) in [(120, "@2x"), (180, "@3x")] {
    let target = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: target)
    NSGraphicsContext.current?.imageInterpolation = .high
    let image = NSImage(size: NSSize(width: size, height: size)); image.addRepresentation(bitmap)
    image.draw(in: NSRect(x: 0, y: 0, width: pixels, height: pixels))
    NSGraphicsContext.restoreGraphicsState()
    try target.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "App/AppIcon60x60\(suffix).png"))
}
