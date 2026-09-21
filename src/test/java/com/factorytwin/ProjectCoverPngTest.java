package com.factorytwin;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.IIOImage;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

class ProjectCoverPngTest {
  static byte[] png(int width, int height, int type, boolean interlaced) throws IOException {
    var image = new BufferedImage(width, height, type);
    var bytes = new ByteArrayOutputStream();
    try (var out = new MemoryCacheImageOutputStream(bytes)) {
      var writer = ImageIO.getImageWritersByFormatName("png").next();
      try {
        writer.setOutput(out);
        var params = writer.getDefaultWriteParam();
        params.setProgressiveMode(interlaced ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
        writer.write(null, new IIOImage(image, null, null), params);
      } finally { writer.dispose(); image.flush(); }
    }
    return bytes.toByteArray();
  }

  static byte[] validPng() throws IOException {
    return png(960, 540, BufferedImage.TYPE_INT_ARGB, false);
  }

  static byte[] chunk(String type, byte[] payload) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    byte[] name = type.getBytes(StandardCharsets.US_ASCII);
    out.writeInt(payload.length); out.write(name); out.write(payload);
    var crc = new CRC32(); crc.update(name); crc.update(payload); out.writeInt((int) crc.getValue());
    return bytes.toByteArray();
  }

  static byte[] withPixels(byte[] pixels) throws IOException {
    byte[] valid = validPng();
    var bytes = new ByteArrayOutputStream();
    bytes.write(valid, 0, 33);
    bytes.write(chunk("IDAT", pixels)); bytes.write(chunk("IEND", new byte[0]));
    return bytes.toByteArray();
  }

  static void invalid(byte[] bytes) {
    var error = assertThrows(ApiException.class, () -> ProjectCoverPng.validate(bytes));
    assertEquals(400, error.status); assertEquals("invalid_project_cover_png", error.code);
  }

  @Test void acceptsDecodedRgbRgbaGrayscalePaletteAndAdam7() throws Exception {
    for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_BYTE_INDEXED}) {
      for (boolean interlace : new boolean[] {false, true}) {
        assertDoesNotThrow(() -> ProjectCoverPng.validate(png(960, 540, type, interlace)));
      }
    }
  }

  @Test void rejectsSignatureTruncationChecksumAndTrailingBytes() throws Exception {
    invalid("<svg/>".getBytes(StandardCharsets.UTF_8));
    byte[] valid = validPng();
    invalid(Arrays.copyOf(valid, valid.length - 1));
    invalid(Arrays.copyOf(valid, valid.length + 1));
    valid[30] ^= 1; invalid(valid);
  }

  @Test void rejectsWrongDimensionsBeforeDecodingPixels() throws Exception {
    invalid(png(959, 540, BufferedImage.TYPE_INT_RGB, false));
    byte[] valid = validPng();
    byte[] ihdr = Arrays.copyOfRange(valid, 16, 29);
    ByteBuffer.wrap(ihdr).putInt(Integer.MAX_VALUE);
    System.arraycopy(chunk("IHDR", ihdr), 0, valid, 8, 25);
    invalid(valid);
  }

  @Test void rejectsOversizedBody() {
    var error = assertThrows(ApiException.class,
        () -> ProjectCoverPng.validate(new byte[ProjectCoverPng.MAX_BYTES + 1]));
    assertEquals(413, error.status); assertEquals("project_cover_too_large", error.code);
  }

  @Test void rejectsCompressionBombWithinTheFixedPixelBudget() throws Exception {
    var compressed = new ByteArrayOutputStream();
    try (var deflate = new DeflaterOutputStream(compressed)) { deflate.write(new byte[16 * 1024 * 1024]); }
    byte[] bomb = withPixels(compressed.toByteArray());
    assertTrue(bomb.length < ProjectCoverPng.MAX_BYTES);
    assertTimeout(Duration.ofSeconds(2), () -> invalid(bomb));
  }

  @Test void rejectsCorruptCompressedDataAndInvalidScanlineFilters() throws Exception {
    invalid(withPixels(new byte[] {1, 2, 3, 4}));
    var compressed = new ByteArrayOutputStream();
    byte[] rows = new byte[540 * (1 + 960 * 4)]; rows[0] = 5;
    try (var deflate = new DeflaterOutputStream(compressed)) { deflate.write(rows); }
    invalid(withPixels(compressed.toByteArray()));
  }

  @Test void rejectsCompressedMetadataAndAnimation() throws Exception {
    byte[] valid = validPng();
    for (String type : new String[] {"zTXt", "iCCP", "acTL"}) {
      var bytes = new ByteArrayOutputStream(); bytes.write(valid, 0, 33);
      bytes.write(chunk(type, new byte[8])); bytes.write(valid, 33, valid.length - 33);
      invalid(bytes.toByteArray());
    }
  }
}
