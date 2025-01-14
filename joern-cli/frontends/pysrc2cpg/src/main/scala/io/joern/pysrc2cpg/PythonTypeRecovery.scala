package io.joern.pysrc2cpg

import io.joern.x2cpg.Defines
import io.joern.x2cpg.passes.frontend._
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes._
import io.shiftleft.semanticcpg.language._
import io.shiftleft.semanticcpg.language.operatorextension.OpNodes
import io.shiftleft.semanticcpg.language.operatorextension.OpNodes.{Assignment, FieldAccess}
import overflowdb.BatchedUpdate.DiffGraphBuilder
import overflowdb.traversal.Traversal

import java.io.{File => JFile}
import java.util.regex.Matcher
import scala.util.Try

class PythonTypeRecovery(cpg: Cpg) extends XTypeRecovery[File](cpg) {

  override def computationalUnit: Traversal[File] = cpg.file
  override def generateRecoveryForCompilationUnitTask(
    unit: File,
    builder: DiffGraphBuilder
  ): RecoverForXCompilationUnit[File] = new RecoverForPythonFile(cpg, unit, builder)
}

/** Defines how a procedure is available to be called in the current scope either by it being defined in this module or
  * being imported.
  *
  * @param callingName
  *   how this procedure is to be called, i.e., alias name, name with path, etc.
  * @param fullName
  *   the full name to where this method is defined where it's assumed to be defined under a named Python file.
  */
class ScopedPythonProcedure(callingName: String, fullName: String, isConstructor: Boolean = false)
    extends ScopedXProcedure(callingName, fullName, isConstructor) {

  /** @return
    *   the full name of the procedure where it's assumed that it is defined within an <code>__init.py__</code> of the
    *   module.
    */
  private def fullNameAsInit: String = fullName.replace(".py", s"${JFile.separator}__init__.py")

  /** @return
    *   the two ways that this procedure could be resolved to in Python. This will be pruned later by comparing this to
    *   actual methods in the CPG.
    */
  override def possibleCalleeNames: Set[String] =
    if (isConstructor)
      Set(fullName.concat(s".${Defines.ConstructorMethodName}"))
    else
      Set(fullName, fullNameAsInit)

}

/** Tasks responsible for populating the symbol table with import data and method definition data.
  *
  * @param node
  *   a node that references import information.
  */
class SetPythonProcedureDefTask(node: CfgNode, symbolTable: SymbolTable[LocalKey]) extends SetXProcedureDefTask(node) {

  /** Refers to the declared import information.
    *
    * @param importCall
    *   the call that imports entities into this scope.
    */
  override def visitImport(importCall: Call): Unit = {
    importCall.argumentOut.l match {
      case List(path: Literal, funcOrModule: Literal) =>
        val calleeNames = extractMethodDetailsFromImport(path.code, funcOrModule.code).possibleCalleeNames
        symbolTable.put(CallAlias(funcOrModule.code), calleeNames)
      case List(path: Literal, funcOrModule: Literal, alias: Literal) =>
        val calleeNames =
          extractMethodDetailsFromImport(path.code, funcOrModule.code, Option(alias.code)).possibleCalleeNames
        symbolTable.put(CallAlias(alias.code), calleeNames)
      case x => logger.warn(s"Unknown import pattern: ${x.map(_.label).mkString(", ")}")
    }
  }

  override def visitImport(m: Method): Unit = {
    val calleeNames = new ScopedPythonProcedure(m.name, m.fullName).possibleCalleeNames
    symbolTable.put(m, calleeNames)
  }

