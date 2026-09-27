import {MODEL_COMPRESSION_ALGORITHM,validateCompressionProvenance,type ModelCompressionProvenance} from "./model-compression-types";
import { MeshoptEncoder } from "meshoptimizer/encoder";
import { MeshoptDecoder } from "meshoptimizer/decoder";
import { createHash } from "node:crypto";
import { inspectMeshoptBytes } from "./gltf-meshopt";
export type ModelCompressionReport = ModelCompressionProvenance & {outputSha256:string;outputBytes:number};
const LIMIT = 25*1024*1024,DECODED_LIMIT = 64*1024*1024;
const knownExtensions = new Set(["KHR_lights_punctual","EXT_mesh_gpu_instancing","KHR_mesh_quantization","KHR_texture_transform","KHR_materials_unlit","KHR_materials_clearcoat","KHR_materials_emissive_strength","KHR_materials_ior","KHR_materials_iridescence","KHR_materials_sheen","KHR_materials_specular","KHR_materials_transmission","KHR_materials_volume","KHR_materials_anisotropy","KHR_materials_dispersion","KHR_materials_pbrSpecularGlossiness","KHR_materials_variants","EXT_texture_webp","EXT_texture_avif"]);
const fail = (code:string):never => { throw Object.assign(new Error(code),{compressionCode:code}); };
const digest = (bytes:Uint8Array) => createHash("sha256").update(bytes).digest("hex");
const validInteger = (value:unknown,min=0):value is number => Number.isSafeInteger(value) && (value as number)>=min;
function unpack(bytes:Uint8Array):{json:any;binary?:Uint8Array} {
  if (bytes.length<1 || bytes.length>LIMIT) fail("model_compression_input_limit");
  const view = new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
  if (bytes.length>=20 && view.getUint32(0,true) === 0x46546c67) {
    const length = view.getUint32(12,true);if(view.getUint32(4,true)!==2 || view.getUint32(8,true)!==bytes.length || view.getUint32(16,true)!==0x4e4f534a || length%4 || length+20>bytes.length) fail("model_compression_input_invalid");
    const json=JSON.parse(new TextDecoder().decode(bytes.subarray(20,20+length)));let binary:Uint8Array|undefined;
    for(let offset=20+length;offset<bytes.length;) { if(offset+8>bytes.length) fail("model_compression_input_invalid");const size=view.getUint32(offset,true),type=view.getUint32(offset+4,true);if(size%4 || offset+8+size>bytes.length || type!==0x004e4942 || binary) fail("model_compression_input_invalid");binary=bytes.subarray(offset+8,offset+8+size);offset+=8+size; }
    return {json,binary};
  }
  return {json:JSON.parse(new TextDecoder().decode(bytes))};
}
function pack(json:unknown,binary:Uint8Array):Uint8Array {
  const text=Buffer.from(JSON.stringify(json)),padded=Buffer.concat([text,Buffer.alloc((4-text.length%4)%4,32)]),data=Buffer.concat([binary,Buffer.alloc((4-binary.length%4)%4)]),header=Buffer.alloc(20),chunk=Buffer.alloc(8);header.writeUInt32LE(0x46546c67,0);header.writeUInt32LE(2,4);header.writeUInt32LE(28+padded.length+data.length,8);header.writeUInt32LE(padded.length,12);header.writeUInt32LE(0x4e4f534a,16);chunk.writeUInt32LE(data.length,0);chunk.writeUInt32LE(0x004e4942,4);return Buffer.concat([header,padded,chunk,data]);
}
/** Preserve JSON identities and accessor layout. No quantization, reordering or simplification. */
export async function compressModelBytes(bytes:Uint8Array):Promise<{bytes:Uint8Array;report:ModelCompressionReport}> {
  const input=unpack(bytes),json=input.json;
  if (!json || typeof json!=="object" || json.asset?.version!=="2.0" || !Array.isArray(json.bufferViews) || !Array.isArray(json.buffers)) fail("model_compression_input_invalid");
  const walk=(value:unknown) => {if(!value || typeof value!=="object") return;for(const [key,item] of Object.entries(value)) { if(key==="extras") continue;if(key==="extensions") {if(!item || typeof item!=="object" || Array.isArray(item)) fail("model_compression_extension_unsupported");for(const name of Object.keys(item)) if(!knownExtensions.has(name)) fail("model_compression_extension_unsupported");}walk(item);} };
  if(json.asset.extras?.meshoptCompression!==undefined) fail("model_compression_already_processed");
  walk(json);for(const field of ["extensionsUsed","extensionsRequired"]) if(json[field]!==undefined && (!Array.isArray(json[field]) || json[field].some((name:unknown)=>typeof name!=="string" || !knownExtensions.has(name)))) fail("model_compression_extension_unsupported");
  await Promise.all([MeshoptEncoder.ready,MeshoptDecoder.ready]);
  let total=0;const buffers:Uint8Array[]=json.buffers.map((buffer:any,index:number) => {
    if(!validInteger(buffer.byteLength,1)) fail("model_compression_input_invalid");total+=buffer.byteLength;if(total>DECODED_LIMIT) fail("model_compression_decoded_limit");
    let value:Uint8Array;
    if(buffer.uri===undefined && index===0 && input.binary) value=input.binary;
    else {if(typeof buffer.uri!=="string" || !/^data:[^,]*;base64,/i.test(buffer.uri)) fail("model_compression_external_resource");value=Uint8Array.from(atob(decodeURIComponent(buffer.uri.slice(buffer.uri.indexOf(",")+1))),(char)=>char.charCodeAt(0));}
    if(value.byteLength<buffer.byteLength) fail("model_compression_input_invalid");return value;
  });
  for(const image of json.images ?? []) if(image.uri!==undefined && (typeof image.uri!=="string" || !image.uri.startsWith("data:"))) fail("model_compression_external_resource");
  const chunks:Uint8Array[]=[];let outputOffset=0,compressedViews=0,preservedViews=0,decodedBytes=0;
  const append=(data:Uint8Array) => {const offset=outputOffset,padding=(4-data.length%4)%4;if(!Number.isSafeInteger(outputOffset+data.length+padding) || outputOffset+data.length+padding>LIMIT) fail("model_compression_output_limit");chunks.push(data);if(padding) chunks.push(new Uint8Array(padding));outputOffset+=data.length+padding;return offset;};
  const views=json.bufferViews.map((view:any,index:number) => {
    if(!view || !validInteger(view.buffer) || view.buffer>=buffers.length || !validInteger(view.byteOffset ?? 0) || !validInteger(view.byteLength,1) || (view.byteOffset ?? 0)+view.byteLength>json.buffers[view.buffer].byteLength) fail("model_compression_input_invalid");
    const original=buffers[view.buffer].subarray(view.byteOffset ?? 0,(view.byteOffset ?? 0)+view.byteLength),accessors=(json.accessors ?? []).filter((accessor:any)=>accessor.bufferView===index);
    // INDICES preserves exact index order; U8 or mixed layouts remain valid uncompressed views.
    const indexOnly=accessors.length>0 && accessors.every((accessor:any)=>(json.meshes ?? []).some((mesh:any)=>mesh.primitives?.some((primitive:any)=>primitive.indices===(json.accessors ?? []).indexOf(accessor))));
    const mode=indexOnly ? "INDICES":"ATTRIBUTES",stride=indexOnly ? ({5123:2,5125:4} as Record<number,number>)[accessors[0].componentType]:view.byteStride ?? 4;
    const suitable=indexOnly ? [2,4].includes(stride) && accessors.every((accessor:any)=>accessor.componentType===accessors[0].componentType):validInteger(stride,4) && stride<=256 && stride%4===0;
    if(suitable && original.length%stride===0) {
      const count=original.length/stride,encoded=MeshoptEncoder.encodeGltfBuffer(original,count,stride,mode),decoded=new Uint8Array(original.length);MeshoptDecoder.decodeGltfBuffer(decoded,count,stride,encoded,mode);
      if(Buffer.compare(Buffer.from(decoded),Buffer.from(original))!==0) fail("model_compression_verification_failed");
      const offset=append(encoded);compressedViews++;decodedBytes+=original.length;if(decodedBytes>DECODED_LIMIT) fail("model_compression_decoded_limit");
      return {...view,buffer:view.buffer+1,extensions:{...view.extensions,EXT_meshopt_compression:{buffer:0,byteOffset:offset,byteLength:encoded.length,byteStride:stride,count,mode}}};
    }
    preservedViews++;return {...view,buffer:0,byteOffset:append(original)};
  });
  if(!compressedViews) fail("model_compression_no_supported_views");
  const provenance:ModelCompressionProvenance={algorithm:MODEL_COMPRESSION_ALGORITHM,encoderVersion:"1.1.1",sourceSha256:digest(bytes),sourceBytes:bytes.length,compressedViews,preservedViews,decodedBytes,attributesExact:true,indicesExact:true};
  const outputJson={...json,asset:{...json.asset,extras:{...json.asset.extras,meshoptCompression:provenance}},buffers:[{byteLength:outputOffset},...json.buffers.map(({uri,...buffer}:any)=>({...buffer,extensions:{...buffer.extensions,EXT_meshopt_compression:{fallback:true}}}))],bufferViews:views,extensionsUsed:[...new Set([...(json.extensionsUsed ?? []),"EXT_meshopt_compression"])],extensionsRequired:[...new Set([...(json.extensionsRequired ?? []),"EXT_meshopt_compression"])]};
  if(Buffer.byteLength(JSON.stringify(outputJson))+32+outputOffset>LIMIT) fail("model_compression_output_limit");
  const output=pack(outputJson,Buffer.concat(chunks));if(output.length>LIMIT) fail("model_compression_output_limit");inspectMeshoptBytes(output);
  return {bytes:output,report:{algorithm:MODEL_COMPRESSION_ALGORITHM,encoderVersion:"1.1.1",sourceSha256:digest(bytes),outputSha256:digest(output),sourceBytes:bytes.length,outputBytes:output.length,compressedViews,preservedViews,decodedBytes,attributesExact:true,indicesExact:true}};
}

