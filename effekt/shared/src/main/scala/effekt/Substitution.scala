package effekt

import effekt.context.Context
import effekt.substitutions.TypeComparer
import effekt.symbols.*
import effekt.symbols.builtins.{ TBottom, TInt, TTop }
import effekt.util.messages.ErrorReporter

object substitutions {

  private var scopeId: Int = 0


  case class CaptureConstraints(lower: Set[Capture], upper: Set[Capture])

  /**
   * The state of the unification scope, used for backtracking on overload resolution
   *
   * See [[UnificationScope.backup]] and [[UnificationScope.restore]]
   */
  case class UnificationState(
    skolems: List[UnificationVar],
    constraints: ConstraintGraph
  )

  /**
   * A graph of connected nodes, where each node can have multiple "upper"
   * and multiple "lower" nodes. Each node carries a payload
   *
   *    (ValueType, ValueType)
   *
   * corresponding to the lower and upper type bounds of a unification variable.
   * The graph itself establishes the following invariants:
   *
   * - Direct: all transitive (lower/higher) nodes are also immediate (lower/higher) nodes
   * - Compact: all cycles (by directness) lead to cliques. Those are represented by a single node
   *
   * The graph implementation does NOT establish invariants about the payloads (type bounds).
   * Those have to be established externally.
   */
  class ConstraintGraph {


    /**
     * Represents a node in the constraint propagation graph
     *
     *                    ┏━━━━━━━━━━━━━━━━━━┓
     *    --------------> ┃ {?MyVar, ?Other} ┃ -------------->
     *     lower nodes    ┠─────────┬────────┨   upper nodes
     *    --------------> ┃  Lower  │  Upper ┃ -------------->
     *                    ┗━━━━━━━━━┷━━━━━━━━┛
     *
     * The arrows in the picture above indicate the suptyping relationship.
     * They are in fact navigatable in both directions, since new _upper_ bounds
     * need to flow through the node from right-to-left.
     *
     * Every node represents potentially a set of unification variables that
     * are assumed to be equal. Type equality constraints, invariant positions
     * and mutually recursive unification variables lead to these sets having
     * more than one member.
     *
     * We have the following invariants:
     * - Direct: all transitive lower nodes are also immediate lower nodes
     * - Intra-Consistency: Lower is always consistent to Upper
     * - Inter-Consistency: if two nodes ?S <: ?T, then the lower bound of ?S has been
     *     propagated as lower bound to ?T and the upper bound of ?T has been propagated
     *     as upper bound of ?S.
     */
    private case class NodeData(lower: Set[Node], payload: (ValueType, ValueType), upper: Set[Node])

    private class Node

    private var valueConstraints: Map[Node, NodeData] = Map.empty

    // a map from a member in the equivalence class to the class' representative
    private var nodes: Map[UnificationVar, Node] = Map.empty

    private def getNode(x: UnificationVar): Node =
      nodes.getOrElse(x, { val rep = new Node; nodes += (x -> rep); rep })

    /**
     * Given a representative gives the list of all unification variables it is known to
     * be in the same equivalence class with.
     */
    private def variablesFor(representative: Node): Set[UnificationVar] =
      val transposed = nodes.groupMap { case (el, repr) => repr } { case (el, repr) => el }
      transposed.getOrElse(representative, Nil).toSet

    private def getBounds(x: Node): NodeData =
      valueConstraints.getOrElse(x, NodeData(Set.empty, (TBottom, TTop), Set.empty))

    private def setBounds(x: Node, bounds: NodeData): Unit =
      valueConstraints = valueConstraints.updated(x, bounds)

    def boundsFor(x: UnificationVar): (ValueType, ValueType) = getBounds(getNode(x)).payload
    def lowerBound(x: UnificationVar): ValueType = boundsFor(x)._1
    def upperBound(x: UnificationVar): ValueType = boundsFor(x)._2

    def lowerBounds(x: UnificationVar): Set[UnificationVar] =
      getBounds(getNode(x)).lower.flatMap { variablesFor }

    def upperBounds(x: UnificationVar): Set[UnificationVar] =
      getBounds(getNode(x)).upper.flatMap { variablesFor }

    def updateLowerBound(x: UnificationVar, bound: ValueType): Unit = bound match {
      case y: UnificationVar => sys error s"Cannot set unification variable ${y} as a lower bound for ${x}"
      case _ =>
        val rep = getNode(x)
        val bounds = getBounds(rep)
        val (low, up) = bounds.payload
        setBounds(rep, bounds.copy(payload = (bound, up)))
    }

