package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class FileInspection {
  static final Map<String, String> MIMES =
      Map.ofEntries(
          Map.entry("glb", "model/gltf-binary"),
          Map.entry("gltf", "model/gltf+json"),
          Map.entry("png", "image/png"),
          Map.entry("jpg", "image/jpeg"),
          Map.entry("jpeg", "image/jpeg"),
          Map.entry("webp", "image/webp"),
          Map.entry("mp4", "video/mp4"),
          Map.entry("webm", "video/webm"),
          Map.entry("mp3", "audio/mpeg"),
          Map.entry("wav", "audio/wav"),
          Map.entry("ogg", "audio/ogg"),
          Map.entry("m4a", "audio/mp4"),
          Map.entry("aac", "audio/aac"));

  static String extension(String filename) {
    int dot = filename.lastIndexOf('.');
    Json.require(
        dot > 0 && filename.length() <= 240 && !filename.matches(".*[/\\\\\\p{Cntrl}].*"),
        "A plain filename with an extension is required.");
    String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (!MIMES.containsKey(ext))
      throw new ApiException(415, "unsupported_file_format", "Unsupported file extension.");
    return ext;
  }

  static long limit(String kind, String filename) {
    String ext = extension(filename);
    String mime = MIMES.get(ext);
    boolean ok =
        switch (kind) {
          case "model" -> ext.equals("glb") || ext.equals("gltf");
          case "image" -> mime.startsWith("image/");
          case "media" -> mime.startsWith("video/") || mime.startsWith("audio/");
          default -> false;
        };
    Json.require(ok, "File kind and extension disagree.");
    return kind.equals("model")
        ? 25L * 1024 * 1024
        : kind.equals("image")
            ? 8L * 1024 * 1024
            : mime.startsWith("audio/") ? 30L * 1024 * 1024 : 100L * 1024 * 1024;
  }

  public static ObjectNode inspect(Path path, String kind, String filename) throws IOException {
    long size = Files.size(path);
    Json.require(
        size > 0 && size <= limit(kind, filename),
        "File exceeds its type-specific size budget or is empty.");
    String ext = extension(filename);
    String sha;
    try (InputStream in = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
      sha = HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    ObjectNode result =
        Json.obj(
            "sha256",
            sha,
            "format",
            ext.equals("jpg") ? "jpeg" : ext,
            "contentType",
            MIMES.get(ext),
            "byteSize",
            size);
    byte[] header;
    try (InputStream in = Files.newInputStream(path)) {
      header = in.readNBytes(32);
    }
    if (kind.equals("model")) result.set("inspection", model(path, ext));
    else {
      boolean valid =
          switch (ext) {
            case "png" ->
                header.length >= 8
                    && Arrays.equals(
                        Arrays.copyOf(header, 8),
                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "jpg", "jpeg" ->
                header.length >= 3
                    && (header[0] & 255) == 255
                    && (header[1] & 255) == 216
                    && (header[2] & 255) == 255;
            case "webp" -> ascii(header, 0, "RIFF") && ascii(header, 8, "WEBP");
            case "mp4", "m4a" -> ascii(header, 4, "ftyp");
            case "webm" -> header.length >= 4 && ByteBuffer.wrap(header).getInt() == 0x1A45DFA3;
            case "wav" -> ascii(header, 0, "RIFF") && ascii(header, 8, "WAVE");
            case "ogg" -> ascii(header, 0, "OggS");
            case "mp3" ->
                ascii(header, 0, "ID3")
                    || (header.length >= 2 && (header[0] & 255) == 255 && (header[1] & 224) == 224);
            case "aac" ->
                header.length >= 2 && (header[0] & 255) == 255 && (header[1] & 246) == 240;
            default -> false;
          };
      Json.require(valid, "File signature does not match its declared extension.");
      result.set("inspection", Json.obj("signatureChecked", true));
      if (kind.equals("media"))
        result.put("mediaType", MIMES.get(ext).startsWith("video/") ? "video" : "audio");
    }
    return result;
  }

  static boolean ascii(byte[] bytes, int offset, String value) {
    return bytes.length >= offset + value.length()
        && new String(bytes, offset, value.length(), StandardCharsets.US_ASCII).equals(value);
  }

  static ObjectNode model(Path path, String format) throws IOException {
    JsonNode document;
    if (format.equals("glb")) {
      try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
        byte[] header = new byte[20];
        file.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Json.require(b.getInt() == 0x46546c67 && b.getInt() == 2, "Expected a GLB version 2 file.");
        Json.require(
            Integer.toUnsignedLong(b.getInt()) == file.length(),
            "GLB declared length does not match file size.");
        long len = Integer.toUnsignedLong(b.getInt());
        Json.require(
            b.getInt() == 0x4E4F534A
                && len <= 8 * 1024 * 1024
                && len + 20 <= file.length()
                && len % 4 == 0,
            "Invalid GLB JSON chunk.");
        byte[] json = new byte[(int) len];
        file.readFully(json);
        document = Json.M.readTree(json);
        while (file.getFilePointer() < file.length()) {
          Json.require(file.length() - file.getFilePointer() >= 8, "Truncated GLB chunk.");
          long n = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
          file.readInt();
          Json.require(
              n % 4 == 0 && n <= file.length() - file.getFilePointer(),
              "Invalid GLB chunk length.");
          file.seek(file.getFilePointer() + n);
        }
      }
    } else
      try (InputStream in = Files.newInputStream(path)) {
        document = Json.M.readTree(in);
      }
    Json.require(
        document != null
            && document.isObject()
            && document.path("asset").path("version").asText().equals("2.0"),
        "Only glTF 2.0 is accepted.");
    for (String k : List.of("buffers", "images"))
      for (JsonNode n : document.path(k))
        if (n.has("uri"))
          Json.require(
              n.path("uri").asText().startsWith("data:"),
              "External model dependencies are not accepted; upload a self-contained GLB/glTF.");
    Set<String> seen = new HashSet<>(), duplicates = new LinkedHashSet<>();
    int named = 0;
    for (JsonNode n : document.path("nodes"))
      if (n.has("name")) {
        named++;
        if (!seen.add(n.path("name").asText())) duplicates.add(n.path("name").asText());
      }
    return Json.obj(
        "format",
        format,
        "gltfVersion",
        "2.0",
        "sceneCount",
        document.path("scenes").size(),
        "nodeCount",
        document.path("nodes").size(),
        "meshCount",
        document.path("meshes").size(),
        "materialCount",
        document.path("materials").size(),
        "textureCount",
        document.path("textures").size(),
        "imageCount",
        document.path("images").size(),
        "animationCount",
        document.path("animations").size(),
        "namedNodeCount",
        named,
        "duplicateNodeNames",
        duplicates,
        "externalResourceCount",
        0);
  }
}
