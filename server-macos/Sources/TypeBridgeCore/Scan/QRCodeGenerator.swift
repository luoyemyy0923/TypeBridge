import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins

public enum QRCodeGenerator {
    /// 生成二维码位图，`pointSize` 为输出边长。
    public static func image(for content: String, pointSize: CGFloat = 240) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(content.utf8)
        filter.correctionLevel = "M"

        guard let output = filter.outputImage else { return nil }
        let scale = pointSize / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