    def updateUpperBound(x: UnificationVar, bound: ValueType): Unit = bound match {
      case y: UnificationVar => sys error s"Cannot set unification variable ${y} as a upper bound for ${x}"
      case _ =>
        val rep = getNode(x)
        val bounds = getBounds(rep)
        val (low, up) = bounds.payload
        setBounds(rep, bounds.copy(payload = (low, bound)))
    }

    /**
     * Decides whether [[x]] is a subtype of [[y]] solely by inspecting the bounds
     */
    def isSubtypeOf(x: UnificationVar, y: UnificationVar): Boolean =
      val bounds = getBounds(getNode(x))
      val yRepr = getNode(y)
      bounds.upper contains yRepr

    def isSupertypeOf(x: UnificationVar, y: UnificationVar): Boolean =
      val bounds = getBounds(getNode(x))
      val yRepr = getNode(y)
      bounds.lower contains yRepr


    /**
     * Adds x as a lower bound to y, and y as a lower bound to x.
     */
    def connect(x: UnificationVar, y: UnificationVar): Unit =
      val repX = getNode(x)
      val repY = getNode(y)

      val boundsX = getBounds(repX)
      val boundsY = getBounds(repY)

      // Already connected
      if (boundsY.lower contains repX) {
        return ()
      }

      // They will form a cycle
      else if (boundsY.upper contains repX) {
        setBounds(repY, boundsY.copy(
          lower = (boundsY.lower ++ boundsX.lower + repX) - repY
        ))
        replaceVariableByVariable(repX, repY)
        return;
      }

      else {
        setBounds(repX, boundsX.copy(
          upper = (boundsX.upper ++ boundsY.upper + repY) - repX
        ))
        setBounds(repY, boundsY.copy(
          lower = (boundsY.lower ++ boundsX.lower + repX) - repY
        ))
      }

    private def replaceVariableByVariable(x: Node, y: Node): Unit =
      valueConstraints = valueConstraints.collect {
        case (z, NodeData(loNodes, payload, upNodes)) if z != x =>
          (z, NodeData(
            if (loNodes.contains(x)) loNodes - x + y - z else loNodes,
            payload,
            if (upNodes.contains(x)) upNodes - x + y - z else upNodes
          ))
      }
      // create mapping to representative
      nodes = nodes.view.mapValues { v => if (v == x) y else v }.toMap



    def dumpConstraints() =
      val colSize = 12
      val varSize = 5
      val sep = "━".repeat(colSize)
      val varSep = "━".repeat(varSize)

      println(s"┏$sep┯$sep━━━━━$varSep━━━━━$sep┯$sep┓")

      def showNode(n: Node): String =
        val equiv = variablesFor(n).toList
        if (equiv.size == 1) equiv.head.toString else s"{${equiv.mkString(", ")}}"

      valueConstraints.foreach {
        case (x, NodeData(lowerVars, (lower, upper), upperVars)) =>
          val lowNodes = lowerVars.map(showNode).mkString(", ").padTo(colSize, ' ')
          val lowType  = lower.toString.padTo(colSize, ' ')
          val variable = showNode(x).padTo(varSize, ' ')
          val upType = upper.toString.padTo(colSize, ' ')
          val upNodes = upperVars.map(showNode).mkString(", ")
          println(s"$lowNodes │ $lowType <: $variable <: $upType │ $upNodes")
      }
      println(s"┗$sep┷$sep━━━━━$varSep━━━━━$sep┷$sep┛")
  }

  /**
   * A unification scope -- every fresh unification variable is associated with a scope.
   */
  class UnificationScope { self =>

    val id = { scopeId += 1; scopeId }

    // Unification Variables
    // ---------------------

    private var skolems: List[UnificationVar] = Nil
    private var capture_skolems: List[CaptureUnificationVar] = Nil

    def fresh(role: UnificationVar.Role): UnificationVar = {
      val x = UnificationVar(role, this)
      skolems = x :: skolems
      x
    }

    def freshCaptVar(underlying: Capture): CaptureUnificationVar = {
      val x = CaptureUnificationVar(underlying, this)
      capture_skolems = x :: capture_skolems
      x
    }

