package com.factorytwin;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Small, fully decoded browser screenshots only; never accept arbitrary image metadata. */
final class ProjectCoverPng {
  static final int WIDTH = 960;
  static final int HEIGHT = 540;
  static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
  // No APNG, compressed text, ICC profiles, EXIF, or unknown metadata. Canvas PNGs do not need them.
  private static final Set<String> CHUNKS = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "sRGB", "gAMA", "cHRM", "pHYs");

  static void validate(byte[] png) {
    if (png.length > MAX_BYTES) {
      throw new ApiException(413, "project_cover_too_large", "Project cover PNG must not exceed 2 MiB.");
    }
    require(png.length >= 45 && Arrays.equals(SIGNATURE, Arrays.copyOf(png, 8)), "Invalid PNG signature or truncated image.");
    int offset = 8, expectedInflated = 0, colorType = -1, paletteEntries = 0;
    boolean header = false, pixels = false, pixelsEnded = false, end = false;
    Set<String> seen = new HashSet<>();
    var compressed = new ByteArrayOutputStream();
    while (offset < png.length) {
      require(png.length - offset >= 12, "Truncated PNG chunk.");
      int size = ByteBuffer.wrap(png, offset, 4).getInt();
      require(size >= 0 && size <= png.length - offset - 12, "Invalid PNG chunk length.");
      String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
      require(CHUNKS.contains(type), "Unsupported PNG chunk: " + type + ". Export a static browser canvas PNG.");
      var crc = new CRC32();
      crc.update(png, offset + 4, size + 4);
      long storedCrc = Integer.toUnsignedLong(ByteBuffer.wrap(png, offset + 8 + size, 4).getInt());
      require(crc.getValue() == storedCrc, "PNG chunk checksum mismatch.");
      require(header || type.equals("IHDR"), "PNG must begin with IHDR.");
      if (!type.equals("IDAT")) require(seen.add(type), "Duplicate PNG chunk: " + type);
      if (pixels && !type.equals("IDAT")) pixelsEnded = true;
      int data = offset + 8;
      switch (type) {
        case "IHDR" -> {
          require(size == 13, "Invalid PNG header length.");
          int width = ByteBuffer.wrap(png, data, 4).getInt();
          int height = ByteBuffer.wrap(png, data + 4, 4).getInt();
          require(width == WIDTH && height == HEIGHT, "Project cover PNG must be exactly 960 × 540 pixels.");
          int depth = png[data + 8] & 255, color = png[data + 9] & 255;
          colorType = color;
          int channels = switch (color) { case 0, 3 -> 1; case 2 -> 3; case 4 -> 2; case 6 -> 4; default -> 0; };
          boolean legalDepth = switch (color) {
            case 0 -> Set.of(1, 2, 4, 8, 16).contains(depth);
            case 3 -> Set.of(1, 2, 4, 8).contains(depth);
            case 2, 4, 6 -> depth == 8 || depth == 16;
            default -> false;
          };
          require(legalDepth && channels > 0 && png[data + 10] == 0 && png[data + 11] == 0,
              "Unsupported PNG pixel encoding.");
          int interlace = png[data + 12] & 255;
          require(interlace <= 1, "Invalid PNG interlace method.");
          expectedInflated = scanlineBytes(channels * depth, interlace);
          header = true;
        }
        case "IDAT" -> {
          require(!pixelsEnded, "PNG pixel chunks must be consecutive.");
          require(colorType != 3 || paletteEntries > 0, "Indexed PNG requires a palette before pixels.");
          pixels = true;
          compressed.write(png, data, size);
        }
        case "IEND" -> {
          require(size == 0 && pixels && offset + 12 == png.length, "Invalid PNG end or trailing bytes.");
          end = true;
        }
        default -> {
          require(!pixels, "PNG metadata must precede pixel data.");
          require(switch (type) {
            case "PLTE" -> size >= 3 && size <= 768 && size % 3 == 0;
            case "tRNS" -> size >= 1 && size <= 256;
            case "sRGB" -> size == 1;
            case "gAMA" -> size == 4;
            case "cHRM" -> size == 32;
            case "pHYs" -> size == 9;
            default -> false;
          }, "Invalid PNG metadata size.");
          if (type.equals("PLTE")) {
            require(colorType != 0 && colorType != 4, "Grayscale PNG cannot contain a palette.");
            paletteEntries = size / 3;
          }
          if (type.equals("tRNS")) {
            require((colorType == 0 && size == 2) || (colorType == 2 && size == 6)
                || (colorType == 3 && paletteEntries > 0 && size <= paletteEntries),
                "PNG transparency does not match its pixel encoding.");
          }
          if (type.equals("sRGB")) require((png[data] & 255) <= 3, "Invalid PNG rendering intent.");
        }
      }
      offset += size + 12;
    }
    require(header && pixels && end, "PNG is missing required chunks.");
    inflateBounded(compressed.toByteArray(), expectedInflated);
    // Decode only after all allocation budgets are known, to validate palette, filters and pixels.
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
      var readers = ImageIO.getImageReadersByFormatName("png");
      if (!readers.hasNext()) throw new IllegalStateException("PNG ImageIO decoder is unavailable.");
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        require(reader.getWidth(0) == WIDTH && reader.getHeight(0) == HEIGHT, "PNG decoded dimensions are invalid.");
        var decoded = reader.read(0);
        require(decoded != null && decoded.getWidth() == WIDTH && decoded.getHeight() == HEIGHT, "PNG pixels could not be decoded.");
        decoded.flush();
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new ApiException(400, "invalid_project_cover_png", "PNG decoding failed: " + e.getMessage());
    }
  }

  private static int scanlineBytes(int bitsPerPixel, int interlace) {
    if (interlace == 0) return HEIGHT * (1 + (WIDTH * bitsPerPixel + 7) / 8);
    int[] x = {0, 4, 0, 2, 0, 1, 0}, y = {0, 0, 4, 0, 2, 0, 1};
    int[] dx = {8, 8, 4, 4, 2, 2, 1}, dy = {8, 8, 8, 4, 4, 2, 2};
    int total = 0;
    for (int pass = 0; pass < 7; pass++) {
      int w = (WIDTH - x[pass] + dx[pass] - 1) / dx[pass];
      int h = (HEIGHT - y[pass] + dy[pass] - 1) / dy[pass];
      total += h * (1 + (w * bitsPerPixel + 7) / 8);
    }
    return total;
  }

  private static void inflateBounded(byte[] compressed, int expected) {
    var inflater = new Inflater();
    try {
      inflater.setInput(compressed);
      byte[] block = new byte[8192];
      int total = 0;
      while (!inflater.finished()) {
        int count = inflater.inflate(block);
        total += count;
        require(total <= expected, "PNG decompressed pixels exceed the fixed image budget.");
        require(count > 0 || inflater.finished(), "PNG pixel compression is incomplete or invalid.");
      }
      require(total == expected && inflater.getRemaining() == 0, "PNG pixel length or compression stream is invalid.");
    } catch (DataFormatException e) {
      throw new ApiException(400, "invalid_project_cover_png", "Invalid PNG compressed pixel data.");
    } finally {
      inflater.end();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new ApiException(400, "invalid_project_cover_png", message);
  }
}
