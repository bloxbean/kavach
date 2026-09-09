import Foundation
import CoreImage.CIFilterBuiltins
import ImageIO
import UniformTypeIdentifiers
let args = CommandLine.arguments
if args.count != 3 { fatalError("Usage: swift tools/render-qr.swift INPUT OUTPUT.png") }
let filter = CIFilter.qrCodeGenerator()
filter.message = try Data(contentsOf: URL(fileURLWithPath: args[1]))
filter.correctionLevel = "M"
let qr = filter.outputImage!
let extent = qr.extent.insetBy(dx: -4, dy: -4)
let white = CIImage(color: CIColor.white).cropped(to: extent)
let padded = qr.composited(over: white).transformed(by: CGAffineTransform(scaleX: 7, y: 7))
let image = CIContext().createCGImage(padded, from: padded.extent)!
let out = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[2]) as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(out, image, nil)
if !CGImageDestinationFinalize(out) { fatalError("Could not save QR") }
