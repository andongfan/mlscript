
package mlscript.ucs.stages

import mlscript.ucs.{Lines, LinesOps}
import mlscript.ucs.core._
import mlscript.ucs.helpers._
import mlscript.pretyper.Symbol
import mlscript.{App, CaseOf, Fld, FldFlags, Let, Loc, Sel, Term, Tup, Var, StrLit}
import mlscript.{CaseBranches, Case, Wildcard, NoCases}
import mlscript.Message, Message.MessageContext
import mlscript.utils._, shorthands._

trait Normalization { self: mlscript.pretyper.Traceable =>
  import Normalization._

  /**
    * Normalize core abstract syntax to MLscript syntax.
    *
    * @param split the split to normalize
    * @return the normalized term
    */ 
  @inline def normalize(split: Split): Term = normalizeToTerm(split)

  private def normalizeToTerm(split: Split): Term = trace("normalizeToTerm") {
    split match {
      case Split.Cons(Branch(scrutinee, Pattern.Name(nme), continuation), tail) =>
        println(s"alias $scrutinee => $nme")
        Let(false, nme, scrutinee, normalizeToTerm(continuation ++ tail))
      case Split.Cons(Branch(scrutinee, pattern @ Pattern.Literal(literal), continuation), tail) =>
        val trueBranch = normalizeToTerm(specialize(continuation ++ tail, Yes)(scrutinee.symbol, pattern))
        val falseBranch = normalizeToCaseBranches(specialize(tail, No)(scrutinee.symbol, pattern))
        CaseOf(scrutinee, Case(literal, trueBranch, falseBranch))
      // No class parameters. Easy
      case Split.Cons(Branch(scrutinee, pattern @ Pattern.Class(nme, N), continuation), tail) =>
        println(s"match $scrutinee with $nme")
        val trueBranch = normalizeToTerm(specialize(continuation ++ tail, Yes)(scrutinee.symbol, pattern))
        val falseBranch = normalizeToCaseBranches(specialize(tail, No)(scrutinee.symbol, pattern))
        CaseOf(scrutinee, Case(nme, trueBranch, falseBranch))
      case Split.Cons(Branch(scrutinee, pattern @ Pattern.Class(nme, S(parameters)), continuation), tail) =>
        println(s"match $scrutinee with $pattern")
        val trueBranch = trace("compute true branch"){
          normalizeToTerm(specialize(continuation ++ tail, Yes)(scrutinee.symbol, pattern))
        }()
        val falseBranch = trace("compute false branch"){
          normalizeToCaseBranches(specialize(tail, No)(scrutinee.symbol, pattern))
        }()
        val unappliedVar = Var(s"args_${scrutinee.name}$$${nme.name}")
        val trueBranchWithBindings = Let(
          isRec = false,
          name = unappliedVar,
          rhs = {
            val arguments = N -> Fld(FldFlags.empty, scrutinee) :: Nil
            App(Sel(nme, Var("unapply")), Tup(arguments))
          }, 
          body = parameters.zipWithIndex.foldRight(trueBranch) {
            case ((N, i), next) => next
            case ((S(parameter), i), next) => Let(false, parameter, Sel(unappliedVar, Var(i.toString)), next)
          }
        )
        CaseOf(scrutinee, Case(nme, trueBranchWithBindings, falseBranch))
      case Split.Cons(Branch(scrutinee, pattern, continuation), tail) =>
        throw new NormalizationException((msg"Unsupported pattern: ${pattern.toString}" -> pattern.toLoc) :: Nil)
      case Split.Let(rec, nme, rhs, tail) => Let(rec, nme, rhs, normalizeToTerm(tail))
      case Split.Else(default) => default
      case Split.Nil => StrLit("test") // FIXME
    }
  }()

  private def normalizeToCaseBranches(split: Split): CaseBranches = trace("normalizeToCaseBranches") {
    split match {
      // case Split.Cons(head, Split.Nil) => Case(head.pattern, normalizeToTerm(head.continuation), NoCases)
      case other @ (Split.Cons(_, _) | Split.Let(_, _, _, _)) => Wildcard(normalizeToTerm(other))
      case Split.Else(default) => Wildcard(default)
      case Split.Nil => NoCases
    }
  }()

