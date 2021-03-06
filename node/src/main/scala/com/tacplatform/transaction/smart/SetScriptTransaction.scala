package com.tacplatform.transaction.smart

import com.tacplatform.account._
import com.tacplatform.crypto
import com.tacplatform.lang.ValidationError
import com.tacplatform.lang.script.Script
import com.tacplatform.transaction._
import com.tacplatform.transaction.serialization.impl.SetScriptTxSerializer
import com.tacplatform.transaction.validation.TxValidator
import com.tacplatform.transaction.validation.impl.SetScriptTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

import scala.util.Try

case class SetScriptTransaction(
    version: TxVersion,
    sender: PublicKey,
    script: Option[Script],
    fee: TxAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends ProvenTransaction
    with VersionedTransaction
    with TxWithFee.InTac
    with FastHashId
    with LegacyPBSwitch.V2 {

  //noinspection TypeAnnotation
  override val builder = SetScriptTransaction

  val bodyBytes: Coeval[Array[Byte]]      = Coeval.evalOnce(SetScriptTransaction.serializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]] = Coeval.evalOnce(SetScriptTransaction.serializer.toBytes(this))
  override val json: Coeval[JsObject]     = Coeval.evalOnce(SetScriptTransaction.serializer.toJson(this))
}

object SetScriptTransaction extends TransactionParser {
  type TransactionT = SetScriptTransaction

  override val typeId: TxType                    = 13: Byte
  override val supportedVersions: Set[TxVersion] = Set(1, 2)

  implicit val validator: TxValidator[SetScriptTransaction] = SetScriptTxValidator
  val serializer                                            = SetScriptTxSerializer

  implicit def sign(tx: SetScriptTransaction, privateKey: PrivateKey): SetScriptTransaction =
    tx.copy(proofs = Proofs(crypto.sign(privateKey, tx.bodyBytes())))

  override def parseBytes(bytes: Array[TxVersion]): Try[SetScriptTransaction] =
    serializer.parseBytes(bytes)

  def create(
      version: TxVersion,
      sender: PublicKey,
      script: Option[Script],
      fee: TxAmount,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, SetScriptTransaction] =
    SetScriptTransaction(version, sender, script, fee, timestamp, proofs, chainId).validatedEither

  def signed(
      version: TxVersion,
      sender: PublicKey,
      script: Option[Script],
      fee: TxAmount,
      timestamp: TxTimestamp,
      signer: PrivateKey
  ): Either[ValidationError, SetScriptTransaction] =
    create(version, sender, script, fee, timestamp, Proofs.empty).map(_.signWith(signer))

  def selfSigned(
      version: TxVersion,
      sender: KeyPair,
      script: Option[Script],
      fee: TxAmount,
      timestamp: TxTimestamp
  ): Either[ValidationError, SetScriptTransaction] =
    signed(version, sender.publicKey, script, fee, timestamp, sender.privateKey)
}
