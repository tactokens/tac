package com.tacplatform.lang.compiler

import com.tacplatform.lang.Common.NoShrink
import com.tacplatform.lang.contract.DApp
import com.tacplatform.lang.directives.values.Imports
import com.tacplatform.lang.directives.{Directive, DirectiveParser}
import com.tacplatform.lang.utils.lazyContexts
import com.tacplatform.lang.v1.compiler.{CompilationError, ContractCompiler}
import com.tacplatform.lang.v1.parser.Expressions
import org.scalatest.{Matchers, PropSpec}
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class ContractCompilerWithParserV2Test extends PropSpec with PropertyChecks with Matchers with NoShrink {

  def compile(script: String, saveExprContext: Boolean = false): Either[String, (Option[DApp], Expressions.DAPP, Iterable[CompilationError])] = {

    val result = for {
      directives <- DirectiveParser(script)
      ds         <- Directive.extractDirectives(directives)
      ctx = lazyContexts(ds.copy(imports = Imports()))().compilerContext
      compResult <- ContractCompiler.compileWithParseResult(script, ctx, ds.stdLibVersion, saveExprContext)
    } yield compResult

    result
  }

  property("simple test 2") {
    val script = """
                   |{-# STDLIB_VERSION 3 #-}
                   |{-# SCRIPT_TYPE ACCOUNT #-}
                   |{-# CONTENT_TYPE DAPP #-}
                   |
                   |@Callable(inv)
                   |func default() = {
                   |  [ IntegerEntry("x", inv.payment.extract().amount) ]
                   |}
                   |
                   |""".stripMargin

    val result = compile(script)

    result shouldBe Symbol("right")
  }
}
