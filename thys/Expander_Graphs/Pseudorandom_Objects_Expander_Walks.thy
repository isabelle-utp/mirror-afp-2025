section \<open>Expander Walks as Pseudorandom Objects\<close>

theory Pseudorandom_Objects_Expander_Walks
  imports
    Universal_Hash_Families.Pseudorandom_Objects
    Expander_Graphs.Expander_Graphs_Strongly_Explicit
begin

hide_const (open) Quantum.T

definition expander_pro ::
  "nat \<Rightarrow> real \<Rightarrow> ('a,'b) pseudorandom_object_scheme \<Rightarrow> (nat \<Rightarrow> 'a) pseudorandom_object"
  where "expander_pro l \<Lambda> S = (
    let e = see_standard (pro_size S) \<Lambda> in
      \<lparr> pro_last = see_size e * see_degree e^(l-1) - 1,
        pro_select = (\<lambda>i j. pro_select S (see_sample_walk e (l-1) i ! j mod pro_size S)) \<rparr>
    )"

context
  fixes l :: nat
  fixes \<Lambda> :: real
  fixes S :: "('a,'b) pseudorandom_object_scheme"
  assumes l_gt_0: "l > 0"
  assumes \<Lambda>_gt_0: "\<Lambda> > 0"
begin

private definition e where "e = see_standard (pro_size S) \<Lambda>"

private lemma expander_pro_alt: "expander_pro l \<Lambda> S = \<lparr> pro_last = see_size e * see_degree e^(l-1) - 1,
        pro_select = (\<lambda>i j. pro_select S (see_sample_walk e (l-1) i ! j mod pro_size S)) \<rparr>"
  unfolding expander_pro_def e_def[symmetric] by (auto simp:Let_def)

private lemmas see_standard = see_standard [OF pro_size_gt_0[where S="S"] \<Lambda>_gt_0]

interpretation E: regular_graph "graph_of e"
  using see_standard(1) unfolding is_expander_def e_def by auto

private lemma e_deg_gt_0: "see_degree e > 0"
  unfolding e_def see_standard by simp

private lemma e_size_gt_0: "see_size e > 0"
  unfolding e_def using see_standard pro_size_gt_0 by simp

private lemma expander_sample_size: "pro_size (expander_pro l \<Lambda> S) =  see_size e * see_degree e^(l-1)"
  using e_deg_gt_0 e_size_gt_0 unfolding expander_pro_alt pro_size_def by simp

private lemma sample_pro_expander_walks:
  defines "R \<equiv> map_pmf (\<lambda>xs i. pro_select S (xs ! i mod pro_size S))
    (pmf_of_multiset (walks (graph_of e) l))"
  shows "sample_pro (expander_pro l \<Lambda> S) = R"
proof -
  let ?S = "{..<see_size e * see_degree e ^ (l-1)}"
  let ?T = "(map_pmf (see_sample_walk e (l-1)) (pmf_of_set ?S))"

  have "0 \<in> ?S"
    using e_size_gt_0 e_deg_gt_0  by auto
  hence "?S \<noteq> {}"
    by blast
  hence "?T = pmf_of_multiset {#see_sample_walk e (l-1) i. i \<in># mset_set ?S#}"
    by (subst map_pmf_of_set) simp_all
  also have "... = pmf_of_multiset (walks' (graph_of e) (l-1))"
    by (subst see_sample_walk) auto
  also have "... = pmf_of_multiset (walks (graph_of e) l)"
    unfolding walks_def using l_gt_0 by (cases l, simp_all)
  finally have 0:"?T = pmf_of_multiset (walks (graph_of e) l)"
    by simp

  have "sample_pro (expander_pro l \<Lambda> S) = map_pmf (\<lambda>xs j. pro_select S (xs ! j mod pro_size S)) ?T"
    unfolding expander_sample_size sample_pro_alt unfolding map_pmf_comp expander_pro_alt by simp
  also have "... = R" unfolding 0 R_def by simp
  finally show ?thesis by simp
qed

lemma expander_uniform_property:
  assumes "i < l"
  shows "map_pmf (\<lambda>w. w i) (sample_pro (expander_pro l \<Lambda> S)) = sample_pro S" (is "?L = ?R")
