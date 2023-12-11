package io.joern.scanners.java

import io.joern.scanners._
import io.joern.console._
import io.shiftleft.semanticcpg.language._
import io.joern.macros.QueryMacros._

object SampleQuery extends QueryBundle {

  @q
  def tooManyParameters(n: Int = 2): Query =
    Query.make(
      name = "java-geq-params",
      author = "miraleung",
      title = s"Number of parameters greater than or equal to $n",
      description = s"This query identifies functions with at least $n formal parameters",
      score = 1.0,
      withStrRep({ cpg =>
        cpg.method.internal.nameNot("<global>").filter(_.parameter.size >= n)
      }),
      tags = List(QueryTags.metrics),
      codeExamples = CodeExamples(
        List("""
          |
          |public int too_many_params(int a, int b) {
          |
          |}
          |
          |""".stripMargin),
        List("""
          |
          |public void good() {
          |
          |}
          |
          |""".stripMargin)
      )
    )
}