  // Specialize `split` with the assumption that `scrutinee` matches `pattern`.
  private def specialize
      (split: Split, matchOrNot: MatchOrNot)
      (implicit scrutinee: Symbol, pattern: Pattern): Split =
  trace(s"S${matchOrNot} <== ${scrutinee.name} is ${pattern}") {
    (matchOrNot, split) match {
      // Name patterns are translated to let bindings.
      case (Yes | No, Split.Cons(Branch(otherScrutineeVar, Pattern.Name(alias), continuation), tail)) =>
        Split.Let(false, alias, otherScrutineeVar, specialize(continuation, matchOrNot))
      // Class pattern. Positive.
      case (Yes, split @ Split.Cons(head @ Branch(otherScrutineeVar, Pattern.Class(otherClassName, otherParameters), continuation), tail)) =>
        val otherScrutinee = otherScrutineeVar.symbol
        lazy val specializedTail = {
          println(s"specialized next")
          specialize(tail, Yes)
        }
        if (scrutinee === otherScrutinee) {
          println(s"scrutinee: ${scrutinee.name} === ${otherScrutinee.name}")
          pattern match {
            case Pattern.Class(className, parameters) =>
              if (className === otherClassName) {
                println(s"class name: $className === $otherClassName")
                (parameters, otherParameters) match {
                  case (S(parameters), S(otherParameters)) =>
                    if (parameters.length === otherParameters.length) {
                      println(s"same number of parameters: ${parameters.length}")
                      // Check if the class parameters are the same.
                      // Generate a function that generates bindings.
                      // TODO: Hygienic problems.
                      val addLetBindings = parameters.iterator.zip(otherParameters).zipWithIndex.foldLeft[Split => Split](identity) {
                        case (acc, N -> S(otherParameter) -> index) => ??? // TODO: How can we get the unapplied variable?
                        case (acc, S(parameter) -> S(otherParameter) -> index) if parameter.name =/= otherParameter.name =>
                          println(s"different parameter names at $index: ${parameter.name} =/= ${otherParameter.name}")
                          tail => Split.Let(false, otherParameter, parameter, tail)
                        case (acc, _) => acc
                      }
                      // addLetBindings(specialize(continuation ++ tail, Yes))
                      val specialized = addLetBindings(specialize(continuation, Yes))
                      if (specialized.hasElse) {
                        println("tail is discarded")
                        specialized.withDiagnostic(
                          msg"Discarded split because of else branch" -> None // TODO: Synthesize locations
                        )
                      } else {
                        specialized ++ specialize(tail, Yes)
                      }
                    } else {
                      throw new NormalizationException({
                        msg"Mismatched numbers of parameters of ${className.name}:" -> otherClassName.toLoc ::
                          msg"There are ${"parameters".pluralize(parameters.length, true)}." -> Pattern.getParametersLoc(parameters) ::
                          msg"But there are ${"parameters".pluralize(otherParameters.length, true)}." -> Pattern.getParametersLoc(otherParameters) ::
                          Nil
                      })
                    }
                  // TODO: Other cases
                  case (_, _) => specialize(continuation ++ tail, Yes)
                } // END match
              } else {
                println(s"class name: $className =/= $otherClassName")
                specializedTail
              }
            case _ => throw new NormalizationException((msg"Incompatible: ${pattern.toString}" -> pattern.toLoc) :: Nil)
          }
        } else {
          println(s"scrutinee: ${scrutinee.name} =/= ${otherScrutinee.name}")
          if (scrutinee.name === otherScrutinee.name) {
            println(s"WRONG!!!")
          }
          split.copy(
            head = head.copy(continuation = specialize(continuation, Yes)),
            tail = specializedTail
          )
        }
      // Class pattern. Negative
      case (No, split @ Split.Cons(head @ Branch(otherScrutineeVar, Pattern.Class(otherClassName, otherParameters), continuation), tail)) =>
        val otherScrutinee = otherScrutineeVar.symbol
        if (scrutinee === otherScrutinee) {
          println(s"scrutinee: ${scrutinee.name} === ${otherScrutinee.name}")
          pattern match {
            case Pattern.Class(className, parameters) =>
              if (className === otherClassName) {
                println(s"class name: $className === $otherClassName")
                specialize(tail, No) // TODO: Subsitute parameters to otherParameters
              } else {
                println(s"class name: $className =/= $otherClassName")
                split.copy(
                  // head = head.copy(continuation = specialize(continuation, No)),
                  tail = specialize(tail, No)
                )
              }
            case _ => throw new NormalizationException((msg"Incompatible: ${pattern.toString}" -> pattern.toLoc) :: Nil)
          }
        } else {
          println(s"scrutinee: ${scrutinee.name} =/= ${otherScrutinee.name}")
          split.copy(
            head = head.copy(continuation = specialize(continuation, matchOrNot)),
            tail = specialize(tail, matchOrNot)
          )
        }
      // Other patterns. Not implemented.
      case (Yes | No, Split.Cons(Branch(otherScrutineeVar, pattern, continuation), tail)) =>
        throw new NormalizationException((msg"Unsupported pattern: ${pattern.toString}" -> pattern.toLoc) :: Nil)
      case (Yes | No, let @ Split.Let(_, _, _, tail)) =>
        println("let binding, go next")
        let.copy(tail = specialize(tail, matchOrNot))
      // Ending cases remain the same.
      case (Yes | No, end @ (Split.Else(_) | Split.Nil)) => println("the end"); end
    } // <-- end match
  }(showSplit(s"S${matchOrNot} ==>", _))

  /**
    * Print a normalized term with indentation.
    */
  @inline protected def printNormalizedTerm(term: Term): Unit =
    println(showNormalizedTerm(term))
}

object Normalization {
  class NormalizationException(val messages: Ls[Message -> Opt[Loc]]) extends Throwable {
    def this(message: Message, location: Opt[Loc]) = this(message -> location :: Nil)
  }

  /**
    * Convert a normalized term to a string with indentation.
    */
  def showNormalizedTerm(term: Term): String = {
    def showTerm(term: Term): Lines = term match {
      case let: Let => showLet(let)
      case caseOf: CaseOf => showCaseOf(caseOf)
      case other => (0, other.toString) :: Nil
    }
    def showCaseOf(caseOf: CaseOf): Lines = {
      val CaseOf(trm, cases) = caseOf
      s"$trm match" ##: showCaseBranches(cases)
    }
    def showCaseBranches(caseBranches: CaseBranches): Lines =
      caseBranches match {
        case Case(pat, rhs, tail) =>
          (s"case $pat =>" @: showTerm(rhs)) ++ showCaseBranches(tail)
        case Wildcard(term) => s"case _ =>" @: showTerm(term)
        case NoCases => Nil
      }
    def showVar(`var`: Var): Str =
      // If the variable is associated with a symbol, mark it with an asterisk.
      `var`.name + (`var`.symbolOption.fold("")(_ => "*"))
    def showLet(let: Let): Lines = {
      val Let(rec, nme, rhs, body) = let
      (0, s"let ${showVar(nme)} = $rhs") :: showTerm(body)
    }
    showTerm(term).map { case (n, line) => "  " * n + line }.mkString("\n")
  }
}