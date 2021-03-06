package com.tacplatform.history

import com.tacplatform.common.utils.EitherExt2
import com.tacplatform.features.BlockchainFeatures
import com.tacplatform.history.Domain.BlockchainUpdaterExt
import com.tacplatform.state.diffs._
import com.tacplatform.transaction._
import com.tacplatform.transaction.transfer._
import com.tacplatform.{EitherMatchers, TransactionGen}
import org.scalacheck.Gen
import org.scalatest._
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class BlockchainUpdaterBlockOnlyTest
    extends PropSpec
    with PropertyChecks
    with DomainScenarioDrivenPropertyCheck
    with Matchers
    with EitherMatchers
    with TransactionGen {

  def preconditionsAndPayments(paymentsAmt: Int): Gen[(GenesisTransaction, Seq[TransferTransaction])] =
    for {
      master    <- accountGen
      recipient <- accountGen
      ts        <- positiveIntGen
      genesis: GenesisTransaction = GenesisTransaction.create(master.toAddress, ENOUGH_AMT, ts).explicitGet()
      payments <- Gen.listOfN(paymentsAmt, tacTransferGeneratorP(ts, master, recipient.toAddress))
    } yield (genesis, payments)

  property("can apply valid blocks") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments(1)) {
      case (domain, (genesis, payments)) =>
        val blocks = chainBlocks(Seq(Seq(genesis), Seq(payments.head)))
        blocks.map(block => domain.blockchainUpdater.processBlock(block) should beRight)
    }
  }

  property("can apply, rollback and reprocess valid blocks") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments(2)) {
      case (domain, (genesis, payments)) =>
        val blocks = chainBlocks(Seq(Seq(genesis), Seq(payments.head), Seq(payments(1))))
        domain.blockchainUpdater.processBlock(blocks.head) should beRight
        domain.blockchainUpdater.height shouldBe 1
        domain.blockchainUpdater.processBlock(blocks(1)) should beRight
        domain.blockchainUpdater.height shouldBe 2
        domain.blockchainUpdater.removeAfter(blocks.head.id()) should beRight
        domain.blockchainUpdater.height shouldBe 1
        domain.blockchainUpdater.processBlock(blocks(1)) should beRight
        domain.blockchainUpdater.processBlock(blocks(2)) should beRight
    }
  }

  property("can't apply block with invalid signature") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments(1)) {
      case (domain, (genesis, payment)) =>
        val blocks = chainBlocks(Seq(Seq(genesis), payment))
        domain.blockchainUpdater.processBlock(blocks.head) should beRight
        domain.blockchainUpdater.processBlock(spoilSignature(blocks.last)) should produce("invalid signature")
    }
  }

  property("can't apply block with invalid signature after rollback") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments(1)) {
      case (domain, (genesis, payment)) =>
        val blocks = chainBlocks(Seq(Seq(genesis), payment))
        domain.blockchainUpdater.processBlock(blocks.head) should beRight
        domain.blockchainUpdater.processBlock(blocks(1)) should beRight
        domain.blockchainUpdater.removeAfter(blocks.head.id()) should beRight
        domain.blockchainUpdater.processBlock(spoilSignature(blocks(1))) should produce("invalid signature")
    }
  }

  property("can process 11 blocks and then rollback to genesis") {
    assume(BlockchainFeatures.implemented.contains(BlockchainFeatures.SmartAccounts.id))
    scenario(preconditionsAndPayments(10)) {
      case (domain, (genesis, payments)) =>
        val blocks = chainBlocks(Seq(genesis) +: payments.map(Seq(_)))
        blocks.foreach { b =>
          domain.blockchainUpdater.processBlock(b) should beRight
        }
        domain.blockchainUpdater.removeAfter(blocks.head.id()) should beRight
    }
  }
}