proof -
  have "?L = map_pmf (\<lambda>x. pro_select S (x mod pro_size S)) (map_pmf (\<lambda>xs. (xs ! i)) (pmf_of_multiset (walks (graph_of e) l)))"
    unfolding sample_pro_expander_walks by (simp add: map_pmf_comp)
  also have "... = map_pmf (\<lambda>x. pro_select S (x mod pro_size S)) (pmf_of_set (verts (graph_of e)))"
    unfolding E.uniform_property[OF assms] by simp
  also have "... = ?R"
    using pro_size_gt_0 unfolding sample_pro_alt
    by (intro map_pmf_cong) (simp_all add:e_def graph_of_def see_standard select_def)
  finally show ?thesis
    by simp
qed

lemma expander_kl_chernoff_bound:
  assumes "measure (sample_pro S) {w. T w} \<le> \<mu>"
  assumes "\<gamma> \<le> 1" "\<mu> + \<Lambda> * (1-\<mu>) \<le> \<gamma>" "\<mu> \<le> 1"
  shows "measure (sample_pro (expander_pro l \<Lambda> S)) {w. real (card {i \<in> {..<l}. T (w i)}) \<ge> \<gamma>*l}
    \<le> exp (- real l * KL_div \<gamma> (\<mu> + \<Lambda>*(1-\<mu>)))" (is "?L \<le> ?R")
proof (cases "measure (sample_pro S) {w. T w} > 0")
  case True
  let ?w = "pmf_of_multiset (walks (graph_of e) l)"
  define V where "V = {v\<in> verts (graph_of e). T (pro_select S v)} "
  define \<nu> where "\<nu> = measure (sample_pro S) {w. T w}"

  have \<nu>_gt_0: "\<nu> > 0" unfolding \<nu>_def using True by simp
  have \<nu>_le_1: "\<nu> \<le> 1" unfolding \<nu>_def by simp
  have \<nu>_le_\<mu>: "\<nu> \<le> \<mu>" unfolding \<nu>_def using assms(1) by simp

  have 0: "card {i \<in> {..<l}. T (pro_select S (w ! i mod pro_size S))} = card {i \<in> {..<l}. w ! i \<in> V}"
    if "w  \<in> set_pmf (pmf_of_multiset (walks (graph_of e) l))" for w
  proof -
    have a0: "w \<in># walks (graph_of e) l" using that E.walks_nonempty by simp
    have a1:"w ! i \<in> verts (graph_of e)" if "i < l" for i
      using that E.set_walks_3[OF a0] by auto
    moreover have "w ! i mod pro_size S = w ! i" if "i < l" for i
      using a1[OF that] see_standard(2) e_def by (simp add:graph_of_def)
    ultimately show ?thesis
      unfolding V_def
      by (intro arg_cong[where f="card"] restr_Collect_cong) auto
  qed

  have 1:"E.\<Lambda>\<^sub>a \<le> \<Lambda>"
    using see_standard(1) unfolding is_expander_def e_def by simp

  have 2: "V \<subseteq> verts (graph_of e)"
    unfolding V_def by simp

  have "\<nu> = measure (pmf_of_set {..<pro_size S}) ({v. T (pro_select S v)})"
    unfolding \<nu>_def sample_pro_alt by simp
  also have "... = real (card ({v\<in>{..<pro_size S}. T (pro_select S v)})) / real (pro_size S)"
    using pro_size_gt_0 by (subst measure_pmf_of_set) (auto simp add:Int_def)
  also have "... = real (card V) / card (verts (graph_of e))"
    unfolding V_def graph_of_def e_def using see_standard by (simp add:Int_commute)
  finally have \<nu>_eq: "\<nu> = real (card V) / card (verts (graph_of e))"
    by simp

  have 3: "0 < \<nu> + E.\<Lambda>\<^sub>a * (1 - \<nu>)"
    using \<nu>_le_1 by (intro add_pos_nonneg \<nu>_gt_0 mult_nonneg_nonneg E.\<Lambda>_ge_0) auto

  have "\<nu> + E.\<Lambda>\<^sub>a * (1 - \<nu>) = \<nu> * (1 - E.\<Lambda>\<^sub>a) + E.\<Lambda>\<^sub>a" by (simp add:algebra_simps)
  also have "... \<le> \<mu> * (1- E.\<Lambda>\<^sub>a) + E.\<Lambda>\<^sub>a" using E.\<Lambda>_le_1
    by (intro add_mono mult_right_mono \<nu>_le_\<mu>) auto
  also have "... = \<mu> + E.\<Lambda>\<^sub>a * (1 - \<mu>)" by (simp add:algebra_simps)
  also have "... \<le> \<mu> + \<Lambda> * (1 - \<mu>)" using assms(4) by (intro add_mono mult_right_mono 1) auto
  finally have 4: "\<nu> + E.\<Lambda>\<^sub>a * (1 - \<nu>) \<le> \<mu> + \<Lambda> * (1 - \<mu>)" by simp

  have 5: "\<nu> + E.\<Lambda>\<^sub>a*(1-\<nu>) \<le> \<gamma>" using 4 assms(3) by simp

  have "?L = measure ?w {y. \<gamma> * real l \<le> real (card {i \<in> {..<l}. T (pro_select S (y ! i mod pro_size S))})}"
    unfolding sample_pro_expander_walks by simp
  also have "... = measure ?w {y. \<gamma> * real l \<le> real (card {i \<in> {..<l}. y ! i \<in> V})}"
    using 0 by (intro measure_pmf_cong) (simp)
  also have "... \<le> exp (- real l * KL_div \<gamma> (\<nu> + E.\<Lambda>\<^sub>a*(1-\<nu>)) )"
    using assms(2) 3 5 unfolding \<nu>_eq by (intro E.kl_chernoff_property l_gt_0 2) auto
  also have "... \<le> exp (- real l * KL_div \<gamma> (\<mu> + \<Lambda>*(1-\<mu>)))"
    using l_gt_0 by (intro iffD2[OF exp_le_cancel_iff] iffD2[OF mult_le_cancel_left_neg]
      KL_div_mono_right[OF disjI2] conjI 3 4 assms(2,3)) auto
  finally show ?thesis by simp
