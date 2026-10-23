/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

// The generated Emscripten code is inserted inside this factory. Supply the
// upstream WASM GEMM imports through its public exported fallback functions;
// unlike the old upstream sed patch, this does not depend on generated variable
// names or the removed Module.asm property. Android does not provide mozIntGemm.
function loadBergamot(Module) {
  if (!Module || !Module.wasmBinary || Module.instantiateWasm) {
    throw new Error("A bundled Bergamot WASM binary is required");
  }
  Module.instantiateWasm = function (imports, receive) {
    const fallback = {
      int8_prepare_a: "_int8PrepareAFallback",
      int8_prepare_b: "_int8PrepareBFallback",
      int8_prepare_b_from_transposed: "_int8PrepareBFromTransposedFallback",
      int8_prepare_b_from_quantized_transposed: "_int8PrepareBFromQuantizedTransposedFallback",
      int8_prepare_bias: "_int8PrepareBiasFallback",
      int8_multiply_and_add_bias: "_int8MultiplyAndAddBiasFallback",
      int8_select_columns_of_b: "_int8SelectColumnsOfBFallback"
    };
    imports.wasm_gemm = {};
    Object.keys(fallback).forEach(function (name) {
      imports.wasm_gemm[name] = function () {
        return Module[fallback[name]].apply(null, arguments);
      };
    });
    WebAssembly.instantiate(Module.wasmBinary, imports).then(function (result) {
      receive(result.instance, result.module);
    }).catch(function (error) {
      if (Module.onAbort) Module.onAbort(String(error));
    });
    return {};
  };

/* SLOOOP_GENERATED_EMSCRIPTEN */

  return Module;
}
