import {compressModelBytes,verifyModelCompression} from './model-compression';
let size=0;const chunks:Buffer[]=[];
try {
  for await(const chunk of process.stdin) {size+=chunk.length;if(size>25*1024*1024) throw Object.assign(new Error(),{compressionCode:'model_compression_input_limit'});chunks.push(chunk);}
  const source=Buffer.concat(chunks);const result=await compressModelBytes(source);
  await verifyModelCompression(source,result.bytes);
  process.stdout.write(result.bytes);
}catch(error){process.stderr.write(JSON.stringify({code:(error as {compressionCode?:string}).compressionCode ?? 'model_compression_failed'}));process.exitCode=1;}