next
  case False
  hence 0:"measure (sample_pro S) {w. T w} = 0" using zero_less_measure_iff by blast
  hence 1:"T w = False" if "w \<in> pro_set S" for w using that measure_pmf_posI by force

  have "\<mu> + \<Lambda> * (1-\<mu>) > 0"
  proof (cases "\<mu> = 0")
    case True then show ?thesis using \<Lambda>_gt_0 by auto
  next
    case False
    then show ?thesis using assms(1,4) 0 \<Lambda>_gt_0
      by (intro add_pos_nonneg mult_nonneg_nonneg) simp_all
  qed
  hence "\<gamma> > 0" using assms(3) by auto
  hence 2:"\<gamma>*real l > 0" using l_gt_0 by simp

  let ?w = "pmf_of_multiset (walks (graph_of e) l)"

  have "?L = measure ?w {y. \<gamma>*real l\<le> card {i \<in> {..<l}. T (pro_select S (y ! i mod pro_size S))}}"
    unfolding sample_pro_expander_walks by simp
  also have "... = 0" using pro_select_in_set 2 by (subst 1) auto
  also have "... \<le> ?R" by simp
  finally show ?thesis by simp
qed

lemma expander_pro_size:
  "pro_size (expander_pro l \<Lambda> S) = pro_size S * (16 ^ ((l-1) * nat \<lceil>ln \<Lambda> / ln (19 / 20)\<rceil>))"
  (is "?L = ?R")
proof -
  have "?L = see_size e * see_degree e ^ (l - 1)"
    unfolding expander_sample_size by simp
  also have "... = pro_size S * (16 ^ nat \<lceil>ln \<Lambda> / ln (19 / 20)\<rceil>) ^ (l - 1)"
    using see_standard unfolding e_def by simp
  also have "... = pro_size S * (16 ^ ((l-1) * nat \<lceil>ln \<Lambda> / ln (19 / 20)\<rceil>))"
    unfolding power_mult[symmetric] by (simp add:ac_simps)
  finally show ?thesis
    by simp
qed

lemma expander_pro_range: "pro_select (expander_pro l \<Lambda> S) i j \<in> pro_set S"
  unfolding expander_pro_alt by (simp add:pro_select_in_set)

end

bundle expander_pseudorandom_object_notation
begin
notation expander_pro ("\<E>")
end

bundle no_expander_pseudorandom_object_notation
begin
no_notation expander_pro ("\<E>")
end

unbundle expander_pseudorandom_object_notation

end