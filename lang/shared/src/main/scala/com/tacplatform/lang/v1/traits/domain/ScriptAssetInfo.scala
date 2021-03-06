package com.tacplatform.lang.v1.traits.domain

import com.tacplatform.common.state.ByteStr
import com.tacplatform.lang.v1.traits.domain.Recipient.Address

case class ScriptAssetInfo(
  id:            ByteStr,
  name:          String,
  description:   String,
  quantity:      Long,
  decimals:      Int,
  issuer:        Address,
  issuerPk:      ByteStr,
  reissuable:    Boolean,
  scripted:      Boolean,
  minSponsoredFee: Option[Long]
)
