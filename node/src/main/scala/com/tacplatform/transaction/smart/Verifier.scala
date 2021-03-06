package com.tacplatform.transaction.smart

import cats.Id
import cats.implicits._
import com.google.common.base.Throwables
import com.tacplatform.common.state.ByteStr
import com.tacplatform.crypto
import com.tacplatform.features.EstimatorProvider.EstimatorBlockchainExt
import com.tacplatform.lang.ValidationError
import com.tacplatform.lang.script.Script
import com.tacplatform.lang.v1.ContractLimits
import com.tacplatform.lang.v1.compiler.TermPrinter
import com.tacplatform.lang.v1.compiler.Terms.{EVALUATED, FALSE, TRUE}
import com.tacplatform.lang.v1.evaluator.Log
import com.tacplatform.lang.v1.traits.Environment
import com.tacplatform.lang.v1.traits.domain.Recipient
import com.tacplatform.metrics._
import com.tacplatform.state._
import com.tacplatform.transaction.Asset.IssuedAsset
import com.tacplatform.transaction.TxValidationError.{GenericError, ScriptExecutionError, TransactionNotAllowedByScript}
import com.tacplatform.transaction._
import com.tacplatform.transaction.assets.exchange.{ExchangeTransaction, Order}
import com.tacplatform.transaction.smart.script.ScriptRunner
import com.tacplatform.transaction.smart.script.ScriptRunner.TxOrd
import com.tacplatform.transaction.smart.script.trace.AssetVerifierTrace.AssetContext
import com.tacplatform.transaction.smart.script.trace.{AccountVerifierTrace, AssetVerifierTrace, TraceStep, TracedResult}
import com.tacplatform.utils.ScorexLogging
import org.msgpack.core.annotations.VisibleForTesting
import shapeless.Coproduct

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object Verifier extends ScorexLogging {

  private val stats = TxProcessingStats

  import stats.TxTimerExt

  type ValidationResult[T] = Either[ValidationError, T]

  def apply(blockchain: Blockchain, limitedExecution: Boolean = false)(tx: Transaction): TracedResult[ValidationError, Int] = tx match {
    case _: GenesisTransaction => Right(0)
    case pt: ProvenTransaction =>
      (pt, blockchain.accountScript(pt.sender.toAddress)) match {
        case (stx: SignedTransaction, None) =>
          stats.signatureVerification
            .measureForType(stx.typeId)(stx.signaturesValid())
            .as(0)
        case (et: ExchangeTransaction, scriptOpt) =>
          verifyExchange(et, blockchain, scriptOpt, if (limitedExecution) ContractLimits.FailFreeInvokeComplexity else Int.MaxValue)
        case (tx: SigProofsSwitch, Some(_)) if tx.usesLegacySignature =>
          Left(GenericError("Can't process transaction with signature from scripted account"))
        case (_: SignedTransaction, Some(_)) =>
          Left(GenericError("Can't process transaction with signature from scripted account"))
        case (_, Some(script)) =>
          stats.accountScriptExecution
            .measureForType(pt.typeId)(verifyTx(blockchain, script.script, script.verifierComplexity.toInt, pt, None))
        case _ =>
          stats.signatureVerification
            .measureForType(tx.typeId)(verifyAsEllipticCurveSignature(pt))
            .as(0)
      }
  }

  /** Verifies asset scripts and returns diff with complexity. In case of error returns spent complexity */
  def assets(blockchain: Blockchain, remainingComplexity: Int)(tx: Transaction): TracedResult[(Long, ValidationError), Diff] = {
    case class AssetForCheck(asset: IssuedAsset, script: AssetScriptInfo, assetType: AssetContext)

    @tailrec
    def loop(
        assets: List[AssetForCheck],
        fullComplexity: Long,
        fullTrace: List[TraceStep]
    ): (Long, TracedResult[ValidationError, Int]) = {
      assets match {
        case AssetForCheck(asset, AssetScriptInfo(script, estimatedComplexity), context) :: remaining =>
          val complexityLimit =
            if (remainingComplexity == Int.MaxValue) remainingComplexity
            else remainingComplexity - fullComplexity.toInt

          def verify = verifyTx(blockchain, script, estimatedComplexity.toInt, tx, Some(asset.id), complexityLimit, context)

          stats.assetScriptExecution.measureForType(tx.typeId)(verify) match {
            case TracedResult(e @ Left(_), trace)       => (fullComplexity + estimatedComplexity, TracedResult(e, fullTrace ::: trace))
            case TracedResult(Right(complexity), trace) => loop(remaining, fullComplexity + complexity, fullTrace ::: trace)
          }
        case Nil => (fullComplexity, TracedResult(Right(0), fullTrace))
      }
    }

    def assetScript(asset: IssuedAsset): Option[AssetScriptInfo] =
      blockchain.assetDescription(asset).flatMap(_.script)

    val assets = for {
      asset  <- tx.checkedAssets.toList
      script <- assetScript(asset)
      context = AssetContext.fromTxAndAsset(tx, asset)
    } yield AssetForCheck(asset, script, context)

    val additionalAssets = tx match {
      case e: ExchangeTransaction =>
        for {
          asset  <- List(e.buyOrder.matcherFeeAssetId, e.sellOrder.matcherFeeAssetId).distinct.collect { case ia: IssuedAsset => ia }
          script <- assetScript(asset)
        } yield AssetForCheck(asset, script, AssetContext.MatcherFee)

      case _ => Nil
    }

    val (complexity, result)  = loop(assets, 0L, Nil)
    val (_, additionalResult) = loop(additionalAssets, 0L, Nil)

    result
      .flatMap(_ => additionalResult)
      .leftMap(ve => (complexity, ve))
      .as(Diff.empty.copy(scriptsComplexity = complexity))
  }

  private def logIfNecessary(
      result: Either[ValidationError, _],
      id: String,
      execLog: Log[Id],
      execResult: Either[String, EVALUATED]
  ): Unit =
    result match {
      case Left(_) if log.logger.isDebugEnabled => log.debug(buildLogs(id, execLog, execResult))
      case _ if log.logger.isTraceEnabled       => log.trace(buildLogs(id, execLog, execResult))
      case _                                    => ()
    }

  private def verifyTx(
      blockchain: Blockchain,
      script: Script,
      estimatedComplexity: Int,
      transaction: Transaction,
      assetIdOpt: Option[ByteStr],
      complexityLimit: Int = Int.MaxValue,
      assetContext: AssetContext.Value = AssetContext.Unknown
  ): TracedResult[ValidationError, Int] = {

    val isAsset       = assetIdOpt.nonEmpty
    val senderAddress = transaction.asInstanceOf[Authorized].sender.toAddress

    val resultE = Try {
      val containerAddress = assetIdOpt.fold(Coproduct[Environment.Tthis](Recipient.Address(ByteStr(senderAddress.bytes))))(
        v => Coproduct[Environment.Tthis](Environment.AssetId(v.arr))
      )
      val (log, evaluatedComplexity, result) =
        ScriptRunner(Coproduct[TxOrd](transaction), blockchain, script, isAsset, containerAddress, complexityLimit)
      val complexity = if (blockchain.storeEvaluatedComplexity) evaluatedComplexity else estimatedComplexity
      val resultE = result match {
        case Left(execError) => Left(ScriptExecutionError(execError, log, assetIdOpt))
        case Right(FALSE)    => Left(TransactionNotAllowedByScript(log, assetIdOpt))
        case Right(TRUE)     => Right(complexity)
        case Right(x)        => Left(ScriptExecutionError(s"Script returned not a boolean result, but $x", log, assetIdOpt))
      }
      val logId = s"transaction ${transaction.id()}"
      logIfNecessary(resultE, logId, log, result)
      resultE
    } match {
      case Failure(e) =>
        Left(ScriptExecutionError(s"Uncaught execution error: ${Throwables.getStackTraceAsString(e)}", List.empty, assetIdOpt))
      case Success(s) => s
    }

    val createTrace = { maybeError: Option[ValidationError] =>
      val trace = assetIdOpt match {
        case Some(assetId) => AssetVerifierTrace(assetId, maybeError, assetContext)
        case None          => AccountVerifierTrace(senderAddress, maybeError)
      }
      List(trace)
    }

    resultE match {
      case Right(_)    => TracedResult(resultE, createTrace(None))
      case Left(error) => TracedResult(resultE, createTrace(Some(error)))
    }
  }

  private def verifyOrder(blockchain: Blockchain, script: AccountScriptInfo, order: Order, complexityLimit: Int): ValidationResult[Int] =
    Try(
      ScriptRunner(
        Coproduct[ScriptRunner.TxOrd](order),
        blockchain,
        script.script,
        isAssetScript = false,
        Coproduct[Environment.Tthis](Recipient.Address(ByteStr(order.sender.toAddress.bytes))),
        complexityLimit
      )
    ).toEither
      .leftMap(e => ScriptExecutionError(s"Uncaught execution error: $e", Nil, None))
      .flatMap {
        case (log, evaluatedComplexity, evaluationResult) =>
          val complexity = if (blockchain.storeEvaluatedComplexity) evaluatedComplexity else script.verifierComplexity.toInt
          val verifierResult = evaluationResult match {
            case Left(execError) => Left(ScriptExecutionError(execError, log, None))
            case Right(FALSE)    => Left(TransactionNotAllowedByScript(log, None))
            case Right(TRUE)     => Right(complexity)
            case Right(x)        => Left(GenericError(s"Script returned not a boolean result, but $x"))
          }
          val logId = s"order ${order.idStr()}"
          logIfNecessary(verifierResult, logId, log, evaluationResult)
          verifierResult
      }

  private def verifyExchange(
      et: ExchangeTransaction,
      blockchain: Blockchain,
      matcherScriptOpt: Option[AccountScriptInfo],
      complexityLimit: Int
  ): TracedResult[ValidationError, Int] = {

    val typeId    = et.typeId
    val sellOrder = et.sellOrder
    val buyOrder  = et.buyOrder

    def matcherTxVerification: TracedResult[ValidationError, Int] =
      matcherScriptOpt
        .map { script =>
          if (et.version != 1) {
            stats.accountScriptExecution
              .measureForType(typeId)(verifyTx(blockchain, script.script, script.verifierComplexity.toInt, et, None, complexityLimit))
          } else {
            TracedResult(Left(GenericError("Can't process transaction with signature from scripted account")))
          }
        }
        .getOrElse(stats.signatureVerification.measureForType(typeId)(verifyAsEllipticCurveSignature(et).as(0)))

    def orderVerification(order: Order): TracedResult[ValidationError, Int] = {
      val verificationResult = blockchain
        .accountScript(order.sender.toAddress)
        .map { asi =>
          if (order.version != 1) {
            stats.orderValidation.withoutTags().measure(verifyOrder(blockchain, asi, order, complexityLimit))
          } else {
            Left(GenericError("Can't process order with signature from scripted account"))
          }
        }
        .getOrElse(stats.signatureVerification.measureForType(typeId)(verifyAsEllipticCurveSignature(order).as(0)))

      TracedResult(verificationResult)
    }

    for {
      matcherComplexity <- matcherTxVerification
      sellerComplexity  <- orderVerification(sellOrder)
      buyerComplexity   <- orderVerification(buyOrder)
    } yield matcherComplexity + sellerComplexity + buyerComplexity
  }

  def verifyAsEllipticCurveSignature[T <: Proven with Authorized](pt: T): Either[GenericError, T] =
    pt.proofs.proofs match {
      case p +: Nil =>
        Either.cond(crypto.verify(p, pt.bodyBytes(), pt.sender), pt, GenericError(s"Proof doesn't validate as signature for $pt"))
      case _ => Left(GenericError("Transactions from non-scripted accounts must have exactly 1 proof"))
    }

  @VisibleForTesting
  private[smart] def buildLogs(
      id: String,
      execLog: Log[Id],
      execResult: Either[String, EVALUATED]
  ): String = {
    val builder = new StringBuilder(s"Script for $id evaluated to $execResult")
    execLog
      .foldLeft(builder) {
        case (sb, (k, Right(v))) =>
          sb.append(s"\nEvaluated `$k` to ")
          v match {
            case obj: EVALUATED => TermPrinter.print(str => sb.append(str), obj); sb
            case a              => sb.append(a.toString)
          }
        case (sb, (k, Left(err))) => sb.append(s"\nFailed to evaluate `$k`: $err")
      }
      .toString
  }
}
