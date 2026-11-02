// WAV / PCM -> WeChat SILK v3 (local codec only, no network).
//
// JSON in / JSON out, mirroring decode-wechat-voice.mjs:
//   stdin : {"b64":"<wav or pcm16le>","sampleRate":16000,"wav":true}
//   stdout: {"b64":"<silk>","bytes":n,"sampleRate":16000,"durationMs":1234,"frames":n}
//
// WeChat voice messages are 16 kHz mono SILK v3 and the file starts with a 0x02
// byte followed by "#!SILK_V3"; silk-wasm gives us the bare stream, so we add it.
//
// The wasm codec itself lives in the WeChatAgent install; point SILK_WASM_PATH at
// it if it is somewhere else.
import {fileURLToPath, pathToFileURL} from 'node:url';
import path from 'node:path';

const MAX_BYTES = 8 * 1024 * 1024;
const MAGIC = Buffer.from('#!SILK_V3', 'ascii');
const DEFAULT_SILK_WASM = 'D:/WeChatAgent-main/packages/voice-codec/silk-wasm/lib/index.mjs';
const fault = code => new Error(code);

async function codec() {
  const candidates = [process.env.SILK_WASM_PATH, DEFAULT_SILK_WASM].filter(Boolean);
  for (const candidate of candidates) {
    try {
      return await import(pathToFileURL(candidate).href);
    } catch {
      // try the next one
    }
  }
  throw fault('VOICE_ENCODER_MISSING');
}

function wechatFrame(silk) {
  if (silk.length > 10 && silk[0] === 2 && silk.subarray(1, 10).equals(MAGIC)) {
    return silk;
  }
  if (silk.length > 9 && silk.subarray(0, 9).equals(MAGIC)) {
    return Buffer.concat([Buffer.from([2]), silk]);
  }
  return Buffer.concat([Buffer.from([2]), MAGIC, silk]);
}

function wavSampleRate(wav) {
  // "RIFF"…"WAVEfmt " + 16 字节 fmt 块，采样率在偏移 24
  return wav.length >= 28 ? wav.readUInt32LE(24) : 0;
}

export async function encodeVoice(input, sampleRate) {
  if (!input.length || input.length > MAX_BYTES) throw fault('VOICE_ENCODE_LIMIT');
  const {encode} = await codec();
  // WAV 自己带采样率，再传一个会把时长算错（24000Hz 的音频按 16000 编码 = 慢 1.5 倍）
  const isWav = input.length > 12 && input.toString('ascii', 0, 4) === 'RIFF';
  const rate = isWav ? (wavSampleRate(input) || sampleRate || 16000) : (sampleRate || 16000);
  const {data, duration} = isWav ? await encode(input) : await encode(input, rate);
  const silk = wechatFrame(Buffer.from(data));
  let frames = 0;
  for (let offset = 10; offset + 2 <= silk.length;) {
    const size = silk.readUInt16LE(offset);
    if (size === 0) break;
    offset += 2 + size;
    frames += 1;
  }
  return {b64: silk.toString('base64'), bytes: silk.length, sampleRate: rate, durationMs: Math.round(duration), frames};
}

export async function probeEncoder() {
  const {encode} = await codec();
  const {data} = await encode(Buffer.alloc(3200), 16000);
  const silk = wechatFrame(Buffer.from(data));
  return {encoder: true, backend: 'silk-wasm', sampleRate: 16000, bytes: silk.length};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    let result;
    if (process.argv[2] === '--probe') {
      result = await probeEncoder();
    } else {
      const chunks = [];
      let size = 0;
      for await (const chunk of process.stdin) {
        size += chunk.length;
        if (size > 12 * 1024 * 1024) throw fault('VOICE_ENCODE_LIMIT');
        chunks.push(chunk);
      }
      const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (typeof value.b64 !== 'string' || !value.b64.length) throw fault('VOICE_FORMAT_UNSUPPORTED');
      result = await encodeVoice(Buffer.from(value.b64, 'base64'), Number(value.sampleRate) || 16000);
    }
    process.stdout.write(JSON.stringify(result) + '\n');
  } catch (error) {
    const code = /^VOICE_[A-Z_]{3,80}$/.test(error.message ?? '') ? error.message : 'VOICE_ENCODE_FAILED';
    process.stderr.write(JSON.stringify({error: code}) + '\n');
    process.exitCode = 1;
  }
}
