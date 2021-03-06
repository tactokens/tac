package com.tacplatform.transaction.validation.impl

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.implicits._
import com.tacplatform.lang.v1.compiler.Terms.FUNCTION_CALL
import com.tacplatform.lang.v1.{ContractLimits, FunctionHeader}
import com.tacplatform.protobuf.transaction.PBTransactions
import com.tacplatform.transaction.TxValidationError.{GenericError, NonPositiveAmount, TooBigArray}
import com.tacplatform.transaction.smart.InvokeScriptTransaction
import com.tacplatform.transaction.smart.InvokeScriptTransaction.Payment
import com.tacplatform.transaction.validation.{TxValidator, ValidatedNV, ValidatedV}
import com.tacplatform.utils._

import scala.util.Try

object InvokeScriptTxValidator extends TxValidator[InvokeScriptTransaction] {
  override def validate(tx: InvokeScriptTransaction): ValidatedV[InvokeScriptTransaction] = {
    import tx._

    def checkAmounts(payments: Seq[Payment]): ValidatedNV = {
      val invalid = payments.filter(_.amount <= 0)
      if (invalid.nonEmpty)
        Invalid(NonEmptyList.fromListUnsafe(invalid.toList).map(p => NonPositiveAmount(p.amount, p.assetId.fold("Tac")(_.toString))))
      else Valid(())
    }

    def checkLength =
      if (tx.isProtobufVersion)
        PBTransactions
          .toPBInvokeScriptData(tx.dAppAddressOrAlias, tx.funcCallOpt, tx.payments)
          .toByteArray
          .length <= ContractLimits.MaxInvokeScriptSizeInBytes
      else tx.bytes().length <= ContractLimits.MaxInvokeScriptSizeInBytes

    val callableNameSize =
      funcCallOpt match {
        case Some(FUNCTION_CALL(FunctionHeader.User(internalName, _), _)) => internalName.utf8Bytes.length
        case _ => 0
      }

    V.seq(tx)(
      V.addressChainId(dAppAddressOrAlias, chainId),
      V.cond(
        funcCallOpt.isEmpty || funcCallOpt.get.args.size <= ContractLimits.MaxInvokeScriptArgs,
        GenericError(s"InvokeScript can't have more than ${ContractLimits.MaxInvokeScriptArgs} arguments")
      ),
      V.cond(
        callableNameSize <= ContractLimits.MaxDeclarationNameInBytes,
        GenericError(s"Callable function name size = $callableNameSize bytes must be less than ${ContractLimits.MaxDeclarationNameInBytes}")
      ),
      checkAmounts(payments),
      V.fee(fee),
      Try(checkLength)
        .toEither
        .leftMap(err => GenericError(err.getMessage))
        .filterOrElse(identity, TooBigArray)
        .toValidatedNel
        .map(_ => ())
    )
  }
}