    // The Constraint Graph
    // --------------------
    var constraints = new ConstraintGraph

    def dumpConstraints() = constraints.dumpConstraints()

    def backup(): UnificationState = UnificationState(skolems, constraints)
    def restore(state: UnificationState): Unit =
      skolems = state.skolems
      constraints = state.constraints

    /**
     * Updates the network to replace variable by type [[tpe]]
     *
     * TODO generalize substitution to bi-substitution and also generalize this method:
     */
    def replaceVariableByType(x: UnificationVar, tpe: ValueType)(using ErrorReporter): Unit = ()
//
//      val ValueTypeConstraints(lowerNodes, lower, upper, upperNodes) = boundsFor(x)
//      // substitute [[tpe]] for [[x]] in all types and
//      // remove all references to [[x]] from bounds.
//      valueConstraints = valueConstraints.collect {
//        case (y, ValueTypeConstraints(loNodes, low, up, upNodes)) if x != y =>
//          val subst: Substitutions = Map(x -> tpe)
//          (y, ValueTypeConstraints(
//            loNodes - x,
//            subst.substitute(low),
//            subst.substitute(up),
//            upNodes - x
//          ))
//      }
//      // Those nodes that had x as bound now need to have [[tpe]] as bound.
//      // Push this info through the system... this might be horribly inefficient, right now.
//      lowerNodes.foreach { y => requireSubtype(y, tpe) }
//      upperNodes.foreach { y => requireSubtype(tpe, y) }



    // TODO do we need to compute a bisubstitution here???
    var valueSubstitution: Map[UnificationVar, ValueType] = Map.empty

    def solve()(using C: ErrorReporter): (Map[UnificationVar, ValueType], Unit) = {
      // TODO graph without skolem nodes

//      println(s"Solving for ${skolems}:")
//      dumpConstraints()

      //skolems.foreach { x => replaceVariable(x, TTop) }
      //skolems = Nil // this will be achieved by replacing the scope.

//      println(s"Done solving for ${skolems}")
//      dumpConstraints()

      (Map.empty, ())
    }


    /**
     * Checks whether t1 <: t2
     *
     * Has the side effect of registering constraints.
     */
    def requireSubtype(t1: ValueType, t2: ValueType)(using C: ErrorReporter): Unit =
      comparer.unifyValueTypes(t1, t2)

    def requireSubtype(t1: BlockType, t2: BlockType)(using C: ErrorReporter): Unit =
      sys error s"Requiring that ${t1} <:< ${t2}"

    def requireSubregion(c1: CaptureSet, c2: CaptureSet)(using C: ErrorReporter): Unit =
      sys error s"Requiring that ${c1} <:< ${c2}"


    /**
     * Given the current unification state, can we decide whether one type is a subtype of another?
     *
     * Does not influence the constraint graph.
     */
    def isSubtype(tpe1: ValueType, tpe2: ValueType): Boolean =
      object NotASubtype extends Throwable
      object comparer extends TypeComparer {
        def unify(c1: CaptureSet, c2: CaptureSet): Unit = ???
        def abort(msg: String) = throw NotASubtype
        def requireLowerBound(x: UnificationVar, tpe: ValueType) =
          tpe match {
            case y: UnificationVar =>
              if (!constraints.isSupertypeOf(x, y)) throw NotASubtype
            case tpe =>
              unifyValueTypes(tpe, constraints.upperBound(x))
          }
        def requireUpperBound(x: UnificationVar, tpe: ValueType) =
          tpe match {
            case y: UnificationVar =>
              if (!constraints.isSubtypeOf(x, y)) throw NotASubtype
            case tpe =>
              unifyValueTypes(constraints.lowerBound(x), tpe)
          }
      }
      val res = try { comparer.unifyValueTypes(tpe1, tpe2); true } catch {
        case NotASubtype => false
      }
      println(s"Checking ${tpe1} <: ${tpe2}: $res")
      res

    /**
     * Given the current unification state, can we decide whether one effect is a subtype of another?
     *
     * Used to subtract one set of effects from another (when checking handling, or higher-order functions)
     */
    def isSubtype(e1: Effect, e2: Effect): Boolean = ???

