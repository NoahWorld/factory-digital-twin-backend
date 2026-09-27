export type MeshoptSummary = { codec:"meshopt";views:number;compressedBytes:number;decodedBytes:number };
const MiB = 1024*1024;
const object = (value:unknown):value is Record<string,any> => !!value && typeof value === "object" && !Array.isArray(value);
const integer = (value:unknown,minimum=0):value is number => Number.isSafeInteger(value) && (value as number)>=minimum;
const invalid = (message:string):never => { throw new Error(`Meshopt: ${message}`); };
export function validateMeshoptSummary(value:unknown):MeshoptSummary {
  if (!object(value) || Object.keys(value).some((key) => !["codec","views","compressedBytes","decodedBytes"].includes(key)) || value.codec !== "meshopt" || !integer(value.views,1) || !integer(value.compressedBytes,1) || !integer(value.decodedBytes,1) || value.decodedBytes>64*MiB || value.compressedBytes>128*MiB) return invalid("压缩报告无效。");
  return value as MeshoptSummary;
}
/** Runs before any decoder allocation. Fallback-buffer metadata is bounded too. */
export function inspectMeshoptDocument(input:unknown,binary=false):MeshoptSummary|undefined {
  if (!object(input)) return invalid("模型JSON不是对象。");
  const views = input.bufferViews ?? [],buffers = input.buffers ?? [];
  if (!Array.isArray(views) || !Array.isArray(buffers)) return invalid("缓冲清单无效。");
  if (!views.some((view) => object(view) && object(view.extensions) && view.extensions.EXT_meshopt_compression !== undefined)) return undefined;
  if (!Array.isArray(input.extensionsUsed) || !input.extensionsUsed.includes("EXT_meshopt_compression")) return invalid("压缩扩展未声明。");
  if (input.extensionsRequired !== undefined && !Array.isArray(input.extensionsRequired)) return invalid("必要扩展清单无效。");
  let declared = 0,decodedBytes = 0,compressedBytes = 0,count = 0;
  for (const buffer of buffers) { if (!object(buffer) || !integer(buffer.byteLength,1)) return invalid("缓冲长度无效。");declared+=buffer.byteLength;if (!Number.isSafeInteger(declared) || declared>128*MiB) return invalid("总声明缓冲超过128MiB预算。"); }
  const range = (buffer:unknown,offset:unknown,length:unknown) => {
    if (!integer(buffer) || buffer>=buffers.length || !integer(offset) || !integer(length,1) || !Number.isSafeInteger(offset+length) || offset+length>buffers[buffer].byteLength) return invalid("缓冲视图范围越界。");
  };
  for (const view of views) {
    if (!object(view)) return invalid("缓冲视图无效。");const extension = view.extensions?.EXT_meshopt_compression;
    if (extension === undefined) continue;
    if (!object(extension) || !integer(extension.count,1) || !integer(extension.byteStride,1)) return invalid("count和byteStride必须为正整数。");
    const mode = extension.mode,filter = extension.filter ?? "NONE",stride = extension.byteStride,length = extension.count*stride;
    if (!Number.isSafeInteger(length) || length>64*MiB) return invalid("解码视图超过64MiB预算。");
    if (!["ATTRIBUTES","TRIANGLES","INDICES"].includes(mode) || !["NONE","OCTAHEDRAL","QUATERNION","EXPONENTIAL"].includes(filter)) return invalid("压缩模式或过滤器不支持。");
    if (view.byteLength !== length || view.byteStride !== undefined && view.byteStride !== stride) return invalid("父视图长度或步长与解码布局不符。");
    if (mode === "ATTRIBUTES" && (stride%4 || stride>256) || mode !== "ATTRIBUTES" && (![2,4].includes(stride) || filter !== "NONE") || mode === "TRIANGLES" && extension.count%3 || filter === "OCTAHEDRAL" && ![4,8].includes(stride) || filter === "QUATERNION" && stride !== 8 || filter === "EXPONENTIAL" && stride%4) return invalid("模式、步长或过滤器组合无效。");
    range(view.buffer,view.byteOffset ?? 0,view.byteLength);range(extension.buffer,extension.byteOffset ?? 0,extension.byteLength);
    if (buffers[extension.buffer].extensions?.EXT_meshopt_compression?.fallback === true) return invalid("压缩数据不能引用fallback缓冲。");
    decodedBytes+=length;compressedBytes+=extension.byteLength;count++;
    if (decodedBytes>64*MiB || compressedBytes>128*MiB) return invalid("累计解码或压缩视图超过预算。");
  }
  for (let index=0;index<buffers.length;index++) {
    const fallback = buffers[index].extensions?.EXT_meshopt_compression?.fallback;
    if (fallback !== undefined && typeof fallback !== "boolean") return invalid("fallback标记无效。");
    if (fallback === true && views.some((view) => view.buffer === index && !view.extensions?.EXT_meshopt_compression)) return invalid("fallback缓冲被未压缩视图引用。");
    if (buffers[index].uri === undefined && !(binary && index === 0) && !input.extensionsRequired?.includes("EXT_meshopt_compression")) return invalid("无存储的fallback缓冲要求必要扩展声明。");
  }
  return { codec:"meshopt",views:count,compressedBytes,decodedBytes };
}
export function inspectMeshoptBytes(bytes:Uint8Array):MeshoptSummary|undefined {
  let document:Record<string,any>,binarySize:number|undefined;
  const binary = bytes.byteLength>=4 && new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength).getUint32(0,true) === 0x46546c67;
  if (binary) {
    if (bytes.byteLength<20) return invalid("GLB头不完整。");const view = new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),length = view.getUint32(12,true);
    if (view.getUint32(4,true)!==2 || view.getUint32(8,true)!==bytes.byteLength || view.getUint32(16,true)!==0x4e4f534a || length%4 || 20+length>bytes.byteLength) return invalid("GLB JSON块无效。");
    document = JSON.parse(new TextDecoder().decode(bytes.subarray(20,20+length)));
    for (let offset=20+length;offset<bytes.length;) {
      if (offset+8>bytes.length) return invalid("GLB块头不完整。");const size = view.getUint32(offset,true),type = view.getUint32(offset+4,true);
      if (size%4 || offset+8+size>bytes.length) return invalid("GLB块范围无效。");
      if (type === 0x004e4942) { if (binarySize !== undefined) return invalid("GLB包含重复BIN块。");binarySize = size; }
      offset+=8+size;
    }
  } else document = JSON.parse(new TextDecoder().decode(bytes));
  const summary = inspectMeshoptDocument(document,binary);if (!summary) return undefined;
  const lengths = new Map<number,number>();
  for (const view of document.bufferViews) {
    const extension = view.extensions?.EXT_meshopt_compression;if (!extension) continue;
    if (!lengths.has(extension.buffer)) {
      const buffer = document.buffers[extension.buffer];let size:number;
      if (buffer.uri === undefined && binary && extension.buffer === 0 && binarySize !== undefined) size = binarySize;
      else {
        if (typeof buffer.uri !== "string" || !/^data:[^,]*;base64,/i.test(buffer.uri)) return invalid("压缩缓冲须为内嵌BIN或base64 data URI。");
        try { size = atob(decodeURIComponent(buffer.uri.slice(buffer.uri.indexOf(",")+1))).length; } catch { return invalid("压缩缓冲data URI无效。"); }
      }
      lengths.set(extension.buffer,size);
    }
    if ((extension.byteOffset ?? 0)+extension.byteLength>lengths.get(extension.buffer)!) return invalid("压缩字节超出实际内嵌数据。");
  }
  return summary;
}
