export const MODEL_COMPRESSION_ALGORITHM = "meshopt-buffer-views-v1";
export type ModelCompressionProvenance = {algorithm:typeof MODEL_COMPRESSION_ALGORITHM;encoderVersion:"1.1.1";sourceSha256:string;sourceBytes:number;compressedViews:number;preservedViews:number;decodedBytes:number;attributesExact:true;indicesExact:true};
export function validateCompressionProvenance(value:unknown):ModelCompressionProvenance {
  const row=value as ModelCompressionProvenance;
  if(!row || typeof row!=="object" || Array.isArray(row) || Object.keys(row).some((key)=>!["algorithm","encoderVersion","sourceSha256","sourceBytes","compressedViews","preservedViews","decodedBytes","attributesExact","indicesExact"].includes(key)) || row.algorithm!==MODEL_COMPRESSION_ALGORITHM || row.encoderVersion!=="1.1.1" || !/^[a-f0-9]{64}$/.test(row.sourceSha256) || !Number.isSafeInteger(row.sourceBytes) || row.sourceBytes<1 || row.sourceBytes>25*1024*1024 || !Number.isSafeInteger(row.compressedViews) || row.compressedViews<1 || !Number.isSafeInteger(row.preservedViews) || row.preservedViews<0 || !Number.isSafeInteger(row.decodedBytes) || row.decodedBytes<1 || row.decodedBytes>64*1024*1024 || row.attributesExact!==true || row.indicesExact!==true) throw new Error("模型压缩来源记录无效。");
  return structuredClone(row);
}