  /** Parses all imports and identifies their full names and how they are to be called in this scope.
    *
    * @param path
    *   the module path.
    * @param funcOrModule
    *   the name of the imported entity.
    * @param maybeAlias
    *   an optional alias given to the imported entity.
    * @return
    *   the procedure information in this scope.
    */
  private def extractMethodDetailsFromImport(
    path: String,
    funcOrModule: String,
    maybeAlias: Option[String] = None
  ): ScopedXProcedure = {
    val isConstructor = funcOrModule.split("\\.").last.charAt(0).isUpper
    if (path.isEmpty) {
      if (funcOrModule.contains(".")) {
        // Case 1: We have imported a function using a qualified path, e.g., import foo.bar => (bar.py or bar/__init.py)
        val splitFunc = funcOrModule.split("\\.")
        val name      = splitFunc.tail.mkString(".")
        new ScopedPythonProcedure(name, s"${splitFunc(0)}.py:<module>.$name", isConstructor)
      } else {
        // Case 2: We have imported a module, e.g., import foo => (foo.py or foo/__init.py)
        new ScopedPythonProcedure(funcOrModule, s"$funcOrModule.py:<module>", isConstructor)
      }
    } else {
      val sep = Matcher.quoteReplacement(JFile.separator)
      maybeAlias match {
        // TODO: This assumes importing from modules and never importing nested method
        // Case 3:  We have imported a function from a module using an alias, e.g. import bar from foo as faz
        case Some(alias) =>
          new ScopedPythonProcedure(alias, s"${path.replaceAll("\\.", sep)}.py:<module>.$funcOrModule", isConstructor)
        // Case 4: We have imported a function from a module, e.g. import bar from foo
        case None =>
          new ScopedPythonProcedure(
            funcOrModule,
            s"${path.replaceAll("\\.", sep)}.py:<module>.$funcOrModule",
            isConstructor
          )
      }
    }
  }

}

/** Performs type recovery from the root of a compilation unit level
  *
  * @param cu
  *   a compilation unit, e.g. file.
  * @param builder
  *   the graph builder
  */
