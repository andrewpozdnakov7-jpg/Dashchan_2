// Executes the actual app runner against the source-built engine and pinned
// Mozilla models. This is a Node/WebAssembly integration test, not Android QA.
"use strict";
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const crypto = require("node:crypto");
const zlib = require("node:zlib");
const assert = require("node:assert/strict");
const {TextEncoder, TextDecoder} = require("node:util");

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

async function prepareModel(spec, model, cache) {
  fs.mkdirSync(cache, {recursive: true});
  const result = new Map();
  for (const file of model.files) {
    const cached = path.join(cache, file.compressedSha256 + ".gz");
    let compressed;
    if (fs.existsSync(cached)) {
      compressed = fs.readFileSync(cached);
    } else {
      const response = await fetch(spec.baseUrl + model.prefix + file.name,
        {signal: AbortSignal.timeout(120000)});
      assert.equal(response.status, 200, "Model download failed");
      compressed = Buffer.from(await response.arrayBuffer());
    }
    assert.equal(compressed.length, file.compressedSize, "Compressed model size");
    assert.equal(sha256(compressed), file.compressedSha256, "Compressed model hash");
    fs.writeFileSync(cached, compressed);
    const bytes = zlib.gunzipSync(compressed, {maxOutputLength: file.size});
    assert.equal(bytes.length, file.size, "Unpacked model size");
    assert.equal(sha256(bytes), file.sha256, "Unpacked model hash");
    result.set("/model/" + file.output, bytes);
  }
  return result;
}

function signal(timeout, label) {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  const timer = setTimeout(() => reject(new Error(label + " timeout")), timeout);
  promise.finally(() => clearTimeout(timer)).catch(() => {});
  return {promise, resolve, reject};
}

async function testDirection(directory, model, files) {
  files.set("/runtime/bergamot-translator.wasm", fs.readFileSync(path.join(directory, "bergamot-translator.wasm")));
  const ready = signal(120000, "Initialization");
  let result;
  const context = vm.createContext({
    console, WebAssembly, TextEncoder, TextDecoder, setTimeout, clearTimeout,
    URL, performance,
    document: {
      currentScript: {src: "https://translation.invalid/runtime/bergamot-translator.js"},
      // The HTML fixture is already canonical and needs no entity decoding.
      createElement: () => ({innerHTML: ""})
    },
    fetch: async (name) => {
      const bytes = files.get(String(name));
      assert.ok(bytes, "Runtime attempted an unexpected fetch: " + name);
      return {ok: true, status: 200,
        arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)};
    },
    SlooopTranslation: {
      onReady: ready.resolve,
      onInitializationError: (error) => ready.reject(new Error(String(error))),
      onTranslationResult: (id, subject, html) => result.resolve({id: String(id), subject, html}),
      onTranslationError: (_id, error) => result.reject(new Error(String(error)))
    }
  });
  vm.runInContext("window = globalThis; self = globalThis; location = {href: 'https://translation.invalid/'};", context);
  vm.runInContext(fs.readFileSync(path.join(directory, "bergamot-translator.js"), "utf8"), context,
    {filename: "bergamot-translator.js"});
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../../assets/translation/runner.js"), "utf8"), context,
    {filename: "runner.js"});
  context.window.SlooopBergamot.initialize(model.source, model.target);
  await ready.promise;
  result = signal(120000, "Translation");
  context.window.SlooopBergamot.translate(1, model.subject, model.html);
  const translated = await result.promise;
  assert.equal(translated.id, "1");
  assert.ok(new RegExp(model.expectedScript).test(translated.subject), "Expected target script");
  assert.notEqual(translated.subject, model.subject, "Subject was not translated");
  assert.notEqual(translated.html, model.html, "HTML was not translated");
  assert.match(translated.html, /<p>[\s\S]+<\/p>/, "HTML paragraph was not preserved");
  // The runner must still handle an empty request without invoking the model.
  result = signal(10000, "Empty request");
  context.window.SlooopBergamot.translate(2, "", "");
  assert.deepEqual(await result.promise, {id: "2", subject: "", html: ""});
  console.log(JSON.stringify({direction: model.source + "-" + model.target, result: translated, passed: true}));
}

async function main() {
  const directory = process.argv[2];
  const cache = process.argv[3];
  assert.ok(directory && cache, "Usage: node test-runtime.cjs RUNTIME_DIR MODEL_CACHE");
  const spec = JSON.parse(fs.readFileSync(path.join(__dirname, "models.json"), "utf8"));
  for (const model of spec.directions) {
    const files = await prepareModel(spec, model, cache);
    await testDirection(directory, model, files);
    if (global.gc) global.gc();
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
