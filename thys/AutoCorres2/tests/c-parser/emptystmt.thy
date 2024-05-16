(*
 * Copyright 2020, Data61, CSIRO (ABN 41 687 119 230)
 * Copyright (c) 2022 Apple Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 *)

theory emptystmt
imports "AutoCorres2.CTranslation"
begin

install_C_file "emptystmt.c"

context emptystmt_simpl
begin
  term emptystmt.g
  thm f_body_def
  thm f_modifies
end

end
