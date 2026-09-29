import CoreGraphics

/// Where a clip's pixels land when it's rendered: source orientation → edit rotation → flip →
/// crop → fit (letterboxed, centered) onto the canvas → the target's coded orientation.
/// Transforms use UIKit's y-down space, where a positive angle turns clockwise.
struct MergeGeometry {
  /// Source coded pixels → target coded pixels, for the layer instruction.
  let transform: CGAffineTransform
  /// The crop in source coded pixels (for `setCropRectangle`); nil when the clip isn't cropped.
  let sourceCrop: CGRect?

  /// - Parameters:
  ///   - sourceSize: the source track's natural (coded) size
  ///   - sourceTransform: the source track's preferredTransform
  ///   - targetSize: the target's coded size (what the writer encodes)
  ///   - targetTransform: the target's preferredTransform (the orientation tag it's written with)
  ///   - canvas: the display size the target shows, e.g. 1080×1920
  init(
    clip: MergeClip, sourceSize: CGSize, sourceTransform: CGAffineTransform,
    targetSize: CGSize, targetTransform: CGAffineTransform, canvas: CGSize
  ) {
    // 1. Source as displayed: its own orientation, moved back to the origin.
    let toDisplay = Self.anchored(sourceTransform, size: sourceSize)
    let display = Self.bounds(sourceSize, toDisplay).size

    // 2. The edit's clockwise rotation.
    let degrees = (Int(clip.rotation.rounded()) % 360 + 360) % 360
    let rotate = Self.anchored(CGAffineTransform(rotationAngle: CGFloat(degrees) * .pi / 180), size: display)
    let rotated = Self.bounds(display, rotate).size

    // 3. Mirror horizontally, after the rotation.
    let flip = clip.flipped
      ? CGAffineTransform(scaleX: -1, y: 1).concatenating(CGAffineTransform(translationX: rotated.width, y: 0))
      : .identity
    let edited = toDisplay.concatenating(rotate).concatenating(flip)

    // 4. Crop, normalized to the rotated + flipped frame.
    var content = CGRect(origin: .zero, size: rotated)
    if let c = clip.crop {
      content = CGRect(
        x: c.x * rotated.width, y: c.y * rotated.height,
        width: c.w * rotated.width, height: c.h * rotated.height
      ).integral.intersection(content)
      sourceCrop = content.applying(edited.inverted())
    } else {
      sourceCrop = nil
    }

    // 5. Fit the content onto the canvas, centered (letterbox / pillarbox).
    let scale = min(canvas.width / content.width, canvas.height / content.height)
    let fit = CGAffineTransform(translationX: -content.minX, y: -content.minY)
      .concatenating(CGAffineTransform(scaleX: scale, y: scale))
      .concatenating(CGAffineTransform(
        translationX: (canvas.width - content.width * scale) / 2,
        y: (canvas.height - content.height * scale) / 2))

    // 6. Canvas → the target's coded pixels, which its orientation tag turns back into the canvas.
    let toCoded = Self.anchored(targetTransform, size: targetSize).inverted()

    transform = edited.concatenating(fit).concatenating(toCoded)
  }

  /// `t` followed by the translation that puts the transformed `size` rect back at the origin.
  static func anchored(_ t: CGAffineTransform, size: CGSize) -> CGAffineTransform {
    let b = bounds(size, t)
    return t.concatenating(CGAffineTransform(translationX: -b.minX, y: -b.minY))
  }

  static func bounds(_ size: CGSize, _ t: CGAffineTransform) -> CGRect {
    CGRect(origin: .zero, size: size).applying(t)
  }
}
