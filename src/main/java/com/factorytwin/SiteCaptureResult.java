package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Validate the complete trusted viewer boundary before publishing any native-worker output. */
final class SiteCaptureResult {
  static final List<String> FIELDS;
  static {
    var names = new ArrayList<>(List.of("x", "y", "z", "nx", "ny", "nz", "f_dc_0", "f_dc_1", "f_dc_2"));
    names.addAll(List.of("opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"));
    FIELDS = List.copyOf(names);
  }
  static void require(boolean value) { if (!value) throw new ApiException(422, "site_capture_result_invalid", "重建输出校验失败，结果未发布。"); }
  static double[] vector(JsonNode value) {
    require(value.isArray() && value.size()==3);
    double[] out = new double[3];
    for (int i=0; i<3; i++) { require(value.get(i).isNumber()); out[i]=value.get(i).asDouble(); require(Double.isFinite(out[i]) && Math.abs(out[i])<=1e9); }
    return out;
  }
  static ObjectNode validate(Path directory, JsonNode input) throws IOException {
    require(input.isObject() && input.path("format").asText().equals("ply") && input.path("file").asText().equals("scene.ply"));
    require(input.path("coordinateSystem").asText().equals("colmap") && input.path("units").asText().equals("arbitrary"));
    Path file = directory.resolve("scene.ply");
    require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
    long bytes = Files.size(file);
    if (bytes > SiteCaptures.OUTPUT_BYTES) throw new ApiException(413, "site_capture_output_too_large", "写实模型超过 200 MiB，请缩小采集区域后重试。");
    require(input.path("byteSize").isIntegralNumber() && input.path("byteSize").canConvertToLong() && input.path("byteSize").asLong()==bytes);
    require(input.path("gaussianCount").isIntegralNumber() && input.path("gaussianCount").canConvertToLong());
    long count = input.path("gaussianCount").asLong(); require(count>0 && count<=1000000);
    double[] min=vector(input.path("bounds").path("min")), max=vector(input.path("bounds").path("max"));
    double extentSquared=0;
    for (int i=0;i<3;i++) { require(min[i]<=max[i]); extentSquared+=Math.pow(max[i]-min[i],2); }
    require(Double.isFinite(extentSquared) && extentSquared>0);
    double[] position=vector(input.path("initialCamera").path("position")), target=vector(input.path("initialCamera").path("target")), up=vector(input.path("initialCamera").path("up"));
    double distance=0, upLength=0, dot=0;
    for (int i=0;i<3;i++) { distance+=Math.pow(position[i]-target[i],2); upLength+=up[i]*up[i]; dot+=(target[i]-position[i])*up[i]; }
    require(distance>1e-12 && Math.abs(upLength-1)<1e-4 && Math.abs(dot)/Math.sqrt(distance)<1e-4);
    var quality=input.path("quality");
    require(quality.path("inputImages").isIntegralNumber() && quality.path("inputImages").canConvertToInt() && quality.path("registeredImages").isIntegralNumber() && quality.path("registeredImages").canConvertToInt() && quality.path("points3D").isIntegralNumber() && quality.path("points3D").canConvertToLong());
    int images=quality.path("inputImages").asInt(), registered=quality.path("registeredImages").asInt();
    require(images>=8 && images<=120 && registered>=8 && registered<=images && registered>=Math.ceil(images*0.8) && quality.path("points3D").asLong()>=500 && quality.path("points3D").asLong()<=10000000);
    require(quality.path("meanReprojectionError").isNumber() && Double.isFinite(quality.path("meanReprojectionError").asDouble()) && quality.path("meanReprojectionError").asDouble()>=0 && quality.path("meanReprojectionError").asDouble()<=2.5);
    require(input.path("warnings").isArray() && input.path("warnings").size()<=20);
    for (var warning:input.path("warnings")) require(warning.isTextual() && warning.asText().length()<=500 && warning.asText().chars().noneMatch(c->c<32));
    try (RandomAccessFile stream=new RandomAccessFile(file.toFile(),"r")) {
      require("ply".equals(headerLine(stream))); require("format binary_little_endian 1.0".equals(headerLine(stream)));
      String line=headerLine(stream);
      while (line.startsWith("comment ")) line=headerLine(stream);
      require(line.equals("element vertex "+count));
      for (String field:FIELDS) require(headerLine(stream).equals("property float "+field));
      require(headerLine(stream).equals("end_header"));
      require(bytes-stream.getFilePointer()==count*FIELDS.size()*4);
      byte[] record=new byte[FIELDS.size()*4];
      double[] actualMin={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY}, actualMax={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
      for(long n=0;n<count;n++) {
        stream.readFully(record); ByteBuffer values=ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
        double rotation=0;
        for(int j=0;j<FIELDS.size();j++) {
          float value=values.getFloat(); require(Float.isFinite(value) && Math.abs(value)<=1e9);
          if(j<3) { actualMin[j]=Math.min(actualMin[j],value); actualMax[j]=Math.max(actualMax[j],value); }
          if(j>=10 && j<=12) require(value>=-12 && value<=9);
          if(j>=13) rotation+=value*value;
        }
        require(rotation>0.5 && rotation<1.5);
      }
      for(int i=0;i<3;i++) {
        double tolerance=1e-6*Math.max(1,Math.max(Math.abs(min[i]),Math.abs(max[i])));
        require(Math.abs(actualMin[i]-min[i])<=tolerance && Math.abs(actualMax[i]-max[i])<=tolerance);
      }
    }
    // Only viewer metadata crosses the API. Native diagnostic paths/camera filenames are not exposed.
    return Json.obj("format","ply","byteSize",bytes,"gaussianCount",count,"bounds",input.path("bounds"),"initialCamera",input.path("initialCamera"),
        "quality",quality,"warnings",input.path("warnings"),"coordinateSystem","colmap","units","arbitrary");
  }
  static String headerLine(RandomAccessFile file) throws IOException {
    require(file.getFilePointer()<16384);
    StringBuilder value=new StringBuilder(); int c;
    while((c=file.read())!=-1 && c!='\n') { require(value.length()<300 && c>=32 && c<=126); value.append((char)c); }
    require(c=='\n'); return value.toString();
  }
}
