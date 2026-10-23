"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

async function main() {
  const template = fs.readFileSync(path.join(__dirname, "factory.js"), "utf8");
  let imports;
  const context = vm.createContext({WebAssembly: {
    instantiate: async (_bytes, supplied) => {
      imports = supplied;
      return {instance: {fixture: true}, module: {}};
    }
  }});
  const generated = `
    Module._int8PrepareAFallback = function(value) { return value + 7; };
    Module.instantiateWasm({env: {memory: 'fixture'}}, function(instance) {
      Module.received = instance.fixture;
    });`;
  vm.runInContext(template.replace("/* SLOOOP_GENERATED_EMSCRIPTEN */", generated), context);
  const options = {wasmBinary: new Uint8Array([0])};
  const module = context.loadBergamot(options);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(module, options);
  assert.equal(module.received, true);
  assert.equal(imports.env.memory, "fixture");
  assert.equal(Object.keys(imports.wasm_gemm).length, 7);
  assert.equal(imports.wasm_gemm.int8_prepare_a(5), 12);
  assert.throws(() => context.loadBergamot({}), /bundled/);
  assert.throws(() => context.loadBergamot({wasmBinary: [0], instantiateWasm() {}}), /bundled/);

  let aborted;
  context.WebAssembly.instantiate = async () => { throw new Error("invalid fixture"); };
  context.loadBergamot({wasmBinary: new Uint8Array([0]), onAbort: error => { aborted = error; }});
  await new Promise(resolve => setImmediate(resolve));
  assert.match(aborted, /invalid fixture/);
  console.log("Factory imports, module-local exports, invalid input and abort tests passed");
}

main().catch(error => { console.error(error); process.exitCode = 1; });
