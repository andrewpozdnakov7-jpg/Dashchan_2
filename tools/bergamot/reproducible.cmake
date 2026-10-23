# Included after each project() without changing the pinned upstream sources.
# Marian replaces CMAKE_CXX_FLAGS, so directory compile options must carry these.
add_compile_options(
  "-ffile-prefix-map=${SLOOOP_BERGAMOT_SOURCE}=/usr/src/bergamot"
  "-fdebug-prefix-map=${SLOOOP_BERGAMOT_SOURCE}=/usr/src/bergamot"
  "-ffile-prefix-map=${SLOOOP_BERGAMOT_BUILD}=/usr/build/bergamot"
  "-fdebug-prefix-map=${SLOOOP_BERGAMOT_BUILD}=/usr/build/bergamot"
)
