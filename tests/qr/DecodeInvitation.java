import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

/** Offline decoding assertion. Never print the invitation or its token. */
class DecodeInvitation {
  public static void main(String[] args) throws Exception {
    BufferedImage image = ImageIO.read(Path.of(args[0]).toFile());
    var hints = Map.<DecodeHintType, Object>of(
        DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER, true);
    String expected = Files.readString(Path.of(args[1]));
    for (int rotation = 0; rotation < 4; rotation++) {
      int width = image.getWidth(), height = image.getHeight();
      int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
      var source = new RGBLuminanceSource(width, height, pixels);
      Result result;
      var bitmap = new BinaryBitmap(new HybridBinarizer(source));
      try { result = new com.google.zxing.qrcode.QRCodeReader().decode(bitmap, hints); }
      catch (ReaderException first) {
        // Same multi-finder retry as the camera/image reader. Never accept a
        // payload without ZXing's full format/error-correction decode.
        try {
          var candidates = new com.google.zxing.multi.qrcode.QRCodeMultiReader().decodeMultiple(bitmap, hints);
          if (candidates.length != 1) throw NotFoundException.getNotFoundInstance();
          result = candidates[0];
        } catch (ReaderException e) { throw new IllegalStateException("Cannot decode " + width + "x" + height + " at rotation " + rotation, e); }
      }
      if (!result.getText().equals(expected))
        throw new AssertionError("Decoded invitation does not match the current invitation");
      BufferedImage rotated = new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
        rotated.setRGB(height - 1 - y, x, pixels[y * width + x]);
      image = rotated;
    }
    System.out.println("Verified exact invitation decode in all four orientations");
  }
}
