package com.tacplatform.state.patch

import com.tacplatform.account.AddressScheme
import com.tacplatform.state.LeaseBalance
import com.tacplatform.utils.ScorexLogging
import play.api.libs.json.{Json, OFormat, Reads}

object PatchLoader extends ScorexLogging {
  implicit val leaseBalanceFormat: OFormat[LeaseBalance] = Json.format[LeaseBalance]
  def read[T: Reads](name: AnyRef): T = {
    val inputStream = Thread.currentThread().getContextClassLoader.getResourceAsStream(s"patches/$name-${AddressScheme.current.chainId.toChar}.json")
    Json.parse(inputStream).as[T]
  }
}