/** Independently prove a packaged provenance claim against the included immutable source. */
export async function verifyModelCompression(source:Uint8Array,output:Uint8Array):Promise<void> {
  const summary=inspectMeshoptBytes(output);const before=unpack(source),after=unpack(output),provenance=validateCompressionProvenance(after.json.asset?.extras?.meshoptCompression);
  if(!summary || provenance.compressedViews!==summary.views || provenance.decodedBytes!==summary.decodedBytes || provenance.preservedViews!==after.json.bufferViews.length-summary.views) fail("model_compression_verification_failed");
  if(!provenance || provenance.sourceSha256!==digest(source) || provenance.sourceBytes!==source.length) fail('model_compression_verification_failed');
  const canonical=(value:any):string=>Array.isArray(value)?`[${value.map(canonical).join(',')}]`:value && typeof value==='object'?'{'+Object.keys(value).sort().map((key)=>JSON.stringify(key)+':'+canonical(value[key])).join(',')+'}':JSON.stringify(value);
  const metadata=(json:any)=>{const value=structuredClone(json);delete value.buffers;delete value.bufferViews;value.extensionsUsed=(value.extensionsUsed ?? []).filter((name:string)=>name!=='EXT_meshopt_compression');value.extensionsRequired=(value.extensionsRequired ?? []).filter((name:string)=>name!=='EXT_meshopt_compression');if(!value.extensionsUsed.length)delete value.extensionsUsed;if(!value.extensionsRequired.length)delete value.extensionsRequired;if(value.asset.extras){delete value.asset.extras.meshoptCompression;if(!Object.keys(value.asset.extras).length)delete value.asset.extras;}return value;};
  if(canonical(metadata(before.json))!==canonical(metadata(after.json)) || before.json.bufferViews.length!==after.json.bufferViews.length) fail('model_compression_verification_failed');
  await MeshoptDecoder.ready;let decodedTotal=0;
  const getBuffer=(document:{json:any;binary?:Uint8Array},index:number):Uint8Array=>{const buffer=document.json.buffers[index];if(!buffer)fail('model_compression_verification_failed');if(index===0 && buffer.uri===undefined && document.binary)return document.binary;if(typeof buffer.uri!=='string' || !/^data:[^,]*;base64,/i.test(buffer.uri))fail('model_compression_verification_failed');return Uint8Array.from(atob(decodeURIComponent(buffer.uri.slice(buffer.uri.indexOf(',')+1))),(char)=>char.charCodeAt(0));};
  const originalBuffers=new Map<number,Uint8Array>(),outputBuffers=new Map<number,Uint8Array>();
  for(let i=0;i<before.json.bufferViews.length;i++) {
    const a=before.json.bufferViews[i],b=after.json.bufferViews[i],clean=(view:any)=>{const value=structuredClone(view);delete value.buffer;delete value.byteOffset;if(value.extensions){delete value.extensions.EXT_meshopt_compression;if(!Object.keys(value.extensions).length)delete value.extensions;}return value;};
    if(canonical(clean(a))!==canonical(clean(b)))fail('model_compression_verification_failed');
    if(!originalBuffers.has(a.buffer))originalBuffers.set(a.buffer,getBuffer(before,a.buffer));const original=originalBuffers.get(a.buffer)!.subarray(a.byteOffset ?? 0,(a.byteOffset ?? 0)+a.byteLength);if(original.length!==a.byteLength)fail('model_compression_verification_failed');let decoded:Uint8Array;
    const extension=b.extensions?.EXT_meshopt_compression;
    if(extension){decodedTotal+=b.byteLength;if(decodedTotal>DECODED_LIMIT)fail('model_compression_decoded_limit');decoded=new Uint8Array(b.byteLength);if(!outputBuffers.has(extension.buffer))outputBuffers.set(extension.buffer,getBuffer(after,extension.buffer));const encoded=outputBuffers.get(extension.buffer)!.subarray(extension.byteOffset ?? 0,(extension.byteOffset ?? 0)+extension.byteLength);MeshoptDecoder.decodeGltfBuffer(decoded,extension.count,extension.byteStride,encoded,extension.mode,extension.filter);}
    else{if(!outputBuffers.has(b.buffer))outputBuffers.set(b.buffer,getBuffer(after,b.buffer));decoded=outputBuffers.get(b.buffer)!.subarray(b.byteOffset ?? 0,(b.byteOffset ?? 0)+b.byteLength);}
    if(Buffer.compare(Buffer.from(original),Buffer.from(decoded))!==0)fail('model_compression_verification_failed');
  }
}
