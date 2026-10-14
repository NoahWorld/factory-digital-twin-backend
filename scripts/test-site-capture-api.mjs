// Real no-GPU capture API verification against disposable PostgreSQL/S3/Valkey.
// Build the current JAR with mvn clean verify first. No reconstruction success is simulated.
// Requires preloaded images; the private Docker network has no external connectivity.
// Never reads local credentials or attaches to the existing development/production stack.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomBytes } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const exec = promisify(execFile);
const backend = resolve(process.env.TWIN_BACKEND_DIR || fileURLToPath(new URL('..', import.meta.url)));
const image = process.env.CAPTURE_TEST_IMAGE || 'factory-twin-backend:engineering-test';
const prefix = 'twin-capture-test-' + randomBytes(5).toString('hex');
const secret = () => randomBytes(24).toString('hex');
const secrets = { DATABASE_PASSWORD: secret(), VALKEY_PASSWORD: secret(), S3_ACCESS_KEY: secret(), S3_SECRET_KEY: secret(), BOOTSTRAP_TOKEN: secret() };
const parent = process.env.CAPTURE_TEST_OUTPUT_DIR ? resolve(process.env.CAPTURE_TEST_OUTPUT_DIR) : tmpdir();
await mkdir(parent, { recursive: true });
const output = await mkdtemp(join(parent, 'capture-smoke-'));
const containers = [];
const checks = [];
let networkCreated = false, base, requestContainer, cookie = '';
async function dockerResult(...args) {
  try { return await exec('docker', args, { maxBuffer: 4 * 1024 * 1024, timeout: 120000 }); }
  catch (error) {
    // Docker command arguments contain disposable credentials; keep diagnostics but redact them.
    const redact = value => Object.values(secrets).reduce((text, secret) => text.replaceAll(secret, '[redacted]'), String(value || ''));
    const failure = new Error(redact(error.message) + '\n' + redact(error.stderr));
    failure.code = error.code;
    throw failure;
  }
}
const docker = async (...args) => (await dockerResult(...args)).stdout.trim();
const delay = ms => new Promise(done => setTimeout(done, ms));
const mark = name => { checks.push(name); console.log('PASS ' + name); };
// Internal Docker networks deliberately expose no host ports on some engines.
// Run the HTTP client inside the API container too, so the whole flow stays offline.
async function offlineFetch(url, init = {}) {
  const encoded = new Request(url, { method: init.method || 'GET', headers: init.headers, body: init.body });
  const payload = { url, method: encoded.method, headers: Object.fromEntries(encoded.headers),
    body: encoded.body ? Buffer.from(await encoded.arrayBuffer()).toString('base64') : null,
    redirect: init.redirect || 'follow', timeoutMs: init.timeoutMs || 30000 };
  const program = `let raw = ''; process.stdin.setEncoding('utf8'); process.stdin.on('data', s => raw += s); process.stdin.on('end', async () => {
    const p = JSON.parse(raw);
    try { const r = await fetch(p.url, { method: p.method, headers: p.headers, body: p.body === null ? undefined : Buffer.from(p.body, 'base64'), redirect: p.redirect, signal: AbortSignal.timeout(p.timeoutMs) });
      process.stdout.write(JSON.stringify({ status: r.status, headers: Object.fromEntries(r.headers), body: Buffer.from(await r.arrayBuffer()).toString('base64') }));
    } catch (error) { process.stdout.write(JSON.stringify({ networkError: error.message })); }
  });`;
  const pending = exec('docker', ['exec', '-i', requestContainer, 'node', '-e', program], { maxBuffer: 40 * 1024 * 1024, timeout: payload.timeoutMs + 10000 });
  pending.child.stdin.end(JSON.stringify(payload));
  const result = JSON.parse((await pending).stdout);
  if (result.networkError) throw new TypeError(result.networkError);
  return new Response(result.status === 204 ? null : Buffer.from(result.body, 'base64'), { status: result.status, headers: result.headers });
}
async function start(suffix, args) {
  const name = prefix + '-' + suffix;
  await docker('run', '-d', '--pull', 'never', '--name', name, '--network', prefix, '--network-alias', suffix, ...args);
  containers.push(name);
  return name;
}
async function waitFor(label, predicate, seconds = 90) {
  const until = Date.now() + seconds * 1000;
  while (Date.now() < until) {
    if (await predicate()) return;
    await delay(500);
  }
  throw new Error('Timed out waiting for ' + label);
}
async function request(path, { method = 'GET', body, status = 200, anonymous = false, headers = {} } = {}) {
  const raw = body instanceof Uint8Array;
  const form = body instanceof FormData;
  const response = await offlineFetch(base + '/api/v1' + path, { method, headers: {
    Origin: base, ...(!anonymous && cookie ? { Cookie: cookie } : {}),
    ...(body && !form && !raw ? { 'Content-Type': 'application/json' } : {}), ...headers,
  }, body: body ? (form || raw ? body : JSON.stringify(body)) : undefined, redirect: 'manual', signal: AbortSignal.timeout(30000) });
  const value = response.headers.get('content-type')?.includes('application/json') ? await response.json() : await response.text();
  assert.equal(response.status, status, method + ' ' + path + ': ' + JSON.stringify(value));
  return { value, response };
}
try {
  await readFile(join(backend, 'target/backend-0.1.0.jar'));
  await docker('image', 'inspect', image);
  await docker('network', 'create', '--internal', prefix); networkCreated = true;
  const postgres = await start('postgres', ['--memory', '384m', '-e', 'POSTGRES_DB=factory_twin', '-e', 'POSTGRES_USER=twin', '-e', 'POSTGRES_PASSWORD=' + secrets.DATABASE_PASSWORD, 'postgres:17-alpine']);
  await start('valkey', ['--memory', '192m', 'valkey/valkey:8.1.3-alpine', 'valkey-server', '--requirepass', secrets.VALKEY_PASSWORD]);
  await start('storage', ['--memory', '512m', '-e', 'AWS_ACCESS_KEY_ID=' + secrets.S3_ACCESS_KEY, '-e', 'AWS_SECRET_ACCESS_KEY=' + secrets.S3_SECRET_KEY, 'chrislusf/seaweedfs:4.47', 'mini', '-dir=/data', '-ip=storage', '-ip.bind=0.0.0.0', '-bucket=factory-twin']);
  await waitFor('PostgreSQL', async () => { try { await docker('exec', postgres, 'pg_isready', '-U', 'twin', '-d', 'factory_twin'); return true; } catch (error) { if (error.code === 1 || error.code === 2) return false; throw error; } });
  base = 'http://api:8080';
  const env = { ...secrets, DATABASE_URL: 'jdbc:postgresql://postgres:5432/factory_twin', DATABASE_USER: 'twin', VALKEY_HOST: 'valkey', VALKEY_PORT: '6379', S3_ENDPOINT: 'http://storage:8333', S3_PUBLIC_ENDPOINT: 'http://storage:8333', S3_BUCKET: 'factory-twin', DEFAULT_TENANT: 'capture-test', SECURE_COOKIE: 'false', ALLOWED_ORIGINS: base, JAVA_TOOL_OPTIONS: '-Xmx384m -XX:+ExitOnOutOfMemoryError', DB_POOL_SIZE: '4', TWIN_CAPTURE_PYTHON: '', TWIN_CAPTURE_WORKER: '' };
  const appArgs = ['--read-only', '--tmpfs', '/tmp:size=256m,mode=1777', '--security-opt', 'no-new-privileges:true', '-v', join(backend, 'target/backend-0.1.0.jar') + ':/app/backend.jar:ro', ...Object.entries(env).flatMap(([key,value]) => ['-e', key + '=' + value])];
  requestContainer = await start('api', ['--memory', '768m', ...appArgs, '-e', 'TWIN_MODE=api', image]);
  async function healthy() { try { return (await offlineFetch(base + '/health', { timeoutMs: 2000 })).ok; } catch (error) { if (error instanceof TypeError || error.name === 'TimeoutError') return false; throw error; } }
  await waitFor('API health', healthy);
  await request('/site-captures/capabilities', { anonymous:true, status:401 });
  const bootstrap = await request('/auth/bootstrap', { method:'POST', body:{ identifier:'admin', email:'admin@capture.invalid', displayName:'Capture test', password:secret() }, status:201, headers:{ 'X-Bootstrap-Token':secrets.BOOTSTRAP_TOKEN } });
  cookie=bootstrap.response.headers.get('set-cookie').split(';')[0];
  const adminCookie=cookie;
  assert.equal((await request('/site-captures/capabilities')).value.available,false);
  mark('authenticated capability requires a live GPU worker; unavailable with no GPU');
  const project=(await request('/projects',{method:'POST',body:{name:'Capture test',projectType:'3d'},status:201})).value.project.id;
  const other=(await request('/projects',{method:'POST',body:{name:'Other capture test',projectType:'3d'},status:201})).value.project.id;
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aFOsAAAAASUVORK5CYII=','base64');
  const body={ name:'Room', inputKind:'images', clientRequestId:crypto.randomUUID(), files:Array.from({length:8},(_,i)=>({filename:`photo-${i}.png`,byteSize:png.length,contentType:'image/png'})) };
  const path=`/projects/${project}/site-captures`;
  const capture=(await request(path,{method:'POST',body,status:201})).value.capture;
  assert.equal(capture.status,'uploading'); assert.equal(capture.files.length,8);
  assert.equal((await request(path,{method:'POST',body,status:201})).value.capture.id,capture.id);
  await request(path,{method:'POST',body:{...body,name:'Different'},status:409});
  await request(path,{method:'POST',body:{...body,clientRequestId:crypto.randomUUID(),files:body.files.slice(0,7)},status:400});
  await request(`/projects/${other}/site-captures/${capture.id}`,{status:404});
  for(const action of ['start','retry','cancel']) await request(`/projects/${other}/site-captures/${capture.id}/${action}`,{method:'POST',status:404});
  await request(`/projects/${other}/site-captures/${capture.id}`,{method:'DELETE',status:404});
  await request(`/projects/${other}/site-captures/${capture.id}/sources/0`,{method:'PUT',body:png,headers:{'Content-Type':'image/png'},status:404});
  await request(`/projects/${other}/site-captures/${capture.id}/content`,{status:404});
  await request(path,{anonymous:true,status:401});
  await request(`${path}/${capture.id}/content`,{anonymous:true,status:401});
  await request(`${path}/${capture.id}/start`,{method:'POST',status:409});
  mark('durable draft, idempotency and mismatch conflict, limits and cross-project isolation');
  await request(`${path}/${capture.id}/sources/0`,{method:'PUT',body:Buffer.alloc(png.length),headers:{'Content-Type':'image/png'},status:415});
  await request(`${path}/${capture.id}/sources/0`,{method:'PUT',body:png.subarray(1),headers:{'Content-Type':'image/png'},status:413});
  await request(`${path}/${capture.id}/sources/0`,{method:'PUT',body:png,headers:{'Content-Type':'image/jpeg'},status:415});
  await request(`${path}/${capture.id}/sources/8`,{method:'PUT',body:png,headers:{'Content-Type':'image/png'},status:404});
  for(let index=0;index<3;index++) await request(`${path}/${capture.id}/sources/${index}`,{method:'PUT',body:png,headers:{'Content-Type':'image/png'}});
  await docker('restart',requestContainer); await waitFor('API restart during partial upload',healthy);
  const partial=(await request(`${path}/${capture.id}`)).value.capture;
  assert.equal(partial.completed,3); assert.deepEqual(partial.files.map(file=>file.uploaded),[true,true,true,false,false,false,false,false]);
  for(let index=3;index<8;index++) await request(`${path}/${capture.id}/sources/${index}`,{method:'PUT',body:png,headers:{'Content-Type':'image/png'}});
  mark('partial upload survives API restart and resumes exactly the remaining slots; byte, MIME and slot boundaries reject invalid requests');
  await request(`${path}/${capture.id}/sources/0`,{method:'PUT',body:png,headers:{'Content-Type':'image/png'}});
  const changed=Buffer.from(png); changed[changed.length-1]^=1;
  await request(`${path}/${capture.id}/sources/0`,{method:'PUT',body:changed,headers:{'Content-Type':'image/png'},status:409});
  let saved=(await request(`${path}/${capture.id}`)).value.capture;
  assert.equal(saved.completed,8); assert.ok(saved.files.every(file=>file.uploaded));
  assert.equal((await request(`${path}/${capture.id}/start`,{method:'POST',status:503})).value.error,'site_capture_unavailable');
  await request(`${path}/${capture.id}/content`,{status:409});
  mark('S3 sources and hashes saved; bad signatures and changed duplicate uploads rejected; no false generation');
  const viewerPassword=secret();
  const viewer=(await request('/users',{method:'POST',body:{loginName:'viewer',email:'viewer@capture.invalid',displayName:'Viewer',password:viewerPassword,role:'viewer',modules:['3d']},status:201})).value.user;
  await request(`/projects/${project}/members/${viewer.id}`,{method:'PUT',body:{role:'viewer'}});
  const login=await request('/auth/login',{method:'POST',body:{identifier:'viewer',password:viewerPassword},anonymous:true});
  cookie=login.response.headers.get('set-cookie').split(';')[0];
  const viewerCookie=cookie;
  await request(path);
  await request(`/projects/${other}/site-captures`,{status:404});
  await request(path,{method:'POST',body:{...body,clientRequestId:crypto.randomUUID()},status:403});
  await request(`${path}/${capture.id}/start`,{method:'POST',status:403});
  await request(`${path}/${capture.id}/retry`,{method:'POST',status:403});
  await request(`${path}/${capture.id}/cancel`,{method:'POST',status:403});
  await request(`${path}/${capture.id}`,{method:'DELETE',status:403});
  await request(`${path}/${capture.id}/sources/0`,{method:'PUT',body:png,headers:{'Content-Type':'image/png'},status:403});
  cookie=adminCookie;
  mark('viewer reads are allowed; all writes require project edit permission');
  await request(`/users/${viewer.id}/modules`,{method:'PATCH',body:{modules:['2d']}});
  cookie=viewerCookie;
  await request(path,{status:403});
  await request(`${path}/${capture.id}`,{status:403});
  await request(`${path}/${capture.id}/content`,{status:403});
  cookie=adminCookie;
  await request(`/users/${viewer.id}/modules`,{method:'PATCH',body:{modules:['3d']}});
  cookie=viewerCookie; await request(path); cookie=adminCookie;
  mark('module permission revocation and restoration are enforced immediately on existing sessions');
  await docker('restart',requestContainer); await waitFor('restarted API',healthy);
  saved=(await request(`${path}/${capture.id}`)).value.capture;
  assert.equal(saved.status,'uploading'); assert.ok(saved.files.every(file=>file.uploaded));
  await request(`${path}/${capture.id}/start`,{method:'POST',status:503});
  const gpuWorker=await start('capture-worker',['--memory','768m',...appArgs,'-e','TWIN_MODE=capture-worker',image]);
  await waitFor('unavailable GPU worker heartbeat',async()=> (await docker('exec',postgres,'psql','-U','twin','-d','factory_twin','-Atc',"SELECT count(*) FROM site_capture_workers WHERE available=false")).trim()==='1');
  assert.equal((await request('/site-captures/capabilities')).value.available,false);
  await request(`${path}/${capture.id}/start`,{method:'POST',status:503});
  mark('API restart preserves uploaded draft; dedicated no-GPU worker stays unavailable');
  await request(`${path}/${capture.id}/cancel`,{method:'POST'});
  assert.equal((await request(`${path}/${capture.id}`)).value.capture.status,'canceled');
  assert.equal((await request(`${path}/${capture.id}/cancel`,{method:'POST'})).value.capture.status,'canceled');
  await request(`${path}/${capture.id}/start`,{method:'POST',status:409});
  await request(`${path}/${capture.id}/retry`,{method:'POST',status:409});
  await request(`${path}/${capture.id}/sources/0`,{method:'PUT',body:png,headers:{'Content-Type':'image/png'},status:409});
  await request(`${path}/${capture.id}`,{method:'DELETE'});
  await request(`${path}/${capture.id}`,{status:404});
  await request(`/projects/${project}`,{method:'DELETE'}); await request(`/projects/${other}`,{method:'DELETE'});
  assert.equal((await docker('exec',postgres,'psql','-U','twin','-d','factory_twin','-Atc','SELECT count(*) FROM site_capture_objects')).trim(),'0');
  await request('/auth/logout',{method:'POST',status:204});
  mark('cancel/delete/project cleanup and session revocation succeed');
  await writeFile(join(output,'results.json'),JSON.stringify({status:'passed',scope:'isolated real HTTP/PostgreSQL/S3, no GPU and no reconstruction claimed',image,checks},null,2));
  console.log('OUTPUT '+output);
} catch (error) {
  for (const name of containers) {
    try { const logs = await dockerResult('logs', '--tail', '1000', name); const safeLogs = Object.values(secrets).reduce((value, secret) => value.replaceAll(secret, '[redacted]'), logs.stdout + logs.stderr); await writeFile(join(output, name + '.log'), safeLogs, { mode: 0o600 }); }
    catch (logError) { console.error('Failed to collect diagnostic log:', name, logError.message); }
  }
  await writeFile(join(output, 'results.json'), JSON.stringify({ status: 'failed', image, checks, error: String(error) }, null, 2));
  console.error('FAILED; diagnostics: ' + output);
  throw error;
} finally {
  const failures = [];
  for (const name of containers.reverse()) {
    try { await docker('rm', '-f', '-v', name); } catch (error) { failures.push(name + ': ' + error.message); }
  }
  if (networkCreated) { try { await docker('network', 'rm', prefix); } catch (error) { failures.push(prefix + ': ' + error.message); } }
  if (failures.length) throw new Error('Isolated test cleanup failed: ' + failures.join('\n'));
}
