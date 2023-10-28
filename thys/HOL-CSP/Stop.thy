(*<*)
\<comment>\<open> ******************************************************************** 
 * Project         : HOL-CSP - A Shallow Embedding of CSP in  Isabelle/HOL
 * Version         : 2.0
 *
 * Author          : Burkhart Wolff.
 *                   (Based on HOL-CSP 1.0 by Haykal Tej and Burkhart Wolff)
 *
 * This file       : A Combined CSP Theory
 *
 * Copyright (c) 2009 Université Paris-Sud, France
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *
 *     * Neither the name of the copyright holders nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************\<close>
(*>*)

section\<open> The STOP Process \<close>

theory     Stop
imports    Process 
begin 

lift_definition STOP :: \<open>'\<alpha> process\<close>
  is \<open>({(s, X). s = []}, {})\<close>
  unfolding is_process_def FAILURES_def DIVERGENCES_def by simp


lemma F_STOP : "\<F> STOP = {(s,X). s = []}"
  by (simp add: FAILURES_def Failures.rep_eq STOP.rep_eq)

lemma D_STOP: "\<D> STOP = {}"
  by (simp add: DIVERGENCES_def Divergences.rep_eq STOP.rep_eq)

lemma T_STOP: "\<T> STOP = {[]}"
  by (simp add: Traces.rep_eq TRACES_def Failures.rep_eq[symmetric] F_STOP)


lemma STOP_iff_T: \<open>P = STOP \<longleftrightarrow> \<T> P = {[]}\<close>
  apply (intro iffI, simp add: T_STOP)
  apply (subst Process_eq_spec, safe, simp_all add: F_STOP D_STOP)
  by (use F_T in force, use is_processT5_S7 in fastforce)
     (metis D_T append_Nil front_tickFree_single is_processT7_S
            list.distinct(1) singletonD tickFree_Nil)





end