    /**
     * Removes effects [[effs2]] from effects [[effs1]] by checking for subtypes.
     *
     * TODO check whether this is sound! It should be, since it is a conservative approximation.
     *   If it turns out two effects ARE subtypes after all, and we have not removed them, it does not
     *   compromise effect safety.
     *
     * TODO potentially dealias first...
     */
    def subtract(effs1: Effects, effs2: Effects): Effects =
      effs1.filterNot(eff1 => effs2.exists(eff2 => isSubtype(eff2, eff1)))

    /**
     * Instantiate a typescheme with fresh, rigid type variables
     *
     * i.e. `[A, B] (A, A) => B` becomes `(?A, ?A) => ?B`
     */
    def instantiate(tpe: FunctionType)(using C: ErrorReporter): (List[UnificationVar], List[CaptureUnificationVar], FunctionType) = {
      val FunctionType(tparams, cparams, vparams, bparams, ret, eff) = tpe
      val typeRigids = tparams map { t => fresh(UnificationVar.TypeVariableInstantiation(t)) }
      val captRigids = cparams map freshCaptVar
      val subst = Substitutions(
        tparams zip typeRigids,
        cparams zip captRigids.map(c => CaptureSet(c)))

      val substitutedVparams = vparams map subst.substitute
      val substitutedBparams = bparams map subst.substitute
      val substitutedReturn = subst.substitute(ret)
      val substitutedEffects = subst.substitute(eff)
      (typeRigids, captRigids, FunctionType(Nil, Nil, substitutedVparams, substitutedBparams, substitutedReturn, substitutedEffects))
    }

