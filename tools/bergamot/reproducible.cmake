# Included after each project() without changing the pinned upstream sources.
# Marian replaces CMAKE_CXX_FLAGS, so directory compile options must carry these.
add_compile_options(
  "-ffile-prefix-map=${SLOOOP_BERGAMOT_SOURCE}=/usr/src/bergamot"
  "-fdebug-prefix-map=${SLOOOP_BERGAMOT_SOURCE}=/usr/src/bergamot"
  "-ffile-prefix-map=${SLOOOP_BERGAMOT_BUILD}=/usr/build/bergamot"
  "-fdebug-prefix-map=${SLOOOP_BERGAMOT_BUILD}=/usr/build/bergamot"
)

if(CMAKE_CURRENT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR)
  function(slooop_bergamot_link_settings)
    get_target_property(options bergamot-translator LINK_OPTIONS)
    # In Emscripten 3.1.69 this is an obsolete always-on setting, rejected
    # in STRICT mode. Keep STRICT enabled; remove only the obsolete spelling.
    list(REMOVE_ITEM options "-sLLD_REPORT_UNDEFINED" "-sDECLARE_ASM_MODULE_EXPORTS=0")
    # Use the recommended per-export declarations. Dynamic global export
    # injection is unsafe inside the app's enclosing loadBergamot factory.
    set_property(TARGET bergamot-translator PROPERTY LINK_OPTIONS "${options}")
    # STRICT intentionally has an empty incoming API. Declare the exact app
    # factory/runner inputs rather than weakening STRICT or adding a filesystem.
    target_link_options(bergamot-translator PRIVATE
      "-sINCOMING_MODULE_JS_API=['wasmBinary','instantiateWasm','onAbort','onRuntimeInitialized','print','printErr','INITIAL_MEMORY']")
  endfunction()
  cmake_language(DEFER CALL slooop_bergamot_link_settings)
endif()