class RecoverForPythonFile(cpg: Cpg, cu: File, builder: DiffGraphBuilder)
    extends RecoverForXCompilationUnit[File](cu, builder) {

  /** Adds built-in functions to expect.
    */
  override def prepopulateSymbolTable(): Unit =
    PythonTypeRecovery.BUILTINS
      .map(t => (CallAlias(t), s"${PythonTypeRecovery.BUILTIN_PREFIX}.$t"))
      .foreach { case (alias, typ) =>
        symbolTable.put(alias, typ)
      }

  override def importNodes(cu: AstNode): Traversal[CfgNode] = cu.ast.isCall.nameExact("import")

  override def postVisitImports(): Unit = {
    symbolTable.view.foreach { case (k, v) =>
      val ms = cpg.method.fullNameExact(v.toSeq: _*).l
      val ts = cpg.typeDecl.fullNameExact(v.toSeq: _*).l
      if (ts.nonEmpty)
        symbolTable.put(k, ts.fullName.toSet)
      else if (ms.nonEmpty)
        symbolTable.put(k, ms.fullName.toSet)
      else {
        // This is likely external and we will ignore the init variant to be consistent
        symbolTable.put(k, symbolTable(k).filterNot(_.contains("__init__.py")))
      }
    }
  }

  override def generateSetProcedureDefTask(node: CfgNode, symbolTable: SymbolTable[LocalKey]): SetXProcedureDefTask =
    new SetPythonProcedureDefTask(node, symbolTable)

  /** Using assignment and import information (in the global symbol table), will propagate these types in the symbol
    * table.
    *
    * @param assignment
    *   assignment call pointer.
    */
  override def visitAssignments(assignment: Assignment): Unit = {
    // TODO: Handle fields being imported and loaded with a new value
    assignment.argumentOut.take(2).l match {
      case List(i: Identifier, c: Call) if symbolTable.contains(c) =>
        val importedTypes = symbolTable.get(c)
        if (!c.code.endsWith(")")) {
          // Case 1: The identifier is at the assignment to a function pointer. Lack of parenthesis should indicate this.
          symbolTable.append(i, importedTypes)
        } else if (c.name.charAt(0).isUpper && c.code.endsWith(")")) {
          // Case 2: The identifier is receiving a constructor invocation, thus is now an instance of the type
          symbolTable.append(i, importedTypes.map(_.stripSuffix(s".${Defines.ConstructorMethodName}")))
        } else {
          // TODO: This identifier should contain the type of the return value of 'c'
        }
      case List(i: Identifier, c: CfgNode)
          if visitLiteralAssignment(i, c, symbolTable) => // if unsuccessful, then check next
      case List(i: Identifier, c: Call) if c.receiver.isCall.name.exists(_.equals(Operators.fieldAccess)) =>
        val field = c.receiver.isCall.name(Operators.fieldAccess).map(new OpNodes.FieldAccess(_)).head
        visitCallFromFieldMember(i, c, field, symbolTable)
      case _ =>
    }
  }

  private def visitCallFromFieldMember(
    i: Identifier,
    c: Call,
    field: FieldAccess,
    symbolTable: SymbolTable[LocalKey]
  ) = {
    field.astChildren.l match {
      case List(rec: Identifier, f: FieldIdentifier) if symbolTable.contains(rec) =>
        val identifierFullName = symbolTable.get(rec).map(_.concat(s".${f.canonicalName}"))
        val callMethodFullName =
          if (f.canonicalName.charAt(0).isUpper)
            identifierFullName.map(_.concat(s".${Defines.ConstructorMethodName}"))
          else
            identifierFullName
        symbolTable.put(i, identifierFullName)
        symbolTable.put(c, callMethodFullName)
      case _ =>
    }
  }

  /** Will handle literal value assignments.
    * @param lhs
    *   the identifier.
    * @param rhs
    *   the literal.
    * @param symbolTable
    *   the symbol table.
    * @return
    *   true if a literal assigment was successfully determined and added to the symbol table, false if otherwise.
    */
  private def visitLiteralAssignment(lhs: Identifier, rhs: CfgNode, symbolTable: SymbolTable[LocalKey]): Boolean = {
    ((lhs, rhs) match {
      case (i: Identifier, l: Literal) if Try(java.lang.Integer.parseInt(l.code)).isSuccess =>
        symbolTable.append(i, Set("int"))
      case (i: Identifier, l: Literal) if Try(java.lang.Double.parseDouble(l.code)).isSuccess =>
        symbolTable.append(i, Set("float"))
      case (i: Identifier, l: Literal) if "True".equals(l.code) || "False".equals(l.code) =>
        symbolTable.append(i, Set("bool"))
      case (i: Identifier, l: Literal) if l.code.matches("^(\"|').*(\"|')$") =>
        symbolTable.append(i, Set("str"))
      case (i: Identifier, c: Call) if c.name.equals("<operator>.listLiteral") =>
        symbolTable.append(i, Set("list"))
      case (i: Identifier, c: Call) if c.name.equals("<operator>.tupleLiteral") =>
        symbolTable.append(i, Set("tuple"))
      case (i: Identifier, b: Block)
          if b.astChildren.isCall.headOption.exists(
            _.argument.isCall.exists(_.name.equals("<operator>.dictLiteral"))
          ) =>
        symbolTable.append(i, Set("dict"))
      case _ => None
    }).hasNext
  }

}

object PythonTypeRecovery {

  /** @see
    *   <a href="https://docs.python.org/3/library/functions.html#func-dict">Python Built-in Functions</a>
    */
  lazy val BUILTINS: Set[String] = Set(
    "abs",
    "aiter",
    "all",
    "anext",
    "ascii",
    "bin",
    "bool",
    "breakpoint",
    "bytearray",
    "bytes",
    "callable",
    "chr",
    "classmethod",
    "compile",
    "complex",
    "delattr",
    "dict",
    "dir",
    "divmod",
    "enumerate",
    "eval",
    "exec",
    "filter",
    "float",
    "format",
    "frozenset",
    "getattr",
    "globals",
    "hasattr",
    "hash",
    "help",
    "hex",
    "id",
    "input",
    "int",
    "isinstance",
    "issubclass",
    "iter",
    "len",
    "list",
    "locals",
    "map",
    "max",
    "memoryview",
    "min",
    "next",
    "object",
    "oct",
    "open",
    "ord",
    "pow",
    "print",
    "property",
    "range",
    "repr",
    "reversed",
    "round",
    "set",
    "setattr",
    "slice",
    "sorted",
    "staticmethod",
    "str",
    "sum",
    "super",
    "tuple",
    "type",
    "vars",
    "zip",
    "__import__"
  )
  def BUILTIN_PREFIX = "builtins.py:<module>"
}