    /**
     * A side effecting type comparer
     *
     * TODO the comparer should build up a "deconstruction trace" that can be used for better type errors.
     */
    def comparer(using C: ErrorReporter): TypeComparer = new TypeComparer {

      private var seen: Set[(ValueType, ValueType)] = Set.empty

      def unify(c1: CaptureSet, c2: CaptureSet): Unit = ???

      def abort(msg: String) = C.abort(msg)


      /**
       * Value type [[tpe]] flows into the unification variable [[x]] as a lower bound.
       *
       *
       *                  ┏━━━━━━━━━━━━━━━━━━━━━━━┓
       *                  ┃           x           ┃
       *  --------------> ┠───────────┬───────────┨ ---------------->
       *    (1) tpe       ┃ Lower (2) │ Upper (3) ┃ (4) upper nodes
       *                  ┗━━━━━━━━━━━┷━━━━━━━━━━━┛
       *
       */
      def requireLowerBound(x: UnificationVar, tpe: ValueType) =
        if (x == tpe) return ()

        tpe match {
          // the new lower bound is a unification variable
          case y: UnificationVar => connectNodes(y, x)

          // the new lower bound is a value type
          case _ =>
            // (1) look up the bounds for node `x` -- this can potentially add a fresh node to the network
            val (lower, upper) = constraints.boundsFor(x)

            // (2) we merge the existing lower bound with the incoming type.
            mergeAndUpdateLowerBound(x, tpe) foreach { merged =>

              // (3) we check the existing upper bound for consistency with the lower bound
              //     We do not have to do this for connected nodes, since type variables in
              //     the upper bounds are connected itself.
              unifyValueTypes(merged, upper)
            }

            // (4) we propagate the incoming type to all upper nodes
            constraints.upperBounds(x) foreach { node => mergeAndUpdateLowerBound(node, tpe) }
        }

      /**
       * Value type [[tpe]] flows into the unification variable [[x]] as an upper bound.
       * Symmetric to [[requireLowerBound]].
       */
      def requireUpperBound(x: UnificationVar, tpe: ValueType) =
        if (x == tpe) return ()

        tpe match {
          // the new lower bound is a unification variable
          case y: UnificationVar => connectNodes(x, y)

          // the new lower bound is a value type
          case _ =>
            // (1) look up the bounds for node `x` -- this can potentially add a fresh node to the network
            val (lower, upper) = constraints.boundsFor(x)

            // (2) we merge the existing lower bound with the incoming type.
            mergeAndUpdateUpperBound(x, tpe) foreach { merged =>

              // (3) we check the existing upper bound for consistency with the lower bound
              unifyValueTypes(lower, merged)
            }

            // (4) we propagate the incoming type to all lower nodes
             constraints.lowerBounds(x) foreach { node => mergeAndUpdateUpperBound(node, tpe) }
        }

      // only updates one layer of connections, not recursively since we have
      // the invariant that all transitive connections are established
      def mergeAndUpdateLowerBound(x: UnificationVar, tpe: ValueType): Option[ValueType] =
        assert (!tpe.isInstanceOf[UnificationVar])
        val lower = constraints.lowerBound(x)
        if (x == tpe || lower == tpe || tpe == TBottom) return None;
        val newBound = mergeLower(lower, tpe)
        constraints.updateLowerBound(x, newBound)
        Some(newBound)

      def mergeAndUpdateUpperBound(x: UnificationVar, tpe: ValueType): Option[ValueType] =
        assert (!tpe.isInstanceOf[UnificationVar])
        val upper = constraints.upperBound(x)
        if (x == tpe || upper == tpe || tpe == TTop) return None;
        val newBound = mergeUpper(upper, tpe)
        constraints.updateUpperBound(x, newBound)
        Some(newBound)

      def connectNodes(x: UnificationVar, y: UnificationVar): Unit =
        if (x == y || (constraints.upperBounds(x) contains y)) return;
        requireLowerBound(y, constraints.lowerBound(x)) // TODO maybe this can be mergeAndUpdateLowerBound
        requireUpperBound(x, constraints.upperBound(y))
        constraints.connect(x, y)

      sealed trait Polarity { def flip: Polarity }
      case object Covariant extends Polarity { def flip = Contravariant}
      case object Contravariant extends Polarity { def flip = Covariant }
      case object Invariant extends Polarity { def flip = Invariant }

      def merge(oldBound: ValueType, newBound: ValueType, polarity: Polarity): ValueType =
        (oldBound, newBound, polarity) match {
          case (t, s, _) if t == s => t
          case (TBottom, t, Covariant) => t
          case (t, TBottom, Covariant) => t
          case (TTop, t, Contravariant) => t
          case (t, TTop, Contravariant) => t

          case (tpe1: UnificationVar, tpe2: UnificationVar, _) =>
            // two unification variables, we create a fresh merge node with two incoming / outgoing edges.

            polarity match {
              case Covariant =>
                val mergeNode = fresh(UnificationVar.MergeVariable)
                connectNodes(tpe1, mergeNode)
                connectNodes(tpe2, mergeNode)
                mergeNode
              case Contravariant =>
                val mergeNode = fresh(UnificationVar.MergeVariable)
                connectNodes(mergeNode, tpe1)
                connectNodes(mergeNode, tpe2)
                mergeNode
              case Invariant =>
                // TODO does this make sense?
                connectNodes(tpe1, tpe2)
                connectNodes(tpe2, tpe1)
                tpe1
            }

/*
┏━━━━━━━━━━━━┯━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┯━━━━━━━━━━━━┓
             │ ⊥            <: ?A109 <: Int          │ ?R107
?B100, ?B108 │ Box[?M114]   <: ?T112 <: Box[Int]     │
?A103        │ ⊥            <: ?R99  <: Int          │ ?M114
?A103, ?R99, ?A109, ?R107 │ ⊥            <: ?M114 <: Int          │
?A109        │ ⊥            <: ?R107 <: Int          │ ?M114
             │ Box[?R99]    <: ?B100 <: Box[Int]     │ ?T112
             │ ⊥            <: ?A103 <: Int          │ ?R99
             │ Box[?R107]   <: ?B108 <: Box[Int]     │ ?T112
┗━━━━━━━━━━━━┷━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┷━━━━━━━━━━━━┛
 */
          // We can use one of them if it is more specific than the other.
          case (tpe1, tpe2, Covariant) if isSubtype(tpe1, tpe2) => tpe2
          case (tpe1, tpe2, Contravariant) if isSubtype(tpe1, tpe2) => tpe1
          case (tpe1, tpe2, Covariant) if isSubtype(tpe2, tpe1) => tpe1
          case (tpe1, tpe2, Contravariant) if isSubtype(tpe2, tpe1) => tpe2

          case (ValueTypeApp(cons1, args1), ValueTypeApp(cons2, args2), _) =>
            if (cons1 != cons2) C.abort(s"Cannot merge different constructors")
            if (args1.size != args2.size) C.abort(s"Different count of argument to type constructor")

            // TODO Here we assume the constructor is covariant
            // TODO perform analysis and then mergeUpper, lower, or require equality.
            val mergedArgs = (args1 zip args2).map { case (t1, t2) =>
              // invariant, for testing purposes:
//              val _ = merge(t1, t2, true);
              merge(t1, t2, Invariant)
            }
            ValueTypeApp(cons1, mergedArgs)

          case _ =>
            C.abort(s"Cannot merge ${oldBound} with ${newBound} at ${polarity} polarity")
        }

      /**
       * Compute the join of two types
       */
      def mergeLower(oldBound: ValueType, newBound: ValueType): ValueType =
        merge(oldBound, newBound, Covariant)

      /**
       * Compute the meet of two types
       */
      def mergeUpper(oldBound: ValueType, newBound: ValueType): ValueType =
        merge(oldBound, newBound, Contravariant)
    }
  }

