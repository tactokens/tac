package com.tacplatform.events.repo

import com.tacplatform.common.state.ByteStr
import com.tacplatform.events._
import com.tacplatform.state.Blockchain
import monix.reactive.Observable

import scala.util.Try

object UpdatesRepo {
  trait Read {
    def height: Try[Int]

    def updateForHeight(height: Int): Try[BlockAppended]

    // inclusive from both sides
    def updatesRange(from: Int, to: Int): Observable[BlockAppended]
  }

  trait Write {
    //  def dropLiquidState(afterId: Option[ByteStr] = None): Unit

    def appendMicroBlock(microBlockAppended: MicroBlockAppended): Try[Unit]
    def appendBlock(blockAppended: BlockAppended): Try[Unit]
    def rollback(blockchain: Blockchain, toId: ByteStr, toHeight: Int, sendEvent: Boolean = true): Try[Unit]
    def rollbackMicroBlock(blockchain: Blockchain, toId: ByteStr): Try[Unit]
  }

  trait Stream {
    // inclusive
    def stream(from: Int): Observable[BlockchainUpdated]
  }
}
