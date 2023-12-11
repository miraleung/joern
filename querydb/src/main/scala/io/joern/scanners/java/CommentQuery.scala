package io.joern.scanners.java

import io.joern.scanners._
import io.joern.console._
import io.shiftleft.semanticcpg.language._
import io.joern.macros.QueryMacros._

object CommentQuery extends QueryBundle {

  @q
  def getComments(): Query =
    Query.make(
      name = "java-comments",
      author = "miraleung",
      title = s"The comments in a file",
      description = s"This query identifies comments",
      score = 1.0,
      withStrRep({ cpg =>
        cpg.method.ast.filter(_.isComment)
      // cpg.method.ast.filter(_.isComment).astParent.map(p => p.astChildren.l.slice(0, 3))
      // cpg.method.internal.nameNot("<global>").filter(_.parameter.size >= n)
      }),
      tags = List(QueryTags.metrics),
      codeExamples = CodeExamples(
        List("""
          |
          |public int noComment(int a, int b) {
          |  return a + b;
          |}
          |
          |""".stripMargin),
        List("""
          |
          |public int foo(int a, int b) {
          |  // This is a comment.
          |  if (a + b > b) {
          |    return a;
          |  }
          |  return b;
          |}
          |
          |""".stripMargin)
      )
    )
}
