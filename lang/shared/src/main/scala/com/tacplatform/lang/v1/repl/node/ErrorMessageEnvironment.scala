package com.tacplatform.lang.v1.repl.node

import com.tacplatform.common.state.ByteStr
import com.tacplatform.lang.ValidationError
import com.tacplatform.lang.script.Script
import com.tacplatform.lang.v1.compiler.Terms.EVALUATED
import com.tacplatform.lang.v1.traits.Environment.InputEntity
import com.tacplatform.lang.v1.traits.domain.Recipient.Address
import com.tacplatform.lang.v1.traits.domain.{BlockInfo, Recipient, ScriptAssetInfo, Tx}
import com.tacplatform.lang.v1.traits.{DataType, Environment}
import monix.eval.Coeval

case class ErrorMessageEnvironment[F[_]](message: String) extends Environment[F] {
  lazy val unavailable                                                                                            = throw new BlockchainUnavailableException(message)
  override def chainId: Byte                                                                                      = 0
  override def height: F[Long]                                                                                    = unavailable
  override def inputEntity: InputEntity                                                                           = unavailable
  override def tthis: Environment.Tthis                                                                           = unavailable
  override def transactionById(id: Array[Byte]): F[Option[Tx]]                                                    = unavailable
  override def transferTransactionById(id: Array[Byte]): F[Option[Tx.Transfer]]                                   = unavailable
  override def transactionHeightById(id: Array[Byte]): F[Option[Long]]                                            = unavailable
  override def assetInfoById(d: Array[Byte]): F[Option[ScriptAssetInfo]]                                          = unavailable
  override def lastBlockOpt(): F[Option[BlockInfo]]                                                               = unavailable
  override def blockInfoByHeight(height: Int): F[Option[BlockInfo]]                                               = unavailable
  override def data(addressOrAlias: Recipient, key: String, dataType: DataType): F[Option[Any]]                   = unavailable
  override def hasData(addressOrAlias: Recipient): F[Boolean]                                                     = unavailable
  override def resolveAlias(name: String): F[Either[String, Recipient.Address]]                                   = unavailable
  override def accountBalanceOf(addressOrAlias: Recipient, assetId: Option[Array[Byte]]): F[Either[String, Long]] = unavailable
  override def accountTacBalanceOf(addressOrAlias: Recipient): F[Either[String, Environment.BalanceDetails]]    = unavailable
  override def multiPaymentAllowed: Boolean                                                                       = unavailable
  override def txId: ByteStr                                                                                      = unavailable
  override def transferTransactionFromProto(b: Array[Byte]): F[Option[Tx.Transfer]]                               = unavailable
  override def addressFromString(address: String): Either[String, Recipient.Address]                              = unavailable
  override def accountScript(addressOrAlias: Recipient): F[Option[Script]]                                        = unavailable
  override def callScript(
      dApp: Address,
      func: String,
      args: List[EVALUATED],
      payments: Seq[(Option[Array[Byte]], Long)],
      availableComplexity: Int
  , reentrant: Boolean): Coeval[F[(Either[ValidationError, EVALUATED], Int)]] = unavailable
}

case class BlockchainUnavailableException(message: String) extends RuntimeException {
  override def toString: String = message
}
