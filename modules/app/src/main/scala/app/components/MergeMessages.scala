package app.components

import app.core.NumberFormat
import app.state.{ParentSelection, RiskLeafField, RiskLeafFormState}
import com.risquanter.register.domain.data.{Mitigation, RiskLeaf, RiskNode, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeName, ScenarioName, TreeId}
import com.risquanter.register.domain.errors.FieldName
import com.risquanter.register.http.responses.{MergeConflictEntry, TreeLabel, Versions}

/** Every sentence the merge modal shows.
  *
  * The server sends a case and the values that belong to it; the sentence is
  * written here rather than at the rendering site, so a sentence and the values
  * it interpolates cannot drift apart (ADR-037).
  */
object MergeMessages:

  val Checking: String =
    "Checking for conflicts…"

  val Clean: String =
    "No conflicts — merging applies this scenario's changes to main."

  val ScenarioMissing: String =
    "This scenario no longer exists."

  val DuplicateNamesHeading: String =
    "Merging would give two risks in one tree the same name:"

  val DuplicateNamesHint: String =
    "Rename one of them on either branch, then re-check."

  val ConflictsHint: String =
    "Change either branch so these fields agree, then re-check."

  /** How many tree, node or mitigation values both branches changed.
    *
    * {{{
    * conflictsHeading(List(entry)) // "1 item was changed differently on both branches since the fork:"
    * }}}
    */
  def conflictsHeading(entries: List[MergeConflictEntry]): String =
    entries.size match
      case 1 => "1 item was changed differently on both branches since the fork:"
      case n => s"$n items were changed differently on both branches since the fork:"

  /** The tree, node or mitigation an entry names, with each name once when the
    * branches agree and per branch when they differ.
    *
    * {{{
    * entryLabel(draftA, nodeConflict) // "Risk 'Fraud' in 'merge-test'"
    * entryLabel(draftA, renamed)      // "Risk 'Fraud' on draft-a, 'Card fraud' on main, in 'merge-test'"
    * entryLabel(draftA, mitigation)   // "Mitigation 'Backups' in 'merge-test'"
    * entryLabel(draftA, metaConflict) // "Tree 'merge-test'"
    * }}}
    */
  def entryLabel(scenario: ScenarioName.ScenarioName, entry: MergeConflictEntry): String =
    entry match
      case MergeConflictEntry.TreeMetadataConflict(treeId, versions, _) =>
        s"Tree ${names(scenario, versions.map(_.name), treeId.value)}"
      case MergeConflictEntry.NodeConflict(tree, nodeId, versions, _) =>
        s"${nodeNoun(versions)} ${names(scenario, versions.map(_.name), nodeId.value)}${inTree(scenario, tree, versions.map(_.name))}"
      case MergeConflictEntry.MitigationConflict(tree, mitigationId, versions, _) =>
        s"Mitigation ${names(scenario, versions.map(_.name), mitigationId.value)}${inTree(scenario, tree, versions.map(_.name))}"

  /** One line per differing field, naming the field and, for a value a user
    * reads directly, both branches' values. Empty for a value one branch
    * deleted, which `presenceLine` describes.
    *
    * {{{
    * fieldLines(draftA, probabilityConflict) // List("Probability: 30% on draft-a, 60% on main")
    * }}}
    */
  def fieldLines(scenario: ScenarioName.ScenarioName, entry: MergeConflictEntry): List[String] =
    entry match
      case MergeConflictEntry.TreeMetadataConflict(_, Versions(_, Some(main), Some(scen)), fields) =>
        fields.map(field => fieldLine(scenario, field, shown(field, Some(main.name), Some(scen.name))))
      case MergeConflictEntry.NodeConflict(_, _, Versions(_, Some(main), Some(scen)), fields) =>
        fields.map(field => fieldLine(scenario, field, shownForNode(field, main, scen)))
      case MergeConflictEntry.MitigationConflict(_, _, Versions(_, Some(main), Some(scen)), fields) =>
        fields.map(field => fieldLine(scenario, field, shownForMitigation(field, main, scen)))
      case _ =>
        Nil

  /** The line for a value one branch deleted, naming the fields the other
    * branch changed.
    *
    * {{{
    * presenceLine(draftA, deletedOnMain) // Some("Deleted on main. Changed on draft-a: Probability, Name")
    * }}}
    */
  def presenceLine(scenario: ScenarioName.ScenarioName, entry: MergeConflictEntry): Option[String] =
    val (versions, fields) = entry match
      case MergeConflictEntry.TreeMetadataConflict(_, v, f) => (v.map(_ => ()), f)
      case MergeConflictEntry.NodeConflict(_, _, v, f)      => (v.map(_ => ()), f)
      case MergeConflictEntry.MitigationConflict(_, _, v, f) => (v.map(_ => ()), f)
    (versions.onMain, versions.onScenario) match
      case (None, Some(_)) => Some(deletedLine(MainName, scenario.value, fields))
      case (Some(_), None) => Some(deletedLine(scenario.value, MainName, fields))
      case _               => None

  /** The repeated names, grouped per tree and ordered, so two previews of the
    * same conflict read identically.
    */
  def duplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]]): String =
    perTree.toList
      .sortBy((treeId, _) => treeId.value)
      .map((_, names) => names.map(_.value).sorted.mkString(", "))
      .mkString("; ")

  // ── building blocks ──────────────────────────────────────────────────────

  /** How main is named beside a scenario in a conflict line. */
  private val MainName: String = "main"

  /** The name a user reads for each field a conflict can name. The form's own
    * label where a form shows the field; otherwise the glossary's word for
    * the concept. */
  private def fieldLabel(field: FieldName): String = field match
    case FieldName.Name             => RiskLeafField.label(RiskLeafField.Name)
    case FieldName.ParentId         => ParentSelection.Label
    case FieldName.Probability      => RiskLeafField.label(RiskLeafField.Probability)
    case FieldName.DistributionType => "Loss distribution"
    case FieldName.SeedVarId        => "Random-number stream"
    case FieldName.ChildIds         => "Children"
    case FieldName.RootId           => "Root portfolio"
    case FieldName.SeedVarHighWater => "Last random-number stream issued"
    case FieldName.TargetingSource  => "Target"
    case FieldName.Spec             => "Reduction"
    case FieldName.Precedence       => "Precedence"
    case other                      => other.toString

  /** A field's line: its label, then both branches' values where the user
    * reads them directly. */
  private def fieldLine(scenario: ScenarioName.ScenarioName, field: FieldName, values: Option[(String, String)]): String =
    values match
      case Some((main, scen)) => s"${fieldLabel(field)}: $scen on ${scenario.value}, $main on $MainName"
      case None               => fieldLabel(field)

  private def shown(field: FieldName, main: Option[SafeName.SafeName], scen: Option[SafeName.SafeName]): Option[(String, String)] =
    (field, main, scen) match
      case (FieldName.Name, Some(m), Some(s)) => Some((quoted(m.value), quoted(s.value)))
      case _                                  => None

  private def shownForNode(field: FieldName, main: RiskNode, scen: RiskNode): Option[(String, String)] =
    (field, main, scen) match
      case (FieldName.Probability, m: RiskLeaf, s: RiskLeaf) => Some((percent(m.probability), percent(s.probability)))
      case _                                                 => shown(field, Some(main.name), Some(scen.name))

  private def shownForMitigation(field: FieldName, main: Mitigation, scen: Mitigation): Option[(String, String)] =
    field match
      case FieldName.Precedence => Some((main.precedence.key.toString, scen.precedence.key.toString))
      case _                    => shown(field, Some(main.name), Some(scen.name))

  private def percent(probability: Double): String =
    s"${NumberFormat.percentValue(probability, RiskLeafFormState.probabilityDecimals)}%"

  private def quoted(name: String): String = s"'$name'"

  /** A name once when main and the scenario agree, and per branch when they
    * differ. A value one branch deleted is named by the branch that holds it,
    * and one neither holds by its name at the fork, or by its identifier. */
  private def names(scenario: ScenarioName.ScenarioName, versions: Versions[SafeName.SafeName], identifier: String): String =
    (versions.onMain, versions.onScenario) match
      case (Some(m), Some(s)) if m == s => quoted(m.value)
      case (Some(m), Some(s))           => s"${quoted(s.value)} on ${scenario.value}, ${quoted(m.value)} on $MainName"
      case (Some(m), None)              => quoted(m.value)
      case (None, Some(s))              => quoted(s.value)
      case (None, None)                 => versions.base.fold(identifier)(b => quoted(b.value))

  /** The tree an entry sits in. A comma separates it from a subject named per
    * branch, so the tree's name is not read as a third branch's. */
  private def inTree(scenario: ScenarioName.ScenarioName, tree: TreeLabel, subject: Versions[SafeName.SafeName]): String =
    val treeNames = names(scenario, tree.names, tree.id.value)
    (subject.onMain, subject.onScenario) match
      case (Some(m), Some(s)) if m != s => s", in $treeNames"
      case _                            => s" in $treeNames"

  /** "Risk" for a leaf and "Portfolio" for a portfolio, read from whichever
    * version exists. */
  private def nodeNoun(versions: Versions[RiskNode]): String =
    versions.onScenario.orElse(versions.onMain).orElse(versions.base) match
      case Some(_: RiskPortfolio) => "Portfolio"
      case _                      => "Risk"

  private def deletedLine(deletedOn: String, changedOn: String, fields: List[FieldName]): String =
    s"Deleted on $deletedOn. Changed on $changedOn: ${fields.map(fieldLabel).mkString(", ")}"
