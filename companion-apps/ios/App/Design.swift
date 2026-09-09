import SwiftUI
import CoreImage.CIFilterBuiltins

enum Palette {
    static let background = Color(red: 0.055, green: 0.071, blue: 0.067)
    static let panel = Color(red: 0.10, green: 0.12, blue: 0.11)
    static let line = Color.white.opacity(0.10)
    static let ink = Color(red: 0.95, green: 0.96, blue: 0.90)
    static let muted = Color(red: 0.60, green: 0.65, blue: 0.60)
    static let accent = Color(red: 0.79, green: 0.93, blue: 0.45)
}
struct PrimaryButton: View {
    let title: String; var icon = "arrow.right"; var disabled = false; let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack { Text(title).font(.system(size: 16, weight: .semibold)); Spacer(); Image(systemName: icon) }
                .padding(20).foregroundStyle(Palette.background).background(Palette.accent.opacity(disabled ? 0.3 : 1), in: RoundedRectangle(cornerRadius: 18))
        }.disabled(disabled).buttonStyle(.plain)
    }
}
struct Surface<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View { VStack(alignment: .leading, spacing: 18) { content }.padding(22).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel, in: RoundedRectangle(cornerRadius: 24)).overlay(RoundedRectangle(cornerRadius: 24).stroke(Palette.line)) }
}
struct Eyebrow: View {
    let text: String
    var body: some View { Text(text.uppercased()).font(.system(size: 10, weight: .bold, design: .monospaced)).tracking(2).foregroundStyle(Palette.muted) }
}
struct Brand: View {
    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: "circle.hexagongrid.fill").font(.system(size: 22)).foregroundStyle(Palette.accent)
            Text("yano").font(.system(size: 26, weight: .semibold, design: .rounded)).tracking(-1)
            Text("/ companion").font(.system(size: 13)).foregroundStyle(Palette.muted)
            Spacer()
        }
    }
}
struct DetailRow: View {
    let title: String, value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 12)).foregroundStyle(Palette.muted)
            Text(value).font(.system(size: 13, weight: .medium, design: .monospaced)).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}
struct QRCode: View {
    let text: String
    private var rendered: UIImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage, let image = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 6, y: 6)), from: output.extent.applying(CGAffineTransform(scaleX: 6, y: 6))) else { return nil }
        return UIImage(cgImage: image)
    }
    var body: some View {
        if let rendered { Image(uiImage: rendered).interpolation(.none).resizable().scaledToFit().padding(20).background(.white, in: RoundedRectangle(cornerRadius: 24)).accessibilityLabel("QR code for the displayed public data") }
    }
}
struct SheetFrame<Content: View>: View {
    let title: String; let dismiss: () -> Void; @ViewBuilder let content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack { Eyebrow(text: title); Spacer(); Button(action: dismiss) { Image(systemName: "xmark").padding(10).background(Palette.line, in: Circle()) }.accessibilityLabel("Close") }
                content
            }.padding(24).padding(.bottom, 24)
        }.background(Palette.background).foregroundStyle(Palette.ink).preferredColorScheme(.dark)
    }
}
