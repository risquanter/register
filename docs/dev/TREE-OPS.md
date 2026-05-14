# Tree manipulation patterns for functional design

**Summary**  
This document collects practical patterns and the underlying mathematics for manipulating immutable tree structures **without** redefining the whole tree. It targets trees with two node types:

- **Leaf** — carries a payload (distribution data).  
- **Portfolio** — internal node with children (each child is a `Leaf` or `Portfolio`).

It explains **zippers**, **optics (lenses/prisms/traversals)**, **recursion schemes**, and **generic traversals**, gives tradeoffs, and includes **Scala sketches** for each approach so an agent can pick up, run, and analyze the ideas.

---

## Quick comparison

| **Pattern** | **Locality** | **Immutability friendly** | **Boilerplate** | **Best for** |
|---|---:|---:|---:|---|
| **Zipper** | **Very high** | **Yes** | **Low–medium** | Focused edits, navigation, incremental updates |
| **Lenses / Optics** | **Medium** | **Yes** | **Low (with lib)** | Composable deep updates and queries |
| **Recursion schemes** | **Global** | **Yes** | **Medium–high** | Generic folds/unfolds, factoring recursion |
| **Traversals / mapM** | **Global** | **Yes** | **Low** | Apply effects/aggregations over all leaves |
| **Edit scripts / diffs** | **Local or global** | **Yes** | **High** | Patching, merging, undo/redo |

---

## Pattern 1 — Zipper (localized edits)

**Idea**  
A zipper represents a focused position in the tree plus a context (the path). You can move the focus (down/up/sibling), modify the focused node, and then reify the root. Because only the path is rebuilt, unrelated subtrees are not copied.

**Mathematical intuition**  
A zipper is the *context* of a data structure; for algebraic data types, the zipper corresponds to the *derivative* of the type (Huet’s derivative of data types). The derivative describes “one-hole contexts” and the zipper stores a path of such contexts.

**Operations** (conceptual)
- `goDown(index)`, `goUp()`, `goLeft()`, `goRight()`
- `modify(focus => newFocus)`
- `toTree()` — reify root

**Scala sketch**

