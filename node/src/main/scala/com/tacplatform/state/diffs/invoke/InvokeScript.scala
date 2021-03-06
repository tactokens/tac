package com.tacplatform.state.diffs.invoke

import com.tacplatform.account._
import com.tacplatform.common.state.ByteStr
import com.tacplatform.lang.v1.compiler.Terms._
import com.tacplatform.transaction.Asset.IssuedAsset
import com.tacplatform.transaction.TxTimestamp
import com.tacplatform.transaction.smart.InvokeScriptTransaction
import com.tacplatform.transaction.smart.InvokeScriptTransaction.Payment

trait InvokeScriptLike {
  def dAppAddressOrAlias: AddressOrAlias
  def funcCall: FUNCTION_CALL
  def payments: Seq[Payment]
  def root: Option[InvokeScriptTransaction]
  def checkedAssets: Seq[IssuedAsset] = payments collect { case Payment(_, assetId: IssuedAsset) => assetId }
  def senderAddress: Address
  def sender: PublicKey

  def txId: ByteStr            = root.map(_.id()).getOrElse(ByteStr.empty)
  val enableEmptyKeys: Boolean = root.forall(_.isProtobufVersion)
  val timestamp: TxTimestamp   = root.map(_.timestamp).getOrElse(0L)
}

case class InvokeScript(
    senderDApp: Address,
    sender: PublicKey,
    dAppAddress: Address,
    funcCall: FUNCTION_CALL,
    payments: Seq[Payment],
    root: Option[InvokeScriptTransaction]
) extends InvokeScriptLike {
  def dAppAddressOrAlias: AddressOrAlias = dAppAddress
  def senderAddress: Address             = senderDApp
}
