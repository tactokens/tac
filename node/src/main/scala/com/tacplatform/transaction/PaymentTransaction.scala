package com.tacplatform.transaction

import com.tacplatform.account.{Address, KeyPair, PublicKey}
import com.tacplatform.common.state.ByteStr
import com.tacplatform.crypto
import com.tacplatform.lang.ValidationError
import com.tacplatform.transaction.serialization.impl.PaymentTxSerializer
import com.tacplatform.transaction.validation.TxValidator
import com.tacplatform.transaction.validation.impl.PaymentTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

import scala.util.Try

case class PaymentTransaction private (
    sender: PublicKey,
    recipient: Address,
    amount: TxAmount,
    fee: TxAmount,
    timestamp: TxTimestamp,
    signature: ByteStr,
    chainId: Byte
) extends SignedTransaction
    with TxWithFee.InTac {

  override val builder             = PaymentTransaction
  override val id: Coeval[ByteStr] = Coeval.evalOnce(signature)

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(builder.serializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(builder.serializer.toBytes(this))
  override val json: Coeval[JsObject]         = Coeval.evalOnce(builder.serializer.toJson(this))
}

object PaymentTransaction extends TransactionParser {
  type TransactionT = PaymentTransaction

  override val typeId: TxType                    = 2: Byte
  override val supportedVersions: Set[TxVersion] = Set(1)

  val serializer = PaymentTxSerializer

  override def parseBytes(bytes: Array[TxVersion]): Try[PaymentTransaction] =
    serializer.parseBytes(bytes)

  implicit val validator: TxValidator[PaymentTransaction] = PaymentTxValidator

  def create(sender: KeyPair, recipient: Address, amount: Long, fee: Long, timestamp: Long): Either[ValidationError, PaymentTransaction] = {
    create(sender.publicKey, recipient, amount, fee, timestamp, ByteStr.empty).map(unsigned => {
      unsigned.copy(signature = crypto.sign(sender.privateKey, unsigned.bodyBytes()))
    })
  }

  def create(
      sender: PublicKey,
      recipient: Address,
      amount: Long,
      fee: Long,
      timestamp: Long,
      signature: ByteStr
  ): Either[ValidationError, PaymentTransaction] =
    PaymentTransaction(sender, recipient, amount, fee, timestamp, signature, recipient.chainId).validatedEither
}
