package com.tacplatform.mining.microblocks

import com.tacplatform.block.MicroBlock
import com.tacplatform.lang.ValidationError

import scala.util.control.NoStackTrace

sealed abstract class MicroBlockMiningError(message: String)                extends Throwable(s"Error while mining microblock: $message") with NoStackTrace
final case class MicroBlockAppendError(mb: MicroBlock, ve: ValidationError) extends MicroBlockMiningError(s"Could not append microblock ($mb) - $ve")
final case class MicroBlockBuildError(ve: ValidationError)                  extends MicroBlockMiningError(s"Could not build microblock: $ve")
final case class BlockBuildError(ve: ValidationError)                       extends MicroBlockMiningError(s"Could not build accumulated block: $ve")
