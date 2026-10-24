#!/usr/bin/env node
// Produce immutable application artifacts; private addresses and credentials are deployment input.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const props = Object.fromEntries(fs.readFileSync(path.join(root, 'mod/gradle.properties'), 'utf8').split('\n').filter(s => s.includes('=') && !s.startsWith('#')).map(s => s.trim().split('=')));
const version = props.mod_version;
const out = path.resolve(process.argv[2] ?? path.join(root, 'dist', `agentcraft-${version}`));
if (fs.existsSync(out)) throw new Error(`Release directory already exists: ${out}`);
const run = (bin, args, cwd = root) => execFileSync(bin, args, { cwd, env: process.env, stdio: 'inherit' });
const sha = data => createHash('sha256').update(data).digest('hex');
async function fetchBytes(url) {
  const r = await fetch(url, { signal: AbortSignal.timeout(120_000) });
  if (!r.ok) throw new Error(`${r.status} downloading ${url}`);
  return Buffer.from(await r.arrayBuffer());
}
async function download(url, dest) {
  const data = await fetchBytes(url); fs.writeFileSync(dest, data); return { url, sha256: sha(data) };
}
run('/bin/sh', ['gradlew', 'build', '--console=plain'], path.join(root, 'mod'));
run('npm', ['run', 'build'], path.join(root, 'foreman'));
fs.mkdirSync(out, { recursive: true });
fs.copyFileSync(path.join(root, 'LICENSE'), path.join(out, 'LICENSE'));
for (const name of ['mods', 'foreman', 'codex']) fs.mkdirSync(path.join(out, name));
for (const name of ['dist', 'package.json', 'package-lock.json']) fs.cpSync(path.join(root, 'foreman', name), path.join(out, 'foreman', name), { recursive: true });
fs.cpSync(path.join(root, 'deploy'), path.join(out, 'deploy'), { recursive: true, filter: src => !src.split(path.sep).includes('__pycache__') && !src.endsWith('.pyc') });
fs.cpSync(path.join(root, 'sandbox'), path.join(out, 'sandbox'), { recursive: true, filter: src => !src.includes('node_modules') && (src === path.join(root, 'sandbox') || fs.statSync(src).isFile()) });
const jar = `agentcraft-${version}.jar`;
fs.copyFileSync(path.join(root, 'mod/build/libs', jar), path.join(out, 'mods', jar));
const api = `fabric-api-${props.fabric_api_version}.jar`;
await download(`https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${props.fabric_api_version}/${api}`, path.join(out, 'mods', api));
const installerVersion = '1.1.2';
const fabricInstaller = await download(`https://maven.fabricmc.net/net/fabricmc/fabric-installer/${installerVersion}/fabric-installer-${installerVersion}.jar`, path.join(out, 'fabric-installer.jar'));
const fabricServer = await download(`https://meta.fabricmc.net/v2/versions/loader/${props.minecraft_version}/${props.loader_version}/${installerVersion}/server/jar`, path.join(out, 'fabric-server.jar'));
const nodeVersion = '22.22.3';
const nodeFilename = `node-v${nodeVersion}-linux-x64.tar.xz`;
const nodeBase = `https://nodejs.org/dist/v${nodeVersion}/`;
const sums = (await fetchBytes(nodeBase + 'SHASUMS256.txt')).toString();
const nodeSum = sums.split('\n').find(line => line.trim().endsWith(' ' + nodeFilename))?.trim().split(/\s+/)[0];
if (!nodeSum || !/^[0-9a-f]{64}$/.test(nodeSum)) throw new Error('Node runtime checksum unavailable');
const javaReleases = JSON.parse((await fetchBytes('https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jre&os=linux&vendor=eclipse')).toString());
const java = javaReleases[0];
if (!java?.binary?.package?.checksum) throw new Error('Java runtime checksum unavailable');
fs.writeFileSync(path.join(out, 'codex/package.json'), JSON.stringify({ private: true, dependencies: { '@openai/codex': '0.160.0' } }, null, 2) + '\n');
run('npm', ['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund'], path.join(out, 'codex'));
const macJava = {};
for (const architecture of ['x64', 'aarch64']) {
  const releases = JSON.parse((await fetchBytes(`https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=${architecture}&image_type=jre&os=mac&vendor=eclipse`)).toString());
  const runtime = releases[0];
  if (!runtime?.binary?.package?.checksum) throw new Error('Mac Java runtime checksum unavailable');
  macJava[architecture] = { url: runtime.binary.package.link, sha256: runtime.binary.package.checksum, version: runtime.release_name };
}
const manifest = {
  version, minecraft: props.minecraft_version, fabricLoader: props.loader_version, fabricApi: props.fabric_api_version,
  codex: '0.160.0', node: nodeVersion, macJava,
  clientMods: [jar, api].map(file => ({ file, sha256: sha(fs.readFileSync(path.join(out, 'mods', file))) })),
  fabricInstaller, fabricServer,
  runtimes: { node: { url: nodeBase + nodeFilename, sha256: nodeSum }, java: { url: java.binary.package.link, sha256: java.binary.package.checksum, version: java.release_name } },
};
fs.writeFileSync(path.join(out, 'release.json'), JSON.stringify(manifest, null, 2) + '\n');
const files = fs.readdirSync(out, { recursive: true }).filter(file => fs.statSync(path.join(out, file)).isFile()).sort();
fs.writeFileSync(path.join(out, 'SHA256SUMS'), files.map(file => `${sha(fs.readFileSync(path.join(out, file)))}  ${file}`).join('\n') + '\n');
run('tar', ['-czf', out + '.tar.gz', '-C', path.dirname(out), path.basename(out)]);
console.log(`Release ready: ${out}.tar.gz`);
