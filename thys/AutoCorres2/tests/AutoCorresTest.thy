(*
 * Copyright (c) 2022 Apple Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 *)

theory AutoCorresTest imports
  "parse-tests/basic"
  "parse-tests/basic_recursion"
  "parse-tests/big_bit_ops"
  "parse-tests/bodyless_function"
  "proof-tests/explosion"
  "parse-tests/heap_infer"
  "parse-tests/heap_lift_array"
  "parse-tests/l2_opt_invariant"
  "parse-tests/loop_test"
  "parse-tests/loop_test2"
  "parse-tests/mutual_recursion"
  "parse-tests/mutual_recursion2"
  "parse-tests/nested_break_cont"
  "parse-tests/read_global_array"
  "parse-tests/signed_ptr_ptr"
  "parse-tests/simple1"
  "parse-tests/single_auxupd"
  "parse-tests/struct1"
  "parse-tests/struct_init"
  "parse-tests/unliftable_call"
  "parse-tests/voidptrptr"
  "parse-tests/while_loop_no_vars"
  "parse-tests/word_abs_exn"
  "parse-tests/write_to_global_array"
  "proof-tests/anonymous_nested_struct"
  "proof-tests/Asm_Labels"
  "proof-tests/CustomWordAbs"
  "proof-tests/SignedWordAbsHeap"
  "proof-tests/Test_Spec_Translation"
  "proof-tests/WhileLoopVarsPreserved"
  "proof-tests/WordAbsFnCall"
  "proof-tests/array_indirect_update"
  "proof-tests/badnames"
  "proof-tests/buffer"
  "proof-tests/flexible_array_member"
  "proof-tests/function_decay"
  "proof-tests/function_pointer_array_decay"
  "proof-tests/globals"
  "proof-tests/global_array_update"
  "proof-tests/Global_Structs"
  "proof-tests/Guard_Simp"
  "proof-tests/heap_lift_force_prevent"
  "proof-tests/In_Out_Parameters_Slow"
  "proof-tests/int128"
  "proof-tests/nested_array"
  "proof-tests/Nested_Field_Update"
  "proof-tests/nested_struct"
  "proof-tests/open_nested"
  "proof-tests/open_nested_array"
  "proof-tests/option_exploration"
  "proof-tests/partial_open_nested"
  "proof-tests/pointers_to_locals_skip_hl"
  "proof-tests/pointers_to_locals_skip_hl_wa"
  "proof-tests/prototyped_functions"
  "proof-tests/side_effect_assignment"
  "proof-tests/skip_heap_abs"
  "proof-tests/skip_in_out_parameters"
  "proof-tests/struct"
  "proof-tests/struct2"
  "proof-tests/struct3"
  "proof-tests/ternary_conditional_operator"
  "proof-tests/try"
  "proof-tests/word_abs_cases"
  "proof-tests/word_abs_options"
  "proof-tests/struct_consecutive_init"
  "proof-tests/profile_conversion"
  "proof-tests/mmio"
  "proof-tests/mmio_assume"
  "proof-tests/EvaluationOrder"
  "proof-tests/unfold_bind_options"
  "proof-tests/bit_shuffle"
  "proof-tests/fnptr_enum0"
  "proof-tests/fnptr_io"
  "proof-tests/fnptr_no_heap_abs"
  "proof-tests/fnptr_skip_heap_abs"
  "proof-tests/fnptr_large_array"
  "proof-tests/underscore_funs"
  "examples/AC_Rename"
  "examples/Alloc_Ex"
  "examples/BinarySearch"
  "examples/CList"
  "examples/CompoundCTypesEx"
  "examples/CompoundCTypesExNew"
  "examples/ConditionGuard"
  "examples/Exception_Rewriting"
  "examples/FactorialTest"
  "examples/FibProof"
  "examples/final_autocorres"
  "examples/FunctionInfoDemo"
  "examples/goto"
  "examples/HeapWrap"
  "examples/Incremental"
  "examples/IsPrime_Ex"
  "examples/Kmalloc"
  "examples/ListRev"
  "examples/Match_Cterm_Ex"
  "examples/Memcpy"
  "examples/Memset"
  "examples/MultByAdd"
  "examples/Plus_Ex"
  "examples/Quicksort_Ex"
  "examples/SchorrWaite_Ex"
  "examples/Simple"
  "examples/Str2Long"
  "examples/Suzuki"
  "examples/Swap_Ex"
  "examples/TraceDemo"
  "examples/WordAbs"
  "examples/type_strengthen_tricks"
  "examples/Mutual_Fixed_Points"

begin


end