\`\`\`scala
// Basic tree
sealed trait Tree
case class Leaf(payload: Distribution) extends Tree
case class Portfolio(children: Vector[Tree]) extends Tree

// A crumb stores siblings and parent info
case class Crumb(left: Vector[Tree], parent: Vector[Tree], right: Vector[Tree])

case class Zipper(focus: Tree, crumbs: List[Crumb]) {
  def goDown(i: Int): Option[Zipper] = focus match {
    case Portfolio(children) if i >= 0 && i < children.length =>
      val (l, r) = children.splitAt(i)
      val child = r.head
      val right = r.tail
      Some(Zipper(child, Crumb(l, Vector.empty, right) :: crumbs))
    case _ => None
  }

  def goUp: Option[Zipper] = crumbs match {
    case Crumb(left, _, right) :: rest =>
      val rebuilt = Portfolio(left ++ (focus +: right))
      Some(Zipper(rebuilt, rest))
    case Nil => None
  }

  def modify(f: Tree => Tree): Zipper = copy(focus = f(focus))

  def toTree: Tree = goUp match {
    case Some(z) => z.toTree
    case None => focus
  }
}
\`\`\`

**Notes**
- Use `Vector` for efficient indexed splits.
- Zipper updates are \(O(\text{depth})\) and avoid copying unrelated subtrees.
- For repeated localized edits, zippers are ideal.

---

## Pattern 2 — Optics: Lenses, Prisms, Traversals

**Idea**  
Optics provide composable getters/setters for nested structures. **Lenses** focus on product fields, **Prisms** on sum variants, and **Traversals** iterate over multiple targets (e.g., all children).

**Mathematical intuition**  
Optics can be formalized via category theory (profunctors) or via the van Laarhoven representation. They are compositional morphisms that let you build complex accessors from simple ones.

**Typical usage**
- Compose optics to reach `portfolio.children.each._Leaf.payload` and `over` a function across all payloads.
- Use `modify` to produce a new tree with updated payloads.

**Scala sketch (simple, no library)**

\`\`\`scala
// Minimal lens-like helpers (not full optics library)
case class Lens[S, A](get: S => A, set: (S, A) => S) {
  def modify(s: S)(f: A => A): S = set(s, f(get(s)))
  def compose[B](other: Lens[A, B]): Lens[S, B] =
    Lens[S, B](s => other.get(get(s)), (s, b) => set(s, other.set(get(s), b)))
}

// Example lenses for Tree
val portfolioChildrenLens: Lens[Portfolio, Vector[Tree]] =
  Lens[Portfolio, Vector[Tree]](_.children, (p, c) => p.copy(children = c))

// Prism-like helper for Node -> Leaf payload
def leafPayloadLens: Option[Lens[Tree, Distribution]] = Some(
  Lens[Tree, Distribution](
    {
      case Leaf(payload) => payload
      case _ => throw new NoSuchElementException("not a leaf")
    },
    (t, d) => t match {
      case Leaf(_) => Leaf(d)
      case _ => t
    }
  )
)
\`\`\`

**Notes**
- For production, use an optics library (Monocle for Scala) to get `each`, `prism`, `Traversal`, and lawful composition.
- Optics are excellent for declarative, composable updates and for combining local and global transformations.

---

## Pattern 3 — Recursion schemes (catamorphisms, anamorphisms, hylomorphisms)

**Idea**  
Recursion schemes separate *recursion pattern* from *business logic*. You define a one-level functor `TreeF` and an algebra; `cata` (catamorphism) folds the tree using that algebra.

**Mathematical intuition**  
Trees are initial algebras of a functor \(F\). A **catamorphism** is the unique homomorphism from the initial algebra to another algebra. This is the categorical abstraction of structural recursion. Anamorphisms are dual (unfolds). Hylomorphisms compose an unfold followed by a fold.

**Why it helps**  
- Factor recursion once and reuse algebras for many folds.
- Express complex transformations as compositions of simple algebras.

**Math notation**  
If \(T \cong 1 + A \times T^n\) (informal), define a functor \(F(X) = 1 + A \times X^n\). The tree is the initial algebra \(\mu F\). A catamorphism for algebra \(a: F(R) \to R\) is \(\text{cata}_a : \mu F \to R\).

**Scala sketch (simple cata)**

\`\`\`scala
// Functor-level tree
sealed trait TreeF[+A]
case class LeafF[A](payload: Distribution) extends TreeF[A]
case class PortfolioF[A](children: Vector[A]) extends TreeF[A]

// Fix point
case class Fix[F[_]](unfix: F[Fix[F]])

// Helpers to build Fix[TreeF]
def leaf(payload: Distribution): Fix[TreeF] = Fix[TreeF](LeafF(payload))
def portfolio(children: Vector[Fix[TreeF]]): Fix[TreeF] = Fix[TreeF](PortfolioF(children))

// Catamorphism
def cata[F[_], A](f: F[A] => A)(fix: Fix[F])(implicit Fmap: Functor[F]): A = {
  val mapped: F[A] = Fmap.map(fix.unfix)(cata(f))
  f(mapped)
}

// Functor instance for TreeF
trait Functor[F[_]] { def map[A,B](fa: F[A])(f: A => B): F[B] }
implicit val treeFFunctor: Functor[TreeF] = new Functor[TreeF] {
  def map[A,B](fa: TreeF[A])(f: A => B): TreeF[B] = fa match {
    case LeafF(p) => LeafF(p)
    case PortfolioF(children) => PortfolioF(children.map(f))
  }
}

// Example: fold to count leaves
val countLeavesAlg: TreeF[Int] => Int = {
  case LeafF(_) => 1
  case PortfolioF(childrenCounts) => childrenCounts.sum
}
def countLeaves(tree: Fix[TreeF]): Int = cata[TreeF, Int](countLeavesAlg)(tree)
\`\`\`

**Notes**
- This sketch uses a `Fix` type and a `Functor` instance. Libraries (Matryoshka, higher-kinded libraries) provide richer tooling.
- Recursion schemes are powerful for reasoning and refactoring repeated recursion patterns.

---

## Pattern 4 — Traversals and effectful mapping

**Idea**  
A traversal visits every leaf (or every node) and applies a function, possibly effectful (e.g., `Option`, `Either`, `IO`). In FP languages, `traverse` generalizes `map` to effectful contexts.

**Scala sketch (pure traversal)**

\`\`\`scala
def traverseTree[A](t: Tree)(f: Distribution => A): Tree = t match {
  case Leaf(payload) => Leaf(f(payload).asInstanceOf[Distribution]) // example mapping
  case Portfolio(children) => Portfolio(children.map(c => traverseTree(c)(f)))
}
\`\`\`

**Effectful version** uses `Applicative`/`Monad` to sequence effects across children.

---

## Deep math properties and correctness concerns

- **Zipper correctness**: Zipper operations preserve the invariant that `toTree` after a sequence of `goDown`/`modify`/`goUp` yields a tree isomorphic to performing the corresponding updates directly. The zipper corresponds to the derivative of the data type; proofs use structural induction on depth and the algebraic properties of the derivative.
- **Optics laws**: Lenses should satisfy **get-set** and **set-get** laws:
  - `set(s, get(s)) == s`
  - `get(set(s, a)) == a`
  - `set(set(s, a), b) == set(s, b)`
  These laws ensure predictable composition and reasoning.
- **Recursion schemes**: Catamorphisms are unique homomorphisms from the initial algebra. Correctness proofs use universal properties: if `T` is the initial algebra for `F`, then for any algebra `a: F(R) -> R` there is a unique `h: T -> R` such that `h . in = a . F(h)`. This gives equational reasoning for folds.
- **Complexity**:
  - Zipper updates: \(O(\text{depth})\) per update.
  - Full-tree traversals: \(O(n)\) where \(n\) is number of nodes.
  - Recursion-scheme overhead: constant-factor overhead for `Fix` and functor mapping; amortized complexity same as explicit recursion.
- **Equational reasoning**: Using algebraic laws (lens laws, functor laws, monad/applicative laws) enables equational reasoning and refactoring.

---

## Practical recipes and patterns

- **Localized edit workflow**: Use a zipper when you need to navigate and perform many edits near a focused area. Reify once at the end.
- **Bulk update workflow**: Use optics/traversals to express “update every leaf payload by `f`” in one composable expression.
- **Algorithmic transforms**: Use recursion schemes to express complex structural transforms (e.g., normalize, annotate, compress) as algebras and compose them.
- **Effectful aggregation**: Use `traverse` with an `Applicative` to collect results or perform effectful updates across leaves.
- **Undo/redo and diffs**: Represent edits as scripts (operations on zippers or paths) and store them for replay or inversion.

---

## Example: update every leaf distribution parameter (Scala, simple)

\`\`\`scala
// Suppose Distribution is a case class
case class Distribution(mean: Double, variance: Double)

// Update function
def scaleMean(d: Distribution, factor: Double): Distribution =
  d.copy(mean = d.mean * factor)

// Pure traversal
def mapLeaves(t: Tree)(f: Distribution => Distribution): Tree = t match {
  case Leaf(payload) => Leaf(f(payload))
  case Portfolio(children) => Portfolio(children.map(mapLeaves(_)(f)))
}

// Usage
val scaled: Tree = mapLeaves(myTree)(d => scaleMean(d, 1.1))
\`\`\`

---

## Implementation checklist for an agent

1. **Model**: Define `Tree` as `sealed trait` with `Leaf(payload)` and `Portfolio(children)`.
2. **Zipper**: Implement `Crumb` and `Zipper` with navigation and `toTree`.
3. **Optics**: Either use a library (Monocle) or implement minimal `Lens`/`Prism`/`Traversal` helpers.
4. **Recursion schemes**: Implement `Fix`, `TreeF`, `Functor[TreeF]`, and `cata`/`ana` as needed.
5. **Tests**:
   - Unit tests for lens laws and zipper roundtrip (`toTree` after navigation).
   - Complexity tests for large trees.
6. **Examples**:
   - Localized edit: change a leaf payload deep in the tree using zipper.
   - Bulk update: scale all leaf means using traversal or optics.
   - Fold: compute aggregated portfolio statistics with `cata`.

---

## Further notes and extensions

- **Memoization / caching**: For expensive aggregates, maintain cached summaries at `Portfolio` nodes and update incrementally via zipper edits.
- **Concurrency**: Immutable structures are friendly to concurrency; use structural sharing to minimize copying.
- **Persistence**: Zippers and functional updates naturally support persistent data structures and undo/redo.
- **Libraries**: In Scala, consider **Monocle** (optics) and **Matryoshka** or higher-kinded libraries for recursion schemes.
