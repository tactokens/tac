package com.tacplatform.state.diffs

import cats.Monoid
import com.tacplatform.common.utils.EitherExt2
import com.tacplatform.db.WithState
import com.tacplatform.lagonaki.mocks.TestBlock
import com.tacplatform.settings.TestFunctionalitySettings
import com.tacplatform.state._
import com.tacplatform.transaction.{GenesisTransaction, PaymentTransaction}
import com.tacplatform.{NoShrink, TransactionGen}
import org.scalacheck.Gen
import org.scalatest.PropSpec
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class PaymentTransactionDiffTest extends PropSpec with PropertyChecks with WithState with TransactionGen with NoShrink {

  val preconditionsAndPayments: Gen[(GenesisTransaction, PaymentTransaction, PaymentTransaction)] = for {
    master    <- accountGen
    recipient <- otherAccountGen(candidate = master)
    ts        <- positiveIntGen
    genesis: GenesisTransaction = GenesisTransaction.create(master.toAddress, ENOUGH_AMT, ts).explicitGet()
    paymentV2: PaymentTransaction <- paymentGeneratorP(master, recipient.toAddress)
    paymentV3: PaymentTransaction <- paymentGeneratorP(master, recipient.toAddress)
  } yield (genesis, paymentV2, paymentV3)

  val settings = TestFunctionalitySettings.Enabled.copy(blockVersion3AfterHeight = 2)

  property("Diff doesn't break invariant before block version 3") {
    forAll(preconditionsAndPayments) {
      case ((genesis, paymentV2, _)) =>
        assertDiffAndState(Seq(TestBlock.create(Seq(genesis))), TestBlock.create(Seq(paymentV2)), settings) { (blockDiff, newState) =>
          val totalPortfolioDiff: Portfolio = Monoid.combineAll(blockDiff.portfolios.values)
          totalPortfolioDiff.balance shouldBe 0
          totalPortfolioDiff.effectiveBalance shouldBe 0
        }
    }
  }

  property("Validation fails with block version 3") {
    forAll(preconditionsAndPayments) {
      case ((genesis, paymentV2, paymentV3)) =>
        assertDiffEi(Seq(TestBlock.create(Seq(genesis)), TestBlock.create(Seq(paymentV2))), TestBlock.create(Seq(paymentV3)), settings) {
          blockDiffEi =>
            blockDiffEi should produce(s"Payment transaction is deprecated after h=${settings.blockVersion3AfterHeight}")
        }
    }
  }

}