  case class SubstitutionException(x: CaptureUnificationVar, subst: Map[Capture, CaptureSet]) extends Exception

  /**
   * Substitutions not only have unification variables as keys, since we also use the same mechanics to
   * instantiate type schemes
   */
  case class Substitutions(
    values: Map[TypeVar, ValueType],
    captures: Map[Capture, CaptureSet]
  ) {

    def isDefinedAt(t: TypeVar) = values.isDefinedAt(t)
    def isDefinedAt(c: Capture) = captures.isDefinedAt(c)

    def get(t: TypeVar) = values.get(t)
    def get(c: Capture) = captures.get(c)

    // amounts to first substituting this, then other
    def updateWith(other: Substitutions): Substitutions =
      Substitutions(values.view.mapValues { t => other.substitute(t) }.toMap, captures.view.mapValues { t => other.substitute(t) }.toMap) ++ other

    // amounts to parallel substitution
    def ++(other: Substitutions): Substitutions = Substitutions(values ++ other.values, captures ++ other.captures)

    // shadowing
    private def without(tps: List[TypeVar], cps: List[Capture]): Substitutions =
      Substitutions(
        values.filterNot { case (t, _) => tps.contains(t) },
        captures.filterNot { case (t, _) => cps.contains(t) }
      )

    // TODO we DO need to distinguish between substituting unification variables for unification variables
    // and substituting concrete captures in unification variables... These are two fundamentally different operations.
    def substitute(c: CaptureSet): CaptureSet = c.flatMap {
      // we are probably instantiating a function type
      case x: CaptureUnificationVar if captures.keys.exists(c => c.concrete) =>
        throw SubstitutionException(x, captures)
      case c => captures.getOrElse(c, CaptureSet(c))
    }

    def substitute(t: ValueType): ValueType = t match {
      case x: TypeVar =>
        values.getOrElse(x, x)
      case ValueTypeApp(t, args) =>
        ValueTypeApp(t, args.map { substitute })
      case BoxedType(tpe, capt) =>
        BoxedType(substitute(tpe), substitute(capt))
      case other => other
    }

    // TODO implement
    def substitute(t: Effects): Effects = t
    def substitute(t: Effect): Effect = t

    def substitute(t: BlockType): BlockType = t match {
      case e: InterfaceType => substitute(e)
      case b: FunctionType        => substitute(b)
    }

    def substitute(t: InterfaceType): InterfaceType = t match {
      case b: Interface           => b
      case BlockTypeApp(c, targs) => BlockTypeApp(c, targs map substitute)
    }

    def substitute(t: FunctionType): FunctionType = t match {
      case FunctionType(tps, cps, vps, bps, ret, eff) =>
        // do not substitute with types parameters bound by this function!
        val substWithout = without(tps, cps)
        FunctionType(
          tps,
          cps,
          vps map substWithout.substitute,
          bps map substWithout.substitute,
          substWithout.substitute(ret),
          substWithout.substitute(eff))
    }
  }

  object Substitutions {
    val empty: Substitutions = Substitutions(Map.empty[TypeVar, ValueType], Map.empty[Capture, CaptureSet])
    def apply(values: List[(TypeVar, ValueType)], captures: List[(Capture, CaptureSet)]): Substitutions = Substitutions(values.toMap, captures.toMap)
  }

  // TODO Mostly for backwards compat
  implicit def typeMapToSubstitution(values: Map[TypeVar, ValueType]): Substitutions = Substitutions(values, Map.empty[Capture, CaptureSet])
  implicit def captMapToSubstitution(captures: Map[Capture, CaptureSet]): Substitutions = Substitutions(Map.empty[TypeVar, ValueType], captures)


  trait TypeComparer {

    // "unification effects"
    def requireLowerBound(x: UnificationVar, tpe: ValueType): Unit
    def requireUpperBound(x: UnificationVar, tpe: ValueType): Unit
    def abort(msg: String): Nothing

