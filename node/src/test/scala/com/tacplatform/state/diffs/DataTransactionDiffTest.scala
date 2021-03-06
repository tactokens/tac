package com.tacplatform.state.diffs

import com.tacplatform.account.KeyPair
import com.tacplatform.common.state.ByteStr
import com.tacplatform.common.utils.EitherExt2
import com.tacplatform.db.WithState
import com.tacplatform.features.BlockchainFeatures
import com.tacplatform.lagonaki.mocks.TestBlock.{create => block}
import com.tacplatform.settings.TestFunctionalitySettings
import com.tacplatform.state.{BinaryDataEntry, BooleanDataEntry, DataEntry, IntegerDataEntry}
import com.tacplatform.transaction.{DataTransaction, GenesisTransaction}
import com.tacplatform.{NoShrink, TransactionGen}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.PropSpec
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class DataTransactionDiffTest extends PropSpec with PropertyChecks with TransactionGen with NoShrink with WithState {

  val fs = TestFunctionalitySettings.Enabled.copy(preActivatedFeatures = Map(BlockchainFeatures.DataTransaction.id -> 0))

  val baseSetup: Gen[(GenesisTransaction, KeyPair, Long)] = for {
    master <- accountGen
    ts     <- positiveLongGen
    genesis: GenesisTransaction = GenesisTransaction.create(master.toAddress, ENOUGH_AMT, ts).explicitGet()
  } yield (genesis, master, ts)

  def data(sender: KeyPair, data: List[DataEntry[_]], fee: Long, timestamp: Long): DataTransaction =
    DataTransaction.selfSigned(1.toByte, sender, data, fee, timestamp).explicitGet()

  property("state invariants hold") {
    val setup = for {
      (genesis, master, ts) <- baseSetup

      key1   <- validAliasStringGen
      value1 <- positiveLongGen
      item1 = IntegerDataEntry(key1, value1)
      fee1 <- smallFeeGen
      dataTx1 = data(master, List(item1), fee1, ts + 10000)

      key2   <- validAliasStringGen
      value2 <- Arbitrary.arbitrary[Boolean]
      item2 = BooleanDataEntry(key2, value2)
      fee2 <- smallFeeGen
      dataTx2 = data(master, List(item2), fee2, ts + 20000)

      value3 <- positiveLongGen
      item3 = IntegerDataEntry(key1, value3)
      fee3 <- smallFeeGen
      dataTx3 = data(master, List(item3), fee3, ts + 30000)
    } yield (genesis, Seq(item1, item2, item3), Seq(dataTx1, dataTx2, dataTx3))

    forAll(setup) {
      case (genesisTx, items, txs) =>
        val sender  = txs.head.sender
        val genesis = block(Seq(genesisTx))
        val blocks  = txs.map(tx => block(Seq(tx)))

        val item1 = items.head
        assertDiffAndState(Seq(genesis), blocks(0), fs) {
          case (totalDiff, state) =>
            assertBalanceInvariant(totalDiff)
            state.balance(sender.toAddress) shouldBe (ENOUGH_AMT - txs(0).fee)
            state.accountData(sender.toAddress, item1.key) shouldBe Some(item1)
        }

        val item2 = items(1)
        assertDiffAndState(Seq(genesis, blocks(0)), blocks(1), fs) {
          case (totalDiff, state) =>
            assertBalanceInvariant(totalDiff)
            state.balance(sender.toAddress) shouldBe (ENOUGH_AMT - txs.take(2).map(_.fee).sum)
            state.accountData(sender.toAddress, item1.key) shouldBe Some(item1)
            state.accountData(sender.toAddress, item2.key) shouldBe Some(item2)
        }

        val item3 = items(2)
        assertDiffAndState(Seq(genesis, blocks(0), blocks(1)), blocks(2), fs) {
          case (totalDiff, state) =>
            assertBalanceInvariant(totalDiff)
            state.balance(sender.toAddress) shouldBe (ENOUGH_AMT - txs.map(_.fee).sum)
            state.accountData(sender.toAddress, item1.key) shouldBe Some(item3)
            state.accountData(sender.toAddress, item2.key) shouldBe Some(item2)
        }
    }
  }

  property("cannot overspend funds") {
    val setup = for {
      (genesis, master, ts) <- baseSetup
      key                   <- validAliasStringGen
      value                 <- bytes64gen
      feeOverhead           <- Gen.choose[Long](1, ENOUGH_AMT)
      dataTx = data(master, List(BinaryDataEntry(key, ByteStr(value))), ENOUGH_AMT + feeOverhead, ts + 10000)
    } yield (genesis, dataTx)

    forAll(setup) {
      case (genesis, dataTx) =>
        assertDiffEi(Seq(block(Seq(genesis))), block(Seq(dataTx)), fs) { blockDiffEi =>
          blockDiffEi should produce("negative tac balance")
        }
    }
  }

  property("validation fails prior to feature activation") {
    val setup = for {
      (genesis, master, ts) <- baseSetup
      fee                   <- smallFeeGen
      dataTx = data(master, List(), fee, ts + 10000)
    } yield (genesis, dataTx)
    val settings = TestFunctionalitySettings.Enabled.copy(preActivatedFeatures = Map(BlockchainFeatures.DataTransaction.id -> 10))

    forAll(setup) {
      case (genesis, data) =>
        assertDiffEi(Seq(block(Seq(genesis))), block(Seq(data)), settings) { blockDiffEi =>
          blockDiffEi should produce("Data Transaction feature has not been activated")
        }
    }
  }
}
