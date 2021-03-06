package com.tacplatform.history

import com.tacplatform.{EitherMatchers, TransactionGen}
import com.tacplatform.common.utils.EitherExt2
import com.tacplatform.features.BlockchainFeatures
import com.tacplatform.state.diffs._
import com.tacplatform.transaction._
import com.tacplatform.transaction.transfer._
import com.tacplatform.history.Domain.BlockchainUpdaterExt
import org.scalacheck.Gen
import org.scalatest._
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class BlockchainUpdaterInMemoryDiffTest
    extends PropSpec
    with PropertyChecks
    with DomainScenarioDrivenPropertyCheck
    with Matchers
    with EitherMatchers
    with TransactionGen {
  val preconditionsAndPayments: Gen[(GenesisTransaction, TransferTransaction, TransferTransaction)] = for {
    master    <- accountGen
    recipient <- accountGen
    ts        <- positiveIntGen
    genesis: GenesisTransaction = GenesisTransaction.create(master.toAddress, ENOUGH_AMT, ts).explicitGet()
    payment: TransferTransaction  <- tacTransferGeneratorP(ts, master, recipient.toAddress)
    payment2: TransferTransaction <- tacTransferGeneratorP(ts, master, recipient.toAddress)
  } yield (genesis, payment, payment2)

  property("compaction with liquid block doesn't make liquid block affect state once") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments) {
      case (domain, (genesis, payment1, payment2)) =>
        val blocksWithoutCompaction = chainBlocks(
          Seq(genesis) +:
            Seq.fill(MaxTransactionsPerBlockDiff * 2 - 1)(Seq.empty[Transaction]) :+
            Seq(payment1))
        val blockTriggersCompaction = buildBlockOfTxs(blocksWithoutCompaction.last.id(), Seq(payment2))

        blocksWithoutCompaction.foreach(b => domain.blockchainUpdater.processBlock(b) should beRight)
        val mastersBalanceAfterPayment1 = domain.balance(genesis.recipient)
        mastersBalanceAfterPayment1 shouldBe (ENOUGH_AMT - payment1.amount - payment1.fee)

        domain.blockchainUpdater.height shouldBe MaxTransactionsPerBlockDiff * 2 + 1

        domain.blockchainUpdater.processBlock(blockTriggersCompaction) should beRight

        domain.blockchainUpdater.height shouldBe MaxTransactionsPerBlockDiff * 2 + 2

        val mastersBalanceAfterPayment1AndPayment2 = domain.blockchainUpdater.balance(genesis.recipient)
        mastersBalanceAfterPayment1AndPayment2 shouldBe (ENOUGH_AMT - payment1.amount - payment1.fee - payment2.amount - payment2.fee)
    }
  }
  property("compaction without liquid block doesn't make liquid block affect state once") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments) {
      case (domain, (genesis, payment1, payment2)) =>
        val firstBlocks             = chainBlocks(Seq(Seq(genesis)) ++ Seq.fill(MaxTransactionsPerBlockDiff * 2 - 2)(Seq.empty[Transaction]))
        val payment1Block           = buildBlockOfTxs(firstBlocks.last.id(), Seq(payment1))
        val emptyBlock              = buildBlockOfTxs(payment1Block.id(), Seq.empty)
        val blockTriggersCompaction = buildBlockOfTxs(payment1Block.id(), Seq(payment2))

        firstBlocks.foreach(b => domain.blockchainUpdater.processBlock(b) should beRight)
        domain.blockchainUpdater.processBlock(payment1Block) should beRight
        domain.blockchainUpdater.processBlock(emptyBlock) should beRight
        val mastersBalanceAfterPayment1 = domain.blockchainUpdater.balance(genesis.recipient)
        mastersBalanceAfterPayment1 shouldBe (ENOUGH_AMT - payment1.amount - payment1.fee)

        // discard liquid block
        domain.blockchainUpdater.removeAfter(payment1Block.id())
        domain.blockchainUpdater.processBlock(blockTriggersCompaction) should beRight

        domain.blockchainUpdater.height shouldBe MaxTransactionsPerBlockDiff * 2 + 1

        val mastersBalanceAfterPayment1AndPayment2 = domain.blockchainUpdater.balance(genesis.recipient)
        mastersBalanceAfterPayment1AndPayment2 shouldBe (ENOUGH_AMT - payment1.amount - payment1.fee - payment2.amount - payment2.fee)
    }
  }
}