    def unify(c1: CaptureSet, c2: CaptureSet): Unit
    def unify(c1: Capture, c2: Capture): Unit = unify(CaptureSet(Set(c1)), CaptureSet(Set(c2)))

    def unifyValueTypes(tpe1: ValueType, tpe2: ValueType): Unit = (tpe1, tpe2) match {
      case (t, s) if t == s => ()
      case (_, TTop) => ()
      case (TBottom, _) => ()

      case (s: UnificationVar, t: ValueType) => requireUpperBound(s, t)

      // occurs for example when checking the first argument of `(1 + 2) == 3` against expected
      // type `?R` (since `==: [R] (R, R) => Boolean`)
      case (s: ValueType, t: UnificationVar) => requireLowerBound(t, s)

      case (ValueTypeApp(t1, args1), ValueTypeApp(t2, args2)) =>
        if (args1.size != args2.size)
          abort(s"Argument count does not match $t1 vs. $t2")

        unifyValueTypes(t1, t2)

        // TODO here we assume that the type constructor is covariant
        (args1 zip args2) foreach { case (t1, t2) => unifyValueTypes(t1, t2) }

      case (BoxedType(tpe1, capt1), BoxedType(tpe2, capt2)) =>
        unifyBlockTypes(tpe1, tpe2)
        unify(capt1, capt2)

      case (t, s) =>
        println(s"Type mismatch: ${t} is not ${s}")
        abort(s"Expected ${t}, but got ${s}")
    }

    def unifyBlockTypes(tpe1: BlockType, tpe2: BlockType): Unit = (tpe1, tpe2) match {
      case (t: FunctionType, s: FunctionType) => unifyFunctionTypes(t, s)
      case (t: InterfaceType, s: InterfaceType) => unifyInterfaceTypes(t, s)
      case (t, s) => abort(s"Expected ${t}, but got ${s}")
    }

    def unifyInterfaceTypes(tpe1: InterfaceType, tpe2: InterfaceType): Unit = (tpe1, tpe2) match {
      case (t1: Interface, t2: Interface) => if (t1 != t2) abort(s"Expected ${t1}, but got ${t2}")
      case (BlockTypeApp(c1, targs1), BlockTypeApp(c2, targs2)) =>
        unifyInterfaceTypes(c2, c2)
        (targs1 zip targs2) foreach { case (t1, t2) => unifyValueTypes(t1, t2) }
      case _ => abort(s"Kind mismatch between ${tpe1} and ${tpe2}")
    }

    def unifyEffects(eff1: Effects, eff2: Effects): Unit = ???

    def unifyFunctionTypes(tpe1: FunctionType, tpe2: FunctionType): Unit = (tpe1, tpe2) match {
      case (f1 @ FunctionType(tparams1, cparams1, vparams1, bparams1, ret1, eff1), f2 @ FunctionType(tparams2, cparams2, vparams2, bparams2, ret2, eff2)) =>

        if (tparams1.size != tparams2.size)
          abort(s"Type parameter count does not match $f1 vs. $f2")

        if (vparams1.size != vparams2.size)
          abort(s"Value parameter count does not match $f1 vs. $f2")

        if (bparams1.size != bparams2.size)
          abort(s"Block parameter count does not match $f1 vs. $f2")

        if (cparams1.size != cparams2.size)
          abort(s"Capture parameter count does not match $f1 vs. $f2")

        val subst = Substitutions(tparams2 zip tparams1, cparams2 zip cparams1.map(c => CaptureSet(c)))

        val (substVparams2, substBparams2, substRet2) = (vparams2 map subst.substitute, bparams2 map subst.substitute, subst.substitute(ret2))

        (vparams1 zip substVparams2) foreach { case (t1, t2) => unifyValueTypes(t2, t1) }
        (bparams1 zip substBparams2) foreach { case (t1, t2) => unifyBlockTypes(t2, t1) }
        unifyValueTypes(ret1, substRet2)
        unifyEffects(eff1, eff2)
    }

    // There are only a few users of dealiasing:
    //  1) checking for effect inclusion (`contains` in Effects)
    //  2) checking exhaustivity of pattern matching
    //  3) type comparer itself
    def dealias(tpe: ValueType): ValueType = ???
    def dealias(tpe: Effects): Effects = ???
  }
}
